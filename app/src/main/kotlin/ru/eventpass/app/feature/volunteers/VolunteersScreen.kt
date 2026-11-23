package ru.eventpass.app.feature.volunteers

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import ru.eventpass.app.R
import ru.eventpass.app.feature.auth.AuthColumn
import ru.eventpass.app.ui.LoadingLayout
import ru.eventpass.app.ui.LoadingSkeleton
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.checkin.*

@Composable
fun VolunteersRoute(eventId: String, visible: Boolean, onDenied: () -> Unit, vm: VolunteersViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(eventId, lifecycle, visible) {
        if (visible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.start(eventId); vm.refresh(); try { awaitCancellation() } finally { vm.stop() }
        }
    }
    LaunchedEffect(state.denied) { if (visible && state.denied) onDenied() }
    VolunteersScreen(state, vm::edit, vm::grant, vm::confirm, vm::retry, visible)
    state.revoke?.let {
        val error = state.revokeError ?: state.error
        ConfirmDialog(stringResource(R.string.volunteer_revoke), it.email,
            stringResource(if (error == null) R.string.volunteer_revoke else R.string.ticket_retry),
            vm::revoke, { vm.confirm(null) }, destructive = true, loading = state.busy,
            errorText = error?.let { cause -> stringResource(volunteerErrorResource(cause)) })
    }
}

@Composable
fun VolunteersScreen(s: VolunteersUiState, edit: (String) -> Unit, grant: () -> Unit,
    confirm: (Volunteer?) -> Unit, retry: () -> Unit, visible: Boolean = true) {
    EventPassRefresh(s.refreshing, retry, Modifier.testTag("refresh-volunteers"), enabled = visible && !s.loading && !s.denied && !s.busy && s.revoke == null) {
    AuthColumn(Modifier.testTag("volunteers-screen")) {
        Text(stringResource(R.string.volunteers_title), style = EventPassTypography.heading)
        Text(stringResource(R.string.volunteers_hint))
        if (s.loading) LoadingSkeleton(LoadingLayout.List)
        if (s.revoke == null) s.error?.takeUnless {it in setOf(VolunteerError.INVALID_TARGET,VolunteerError.ACCOUNT_NOT_FOUND)}?.let { InlineError(stringResource(volunteerErrorResource(it)))
            if (!s.denied) TextAction(stringResource(R.string.ticket_retry), grant, enabled = !s.busy) }
        s.readError?.let { InlineError(stringResource(volunteerErrorResource(it)))
            TextAction(stringResource(R.string.ticket_retry), retry, enabled = !s.busy) }
        if (!s.denied) {
            FormField(s.input, edit, stringResource(R.string.volunteer_email), Modifier.testTag("volunteer-input"), enabled = !s.busy && !s.loading,
                error=s.error?.takeIf {s.revoke == null && it in setOf(VolunteerError.INVALID_TARGET,VolunteerError.ACCOUNT_NOT_FOUND)}?.let {stringResource(volunteerErrorResource(it))})
            EventPassButton(stringResource(R.string.volunteer_grant), grant, Modifier.testTag("volunteer-grant"),
                loading = s.busy, enabled = s.input.isNotBlank() && !s.loading)
            if (!s.loading && s.volunteers.isEmpty()) Text(stringResource(R.string.volunteers_empty))
            s.volunteers.forEach { volunteer -> EventPassSection(Modifier.fillMaxWidth().testTag("volunteer-${volunteer.grant.userId.value}")) {
                volunteer.fullName?.let {Text(it)}
                Text(volunteer.email); Text(volunteer.publicId ?: volunteer.grant.userId.value)
                volunteer.grantedAt?.let {Text(stringResource(R.string.m06_granted_at,it.toString()))}
                Text(stringResource(R.string.volunteer_checked, volunteer.checkedInCount))
                TextAction(stringResource(R.string.volunteer_revoke), { confirm(volunteer) }, enabled = !s.busy)
            } }
        }
    }
    }
}

fun volunteerErrorResource(error: VolunteerError): Int = when (error) {
    VolunteerError.RATE_LIMITED -> R.string.auth_cooldown
    VolunteerError.EVENT_FINISHED -> R.string.scan_event_inactive
    VolunteerError.ACCOUNT_NOT_FOUND -> R.string.volunteer_unknown
    VolunteerError.INVALID_TARGET -> R.string.volunteer_invalid
    VolunteerError.FEATURE_UNAVAILABLE -> R.string.feature_unavailable
    VolunteerError.PROTOCOL -> R.string.auth_protocol
    VolunteerError.FORBIDDEN -> R.string.auth_forbidden
    VolunteerError.SERVER_UNAVAILABLE -> R.string.m02_server_unavailable
    VolunteerError.NETWORK -> R.string.auth_network
    VolunteerError.TIMEOUT -> R.string.auth_timeout
    VolunteerError.STORAGE_UNAVAILABLE -> R.string.auth_storage
    else -> R.string.scan_access_lost
}
