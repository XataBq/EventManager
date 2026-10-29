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
    AuthColumn {
        Text(stringResource(if (outbox) R.string.auth_outbox else R.string.auth_demo_tools), style = EventPassTypography.heading)
        Text(stringResource(R.string.tools_disclaimer))
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
        } else {
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
                if (!busy) scope.launch { busy = true; try { entry.clock().reset(); entry.environment().scenarios.reset(); revision++ } finally { busy = false } }
            }, enabled = !busy)
        }
    }
}
