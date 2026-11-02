package ru.eventpass.app.feature.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
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
import ru.eventpass.app.feature.event.formatEventTime
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.attendance.*
import ru.eventpass.core.domain.registration.TicketStatus
import ru.eventpass.core.domain.event.EventLifecycle

@Composable
fun DashboardRoute(eventId: String, visible: Boolean, scanner: () -> Unit, volunteers: () -> Unit, onDenied: () -> Unit) {
    val vm: DashboardViewModel = hiltViewModel(); val s by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(eventId, lifecycle, visible) { if (visible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        vm.start(eventId); vm.refresh(); try { awaitCancellation() } finally { vm.stop() }
    } }
    LaunchedEffect(s.denied) { if (visible && s.denied) onDenied() }
    DashboardScreen(s, vm::query, vm::refresh, vm::more, vm::retryPage, vm::confirm, scanner, volunteers,
        documents = { ru.eventpass.app.feature.documents.DocumentActions(ru.eventpass.core.domain.documents.DocumentRequest.AttendanceXlsx(ru.eventpass.core.domain.event.EventId(eventId)),visible,s.dashboard!=null) })
    s.selected?.let { target -> ConfirmDialog(stringResource(R.string.attendance_cancel), target.name,
        stringResource(if (s.cancelError == null) R.string.attendance_cancel else R.string.ticket_retry), vm::cancel,
        { vm.confirm(null) }, destructive = true, loading = s.cancelling,
        errorText = s.cancelError?.let { stringResource(attendanceErrorResource(it)) }) }
}

@Composable
fun DashboardScreen(s: DashboardUiState, query: (String) -> Unit, refresh: () -> Unit, more: () -> Unit,
    retryPage: () -> Unit, confirm: (Attendee?) -> Unit, scanner: () -> Unit, volunteers: () -> Unit,
    documents: @Composable () -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize().testTag("dashboard-screen"), contentPadding = PaddingValues(EventPassDimens.page),
        verticalArrangement = Arrangement.spacedBy(EventPassDimens.medium)) {
        item("header") {
            Text(stringResource(R.string.dashboard_title), style = EventPassTypography.heading)
            if (s.loading) Text(stringResource(R.string.loading))
            s.dashboard?.let { d ->
                Text(d.header.title, style = EventPassTypography.cardTitle, modifier = Modifier.testTag("dashboard-event"))
                Text(formatEventTime(d.header.startsAt, d.header.zoneId)); Text(d.header.venue)
                StatusBadge(stringResource(when {
                    d.header.lifecycle == EventLifecycle.CANCELLED -> R.string.event_cancelled
                    d.header.finished -> R.string.event_finished
                    d.updatedAt >= d.header.startsAt -> R.string.event_started
                    !d.header.registrationOpen -> R.string.event_closed
                    else -> R.string.event_open
                }), StatusTone.Neutral)
                EventPassSection(Modifier.fillMaxWidth().testTag("dashboard-totals")) {
                    KeyValueRow(stringResource(R.string.dashboard_registered), d.summary.registered.toString())
                    KeyValueRow(stringResource(R.string.dashboard_checked), "${d.summary.checkedIn} (${d.summary.percent}%)")
                    KeyValueRow(stringResource(if (d.header.finished) R.string.dashboard_absent else R.string.dashboard_expected), d.summary.notCheckedIn.toString())
                    KeyValueRow(stringResource(R.string.dashboard_remaining), d.summary.remaining?.toString() ?: stringResource(R.string.dashboard_unlimited))
                }
                Text(stringResource(R.string.dashboard_updated, formatEventTime(d.updatedAt, d.header.zoneId)), style = EventPassTypography.hint)
                Text(stringResource(R.string.dashboard_local), style = EventPassTypography.hint)
                TextAction(stringResource(R.string.scan_title), scanner, Modifier.testTag("dashboard-scanner"))
                TextAction(stringResource(R.string.volunteers_title), volunteers, Modifier.testTag("dashboard-volunteers"))
            }
            // Keep the picker owner composed while RESUMED dashboard observation pauses for SAF.
            documents()
            s.readError?.let { InlineError(stringResource(attendanceErrorResource(it))) }
            EventPassButton(stringResource(R.string.ticket_refresh), refresh, Modifier.testTag("dashboard-refresh"), loading = s.refreshing)
        }
        if (s.dashboard != null) {
            item("search") {
                FormField(s.query, query, stringResource(R.string.dashboard_search), Modifier.testTag("dashboard-query"))
                Text(stringResource(R.string.dashboard_matches, s.matches), Modifier.testTag("dashboard-matches"))
                Text(stringResource(R.string.dashboard_all_statuses), style = EventPassTypography.hint)
            }
            items(s.rows, key = { it.ticketId.value }) { row -> EventPassSection(Modifier.fillMaxWidth().testTag("attendee-${row.ticketId.value}")) {
                Text(row.name, style = EventPassTypography.cardTitle); Text(row.email); Text(row.displayNumber)
                StatusBadge(stringResource(when (row.status) {
                    TicketStatus.USED -> R.string.ticket_used
                    TicketStatus.CANCELLED -> R.string.ticket_cancelled
                    TicketStatus.ACTIVE -> if (s.dashboard.header.finished) R.string.dashboard_absent else R.string.dashboard_expected
                }), StatusTone.Neutral)
                if (row.status == TicketStatus.USED) Text(row.checkedAt?.let {
                    stringResource(R.string.scan_first, formatEventTime(it, s.dashboard.header.zoneId), row.checkerId?.value.orEmpty())
                } ?: stringResource(R.string.scan_no_history))
                if (row.status == TicketStatus.ACTIVE) TextAction(stringResource(R.string.attendance_cancel), { confirm(row) }, enabled = !s.cancelling)
            } }
            item("footer") {
                if (s.paging) Text(stringResource(R.string.loading))
                if (!s.paging && s.rows.isEmpty() && s.pageError == null) Text(stringResource(if (s.query.isBlank()) R.string.dashboard_empty else R.string.dashboard_no_matches))
                s.pageError?.let { InlineError(stringResource(attendanceErrorResource(it))); TextAction(stringResource(R.string.ticket_retry), retryPage) }
                if (s.next != null) EventPassButton(stringResource(R.string.dashboard_more), more, Modifier.testTag("dashboard-more"), loading = s.paging)
            }
        }
    }
}
fun attendanceErrorResource(error: AttendanceError): Int = when (error) {
    AttendanceError.NETWORK -> R.string.auth_network
    AttendanceError.TIMEOUT -> R.string.auth_timeout
    AttendanceError.STORAGE_UNAVAILABLE -> R.string.auth_storage
    AttendanceError.USED -> R.string.ticket_used_no_cancel
    AttendanceError.STALE_PAGE -> R.string.dashboard_stale
    AttendanceError.INVALID_INPUT -> R.string.dashboard_bad_query
    else -> R.string.scan_access_lost
}
