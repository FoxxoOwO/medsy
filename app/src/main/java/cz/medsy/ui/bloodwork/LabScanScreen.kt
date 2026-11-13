package cz.medsy.ui.bloodwork

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import cz.medsy.ui.message.asText
import cz.medsy.ui.theme.BentoMedium
import cz.medsy.ui.theme.MedsyTheme
import java.io.File
import androidx.compose.ui.res.stringResource
import cz.medsy.R

/**
 * Full-screen capture step for a photographed lab report, plus a gallery fallback for
 * people who already have the photo (or whose camera permission is denied).
 *
 * The screen only produces a [Uri]. Reading it is the view model's job, so a failed
 * recognition lands in the same staging card as a PDF import instead of in a dead-end
 * error screen.
 */
@Composable
fun LabScanScreen(
    onCaptured: (Uri) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val gallery = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onCaptured) }

    LabScanContent(
        onCancel = onCancel,
        onPickGallery = {
            gallery.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        modifier = modifier,
        camera = {
            if (hasPermission) {
                ReportCamera(onCaptured = onCaptured)
            } else {
                CameraPermissionPanel(
                    onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                )
            }
        },
    )
}

/**
 * Layout only: the camera arrives as a slot, so the step can be rendered in a preview
 * without a device camera or an activity-result registry.
 */
@Composable
internal fun LabScanContent(
    onCancel: () -> Unit,
    onPickGallery: () -> Unit,
    modifier: Modifier = Modifier,
    camera: @Composable () -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.labs_scan_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .clip(BentoMedium),
            ) {
                camera()
            }

            Text(
                text = stringResource(R.string.labs_scan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = onPickGallery,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_from_gallery))
            }
        }
    }
}

@Composable
private fun CameraPermissionPanel(onRequest: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.labs_scan_permission),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRequest) {
                Text(stringResource(R.string.scan_allow_camera))
            }
        }
    }
}

@Preview(name = "Lab scan light")
@Preview(name = "Lab scan dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LabScanPreview() {
    MedsyTheme {
        LabScanContent(
            onCancel = {},
            onPickGallery = {},
            camera = { CameraPermissionPanel(onRequest = {}) },
        )
    }
}

@Composable
private fun ReportCamera(onCaptured: (Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val capture = remember {
        ImageCapture.Builder()
            // Latency over quality: the photo only has to be readable by ML Kit, and a
            // slow shutter makes people move and blur the text.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    var error by remember { mutableStateOf<cz.medsy.domain.message.Message?>(null) }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = CameraPreview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
            }.onFailure { error = cz.medsy.domain.message.message(cz.medsy.domain.message.MessageKey.LAB_SCAN_CAMERA_UNAVAILABLE) }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            error?.let {
                Text(
                    text = it.asText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    if (busy) return@Button
                    busy = true
                    error = null
                    // cacheDir is already exposed through the app's FileProvider, so the
                    // file needs no extra permission to be read back.
                    val file = File(context.cacheDir, "lab-report-${System.currentTimeMillis()}.jpg")
                    val options = ImageCapture.OutputFileOptions.Builder(file).build()
                    capture.takePicture(
                        options,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                busy = false
                                onCaptured(Uri.fromFile(file))
                            }

                            override fun onError(exception: ImageCaptureException) {
                                busy = false
                                error = cz.medsy.domain.message.message(cz.medsy.domain.message.MessageKey.LAB_SCAN_CAPTURE_FAILED)
                                file.delete()
                            }
                        },
                    )
                },
                enabled = !busy,
            ) {
                Icon(Icons.Rounded.PhotoCamera, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_capture))
            }
        }
    }
}
