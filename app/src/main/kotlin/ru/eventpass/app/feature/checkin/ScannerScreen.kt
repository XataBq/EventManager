package ru.eventpass.app.feature.checkin

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import ru.eventpass.app.R
import ru.eventpass.app.feature.auth.AuthColumn
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.core.platform.QrCameraPreview

@Composable
fun ScannerRoute(eventId: String, visible: Boolean, onClose: () -> Unit, onDenied: () -> Unit,
    registerBack: ((() -> Unit)?) -> Unit) {
    val vm: ScannerViewModel = hiltViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val input by vm.manualInput.collectAsStateWithLifecycle()
    val cameraFailed by vm.cameraFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as Activity
    val owner = LocalLifecycleOwner.current
    var requested by rememberSaveable { mutableStateOf(false) }
    fun permission(): ScannerPermission = when {
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> ScannerPermission.GRANTED
        requested && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> ScannerPermission.PERMANENTLY_DENIED
        requested -> ScannerPermission.DENIED
        else -> ScannerPermission.NOT_REQUESTED
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.permission(permission())
    }
    LaunchedEffect(eventId) { vm.bind(eventId) }
    LaunchedEffect(owner, visible) {
        if (visible) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.permission(permission()); vm.foreground(true); vm.refresh()
            try { awaitCancellation() } finally { vm.foreground(false) }
        }
    }
    DisposableEffect(vm, visible) { if (visible) registerBack { vm.back(onClose) }; onDispose {
        vm.foreground(false); if (visible) registerBack(null)
    } }
    BackHandler(visible && s.phase == ScannerPhase.RESULT) { vm.back(onClose) }
    LaunchedEffect(s.phase, visible) {
        if (visible && s.phase == ScannerPhase.ACCESS_DENIED && s.error in setOf(CheckInError.FORBIDDEN,
                CheckInError.AUTHENTICATION_REQUIRED, CheckInError.SESSION_CHANGED, CheckInError.NOT_FOUND)) onDenied()
        if (visible && s.phase == ScannerPhase.PERMISSION_REQUIRED) {
            if (permission() == ScannerPermission.PERMANENTLY_DENIED) vm.permission(ScannerPermission.PERMANENTLY_DENIED)
            else { requested = true; launcher.launch(Manifest.permission.CAMERA) }
        }
    }
    ScannerScreen(s, input, vm::edit, vm::submit, vm::camera, vm::next, { vm.refresh() }, cameraFailed,
        { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }, {
            if (visible && s.cameraActive) key(s.generation) {
                val cameraGeneration = s.generation
                val view = remember { QrCameraPreview(context) }
                DisposableEffect(view, owner) {
                    view.start(owner, { vm.frame(cameraGeneration, it) }, vm::cameraFailure)
                    onDispose { view.close() }
                }
                AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(280.dp).testTag("camera-preview"))
            }
        })
}

@Composable
fun ScannerScreen(s: ScannerState, input: String, edit: (String) -> Unit, submit: () -> Unit, camera: () -> Unit,
    next: () -> Unit, retry: () -> Unit, cameraFailed: Boolean, settings: () -> Unit, preview: @Composable () -> Unit) {
    AuthColumn(Modifier.testTag("scanner-screen")) {
        Text(stringResource(R.string.scan_title), style = EventPassTypography.heading)
        s.access?.let { Text(it.title, style = EventPassTypography.cardTitle) }
        when (s.phase) {
            ScannerPhase.ACCESS_LOADING -> Text(stringResource(R.string.loading))
            ScannerPhase.ACCESS_DENIED -> {
                InlineError(stringResource(s.error?.let(::scanErrorResource) ?: R.string.scan_access_lost))
                TextAction(stringResource(R.string.ticket_retry), retry)
            }
            ScannerPhase.SUBMITTING -> Text(stringResource(R.string.scan_submitting), Modifier.testTag("scan-submitting"))
            ScannerPhase.RESULT -> {
                val outcome = s.result
                val title = when (outcome) {
                    is CheckInOutcome.Admitted -> R.string.scan_admitted
                    is CheckInOutcome.AlreadyUsed -> R.string.scan_used
                    else -> R.string.scan_invalid
                }
                EventPassSection(Modifier.fillMaxWidth().testTag("scan-result")) {
                    StatusBadge(stringResource(title), when (outcome) {
                        is CheckInOutcome.Admitted -> StatusTone.Success
                        is CheckInOutcome.AlreadyUsed -> StatusTone.Warning
                        else -> StatusTone.Danger
                    })
                    val record = when (outcome) { is CheckInOutcome.Admitted -> outcome.record; is CheckInOutcome.AlreadyUsed -> outcome.first; else -> null }
                    record?.let {
                        Text(it.attendeeName); Text(stringResource(R.string.ticket_number, it.displayNumber))
                        val time = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss 'UTC'", java.util.Locale.forLanguageTag("ru"))
                            .withZone(java.time.ZoneOffset.UTC).format(it.checkedAt)
                        Text(stringResource(R.string.scan_first, time, it.checkerId.value))
                    }
                    if (outcome is CheckInOutcome.AlreadyUsed && record == null) Text(stringResource(R.string.scan_no_history))
                    if (outcome is CheckInOutcome.Invalid) Text(stringResource(when (outcome.reason) {
                        InvalidTicketReason.WRONG_EVENT -> R.string.scan_wrong_event
                        InvalidTicketReason.CANCELLED -> R.string.ticket_cancelled
                        InvalidTicketReason.EVENT_INACTIVE -> R.string.scan_event_inactive
                        InvalidTicketReason.UNKNOWN -> R.string.scan_unknown
                    }))
                    EventPassButton(stringResource(R.string.scan_next), next, Modifier.testTag("scan-next"))
                }
            }
            else -> {
                preview()
                if (cameraFailed) InlineError(stringResource(R.string.scan_camera_failed))
                s.error?.let { InlineError(stringResource(scanErrorResource(it))) }
                if (s.permission in setOf(ScannerPermission.DENIED, ScannerPermission.PERMANENTLY_DENIED))
                    Text(stringResource(R.string.scan_permission_explanation))
                if (s.permission == ScannerPermission.PERMANENTLY_DENIED)
                    TextAction(stringResource(R.string.scan_settings), settings)
                else if (!s.cameraActive) EventPassButton(stringResource(R.string.scan_camera), camera, Modifier.testTag("scan-camera"))
                Text(stringResource(R.string.scan_hint))
                FormField(input, edit, stringResource(R.string.scan_manual), Modifier.testTag("scan-input"))
                EventPassButton(stringResource(R.string.scan_check), submit, Modifier.testTag("scan-submit"), enabled = input.isNotBlank())
            }
        }
    }
}

fun scanErrorResource(error: CheckInError): Int = when (error) {
    CheckInError.NETWORK -> R.string.auth_network
    CheckInError.TIMEOUT -> R.string.auth_timeout
    CheckInError.STORAGE_UNAVAILABLE -> R.string.auth_storage
    CheckInError.INVALID_INPUT -> R.string.scan_bad_format
    else -> R.string.scan_access_lost
}
