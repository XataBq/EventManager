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
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.checkin.*

@Composable
fun VolunteersRoute(eventId: String, visible: Boolean, onDenied: () -> Unit) {
    val vm: VolunteersViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(eventId, lifecycle, visible) {
        if (visible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.start(eventId); try { awaitCancellation() } finally { vm.stop() }
        }
    }
    LaunchedEffect(state.denied) { if (visible && state.denied) onDenied() }
    VolunteersScreen(state, vm::edit, vm::grant, vm::confirm, vm::retry)
    state.revoke?.let {
        ConfirmDialog(stringResource(R.string.volunteer_revoke), it.email, stringResource(R.string.volunteer_revoke),
            vm::revoke, { vm.confirm(null) }, destructive = true, loading = state.busy)
    }
}

@Composable
fun VolunteersScreen(s: VolunteersUiState, edit: (String) -> Unit, grant: () -> Unit,
    confirm: (Volunteer?) -> Unit, retry: () -> Unit) {
    AuthColumn(Modifier.testTag("volunteers-screen")) {
        Text(stringResource(R.string.volunteers_title), style = EventPassTypography.heading)
        Text(stringResource(R.string.volunteers_hint))
        if (s.loading) Text(stringResource(R.string.loading))
        s.error?.let { InlineError(stringResource(when (it) {
            VolunteerError.ACCOUNT_NOT_FOUND -> R.string.volunteer_unknown
            VolunteerError.INVALID_TARGET -> R.string.volunteer_invalid
            VolunteerError.NETWORK -> R.string.auth_network
            VolunteerError.TIMEOUT -> R.string.auth_timeout
            VolunteerError.STORAGE_UNAVAILABLE -> R.string.auth_storage
            else -> R.string.scan_access_lost
        })); TextAction(stringResource(R.string.ticket_retry), retry, enabled = !s.busy) }
        if (!s.denied) {
            FormField(s.input, edit, stringResource(R.string.volunteer_email), Modifier.testTag("volunteer-input"), enabled = !s.busy && !s.loading)
            EventPassButton(stringResource(R.string.volunteer_grant), grant, Modifier.testTag("volunteer-grant"),
                loading = s.busy, enabled = s.input.isNotBlank() && !s.loading)
            if (!s.loading && s.volunteers.isEmpty()) Text(stringResource(R.string.volunteers_empty))
            s.volunteers.forEach { volunteer -> EventPassSection(Modifier.fillMaxWidth().testTag("volunteer-${volunteer.grant.userId.value}")) {
                Text(volunteer.email); Text(volunteer.grant.userId.value)
                TextAction(stringResource(R.string.volunteer_revoke), { confirm(volunteer) }, enabled = !s.busy)
            } }
        }
    }
}
