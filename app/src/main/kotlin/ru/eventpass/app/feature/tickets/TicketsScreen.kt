package ru.eventpass.app.feature.tickets

import androidx.activity.compose.BackHandler
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
import kotlinx.coroutines.awaitCancellation
import ru.eventpass.app.R
import ru.eventpass.app.feature.auth.AuthColumn
import ru.eventpass.app.feature.event.*
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.registration.*

@Composable
fun TicketsRoute(selected: String?, onConsumed: () -> Unit, onEvent: (String) -> Unit,
    onDemo: () -> Unit, onGallery: () -> Unit, onTools: () -> Unit, visible: Boolean = true, vm: TicketsViewModel = hiltViewModel(), onCardClosed:(()->Unit)?=null) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(selected) { selected?.let { vm.selectIssued(it); onConsumed() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle, visible) { if (visible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        vm.start(); vm.refresh(); try { awaitCancellation() } finally { vm.stop() }
    } }
    // The selected card belongs to the root entry, so NavDisplay cannot pop it.
    // Dialog windows handle Back themselves before the card returns to the list.
    BackHandler(enabled = visible && s.selected != null && !s.fullscreen && !s.confirming) { onCardClosed?.invoke() ?: vm.select(null) }
    TicketsScreen(s, {id->if(id==null && onCardClosed!=null) onCardClosed() else vm.select(id)}, vm::history, { vm.refresh() }, { vm.fullscreen(true) }, { vm.confirm(true) }, onEvent, onDemo, onGallery, onTools,
        documents = { ru.eventpass.app.feature.documents.DocumentActions(ru.eventpass.core.domain.documents.DocumentRequest.TicketPdf(it.snapshot.ticketId),visible) }, visible = visible)
    if (s.confirming) ConfirmDialog(stringResource(R.string.ticket_cancel_title), stringResource(R.string.ticket_cancel_description),
        stringResource(R.string.ticket_cancel), vm::cancel, { vm.confirm(false) }, destructive = true, loading = s.cancelling, dismissLabel = stringResource(R.string.ticket_keep))
    val selectedTicket = s.selectedTicket
    if (s.fullscreen && selectedTicket != null && selectedTicket.canDisplayQr(s.selectedEvent, s.selectedNow)) {
        FullscreenTicketQr(selectedTicket.snapshot.qrPayload, selectedTicket.snapshot.displayNumber) { vm.fullscreen(false) }
    }
}

