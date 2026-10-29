package ru.eventpass.app.feature.tickets

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import ru.eventpass.app.R
import ru.eventpass.app.feature.auth.AuthColumn
import ru.eventpass.app.feature.event.*
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.registration.*

@Composable
fun TicketsRoute(selected: String?, onConsumed: () -> Unit, onEvent: (String) -> Unit,
    onDemo: () -> Unit, onGallery: () -> Unit, onTools: () -> Unit) {
    val vm: TicketsViewModel = hiltViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(selected) { selected?.let { vm.selectIssued(it); onConsumed() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.refresh() } }
    TicketsScreen(s, vm::select, vm::history, { vm.refresh() }, { vm.fullscreen(true) }, { vm.confirm(true) }, onEvent, onDemo, onGallery, onTools)
    if (s.confirming) ConfirmDialog(stringResource(R.string.ticket_cancel_title), stringResource(R.string.ticket_cancel_description),
        stringResource(R.string.ticket_cancel), vm::cancel, { vm.confirm(false) }, destructive = true, loading = s.cancelling, dismissLabel = stringResource(R.string.ticket_keep))
    val selectedTicket = s.data?.tickets?.firstOrNull { it.snapshot.ticketId.value == s.selected }
    val event = s.data?.events?.get(selectedTicket?.snapshot?.eventId)
    if (s.fullscreen && selectedTicket != null && event != null && selectedTicket.canDisplayQr(event, s.data!!.now)) {
        FullscreenTicketQr(selectedTicket.snapshot.qrPayload, selectedTicket.snapshot.displayNumber) { vm.fullscreen(false) }
    }
}

@Composable
fun TicketsScreen(s: TicketsUiState, onSelect: (String?) -> Unit, onHistory: (Boolean) -> Unit, onRefresh: () -> Unit,
    onQr: () -> Unit, onCancel: () -> Unit, onEvent: (String) -> Unit, onDemo: () -> Unit, onGallery: () -> Unit, onTools: () -> Unit) {
    AuthColumn(Modifier.testTag("root-Tickets")) {
        Text(stringResource(R.string.tickets_title), style = EventPassTypography.heading)
        SegmentedTabs(listOf(SegmentOption(stringResource(R.string.ticket_current)), SegmentOption(stringResource(R.string.ticket_history))), if (s.history) 1 else 0, { onHistory(it == 1) })
        if (s.loading) Text(stringResource(R.string.loading))
        s.error?.let { InlineError(readErrorText(it)); EventPassButton(stringResource(R.string.ticket_retry), onRefresh) }
        if (s.authenticationRequired) Text(stringResource(R.string.auth_login))
        val data = s.data
        val selected = data?.tickets?.firstOrNull { it.snapshot.ticketId.value == s.selected }
        if (selected != null) {
            val t = selected.snapshot
            val event = data.events[t.eventId]
            EventPassSection {
                Text(t.eventTitle, style = EventPassTypography.cardTitle, modifier = Modifier.testTag("selected-ticket"))
                Text(stringResource(R.string.ticket_number, t.displayNumber))
                Text(t.attendeeName)
                KeyValueRow(stringResource(R.string.event_date), formatEventTime(t.startsAt, t.zoneId))
                KeyValueRow(stringResource(R.string.event_venue), t.venue)
                val current = event?.let { selected.isCurrent(it, data.now) } == true
                StatusBadge(stringResource(when (selected.status) {
                    TicketStatus.CANCELLED -> R.string.ticket_cancelled
                    TicketStatus.USED -> R.string.ticket_used
                    TicketStatus.ACTIVE -> if (current) R.string.ticket_active else R.string.ticket_past
                }), if (current) StatusTone.Success else StatusTone.Neutral)
                if (event != null && selected.canDisplayQr(event, data.now)) {
                    TicketQr(t.qrPayload, t.displayNumber)
                    EventPassButton(stringResource(R.string.ticket_open_qr), onQr, Modifier.testTag("open-fullscreen"))
                }
                EventPassButton(stringResource(R.string.ticket_to_event), { onEvent(t.eventId.value) }, style = ActionStyle.Secondary)
                if (selected.status == TicketStatus.ACTIVE) EventPassButton(stringResource(R.string.ticket_cancel), onCancel, Modifier.testTag("cancel-ticket"), style = ActionStyle.Danger, loading = s.cancelling)
                s.cancelError?.let { InlineError(stringResource(when (it) {
                    TicketError.USED -> R.string.ticket_used_no_cancel
                    TicketError.NETWORK -> R.string.auth_network
                    TicketError.TIMEOUT -> R.string.auth_timeout
                    TicketError.STORAGE_UNAVAILABLE -> R.string.auth_storage
                    TicketError.AUTHENTICATION_REQUIRED, TicketError.SESSION_CHANGED -> R.string.auth_session_changed
                    else -> R.string.ticket_missing
                })) }
                TextAction(stringResource(R.string.ticket_back), { onSelect(null) })
            }
        } else if (data != null) {
            val visible = data.tickets.filter { ticket -> (data.events[ticket.snapshot.eventId]?.let { ticket.isCurrent(it, data.now) } == true) != s.history }
            if (visible.isEmpty()) EmptyState(stringResource(if (s.history) R.string.ticket_empty_history else R.string.ticket_empty), stringResource(R.string.ticket_empty_hint))
            visible.forEach { ticket -> EventPassSection {
                Text(ticket.snapshot.eventTitle, style = EventPassTypography.cardTitle)
                Text(formatEventTime(ticket.snapshot.startsAt, ticket.snapshot.zoneId))
                StatusBadge(stringResource(when (ticket.status) { TicketStatus.ACTIVE -> if (s.history) R.string.ticket_past else R.string.ticket_active; TicketStatus.USED -> R.string.ticket_used; TicketStatus.CANCELLED -> R.string.ticket_cancelled }), StatusTone.Neutral)
                EventPassButton(stringResource(R.string.ticket_number, ticket.snapshot.displayNumber), { onSelect(ticket.snapshot.ticketId.value) }, Modifier.testTag("ticket-${ticket.snapshot.ticketId.value}"))
            } }
        }
        TextAction(stringResource(R.string.ticket_refresh), onRefresh)
        TextAction(stringResource(R.string.auth_demo_tools), onTools)
        EventPassButton(stringResource(R.string.open_demo), onDemo, Modifier.testTag("open-demo"))
        EventPassButton(stringResource(R.string.open_gallery), onGallery, Modifier.testTag("open-gallery"), style = ActionStyle.Secondary)
    }
}
