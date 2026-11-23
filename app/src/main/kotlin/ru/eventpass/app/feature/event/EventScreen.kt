package ru.eventpass.app.feature.event

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.eventpass.app.R
import ru.eventpass.app.feature.auth.AuthColumn
import ru.eventpass.app.feature.tickets.*
import ru.eventpass.app.navigation.ReportInitialContent
import ru.eventpass.app.ui.LoadingLayout
import ru.eventpass.app.ui.LoadingSkeleton
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

fun formatEventTime(time: Instant, zone: String): String = DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("ru")).withZone(ZoneId.of(zone)).format(time)

@Composable
fun closureText(reason: RegistrationClosureReason): String = stringResource(when (reason) {
    RegistrationClosureReason.FULL -> R.string.event_full
    RegistrationClosureReason.CLOSED -> R.string.event_closed
    RegistrationClosureReason.STARTED -> R.string.event_started
    RegistrationClosureReason.FINISHED -> R.string.event_finished
    RegistrationClosureReason.CANCELLED -> R.string.event_cancelled
    RegistrationClosureReason.NOT_PUBLISHED -> R.string.event_not_published
})
@Composable
fun readErrorText(error: ProductReadError) = stringResource(when (error) {
    ProductReadError.FEATURE_UNAVAILABLE -> R.string.feature_unavailable
    ProductReadError.PROTOCOL -> R.string.auth_protocol
    ProductReadError.FORBIDDEN -> R.string.auth_forbidden
    ProductReadError.SERVER_UNAVAILABLE -> R.string.m02_server_unavailable
    ProductReadError.NETWORK -> R.string.auth_network
    ProductReadError.TIMEOUT -> R.string.auth_timeout
    ProductReadError.STORAGE_UNAVAILABLE -> R.string.auth_storage
})
@Composable
fun registrationErrorText(error: RegistrationError): String = when (error) {
    is RegistrationError.Unavailable -> closureText(error.reason)
    is RegistrationError.Validation -> stringResource(R.string.event_validation)
    else -> stringResource(when (error) {
        is RegistrationError.RateLimited -> R.string.auth_cooldown
        RegistrationError.PendingRetry -> R.string.m03_pending_retry
        RegistrationError.RecoveryRequired -> R.string.m03_recovery_required
        RegistrationError.Protocol -> R.string.auth_protocol
        RegistrationError.Forbidden -> R.string.auth_forbidden
        RegistrationError.ServerUnavailable -> R.string.m02_server_unavailable
        RegistrationError.FeatureUnavailable -> R.string.feature_unavailable
    RegistrationError.NotFound -> R.string.event_not_found
        RegistrationError.StaleForm -> R.string.event_stale
        RegistrationError.Network -> R.string.auth_network
        RegistrationError.Timeout -> R.string.auth_timeout
        RegistrationError.StorageUnavailable -> R.string.auth_storage
        RegistrationError.AuthenticationRequired -> R.string.auth_login
        else -> R.string.auth_session_changed
    })
}

@Composable
fun EventRoute(eventId: String, onAuth: (String, Boolean, Boolean) -> Unit, onTicket: (String) -> Unit, onClose: () -> Unit,
    visible: Boolean = true, vm: EventViewModel = hiltViewModel(), publicSlug: String? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    ReportInitialContent(visible && (state.data != null || state.receipt != null || state.notFound || state.readError != null))
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    LaunchedEffect(eventId, publicSlug, lifecycle, visible) { if (visible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        vm.bind(publicSlug?.let { PublicEventReference.Slug(EventSlug(it)) } ?: PublicEventReference.Internal(EventId(eventId))); vm.refresh(); try { awaitCancellation() } finally { vm.stopReading() }
    } }
    DisposableEffect(vm, activity) { onDispose {
        vm.stopReading()
        if (activity?.isChangingConfigurations != true) vm.leaveForm()
    } }
    LaunchedEffect(state.action) { when (val action = state.action) {
        is EventAction.Login -> { onAuth(action.email, false, true); vm.consume(action.id) }
        is EventAction.Ticket -> { onTicket(action.ticketId); vm.consume(action.id) }
        null -> Unit
    } }
    val receipt = state.receipt
    LaunchedEffect(receipt?.ticket?.ticketId, lifecycle, visible) {
        if(visible && receipt != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while(isActive) { vm.refreshReceipt(); delay(5000) }
        }
    }
    if (receipt != null) {
        GuestReceiptScreen(receipt, { vm.fullscreen(true) }, { onAuth(receipt.email, false, false) },
            { onAuth(receipt.email, true, false) }, { vm.close(); onClose() }, state.receiptStatus)
        if (state.fullscreen && state.receiptStatus == TicketStatus.ACTIVE) FullscreenTicketQr(receipt.ticket.qrPayload, receipt.ticket.displayNumber) { vm.fullscreen(false) }
    } else EventScreen(eventId, state, vm::edit, vm::submit, vm::refresh, { vm.openOwnTicket() }, {
        val email = state.form.email; vm.leaveForm(); onAuth(email, false, true)
    }, cover = { cover -> ru.eventpass.app.feature.editor.ManagedCover(cover,state.data?.event?.remoteCoverAssetId) }, visible = visible)
}

