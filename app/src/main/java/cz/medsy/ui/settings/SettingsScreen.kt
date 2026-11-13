package cz.medsy.ui.settings

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.medsy.R
import cz.medsy.data.settings.AppSettings
import cz.medsy.data.backup.BackupRepository
import cz.medsy.ui.message.asText
import cz.medsy.ui.theme.BentoMedium
import cz.medsy.ui.theme.MedsyPalette
import cz.medsy.ui.theme.MedsyTheme
import cz.medsy.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val exported by viewModel.exportedReport.collectAsStateWithLifecycle()
    var notificationsBlocked by remember { mutableStateOf(viewModel.notificationsBlocked()) }
    var fullScreenAllowed by remember { mutableStateOf(viewModel.fullScreenAllowed()) }
    // Both permissions live in system settings, so they can change while the app is away.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        notificationsBlocked = viewModel.notificationsBlocked()
        fullScreenAllowed = viewModel.fullScreenAllowed()
        onPauseOrDispose { }
    }
    val healthStatus by viewModel.healthStatus.collectAsStateWithLifecycle()
    val suklSync by viewModel.suklSync.collectAsStateWithLifecycle()
    val healthPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = remember { viewModel.healthConnectPermissionContract() },
    ) { granted ->
        viewModel.onHealthPermissionsResult(granted)
    }

    val backup by viewModel.backup.collectAsStateWithLifecycle()
    val exportPayload by viewModel.exportPayload.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    var exportPasswordDialog by remember { mutableStateOf(false) }
    var importPasswordDialog by remember { mutableStateOf(false) }
    var importModeDialog by remember { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf<String?>(null) }

    // The backup is built in memory first, so the save dialog opens with the bytes already in hand
    // and a cancelled dialog leaves no half-written file in Downloads.
    val saveBackup = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = remember { androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json") },
    ) { uri ->
        if (uri != null) viewModel.writeExport(uri) else viewModel.discardExport()
    }
    val pickBackup = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = remember { androidx.activity.result.contract.ActivityResultContracts.OpenDocument() },
    ) { uri ->
        if (uri != null) viewModel.readImport(uri) else viewModel.discardImport()
    }

    LaunchedEffect(exportPayload) {
        val payload = exportPayload ?: return@LaunchedEffect
        if (payload.isEmpty()) return@LaunchedEffect
        saveBackup.launch("medsy-backup-" + java.time.LocalDate.now() + ".json")
    }

    LaunchedEffect(pendingImport) {
        val pending = pendingImport ?: return@LaunchedEffect
        // An encrypted file needs its password before the mode can be chosen.
        if (pending.encrypted) importPasswordDialog = true else importModeDialog = true
    }

    if (exportPasswordDialog) {
        BackupPasswordDialog(
            title = stringResource(R.string.backup_password_title),
            body = stringResource(R.string.backup_password_body),
            optional = true,
            onConfirm = { password ->
                exportPasswordDialog = false
                viewModel.exportBackup(password)
            },
            onDismiss = { exportPasswordDialog = false },
        )
    }
    if (importPasswordDialog) {
        BackupPasswordDialog(
            title = stringResource(R.string.backup_enter_password),
            body = null,
            optional = false,
            onConfirm = { password ->
                importPassword = password
                importPasswordDialog = false
                importModeDialog = true
            },
            onDismiss = {
                importPasswordDialog = false
                viewModel.discardImport()
            },
        )
    }
    if (importModeDialog) {
        BackupModeDialog(
            onReplace = {
                importModeDialog = false
                viewModel.importBackup(importPassword, BackupRepository.Mode.REPLACE)
            },
            onMerge = {
                importModeDialog = false
                viewModel.importBackup(importPassword, BackupRepository.Mode.MERGE)
            },
            onDismiss = {
                importModeDialog = false
                viewModel.discardImport()
            },
        )
    }
    SettingsScreen(
        settings = settings,
        notificationsBlocked = notificationsBlocked,
        onOpenNotificationSettings = {
            viewModel.openNotificationSettings()
            notificationsBlocked = viewModel.notificationsBlocked()
        },
        onRemindersEnabled = viewModel::setRemindersEnabled,
        onReminderSound = viewModel::setReminderSound,
        onReminderVibration = viewModel::setReminderVibration,
        onReminderLead = viewModel::setReminderLeadMinutes,
        onSnooze = viewModel::setSnoozeMinutes,
        fullScreenAllowed = fullScreenAllowed,
        onFullScreenReminders = viewModel::setFullScreenReminders,
        onOpenFullScreenSettings = viewModel::openFullScreenIntentSettings,
        onTestNotification = viewModel::sendTestNotification,
        onUvWarnings = viewModel::setUvWarnings,
        onUvCity = viewModel::setUvCity,
        healthConnectStatus = healthStatus,
        suklSync = suklSync,
        onHealthConnect = { enabled ->
            if (!enabled) {
                viewModel.setHealthConnect(false)
            } else {
                healthPermissionLauncher.launch(cz.medsy.health.HealthPermissions.all)
            }
        },
        onOpenHealthConnectSettings = viewModel::openHealthConnectSettings,
        exportedReportName = exported?.name,
        onExportReport = viewModel::exportReport,
        onShareReport = viewModel::shareReport,
        backup = backup,
        onExportBackup = { exportPasswordDialog = true },
        onImportBackup = {
            pickBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        },
        onDiscreetMode = viewModel::setDiscreetMode,
        onDiscreetIcon = viewModel::setDiscreetIcon,
        onBiometricLock = viewModel::setBiometricLock,
        onDynamicColor = viewModel::setDynamicColor,
        onPalette = viewModel::setPalette,
        onThemeMode = viewModel::setThemeMode,
        onSyncSukl = viewModel::syncSukl,
    )
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    notificationsBlocked: Boolean = false,
    onOpenNotificationSettings: () -> Unit = {},
    onRemindersEnabled: (Boolean) -> Unit = {},
    onReminderSound: (Boolean) -> Unit = {},
    onReminderVibration: (Boolean) -> Unit = {},
    onReminderLead: (Int) -> Unit = {},
    onSnooze: (Int) -> Unit = {},
    /** Whether Android allows this app to cover the lock screen (Android 14+ asks per app). */
    fullScreenAllowed: Boolean = true,
    onFullScreenReminders: (Boolean) -> Unit = {},
    onOpenFullScreenSettings: () -> Unit = {},
    onTestNotification: () -> Unit = {},
    onUvWarnings: (Boolean) -> Unit = {},
    onUvCity: (String) -> Unit = {},
    /** null while the provider is still being asked — never claim "not installed" before knowing. */
    healthConnectStatus: cz.medsy.health.HealthConnectAvailability.Status? = null,
    onHealthConnect: (Boolean) -> Unit = {},
    onOpenHealthConnectSettings: () -> Unit = {},
    exportedReportName: String? = null,
    onExportReport: () -> Unit = {},
    onShareReport: () -> Unit = {},
    backup: BackupUi = BackupUi(),
    onExportBackup: () -> Unit = {},
    onImportBackup: () -> Unit = {},
    onDiscreetMode: (Boolean) -> Unit,
    onDiscreetIcon: (Boolean) -> Unit,
    onBiometricLock: (Boolean) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onPalette: (MedsyPalette) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onSyncSukl: () -> Unit,
    suklSync: SuklSyncUi = SuklSyncUi(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "settings-title") {
            Text(
                text = stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.displaySmallEmphasized,
            )
        }

        item(key = "settings-privacy") {
            SettingsCard(title = stringResource(R.string.settings_privacy)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_discreet_mode),
                    subtitle = stringResource(R.string.settings_discreet_mode_desc),
                    checked = settings.discreetMode,
                    onCheckedChange = onDiscreetMode,
                )
                SettingSwitch(
                    title = stringResource(R.string.settings_discreet_icon),
                    subtitle = stringResource(R.string.settings_discreet_icon_desc),
                    checked = settings.discreetIcon,
                    onCheckedChange = { enabled ->
                        onDiscreetIcon(enabled)
                        scope.launch { applyLauncherAlias(context, enabled) }
                    },
                )
                SettingSwitch(
                    title = stringResource(R.string.settings_biometric_lock),
                    subtitle = stringResource(R.string.settings_biometric_lock_desc),
                    checked = settings.biometricLock,
                    onCheckedChange = onBiometricLock,
                )
            }
        }

        item(key = "settings-reminders") {
            SettingsCard(title = stringResource(R.string.settings_reminders)) {
                if (notificationsBlocked) {
                    Text(
                        text = stringResource(R.string.permission_notifications_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = onOpenNotificationSettings) {
                        Text(stringResource(R.string.permission_notifications_action))
                    }
                    Spacer(Modifier.height(10.dp))
                }
                SettingSwitch(
                    title = stringResource(R.string.settings_reminders),
                    subtitle = stringResource(R.string.settings_reminders_desc),
                    checked = settings.remindersEnabled,
                    onCheckedChange = onRemindersEnabled,
                )
                SettingSwitch(
                    title = stringResource(R.string.settings_reminder_sound),
                    subtitle = stringResource(R.string.settings_reminder_sound_desc),
                    checked = settings.reminderSound,
                    onCheckedChange = onReminderSound,
                )
                SettingSwitch(
                    title = stringResource(R.string.settings_reminder_vibration),
                    subtitle = stringResource(R.string.settings_reminder_vibration_desc),
                    checked = settings.reminderVibration,
                    onCheckedChange = onReminderVibration,
                )

                SettingSwitch(
                    title = stringResource(R.string.settings_full_screen_alert),
                    subtitle = stringResource(R.string.settings_full_screen_alert_desc),
                    checked = settings.fullScreenReminders,
                    onCheckedChange = onFullScreenReminders,
                )
                if (settings.fullScreenReminders && !fullScreenAllowed) {
                    Text(
                        text = stringResource(R.string.permission_full_screen_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = onOpenFullScreenSettings) {
                        Text(stringResource(R.string.permission_full_screen_action))
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_reminder_lead),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LEAD_OPTIONS.forEach { minutes ->
                        FilterChip(
                            selected = settings.reminderLeadMinutes == minutes,
                            onClick = { onReminderLead(minutes) },
                            label = {
                                Text(
                                    if (minutes == 0) {
                                        stringResource(R.string.lead_exact)
                                    } else {
                                        stringResource(R.string.settings_lead_minutes, minutes)
                                    },
                                )
                            },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.settings_snooze),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SNOOZE_OPTIONS.forEach { minutes ->
                        FilterChip(
                            selected = settings.snoozeMinutes == minutes,
                            onClick = { onSnooze(minutes) },
                            label = { Text(stringResource(R.string.settings_snooze_minutes, minutes)) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(onClick = onTestNotification, enabled = !notificationsBlocked) {
                    Text(stringResource(R.string.settings_test_notification))
                }
            }
        }

        item(key = "settings-sun") {
            SettingsCard(title = stringResource(R.string.settings_sun)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_uv_warning),
                    subtitle = stringResource(R.string.settings_uv_warning_desc),
                    checked = settings.uvWarningsEnabled,
                    onCheckedChange = onUvWarnings,
                )
                Spacer(Modifier.height(8.dp))
                var city by remember(settings.uvCity) {
                    mutableStateOf(settings.uvCity.orEmpty())
                }
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it },
                    label = { Text(stringResource(R.string.settings_uv_city_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onUvCity(city) },
                        enabled = city.trim() != settings.uvCity.orEmpty(),
                    ) {
                        Text(stringResource(R.string.settings_uv_city_save))
                    }
                    TextButton(
                        onClick = {
                            city = ""
                            onUvCity("")
                        },
                        enabled = !settings.uvCity.isNullOrEmpty(),
                    ) {
                        Text(stringResource(R.string.settings_uv_city_clear))
                    }
                }
                Text(
                    text = stringResource(R.string.settings_uv_place_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "settings-appearance") {
            SettingsCard(title = stringResource(R.string.settings_appearance)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_dynamic_color),
                    subtitle = stringResource(R.string.settings_dynamic_color_desc),
                    checked = settings.dynamicColor,
                    onCheckedChange = onDynamicColor,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_palette), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MedsyPalette.entries.take(4).forEach { palette ->
                        FilterChip(
                            selected = settings.palette == palette,
                            onClick = { onPalette(palette) },
                            label = { Text(palette.friendlyName()) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.palette == MedsyPalette.OLED_INK,
                        onClick = { onPalette(MedsyPalette.OLED_INK) },
                        label = { Text(stringResource(R.string.settings_oled)) },
                    )
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onThemeMode(mode) },
                            label = { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
            }
        }

        item(key = "settings-health") {
            SettingsCard(title = stringResource(R.string.settings_health_title)) {
                val status = healthConnectStatus
                if (status == null) {
                    Text(
                        text = stringResource(R.string.settings_health_checking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@SettingsCard
                }
                Text(
                    text = when (status) {
                        cz.medsy.health.HealthConnectAvailability.Status.AVAILABLE ->
                            stringResource(R.string.settings_health_available)
                        cz.medsy.health.HealthConnectAvailability.Status.UPDATE_REQUIRED ->
                            stringResource(R.string.settings_health_update_required)
                        cz.medsy.health.HealthConnectAvailability.Status.UNAVAILABLE ->
                            stringResource(R.string.settings_health_unavailable)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (status == cz.medsy.health.HealthConnectAvailability.Status.AVAILABLE) {
                    SettingSwitch(
                        title = stringResource(R.string.settings_health_write_title),
                        subtitle = stringResource(R.string.settings_health_write_subtitle),
                        checked = settings.healthConnectEnabled,
                        onCheckedChange = onHealthConnect,
                    )
                } else {
                    Button(onClick = onOpenHealthConnectSettings) {
                        Text(
                            if (status == cz.medsy.health.HealthConnectAvailability.Status.UPDATE_REQUIRED) {
                                stringResource(R.string.settings_health_update)
                            } else {
                                stringResource(R.string.settings_health_install)
                            },
                        )
                    }
                }
            }
        }

        item(key = "settings-export") {
            SettingsCard(title = stringResource(R.string.settings_data)) {
                Text(
                    text = stringResource(R.string.settings_sukl_sync_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(onClick = onSyncSukl, enabled = !suklSync.running) {
                        Text(
                            stringResource(
                                if (suklSync.running) R.string.sukl_sync_running else R.string.settings_sukl_sync,
                            ),
                        )
                    }
                    if (suklSync.running) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                SuklSyncStatus(suklSync)
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.settings_pdf_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onExportReport) { Text(stringResource(R.string.settings_export_report)) }
                    if (exportedReportName != null) {
                        Button(onClick = onShareReport) { Text(stringResource(R.string.settings_share)) }
                    }
                }
                exportedReportName?.let { name ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_saved, name),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        item(key = "settings-backup") {
            SettingsCard(title = stringResource(R.string.backup_title)) {
                Text(
                    text = stringResource(R.string.backup_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onExportBackup, enabled = !backup.busy) {
                        Text(stringResource(R.string.backup_export))
                    }
                    Button(onClick = onImportBackup, enabled = !backup.busy) {
                        Text(stringResource(R.string.backup_import))
                    }
                    if (backup.busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                // Errors win over the stale success line: after a failed import the previous
                // "backup saved" would otherwise look like the import had worked.
                val line = backup.error ?: backup.message
                line?.let { text ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = text.asText(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (backup.error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        }

        item(key = "settings-credit") {
            AppCredit()
        }
    }
}

/** Where the project lives. A device with no browser must not crash the settings screen. */
private const val GITHUB_URL = "https://github.com/FoxxoOwO/medsy"

/** Last thing in the list: who made the app, and where to find it. */
@Composable
private fun AppCredit() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.settings_credit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
        ) { Text(stringResource(R.string.settings_credit_github)) }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_trans_rights),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One dialog for both directions: when [optional] the user may keep the file readable, otherwise
 * the password is required and confirmed twice (there is no way to recover a forgotten one).
 */
@Composable
private fun BackupPasswordDialog(
    title: String,
    body: String?,
    optional: Boolean,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var protect by remember { mutableStateOf(!optional) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val mismatch = protect && second.isNotEmpty() && first != second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                body?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                if (optional) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = protect, onCheckedChange = { protect = it })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.backup_password_protect))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (protect) {
                    OutlinedTextField(
                        value = first,
                        onValueChange = { first = it },
                        label = { Text(stringResource(R.string.backup_password_field)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = second,
                        onValueChange = { second = it },
                        label = { Text(stringResource(R.string.backup_password_confirm)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (mismatch) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.backup_password_mismatch),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !protect || (first.isNotEmpty() && first == second),
                onClick = { onConfirm(if (protect) first else null) },
            ) { Text(stringResource(R.string.backup_password_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) }
        },
    )
}

/** Replace or merge: the only place where the user can lose data, so it is explicit. */
@Composable
private fun BackupModeDialog(
    onReplace: () -> Unit,
    onMerge: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_mode_title)) },
        text = { Text(stringResource(R.string.backup_mode_body)) },
        confirmButton = {
            TextButton(onClick = onMerge) { Text(stringResource(R.string.backup_mode_merge)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReplace) { Text(stringResource(R.string.backup_mode_replace)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) }
            }
        },
    )
}

/** Says what the last sync did; the button alone never told the user anything. */
@Composable
private fun SuklSyncStatus(state: SuklSyncUi) {
    val finishedAt = state.finishedAt
    val text = when {
        state.failed -> stringResource(R.string.sukl_sync_failed)
        finishedAt != null -> {
            val whenText = remember(finishedAt) {
                DateUtils.getRelativeTimeSpanString(
                    finishedAt,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString()
            }
            pluralStringResource(R.plurals.sukl_sync_count, state.count, state.count) +
                " · " + stringResource(R.string.sukl_sync_last, whenText)
        }
        else -> stringResource(R.string.settings_sukl_sync_hint)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    val heading = remember(title) { title.uppercase() }
    Surface(
        shape = BentoMedium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = heading,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    Spacer(Modifier.height(6.dp))
}

private val LEAD_OPTIONS = listOf(0, 5, 15, 30)
private val SNOOZE_OPTIONS = listOf(5, 10, 15, 30)

internal fun MedsyPalette.friendlyName(): String = when (this) {
    MedsyPalette.BLOSSOM -> "Blossom"
    MedsyPalette.NONBINARY -> "Pride"
    MedsyPalette.MINT -> "Mint"
    MedsyPalette.SAND -> "Sand"
    MedsyPalette.OLED_INK -> "OLED"
}

internal fun applyLauncherAlias(context: Context, discreet: Boolean) {
    val packageName = context.packageName
    val mainComponent = ComponentName(packageName, "$packageName.MainActivity")
    val aliasComponent = ComponentName(packageName, "$packageName.MainActivityDiscreet")
    val manager = context.packageManager
    manager.setComponentEnabledSetting(
        if (discreet) mainComponent else aliasComponent,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )
    manager.setComponentEnabledSetting(
        if (discreet) aliasComponent else mainComponent,
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP,
    )
}

@Preview(name = "Settings light")
@Preview(name = "Settings dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsPreview() {
    MedsyTheme {
        SettingsScreen(
            settings = AppSettings(discreetMode = true, palette = MedsyPalette.NONBINARY),
            healthConnectStatus = cz.medsy.health.HealthConnectAvailability.Status.AVAILABLE,
            onDiscreetMode = {},
            onDiscreetIcon = {},
            onBiometricLock = {},
            onDynamicColor = {},
            onPalette = {},
            onThemeMode = {},
            onSyncSukl = {},
        )
    }
}
