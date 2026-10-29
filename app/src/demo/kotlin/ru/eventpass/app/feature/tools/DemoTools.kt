package ru.eventpass.app.feature.tools

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dagger.hilt.android.EntryPointAccessors
import java.time.Duration
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import ru.eventpass.app.R
import ru.eventpass.app.di.DemoBackendEntryPoint
import ru.eventpass.app.feature.auth.AuthColumn
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.auth.AuthError
import ru.eventpass.core.domain.auth.SessionState
import ru.eventpass.core.domain.event.PublicEventResult
import ru.eventpass.data.fake.product.FakeFixtures
import ru.eventpass.app.navigation.auth.AccessOutcome
import ru.eventpass.app.navigation.auth.ReturnDestination

/** Deliberately src/demo. No concrete fake escapes to a production ViewModel/Screen. */
@Composable
fun DemoTools(outbox: Boolean, onGallery: () -> Unit, onCounter: () -> Unit, onOutbox: () -> Unit, onLink: (String) -> Unit,
    onGuard: (AccessOutcome, ReturnDestination) -> Unit = { _, _ -> }) {
    val context = LocalContext.current.applicationContext
    val entry = remember(context) { EntryPointAccessors.fromApplication(context, DemoBackendEntryPoint::class.java) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableIntStateOf(0) }
    fun scenario(action: suspend () -> Unit) {
        scope.launch {
            try { action(); message = R.string.tools_action_done; revision++ }
            catch (_: java.io.IOException) { message = R.string.auth_storage }
        }
    }
    AuthColumn {
        Text(stringResource(if (outbox) R.string.auth_outbox else R.string.auth_demo_tools), style = EventPassTypography.heading)
        Text(stringResource(R.string.tools_disclaimer))
        if (message != 0) Text(stringResource(message))
        if (outbox) {
            val mail = remember(revision) { entry.environment().scenarios.authOutbox() }
            if (mail.isEmpty()) Text(stringResource(R.string.tools_empty))
            mail.asReversed().forEach { message ->
                Text(message.challenge.email)
                if (message.reset) Text(stringResource(R.string.tools_code, message.secret.value))
                else EventPassButton(stringResource(R.string.tools_confirm), {
                    onLink("eventpass-demo://demo/confirm/${message.challenge.id}?token=${message.secret.value}")
                })
            }
            TextAction(stringResource(R.string.tools_refresh), { revision++ })
            val tickets = remember(revision) { entry.environment().scenarios.outbox() }
            tickets.asReversed().forEach { Text(stringResource(R.string.tools_ticket_mail, it.email)) }
        } else {
            EventPassButton(stringResource(R.string.tools_open_event), { onLink("eventpass-demo://demo/e/open-registration") })
            EventPassButton(stringResource(R.string.open_gallery), onGallery)
            EventPassButton(stringResource(R.string.open_demo), onCounter)
            EventPassButton(stringResource(R.string.auth_outbox), onOutbox)
            TextAction(stringResource(R.string.tools_guard), { scope.launch {
                val event = entry.environment().events.observePublicEvent(FakeFixtures.OWNED).first()
                val access = when {
                    entry.auth().currentSession().state !is SessionState.Authenticated -> AccessOutcome.Unauthenticated
                    (event as? PublicEventResult.Found)?.access?.canScan == true -> AccessOutcome.Allowed
                    else -> AccessOutcome.Forbidden
                }
                onGuard(access, ReturnDestination.Event(FakeFixtures.OWNED.value))
            } })
            TextAction(stringResource(R.string.tools_network), { scope.launch { entry.environment().scenarios.failNextAuth(AuthError.Network) } })
            TextAction(stringResource(R.string.tools_timeout), { scope.launch { entry.environment().scenarios.failNextAuth(AuthError.Timeout) } })
            TextAction(stringResource(R.string.tools_advance), {
                scope.launch { entry.clock().advance(Duration.ofMinutes(31)); entry.auth().refreshSession(); revision++ }
            })
            TextAction(stringResource(R.string.tools_reset), {
                if (!busy) scope.launch { busy = true; try { entry.clock().reset(); entry.eventClock().reset(); entry.environment().scenarios.reset(); revision++ }
                    catch (_: java.io.IOException) { message = R.string.auth_storage } finally { busy = false } }
            }, enabled = !busy)
            Text(stringResource(R.string.tools_event_scenarios), style = EventPassTypography.cardTitle)
            val base = FakeFixtures.events().first { it.id == FakeFixtures.OPEN }
            TextAction(stringResource(R.string.tools_event_open), { scenario { entry.eventClock().reset(); entry.environment().scenarios.putEvent(base.copy(capacity = null)) } })
            TextAction(stringResource(R.string.tools_event_full), { scenario { entry.environment().scenarios.putEvent(base.copy(capacity = 0)) } })
            TextAction(stringResource(R.string.tools_event_closed), { scenario { entry.environment().scenarios.putEvent(base.copy(registrationOpen = false)) } })
            TextAction(stringResource(R.string.tools_event_started), { scenario {
                entry.environment().scenarios.putEvent(base.copy(startsAt = entry.eventClock().instant().minusSeconds(1), endsAt = null))
            } })
            TextAction(stringResource(R.string.tools_event_finished), { scenario { entry.environment().scenarios.putEvent(base.copy(lifecycle = ru.eventpass.core.domain.event.EventLifecycle.FINISHED)) } })
            TextAction(stringResource(R.string.tools_event_stale), { scenario { entry.environment().scenarios.failNextGuestSubmission(ru.eventpass.core.domain.registration.RegistrationError.StaleForm) } })
            TextAction(stringResource(R.string.tools_event_network), { scenario { entry.environment().scenarios.failNextGuestSubmission(ru.eventpass.core.domain.registration.RegistrationError.Network) } })
            TextAction(stringResource(R.string.tools_event_timeout), { scenario { entry.environment().scenarios.failNextGuestSubmission(ru.eventpass.core.domain.registration.RegistrationError.Timeout) } })
            TextAction(stringResource(R.string.tools_read_error), { scenario { entry.environment().scenarios.failNextRead(ru.eventpass.core.domain.event.ProductReadError.NETWORK) } })
            TextAction(stringResource(R.string.tools_cancel_error), { scenario { entry.environment().scenarios.failNextCancellation(ru.eventpass.core.domain.registration.TicketError.NETWORK) } })
            ru.eventpass.core.domain.registration.FieldRequirement.entries.forEach { requirement ->
                TextAction(stringResource(R.string.tools_phone_policy, when (requirement) {
                    ru.eventpass.core.domain.registration.FieldRequirement.REQUIRED -> stringResource(R.string.event_phone_required)
                    ru.eventpass.core.domain.registration.FieldRequirement.OPTIONAL -> stringResource(R.string.event_phone_optional)
                    ru.eventpass.core.domain.registration.FieldRequirement.HIDDEN -> stringResource(R.string.tools_phone_hidden)
                }), { scenario { entry.environment().scenarios.setPolicy(ru.eventpass.core.domain.registration.RegistrationPolicy(requirement)) } })
            }
            TextAction(stringResource(R.string.tools_used), { scenario {
                val mine = entry.environment().tickets.observeMyTickets().first() as? ru.eventpass.core.domain.registration.MyTicketsResult.Ready
                mine?.tickets?.firstOrNull { it.status == ru.eventpass.core.domain.registration.TicketStatus.ACTIVE }?.let {
                    entry.environment().scenarios.setTicketStatus(it.snapshot.ticketId, ru.eventpass.core.domain.registration.TicketStatus.USED)
                }
            } })
            TextAction(stringResource(R.string.tools_event_advance), { scenario {
                entry.eventClock().advance(Duration.ofDays(2)); entry.environment().events.refreshPublicEvent(FakeFixtures.OPEN); entry.environment().tickets.refreshMyTickets()
            } })
        }
    }
}