@Composable
fun EventScreen(eventId: String, s: EventUiState, onEdit: (RegistrationForm) -> Unit, onSubmit: () -> Unit,
    onRefresh: () -> Unit, onOwn: () -> Unit, onLogin: () -> Unit, cover: @Composable (EventCover?) -> Unit = {}, visible: Boolean = true) {
    EventPassRefresh(s.refreshing, onRefresh, Modifier.testTag("refresh-event"), enabled = visible && !s.loading && !s.submitting && s.receipt == null && !s.fullscreen) {
    AuthColumn(Modifier.testTag("event-screen-$eventId").semantics { testTagsAsResourceId = true }) {
        val data = s.data
        if (s.loading && data == null) LoadingSkeleton(LoadingLayout.Event)
        s.readError?.let { InlineError(readErrorText(it)); EventPassButton(stringResource(R.string.ticket_retry), onRefresh) }
        if (s.notFound) Text(stringResource(R.string.event_not_found), Modifier.testTag("event-not-found"))
        if (data != null) {
            val e = data.event
            Row(horizontalArrangement = Arrangement.spacedBy(EventPassDimens.small)) {
                StatusDot(if (data.availability is RegistrationAvailability.Open) stringResource(R.string.event_open)
                    else closureText((data.availability as RegistrationAvailability.Closed).reason), StatusTone.Neutral)
                StatusBadge(stringResource(if (e.priceType == PriceType.FREE) R.string.event_free else R.string.event_paid), StatusTone.Neutral)
            }
            Text(e.title, style = EventPassTypography.heading, modifier = Modifier.testTag("event-$eventId"))
            Text(e.description)
            EventPassSection(Modifier.fillMaxWidth()) {
                KeyValueRow(stringResource(R.string.event_date), formatEventTime(e.startsAt, e.zoneId))
                KeyValueRow(stringResource(R.string.event_venue), e.venue)
                KeyValueRow(stringResource(R.string.event_places), when (val availability = data.availability) {
                    is RegistrationAvailability.Closed -> closureText(availability.reason)
                    is RegistrationAvailability.Open -> when (val places = availability.places) {
                        AvailablePlaces.Unlimited -> stringResource(R.string.event_unlimited)
                        is AvailablePlaces.Limited -> stringResource(R.string.event_remaining, places.remaining)
                    }
                })
            }
            cover(e.cover)
            if (e.sections.isNotEmpty()) Text(stringResource(R.string.event_program), style = EventPassTypography.label, color = EventPassColors.muted)
            e.sections.forEachIndexed { index, section -> EventPassSection(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EventPassDimens.medium)) {
                    Box(Modifier.size(32.dp).background(EventPassColors.raised, EventPassShapes.control), contentAlignment = Alignment.Center) {
                        Text("${index + 1}".padStart(2, '0'), style = EventPassTypography.label, color = EventPassColors.accentLight)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(EventPassDimens.tiny)) {
                        Text(section.title, style = EventPassTypography.emphasis)
                        Text(section.time, style = EventPassTypography.hint, color = EventPassColors.muted)
                    }
                }
            } }
            if (data.contact == null) TextAction(stringResource(R.string.auth_login), onLogin, Modifier.testTag("event-login"))
            if (data.access.canViewOwnTicket) {
                EventPassButton(stringResource(R.string.ticket_own), onOwn)
                s.error?.let { InlineError(registrationErrorText(it)) }
            }
            else if (data.availability is RegistrationAvailability.Open) EventPassSection {
                Text(stringResource(R.string.event_registration), style = EventPassTypography.cardTitle)
                Text(stringResource(R.string.event_description), style = EventPassTypography.hint)
                val errors = (s.error as? RegistrationError.Validation)?.fields.orEmpty()
                @Composable fun issue(field: RegistrationField): String? = errors.firstOrNull { it.field == field }?.let {
                    stringResource(if (it.issue == FieldIssue.REQUIRED) R.string.event_field_required else R.string.event_field_invalid)
                }
                val f = s.form
                FormField(f.name, { onEdit(f.copy(name = it)) }, stringResource(R.string.auth_name), Modifier.testTag("registration-name"), enabled = data.contact == null && !s.submitting, error = issue(RegistrationField.Name))
                FormField(data.contact?.email ?: f.email, { onEdit(f.copy(email = it)) }, stringResource(R.string.auth_email), Modifier.testTag("registration-email"),
                    enabled = data.contact == null && !s.submitting, error = issue(RegistrationField.Email), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                if (data.policy.phone != FieldRequirement.HIDDEN) FormField(f.phone, { onEdit(f.copy(phone = it)) },
                    stringResource(if (data.policy.phone == FieldRequirement.REQUIRED) R.string.event_phone_required else R.string.event_phone_optional),
                    Modifier.testTag("registration-phone"), enabled = data.contact == null && !s.submitting, error = issue(RegistrationField.Phone), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                e.questions.forEachIndexed { index, q -> key(q.id) {
                    val answer = f.answers[q.id]
                    fun answer(value: Answer) { onEdit(f.copy(answers = f.answers + (q.id to value))) }
                    val title = if (q.required) stringResource(R.string.event_question_required, q.title) else q.title
                    val questionError=issue(RegistrationField.Question(q.id))
                    Column(Modifier.fillMaxWidth().background(EventPassColors.surface,EventPassShapes.section)
                        .border(1.dp,if(questionError != null) EventPassColors.danger else EventPassColors.raised,EventPassShapes.section)
                        .padding(EventPassDimens.large),
                        verticalArrangement=Arrangement.spacedBy(EventPassDimens.small)) {
                    Text(stringResource(R.string.ui_registration_question,index + 1),style=EventPassTypography.label,color=EventPassColors.muted)
                    when (q) {
                        is RegistrationQuestion.Text -> if(q.format == TextFormat.DATE) TemporalField(
                            (answer as? Answer.Text)?.value.orEmpty(),{answer(Answer.Text(it))},title,
                            input=TemporalInput.DATE,enabled=!s.submitting,error=questionError,supportingText=q.help,allowClear=!q.required)
                        else FormField((answer as? Answer.Text)?.value.orEmpty(), { answer(Answer.Text(it)) }, title, enabled = !s.submitting, error = issue(RegistrationField.Question(q.id)), placeholder = q.placeholder.orEmpty(), supportingText = q.help, singleLine = q.format != TextFormat.TEXTAREA,
                            keyboardOptions = KeyboardOptions(keyboardType = when(q.format) { TextFormat.EMAIL -> KeyboardType.Email; TextFormat.TEL -> KeyboardType.Phone; TextFormat.NUMBER -> KeyboardType.Decimal; else -> KeyboardType.Text }))
                        is RegistrationQuestion.Checkbox -> ConsentCheckbox((answer as? Answer.Checkbox)?.checked == true, { answer(Answer.Checkbox(it)) }, title, enabled = !s.submitting)
                        is RegistrationQuestion.SingleChoice -> if(q.format == ChoiceFormat.SELECT) {
                            SelectField((answer as? Answer.SingleChoice)?.optionId,
                                q.options.map { SelectOption(it.id,it.title,it.hint) },
                                { answer(Answer.SingleChoice(it)) },title,Modifier.fillMaxWidth(),enabled=!s.submitting,
                                error=questionError,placeholder=q.placeholder?.takeIf(String::isNotBlank) ?: stringResource(R.string.ui_select_placeholder),supportingText=q.help)
                        } else { Text(title, style = EventPassTypography.label); Column(Modifier.selectableGroup()) { q.options.forEach { option ->
                            RadioOption(option.title, option.hint, (answer as? Answer.SingleChoice)?.optionId == option.id, { answer(Answer.SingleChoice(option.id)) }, enabled = !s.submitting)
                        } } }
                        is RegistrationQuestion.MultipleChoice -> {
                            Text(title, style = EventPassTypography.label)
                            q.options.forEach { option ->
                                val selected = (answer as? Answer.MultipleChoice)?.optionIds.orEmpty()
                                ConsentCheckbox(option.id in selected, { checked -> answer(Answer.MultipleChoice(if(checked) (selected + option.id).distinct() else selected - option.id)) }, option.title, enabled = !s.submitting)
                                option.hint?.let { Text(it, style = EventPassTypography.hint) }
                            }
                        }
                        is RegistrationQuestion.Unsupported -> InlineError(stringResource(R.string.event_unsupported_question))
                    }
                    if (q !is RegistrationQuestion.Text && !(q is RegistrationQuestion.SingleChoice && q.format == ChoiceFormat.SELECT)) {
                        FieldMessage(questionError,q.help?.takeIf(String::isNotBlank) ?: q.placeholder?.takeIf { it.isNotBlank() && answer == null })
                    }
                    }
                }
                }
                Column(verticalArrangement=Arrangement.spacedBy(EventPassDimens.tiny)) {
                ConsentCheckbox(f.consent, { onEdit(f.copy(consent = it)) }, stringResource(R.string.auth_consent), Modifier.testTag("registration-consent"), enabled = !s.submitting)
                FieldMessage(issue(RegistrationField.Consent))
                }
                s.error?.takeUnless { it is RegistrationError.Validation }?.let { InlineError(registrationErrorText(it)) }
                if (s.existingGuest) InlineError(stringResource(R.string.event_existing))
                EventPassButton(stringResource(R.string.event_get_ticket), onSubmit, Modifier.fillMaxWidth().testTag("registration-submit"),
                    enabled = s.readError == null && !s.existingGuest, loading = s.submitting, icon = EventPassGlyph.TicketAction)
                Text(stringResource(if(ru.eventpass.app.BuildConfig.FLAVOR == "demo") R.string.event_ticket_hint_demo else R.string.event_ticket_hint), style = EventPassTypography.hint)
            }
        }
    }
    }
}