@Composable
fun TicketsScreen(s: TicketsUiState, onSelect: (String?) -> Unit, onHistory: (Boolean) -> Unit, onRefresh: () -> Unit,
    onQr: () -> Unit, onCancel: () -> Unit, onEvent: (String) -> Unit, onDemo: () -> Unit, onGallery: () -> Unit, onTools: () -> Unit,
    documents: @Composable (Ticket) -> Unit = {}, visible: Boolean = true) {
    EventPassRefresh(s.refreshing, onRefresh, Modifier.testTag("refresh-tickets"),
        enabled = visible && !s.loading && !s.authenticationRequired && !s.fullscreen && !s.confirming && !s.cancelling) {
    AuthColumn(Modifier.testTag("root-Tickets")) {
        Text(stringResource(R.string.tickets_title), style = EventPassTypography.heading)
        SegmentedTabs(listOf(SegmentOption(stringResource(R.string.ticket_current)), SegmentOption(stringResource(R.string.ticket_history))), if (s.history) 1 else 0, { onHistory(it == 1) })
        if (s.loading) Text(stringResource(R.string.loading))
        s.error?.let { InlineError(readErrorText(it)); EventPassButton(stringResource(R.string.ticket_retry), onRefresh) }
        if (s.authenticationRequired) Text(stringResource(R.string.auth_login))
        val data = s.data
        val selected = s.selectedTicket
        if (selected != null) {
            val t = selected.snapshot
            val event = s.selectedEvent
            EventPassSection {
                Text(t.eventTitle, style = EventPassTypography.cardTitle, modifier = Modifier.testTag("selected-ticket"))
                Text(stringResource(R.string.ticket_number, t.displayNumber))
                Text(t.attendeeName)
                selected.category?.let { KeyValueRow(stringResource(R.string.m03_category), it) }
                selected.zone?.let { KeyValueRow(stringResource(R.string.m03_zone), it) }
                selected.checkedInAt?.let { KeyValueRow(stringResource(R.string.m03_checked_at), formatEventTime(it, t.zoneId)) }
                KeyValueRow(stringResource(R.string.event_date), formatEventTime(t.startsAt, t.zoneId))
                KeyValueRow(stringResource(R.string.event_venue), t.venue)
                val current = selected.isCurrent(event, s.selectedNow)
                StatusBadge(stringResource(when (selected.status) {
                    TicketStatus.CANCELLED -> R.string.ticket_cancelled
                    TicketStatus.USED -> R.string.ticket_used
                    TicketStatus.ACTIVE -> if (current) R.string.ticket_active else R.string.ticket_past
                }), if (current) StatusTone.Success else StatusTone.Neutral)
                if (!s.history && selected.canDisplayQr(event, s.selectedNow)) {
                    TicketQr(t.qrPayload, t.displayNumber)
                    Text(stringResource(R.string.ticket_manual_code, t.manualCode.value), Modifier.testTag("ticket-manual-code"))
                    EventPassButton(stringResource(R.string.ticket_open_qr), onQr, Modifier.testTag("open-fullscreen"))
                    documents(selected)
                }
                EventPassButton(stringResource(R.string.ticket_to_event), { onEvent(selected.summary?.slug?.value ?: t.eventId.value) }, style = ActionStyle.Secondary)
                if (selected.status == TicketStatus.ACTIVE) EventPassButton(stringResource(R.string.ticket_cancel), onCancel, Modifier.testTag("cancel-ticket"), style = ActionStyle.Danger, loading = s.cancelling)
                s.cancelError?.let { InlineError(stringResource(when (it) {
                    TicketError.USED -> R.string.ticket_used_no_cancel
                    TicketError.FEATURE_UNAVAILABLE -> R.string.feature_unavailable
    TicketError.PROTOCOL -> R.string.auth_protocol
    TicketError.FORBIDDEN -> R.string.auth_forbidden
    TicketError.SERVER_UNAVAILABLE -> R.string.m02_server_unavailable
    TicketError.NETWORK -> R.string.auth_network
                    TicketError.TIMEOUT -> R.string.auth_timeout
                    TicketError.STORAGE_UNAVAILABLE -> R.string.auth_storage
                    TicketError.AUTHENTICATION_REQUIRED, TicketError.SESSION_CHANGED -> R.string.auth_session_changed
                    else -> R.string.ticket_missing
                })) }
                TextAction(stringResource(R.string.ticket_back), { onSelect(null) })
            }
        } else if (data != null) {
            val visible = data.tickets.filter { ticket -> data.scope != null || ticket.isCurrent(data.events[ticket.snapshot.eventId], data.now) != s.history }
            if (visible.isEmpty()) EmptyState(stringResource(if (s.history) R.string.ticket_empty_history else R.string.ticket_empty), stringResource(R.string.ticket_empty_hint))
            visible.forEach { ticket -> EventPassSection {
                Text(ticket.snapshot.eventTitle, style = EventPassTypography.cardTitle)
                Text(formatEventTime(ticket.snapshot.startsAt, ticket.snapshot.zoneId))
                StatusBadge(stringResource(when (ticket.status) { TicketStatus.ACTIVE -> if (s.history) R.string.ticket_past else R.string.ticket_active; TicketStatus.USED -> R.string.ticket_used; TicketStatus.CANCELLED -> R.string.ticket_cancelled }), StatusTone.Neutral)
                EventPassButton(stringResource(R.string.ticket_number, ticket.snapshot.displayNumber), { onSelect(ticket.snapshot.ticketId.value) }, Modifier.testTag("ticket-${ticket.snapshot.ticketId.value}"))
            } }
        }
        if(ru.eventpass.app.BuildConfig.FLAVOR == "demo") {
        TextAction(stringResource(R.string.auth_demo_tools), onTools)
        EventPassButton(stringResource(R.string.open_demo), onDemo, Modifier.testTag("open-demo"))
        EventPassButton(stringResource(R.string.open_gallery), onGallery, Modifier.testTag("open-gallery"), style = ActionStyle.Secondary)
        }
    }
    }
}
