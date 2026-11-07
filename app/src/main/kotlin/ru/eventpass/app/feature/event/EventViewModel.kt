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
    val action: EventAction? = null, val receipt: GuestReceipt? = null, val fullscreen: Boolean = false)

@HiltViewModel
class EventViewModel @Inject constructor(private val events: EventRepository, private val registrations: RegistrationRepository,
    private val tickets: TicketRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val receipt = GuestReceiptState(saved)
    private val mutable = MutableStateFlow(EventUiState(receipt = receipt.read(), fullscreen = saved["guestFullscreen"] ?: false))
    val state = mutable.asStateFlow()
    private var eventId: EventId? = null
    private var submitJob: Job? = null
    private var formVersion = 0L
    private var owner = 0L; private var observer: Job? = null; private var read: Job? = null
    private var active = false
    fun bind(id: String) {
        val key = EventId(id)
        if (active && eventId == key) return
        stopReading()
        if (eventId != null && eventId != key) { close(); mutable.value = EventUiState() }
        eventId = key; active = true; val generation = owner
        mutable.value = state.value.copy(loading = state.value.data == null && !state.value.notFound)
        observer = viewModelScope.launch { events.observePublicEvent(key).collect { result -> if (generation == owner) {
            val current = mutable.value
            val stale = result is PublicEventResult.Found && current.data?.event?.formVersion?.let { it != result.event.formVersion } == true
            if (stale) { formVersion++; submitJob?.cancel() }
            mutable.value = when (result) {
                is PublicEventResult.Found -> current.copy(loading = false, data = result, notFound = false, readError = null,
                    form = if (current.data == null || stale) RegistrationForm(result.contact?.name.orEmpty(), result.contact?.email.orEmpty(), result.contact?.phone.orEmpty()) else current.form,
                    error = if (stale) RegistrationError.StaleForm else current.error,
                    submitting = current.submitting && !stale, existingGuest = current.existingGuest && !stale)
                PublicEventResult.NotFound -> current.copy(loading = false, data = null, notFound = true, readError = null)
                is PublicEventResult.Failed -> current.copy(loading = false, readError = result.error)
            }
        } } }
    }
    fun stopReading() {
        active = false; owner++; observer?.cancel(); read?.cancel()
        mutable.value = state.value.copy(loading = false, refreshing = false)
    }
    fun edit(form: RegistrationForm) { if (!mutable.value.submitting) mutable.value = mutable.value.copy(form = form, error = null, existingGuest = false) }
    fun refresh() {
        val id = eventId ?: return
        if (!active || state.value.receipt != null || read?.isActive == true) return
        val generation = owner; mutable.value = state.value.copy(refreshing = !state.value.loading)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { events.refreshPublicEvent(id) }
            finally { if (generation == owner) mutable.value = state.value.copy(loading = false, refreshing = false) }
        }
        read = job; job.start()
    }
    fun leaveForm() {
        formVersion++; submitJob?.cancel()
        val contact = mutable.value.data?.contact
        mutable.value = mutable.value.copy(form = RegistrationForm(contact?.name.orEmpty(), contact?.email.orEmpty(), contact?.phone.orEmpty()), submitting = false, error = null)
    }
    fun fullscreen(value: Boolean) { saved["guestFullscreen"] = value; mutable.value = mutable.value.copy(fullscreen = value) }
    fun close() { leaveForm(); receipt.clear(); mutable.value = mutable.value.copy(receipt = null, fullscreen = false) }
    fun consume(id: String) { if (mutable.value.action?.id == id) mutable.value = mutable.value.copy(action = null) }
    fun openOwnTicket() = viewModelScope.launch {
        val generation = owner; if (!active) return@launch
        val result = tickets.observeMyTickets().first()
        currentCoroutineContext().ensureActive(); if (generation != owner) return@launch
        val ticket = (result as? MyTicketsResult.Ready)?.tickets?.firstOrNull { it.snapshot.eventId == eventId && it.status != TicketStatus.CANCELLED }
        if (ticket != null) mutable.value = mutable.value.copy(action = EventAction.Ticket(ticket.snapshot.ticketId.value))
        else mutable.value = mutable.value.copy(error = when (result) {
            is MyTicketsResult.Failed -> when (result.error) {
                ProductReadError.NETWORK -> RegistrationError.Network
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
        val command = GuestRegistrationCommand(data.event.id, data.event.formVersion, f.name,
            data.contact?.email ?: f.email, f.phone, f.answers, f.consent)
        val validation = RegistrationValidator.validate(command, data.event.questions, data.policy)
        if (validation is RegistrationValidation.Invalid) { mutable.value = s.copy(error = RegistrationError.Validation(validation.errors)); return }
        val version = formVersion
        mutable.value = s.copy(submitting = true, error = null)
        submitJob = viewModelScope.launch {
            try {
                if (data.contact == null) {
                    val result = registrations.submitGuest(command)
                    if (version != formVersion) return@launch
                    when (result) {
                        is GuestRegistrationOutcome.Issued -> {
                            receipt.write(result.ticket, result.email) // Commit saved state before publishing the receipt.
                            mutable.value = mutable.value.copy(receipt = receipt.read(), form = RegistrationForm())
                        }
                        is GuestRegistrationOutcome.RequiresLogin -> { leaveForm(); mutable.value = mutable.value.copy(action = EventAction.Login(result.email)) }
                        is GuestRegistrationOutcome.ExistingRegistration -> mutable.value = mutable.value.copy(existingGuest = true)
                        is GuestRegistrationOutcome.Failed -> fail(result.error)
                    }
                } else {
                    val result = registrations.submitAuthenticated(AuthenticatedRegistrationCommand(command.eventId, command.formVersion,
                        command.name, command.phone, command.answers, command.consentAccepted))
                    if (version != formVersion) return@launch
                    when (result) {
                        is AuthenticatedRegistrationOutcome.Registered -> mutable.value = mutable.value.copy(action = EventAction.Ticket(result.ticketId.value), form = RegistrationForm())
                        is AuthenticatedRegistrationOutcome.Failed -> fail(result.error)
                    }
                }
            } finally {
                // A cancelled call may finish after leaveForm and a newer submission.
                if (version == formVersion) mutable.value = mutable.value.copy(submitting = false)
            }
        }
    }
    private fun fail(error: RegistrationError) {
        mutable.value = mutable.value.copy(error = error)
        if (error == RegistrationError.StaleForm) { leaveForm(); mutable.value = mutable.value.copy(error = error); refresh() }
    }
    override fun onCleared() {
        receipt.clear()
        mutable.value = mutable.value.copy(receipt = null, form = RegistrationForm(), fullscreen = false)
    }
}
