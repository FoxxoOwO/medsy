package cz.medsy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.Warehouse
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cz.medsy.R
import cz.medsy.security.BiometricGate
import cz.medsy.ui.reminders.ReminderPermissionViewModel
import cz.medsy.ui.bloodwork.BloodworkRoute
import cz.medsy.ui.dashboard.DashboardRoute
import cz.medsy.ui.inventory.InventoryRoute
import cz.medsy.ui.medications.MedicationDetailRoute
import cz.medsy.ui.medications.MedicationsRoute
import cz.medsy.ui.scanner.ScannerRoute
import cz.medsy.ui.settings.SettingsRoute
import cz.medsy.ui.symptoms.SymptomsRoute
import cz.medsy.ui.components.MedsyBottomBar
import cz.medsy.ui.theme.FloatingPill
import cz.medsy.ui.theme.LocalDiscreetMode
import cz.medsy.ui.theme.MedsyTheme

enum class TopLevelDestination(
    val route: String,
    @param:StringRes val label: Int,
    val icon: ImageVector,
) {
    DASHBOARD("dashboard", R.string.nav_dashboard, Icons.Rounded.SpaceDashboard),
    MEDICATIONS("medications", R.string.nav_medications, Icons.Rounded.Medication),
    BLOODWORK("bloodwork", R.string.nav_bloodwork, Icons.Rounded.Science),
    INVENTORY("inventory", R.string.nav_inventory, Icons.Rounded.Warehouse),
    SYMPTOMS("symptoms", R.string.nav_symptoms, Icons.Rounded.Favorite),
    SETTINGS("settings", R.string.nav_settings, Icons.Rounded.Settings),
}

@Composable
fun MedsyApp(
    viewModel: AppViewModel = hiltViewModel(),
    permissionViewModel: ReminderPermissionViewModel = hiltViewModel(),
) {
    val permissionState by permissionViewModel.state.collectAsStateWithLifecycle()
    // The user must come back from system settings before the banner can disappear,
    // so re-read the permission state on every resume.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        permissionViewModel.refresh()
        onPauseOrDispose { }
    }
    RequestNotificationPermissionOnce()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val unlocked by viewModel.unlocked.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val gate = remember(activity) {
        activity?.let {
            BiometricGate(it) { success -> if (success) viewModel.onAuthenticated() }
        }
    }

    // Re-lock as soon as the app leaves the foreground. Without this the flag was only
    // ever raised, so a stolen unlocked phone stayed unlocked.
    androidx.lifecycle.compose.LifecycleEventEffect(
        androidx.lifecycle.Lifecycle.Event.ON_STOP,
    ) {
        viewModel.lock()
    }

    MedsyTheme(config = settings.toThemeConfig(), discreetMode = settings.discreetMode) {
        if (settings.biometricLock && !unlocked) {
            // Resolved before the callback: the unlock lambda runs from a click handler and
            // cannot call stringResource itself.
            val unlockTitle = stringResource(R.string.lock_unlock_prompt)
            val unlockSubtitle = stringResource(R.string.lock_confirm)
            LockedScreen(
                unlockUnavailable = gate?.canAuthenticate() != true,
                onUnlock = {
                    gate?.authenticate(title = unlockTitle, subtitle = unlockSubtitle)
                },
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!permissionState.notificationsGranted) {
                    PermissionBanner(
                        title = stringResource(R.string.permission_notifications_title),
                        body = stringResource(R.string.permission_notifications_body),
                        action = stringResource(R.string.permission_notifications_action),
                        onAction = { permissionViewModel.openNotificationSettings() },
                    )
                } else if (!permissionState.exactAlarmsAllowed) {
                    PermissionBanner(
                        title = stringResource(R.string.permission_exact_title),
                        body = stringResource(R.string.permission_exact_body),
                        action = stringResource(R.string.permission_exact_action),
                        onAction = { permissionViewModel.openExactAlarmSettings() },
                    )
                }
                AppScaffold()
            }
        }
    }
}

