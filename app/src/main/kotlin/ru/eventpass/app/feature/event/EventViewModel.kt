package ru.eventpass.app.feature.event

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.eventpass.core.domain.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

data class RegistrationForm(val name: String = "", val email: String = "", val phone: String = "",
    val answers: Map<String, Answer> = emptyMap(), val consent: Boolean = false)
sealed interface EventAction {
    val id: String
    data class Login(val email: String, override val id: String = UUID.randomUUID().toString()) : EventAction
    data class Ticket(val ticketId: String, override val id: String = UUID.randomUUID().toString()) : EventAction
}
data class EventUiState(val loading: Boolean = true, val data: PublicEventResult.Found? = null,
    val refreshing: Boolean = false,
    val notFound: Boolean = false, val readError: ProductReadError? = null, val form: RegistrationForm = RegistrationForm(),
    val submitting: Boolean = false, val error: RegistrationError? = null, val existingGuest: Boolean = false,
    val action: EventAction? = null, val receipt: GuestReceipt? = null, val fullscreen: Boolean = false,
    val receiptStatus: TicketStatus = TicketStatus.ACTIVE)

@HiltViewModel
class EventViewModel @Inject constructor(private val events: EventRepository, private val registrations: RegistrationRepository,
    private val tickets: TicketRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val receipt = GuestReceiptState(saved)
    private val mutable = MutableStateFlow(EventUiState(receipt = receipt.read(), fullscreen = saved["guestFullscreen"] ?: false,
        receiptStatus = saved.get<String>("guestReceiptStatus")?.let { value -> TicketStatus.entries.find { it.name == value } } ?: TicketStatus.ACTIVE))
    val state = mutable.asStateFlow()
    private var reference: PublicEventReference? = null
    private var submitJob: Job? = null
    private var pendingCommand: GuestRegistrationCommand? = null
    // Invalidate commands on owner/actor/resource exit, never on a questions refresh.
    private var commandGeneration = 0L
    private var owner = 0L; private var observer: Job? = null; private var read: Job? = null
    private var active = false
    fun bind(id: String) = bind(PublicEventReference.Internal(EventId(id)))
    fun bind(key: PublicEventReference) {
        if (active && reference == key) return
        stopReading()
        if (reference != null && reference != key) { close(); mutable.value = EventUiState() }
        reference = key; active = true; val generation = owner
        mutable.value = state.value.copy(loading = state.value.data == null && !state.value.notFound)
        observer = viewModelScope.launch { events.observePublicEvent(key).collect { result -> if (generation == owner) {
            val current = mutable.value
            val identityChanged = result is PublicEventResult.Found && current.data != null &&
                (current.data.event.id != result.event.id || current.data.contact?.email != result.contact?.email)
            val denied = result is PublicEventResult.Failed && result.error == ProductReadError.FORBIDDEN
            if (identityChanged || denied || result == PublicEventResult.NotFound) {
                commandGeneration++; submitJob?.cancel()
            }
            val stale = result is PublicEventResult.Found && current.data != null && current.data.event.questions != result.event.questions
            val keptAnswers = if (result is PublicEventResult.Found && stale) current.form.answers.filter { (id, answer) ->
                val before = current.data?.event?.questions?.find { it.id == id }
                val after = result.event.questions.find { it.id == id }
                if(before == null || after == null || before.toDraftQuestion().kind != after.toDraftQuestion().kind) false
                else when(answer) {
                    is Answer.SingleChoice -> (after as? RegistrationQuestion.SingleChoice)?.options?.any { it.id == answer.optionId } == true
                    is Answer.MultipleChoice -> (after as? RegistrationQuestion.MultipleChoice)?.options?.map { it.id }?.containsAll(answer.optionIds) == true
                    else -> true
                }
            } else current.form.answers
            mutable.value = when (result) {
                is PublicEventResult.Found -> current.copy(loading = false, data = result, notFound = false, readError = null,
                    form = if (current.data == null || identityChanged) RegistrationForm(result.contact?.name.orEmpty(), result.contact?.email.orEmpty(), result.contact?.phone.orEmpty()) else current.form.copy(answers = keptAnswers),
                    error = if (identityChanged) null else if (stale && !current.submitting) RegistrationError.StaleForm else current.error,
                    action = if (identityChanged) null else current.action,
                    submitting = current.submitting && !identityChanged, existingGuest = current.existingGuest && !stale && !identityChanged)
                PublicEventResult.NotFound -> current.copy(loading = false, data = null, notFound = true, readError = null, form = RegistrationForm(), submitting = false, action = null)
                is PublicEventResult.Failed -> if (denied) current.copy(loading = false, data = null, readError = result.error,
                    form = RegistrationForm(), submitting = false, action = null) else current.copy(loading = false, readError = result.error)
            }
        } } }
    }
    fun stopReading() {
        active = false; owner++; observer?.cancel(); read?.cancel()
        mutable.value = state.value.copy(loading = false, refreshing = false)
    }
    fun edit(form: RegistrationForm) { if (saved.get<Boolean>("registrationPending") == true && !mutable.value.submitting) { mutable.value = mutable.value.copy(error = RegistrationError.PendingRetry); return }; if (!mutable.value.submitting) mutable.value = mutable.value.copy(form = form, error = null, existingGuest = false) }
    fun refresh() {
        val id = reference ?: return
        if (!active || state.value.receipt != null || read?.isActive == true) return
        val generation = owner; mutable.value = state.value.copy(refreshing = !state.value.loading)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { events.refreshPublicEvent(id) }
            finally { if (generation == owner) mutable.value = state.value.copy(loading = false, refreshing = false) }
        }
        read = job; job.start()
    }
    fun leaveForm() {
        commandGeneration++; submitJob?.cancel(); pendingCommand = null
        val contact = mutable.value.data?.contact
        mutable.value = mutable.value.copy(form = RegistrationForm(contact?.name.orEmpty(), contact?.email.orEmpty(), contact?.phone.orEmpty()), submitting = false, error = null)
    }
    fun fullscreen(value: Boolean) { saved["guestFullscreen"] = value; mutable.value = mutable.value.copy(fullscreen = value) }
    suspend fun refreshReceipt() {
        val current=state.value.receipt ?: return
        val capability=current.ticket.guestAccess ?: return
        if(state.value.receiptStatus != TicketStatus.ACTIVE) return
        val generation=owner
        val result=tickets.observeGuestTicket(current.ticket.ticketId,capability).first()
        currentCoroutineContext().ensureActive()
        if(!active || generation != owner || state.value.receipt != current) return
        if(result is OwnTicketResult.Found) {
            saved["guestReceiptStatus"]=result.ticket.status.name
            mutable.value=state.value.copy(receiptStatus=result.ticket.status,
                fullscreen=state.value.fullscreen && result.ticket.status == TicketStatus.ACTIVE)
        }
    }
    fun close() { leaveForm(); saved.remove<Boolean>("registrationPending"); saved.remove<String>("guestReceiptStatus"); receipt.clear(); mutable.value = mutable.value.copy(receipt = null, fullscreen = false, receiptStatus = TicketStatus.ACTIVE) }
    fun consume(id: String) { if (mutable.value.action?.id == id) mutable.value = mutable.value.copy(action = null) }
    fun openOwnTicket() = viewModelScope.launch {
        val generation = owner; if (!active) return@launch
        val serverTicket = mutable.value.data?.ownTicketId
        if (serverTicket != null) { mutable.value = mutable.value.copy(action = EventAction.Ticket(serverTicket.value)); return@launch }
        val result = tickets.observeMyTickets().first()
        currentCoroutineContext().ensureActive(); if (generation != owner) return@launch
        val ticket = (result as? MyTicketsResult.Ready)?.tickets?.firstOrNull { it.snapshot.eventId == mutable.value.data?.event?.id && it.status != TicketStatus.CANCELLED }
        if (ticket != null) mutable.value = mutable.value.copy(action = EventAction.Ticket(ticket.snapshot.ticketId.value))
        else mutable.value = mutable.value.copy(error = when (result) {
            is MyTicketsResult.Failed -> when (result.error) {
                ProductReadError.FEATURE_UNAVAILABLE -> RegistrationError.FeatureUnavailable
    ProductReadError.NETWORK -> RegistrationError.Network
                ProductReadError.PROTOCOL -> RegistrationError.Protocol
                ProductReadError.FORBIDDEN -> RegistrationError.Forbidden
                ProductReadError.SERVER_UNAVAILABLE -> RegistrationError.ServerUnavailable
                ProductReadError.TIMEOUT -> RegistrationError.Timeout
                ProductReadError.STORAGE_UNAVAILABLE -> RegistrationError.StorageUnavailable
            }
            is MyTicketsResult.Ready -> RegistrationError.NotFound
            else -> RegistrationError.AuthenticationRequired
        })
    }
    fun submit() {
        val s = mutable.value; val data = s.data ?: return
        if (s.submitting || s.receipt != null || s.readError != null) return
        val f = s.form
        if(saved.get<Boolean>("registrationPending") == true && pendingCommand == null) { mutable.value = s.copy(error = RegistrationError.RecoveryRequired); return }
        val command = pendingCommand ?: GuestRegistrationCommand(data.event.id, data.event.localSchemaRevision, f.name,
            data.contact?.email ?: f.email, f.phone, f.answers, f.consent)
        // A pending operation must replay its original body/key even if the questionnaire has changed.
        // Only a new logical command is validated against the currently displayed schema.
        if (pendingCommand == null) {
            val validation = RegistrationValidator.validate(command, data.event.questions, data.policy)
            if (validation is RegistrationValidation.Invalid) { mutable.value = s.copy(error = RegistrationError.Validation(validation.errors)); return }
        }
        pendingCommand = command; saved["registrationPending"] = true
        val version = commandGeneration
        mutable.value = s.copy(submitting = true, error = null)
        submitJob = viewModelScope.launch {
            try {
                if (data.contact == null) {
                    val result = registrations.submitGuest(command)
                    currentCoroutineContext().ensureActive()
                    if (version != commandGeneration) return@launch
                    when (result) {
                        is GuestRegistrationOutcome.Issued -> {
                            pendingCommand = null; saved.remove<Boolean>("registrationPending")
                            receipt.write(result.ticket, result.email, result.accountCreated) // Commit saved state before publishing the receipt.
                            saved.remove<String>("guestReceiptStatus")
                            mutable.value = mutable.value.copy(receipt = receipt.read(), form = RegistrationForm(), receiptStatus = TicketStatus.ACTIVE)
                        }
                        is GuestRegistrationOutcome.RequiresLogin -> { saved.remove<Boolean>("registrationPending"); leaveForm(); mutable.value = mutable.value.copy(action = EventAction.Login(result.email)) }
                        is GuestRegistrationOutcome.ExistingRegistration -> { pendingCommand = null; saved.remove<Boolean>("registrationPending"); mutable.value = mutable.value.copy(existingGuest = true) }
                        is GuestRegistrationOutcome.Failed -> fail(result.error)
                    }
                } else {
                    val result = registrations.submitAuthenticated(AuthenticatedRegistrationCommand(command.eventId, command.localSchemaRevision,
                        command.name, command.phone, command.answers, command.consentAccepted, command.operationId))
                    currentCoroutineContext().ensureActive()
                    if (version != commandGeneration) return@launch
                    when (result) {
                        is AuthenticatedRegistrationOutcome.Registered -> { pendingCommand = null; saved.remove<Boolean>("registrationPending"); mutable.value = mutable.value.copy(action = EventAction.Ticket(result.ticketId.value), form = RegistrationForm()) }
                        is AuthenticatedRegistrationOutcome.Failed -> fail(result.error)
                    }
                }
            } finally {
                // A cancelled call may finish after leaveForm and a newer submission.
                if (version == commandGeneration) mutable.value = mutable.value.copy(submitting = false)
            }
        }
    }
    private fun fail(error: RegistrationError) {
        if(error !is RegistrationError.RateLimited && error !in listOf(RegistrationError.Network, RegistrationError.Timeout, RegistrationError.ServerUnavailable, RegistrationError.Protocol, RegistrationError.PendingRetry, RegistrationError.RecoveryRequired)) {
            pendingCommand = null; saved.remove<Boolean>("registrationPending")
        }
        mutable.value = mutable.value.copy(error = error)
        if (error == RegistrationError.StaleForm) { leaveForm(); mutable.value = mutable.value.copy(error = error); refresh() }
    }
    override fun onCleared() {
        receipt.clear()
        mutable.value = mutable.value.copy(receipt = null, form = RegistrationForm(), fullscreen = false)
    }
}
