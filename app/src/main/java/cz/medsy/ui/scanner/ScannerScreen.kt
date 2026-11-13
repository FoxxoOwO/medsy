package cz.medsy.ui.scanner

import cz.medsy.ui.message.asText

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.ScannedPackage
import cz.medsy.domain.model.Symbology
import cz.medsy.ui.theme.BentoMedium
import cz.medsy.ui.theme.BentoSmall
import cz.medsy.ui.theme.MedsyTheme
import java.time.LocalDate
import java.util.concurrent.Executors
import androidx.compose.ui.res.stringResource
import cz.medsy.R

@Composable
fun ScannerRoute(
    onFinished: () -> Unit,
    prefill: String = "",
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

    // Prefilled from the prescription queue: the user scanned an eRecept and wants the
    // named medication without retyping it.
    LaunchedEffect(prefill) {
        if (prefill.isNotBlank()) viewModel.updateManualName(prefill)
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(state.savedMedicationId) {
        if (state.savedMedicationId != null) onFinished()
    }

    ScannerScreen(
        state = state,
        hasCameraPermission = hasPermission,
        onBarcode = viewModel::onBarcode,
        onNameChange = viewModel::updateManualName,
        onSubstanceChange = viewModel::updateManualSubstance,
        onAtcChange = viewModel::updateManualAtc,
        onStrengthChange = viewModel::updateManualStrength,
        onConcentrationChange = viewModel::updateManualConcentration,
        onQuantityChange = viewModel::updateManualQuantity,
        onFormChange = viewModel::updateManualForm,
        onSearchSukl = viewModel::searchSukl,
        onPickSukl = viewModel::pickSukl,
        onSave = viewModel::save,
        onCancel = onFinished,
    )
}

@Composable
fun ScannerScreen(
    state: ScannerUiState,
    hasCameraPermission: Boolean,
    onBarcode: (String, Int) -> Unit,
    onNameChange: (String) -> Unit,
    onSubstanceChange: (String) -> Unit,
    onAtcChange: (String) -> Unit,
    onStrengthChange: (String) -> Unit,
    onConcentrationChange: (String) -> Unit,
    onQuantityChange: (String) -> Unit,
    onFormChange: (MedicationForm) -> Unit,
    onSearchSukl: (String) -> Unit,
    onPickSukl: (cz.medsy.domain.model.SuklMedication) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.meds_scan),
                style = MaterialTheme.typography.displaySmallEmphasized,
            )
        }

        item {
            Surface(
                shape = BentoMedium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            ) {
                if (hasCameraPermission) {
                    BarcodeCameraPreview(onBarcode = onBarcode)
                } else {
                    Box(modifier = Modifier.padding(20.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.scan_camera_required),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        state.statusMessage?.let { status ->
            item {
                Surface(
                    shape = BentoSmall,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = status.asText(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }

        state.scanned?.let { scanned ->
            item {
                Surface(shape = BentoSmall, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.scan_detail_gtin, scanned.gtin ?: "—"), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.scan_detail_batch, scanned.batchNumber ?: "—"), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.scan_detail_expiry, scanned.expirationDate ?: "—"), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.scan_detail_format, scanned.symbology), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        item {
            Text(stringResource(R.string.scan_details), style = MaterialTheme.typography.headlineSmall)
        }

        item {
            OutlinedTextField(
                value = state.manualName,
                onValueChange = { value ->
                    onNameChange(value)
                    if (value.length >= 3) onSearchSukl(value)
                },
                label = { Text(stringResource(R.string.scan_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.suklSuggestions.isNotEmpty()) {
            item {
                Surface(
                    shape = BentoSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = stringResource(R.string.scan_sukl_matches),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.suklSuggestions.forEach { suggestion ->
                            androidx.compose.material3.TextButton(
                                onClick = { onPickSukl(suggestion) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = suggestion.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            suggestion.suklCode,
                                            suggestion.strength,
                                            suggestion.formName,
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = state.manualSubstance,
                onValueChange = onSubstanceChange,
                label = { Text(stringResource(R.string.scan_active_substance)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.manualAtc,
                    onValueChange = onAtcChange,
                    label = { Text(stringResource(R.string.scan_atc)) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.manualStrength,
                    onValueChange = onStrengthChange,
                    label = { Text(stringResource(R.string.scan_strength)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            OutlinedTextField(
                value = state.manualConcentration,
                onValueChange = onConcentrationChange,
                label = { Text(stringResource(R.string.scan_concentration)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = state.manualQuantity,
                onValueChange = onQuantityChange,
                label = { Text(stringResource(R.string.scan_units)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(stringResource(R.string.scan_form), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MedicationForm.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { form ->
                            FilterChip(
                                selected = state.manualForm == form,
                                onClick = { onFormChange(form) },
                                label = { Text(stringResource(form.shortLabelRes)) },
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSave, enabled = !state.isSaving) { Text(stringResource(R.string.common_save)) }
                Button(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    }
}

/** `ImageProxy.image` is still experimental in CameraX, and lint treats it as an error. */
@OptIn(ExperimentalGetImage::class)
@Composable
private fun BarcodeCameraPreview(onBarcode: (String, Int) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_CODE_128,
                )
                .build(),
        )
    }
    var lastValue by remember { mutableStateOf<String?>(null) }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = CameraPreview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                val mediaImage = proxy.image
                if (mediaImage == null) {
                    proxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(
                    mediaImage,
                    proxy.imageInfo.rotationDegrees,
                )
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        barcodes.firstOrNull()?.rawValue?.let { value ->
                            if (value != lastValue) {
                                lastValue = value
                                onBarcode(value, barcodes.first().format)
                            }
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            scanner.close()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth())
}

/**
 * Short chip labels for the manual-entry form picker. They were English literals, which put
 * "SubQ"/"Drops" next to a Czech screen; the resource ids keep the chips short per language.
 */
@get:StringRes
internal val MedicationForm.shortLabelRes: Int
    get() = when (this) {
        MedicationForm.TABLET -> R.string.form_short_tablet
        MedicationForm.CAPSULE -> R.string.form_short_capsule
        MedicationForm.INJECTION_IM -> R.string.form_short_im
        MedicationForm.INJECTION_SUBQ -> R.string.form_short_subq
        MedicationForm.PATCH -> R.string.form_short_patch
        MedicationForm.GEL -> R.string.form_short_gel
        MedicationForm.SPRAY -> R.string.form_short_spray
        MedicationForm.SUBLINGUAL -> R.string.form_short_sublingual
        MedicationForm.DROPS -> R.string.form_short_drops
    }

@Preview(name = "Scanner light")
@Preview(name = "Scanner dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ScannerPreview() {
    MedsyTheme {
        ScannerScreen(
            state = ScannerUiState(
                scanned = ScannedPackage(
                    gtin = "8594000123456",
                    expirationDate = LocalDate.of(2027, 4, 30),
                    batchNumber = "L2611",
                    serialNumber = null,
                    raw = "01085940001234561727043010L2611",
                    symbology = Symbology.GS1_DATAMATRIX,
                ),
            ),
            hasCameraPermission = false,
            onBarcode = { _, _ -> },
            onNameChange = {},
            onSubstanceChange = {},
            onAtcChange = {},
            onStrengthChange = {},
            onConcentrationChange = {},
            onQuantityChange = {},
            onFormChange = {},
            onSearchSukl = {},
            onPickSukl = {},
            onSave = {},
            onCancel = {},
        )
    }
}