@Composable
private fun AppScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val destinations = remember { TopLevelDestination.entries }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                // The selected tab is matched through the hierarchy so a nested screen still
                // highlights its top-level destination.
                val selected = destinations.firstOrNull { destination ->
                    currentDestination?.hierarchy?.any { it.route == destination.route } == true
                }
                MedsyBottomBar(
                    destinations = destinations,
                    selectedRoute = selected?.route,
                    onSelect = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            },
            floatingActionButton = {
                AnimatedVisibility(visible = true) {
                    FloatingActionButton(
                        onClick = { navController.navigate("scanner") },
                        shape = FloatingPill,
                        containerColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(
                            Icons.Rounded.QrCodeScanner,
                            contentDescription = stringResource(R.string.meds_scan),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = TopLevelDestination.DASHBOARD.route,
                modifier = Modifier.padding(padding),
            ) {
                composable(TopLevelDestination.DASHBOARD.route) {
                    DashboardRoute(
                        onOpenBloodwork = { navController.navigate(TopLevelDestination.BLOODWORK.route) },
                        onOpenInventory = { navController.navigate(TopLevelDestination.INVENTORY.route) },
                        onOpenPrescriptions = { navController.navigate("prescriptions") },
                    )
                }
                composable(TopLevelDestination.MEDICATIONS.route) {
                    MedicationsRoute(
                        onAddMedication = { navController.navigate("scanner") },
                        onOpenMedication = { medicationId ->
                            navController.navigate("medication/$medicationId")
                        },
                        onEditSchedule = { medicationId, scheduleId ->
                            navController.navigate("schedule/$medicationId/$scheduleId")
                        },
                        onNewSchedule = { medicationId ->
                            navController.navigate("schedule/$medicationId/0")
                        },
                    )
                }
                composable(
                    route = "medication/{medicationId}",
                    arguments = listOf(
                        androidx.navigation.navArgument("medicationId") {
                            type = androidx.navigation.NavType.LongType
                        },
                    ),
                ) {
                    MedicationDetailRoute(
                        onBack = { navController.popBackStack() },
                        onEditSchedule = { medicationId, scheduleId ->
                            navController.navigate("schedule/$medicationId/$scheduleId")
                        },
                        onNewSchedule = { medicationId ->
                            navController.navigate("schedule/$medicationId/0")
                        },
                    )
                }
                composable(
                    route = "schedule/{medicationId}/{scheduleId}",
                    arguments = listOf(
                        androidx.navigation.navArgument("medicationId") {
                            type = androidx.navigation.NavType.LongType
                        },
                        androidx.navigation.navArgument("scheduleId") {
                            type = androidx.navigation.NavType.LongType
                            defaultValue = 0L
                        },
                    ),
                ) {
                    cz.medsy.ui.schedule.ScheduleEditorRoute(
                        onFinished = { navController.popBackStack() },
                    )
                }
                composable(TopLevelDestination.BLOODWORK.route) {
                    BloodworkRoute(
                        onEditCustomTarget = { code ->
                            navController.navigate("custom-target/$code")
                        },
                    )
                }
                composable(
                    route = "custom-target/{code}",
                    arguments = listOf(
                        androidx.navigation.navArgument("code") {
                            type = androidx.navigation.NavType.StringType
                        },
                    ),
                ) { entry ->
                    cz.medsy.ui.bloodwork.CustomTargetRoute(
                        code = entry.arguments?.getString("code").orEmpty(),
                        onFinished = { navController.popBackStack() },
                    )
                }
                composable(TopLevelDestination.INVENTORY.route) {
                    InventoryRoute()
                }
                composable(TopLevelDestination.SYMPTOMS.route) {
                    SymptomsRoute()
                }
                composable(TopLevelDestination.SETTINGS.route) {
                    SettingsRoute()
                }
                composable(
                    route = "scanner?prefill={prefill}",
                    arguments = listOf(
                        androidx.navigation.navArgument("prefill") {
                            type = androidx.navigation.NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) { entry ->
                    ScannerRoute(
                        onFinished = { navController.popBackStack() },
                        prefill = entry.arguments?.getString("prefill").orEmpty(),
                    )
                }
                composable("prescriptions") {
                    cz.medsy.ui.prescriptions.PrescriptionsRoute(
                        onBack = { navController.popBackStack() },
                        onCreateMedication = { name ->
                            navController.navigate("scanner?prefill=" + java.net.URLEncoder.encode(name, "UTF-8"))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionBanner(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        // The banner is drawn above the Scaffold, which only insets the content below it;
        // without this the title ran under the clock and the battery icon.
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            androidx.compose.material3.TextButton(onClick = onAction) {
                Text(text = action, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun RequestNotificationPermissionOnce() {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun LockedScreen(
    unlockUnavailable: Boolean,
    onUnlock: () -> Unit,
) {
    val discreet = LocalDiscreetMode.current
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                if (discreet) R.string.lock_title_discreet else R.string.lock_title,
            ),
            style = MaterialTheme.typography.headlineMedium,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(
                if (unlockUnavailable) R.string.lock_no_biometric else R.string.lock_confirm,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
        androidx.compose.material3.Button(onClick = onUnlock, enabled = !unlockUnavailable) {
            Text(stringResource(R.string.common_unlock))
        }
    }
}

@Preview(name = "Locked light")
@Preview(name = "Locked dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LockedPreview() {
    MedsyTheme {
        LockedScreen(unlockUnavailable = false, onUnlock = {})
    }
}

@Preview(name = "Locked, no biometrics")
@Composable
private fun LockedUnavailablePreview() {
    MedsyTheme {
        LockedScreen(unlockUnavailable = true, onUnlock = {})
    }
}