@Composable
fun GuestReceiptScreen(receipt: GuestReceipt, onQr: () -> Unit, onLogin: () -> Unit, onSignUp: () -> Unit, onClose: () -> Unit,
    status: TicketStatus = TicketStatus.ACTIVE) {
    AuthColumn(Modifier.semantics { testTagsAsResourceId = true }) {
        Text(stringResource(R.string.ticket_sent), style = EventPassTypography.heading, modifier = Modifier.testTag("guest-receipt"))
        Text(stringResource(R.string.m03_mail_sent, receipt.email))
        EventPassSection(Modifier.fillMaxWidth()) {
            Text(receipt.ticket.eventTitle, style = EventPassTypography.cardTitle)
            KeyValueRow(stringResource(R.string.event_date), formatEventTime(receipt.ticket.startsAt, receipt.ticket.zoneId))
            KeyValueRow(stringResource(R.string.event_venue), receipt.ticket.venue)
            Text(stringResource(R.string.ticket_number, receipt.ticket.displayNumber))
            if(status == TicketStatus.USED) ResultMark(stringResource(R.string.ui_entry_allowed),StatusTone.Success,Modifier.fillMaxWidth())
            else if(status == TicketStatus.CANCELLED) InlineError(stringResource(R.string.ticket_cancelled))
            else {
            TicketQr(receipt.ticket.qrPayload, receipt.ticket.displayNumber, onQr)
            Text(stringResource(R.string.ticket_manual_code, receipt.ticket.manualCode.value), Modifier.testTag("ticket-manual-code"))
            }
        }
        if (receipt.ticket.guestAccess != null) ru.eventpass.app.feature.documents.DocumentActions(
            ru.eventpass.core.domain.documents.DocumentRequest.TicketPdf(receipt.ticket.ticketId, receipt.ticket.guestAccess))
        Text(stringResource(R.string.ticket_guest_hint))
        if(!receipt.accountCreated) EventPassButton(stringResource(R.string.auth_signup), onSignUp)
        else Text(stringResource(R.string.m03_guest_account_hint))
        TextAction(stringResource(R.string.auth_login), onLogin)
        TextAction(stringResource(R.string.ticket_close_flow), onClose, Modifier.testTag("close-guest-flow"))
    }
}
