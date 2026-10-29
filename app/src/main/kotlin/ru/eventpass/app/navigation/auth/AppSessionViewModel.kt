package ru.eventpass.app.navigation.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import ru.eventpass.app.navigation.RootTab
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.platform.DemoLinkParser

data class OpenLink(val id: String, val destination: ReturnDestination? = null, val demoId: String? = null)
data class LinkUiState(val busy: Boolean = false, val result: AuthLinkResult? = null)

/** Activity owner: retains an unprocessed delivery across rotation, never saves its secret. */
@HiltViewModel
class AppSessionViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val saved: SavedStateHandle,
    private val clock: Clock,
) : ViewModel() {
    private val stores = mutableMapOf<RootTab, ViewModelStoreOwner>()
    val publicOwner: ViewModelStoreOwner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private var queuedLinks = ArrayDeque<Pair<String, String?>>()
    private var processing = false
    private var retryDelivery: Pair<String, String?>? = null
    private val handler = AuthLinkHandler(auth)
    private val mutableLink = MutableStateFlow(LinkUiState())
    val link = mutableLink.asStateFlow()
    private val mutableOpen = MutableStateFlow<OpenLink?>(null)
    val open = mutableOpen.asStateFlow()
    private val mutableForbidden = MutableStateFlow<ReturnDestination?>(null)
    val forbidden = mutableForbidden.asStateFlow()
    val account get() = (auth.currentSession().state as? SessionState.Authenticated)?.session?.account
    fun isCurrent(scope: PrivateScope) = AuthNavigationPolicy.canRestorePrivate(auth.currentSession(), scope)
    fun dismissForbidden() { mutableForbidden.value = null }
    fun guard(access: AccessOutcome, destination: ReturnDestination) {
        when (val decision = AuthNavigationPolicy.guard(access, destination)) {
            GuardDecision.Allow -> mutableOpen.value = OpenLink(UUID.randomUUID().toString(), destination)
            GuardDecision.RemoveForbidden -> mutableForbidden.value = destination
            is GuardDecision.Login -> viewModelScope.launch {
                coordinator.signOut()
                requireLogin(decision.destination)
                mutableOpen.value = OpenLink(UUID.randomUUID().toString())
            }
        }
    }
    private fun clearOwners() {
        stores.values.forEach { it.viewModelStore.clear() }; stores.clear()
        publicOwner.viewModelStore.clear()
    }
    val coordinator = SessionCoordinator(auth, viewModelScope, ::clearOwners,
        saved.get<String>("pending")?.let { Json.decodeFromString<ReturnDestination>(it) })
    val gate = coordinator.gate
    private var destinationScope: PrivateScope? = null
    fun takeDestination(shell: SessionGate.Shell): ReturnDestination? {
        if (destinationScope == shell.scope) return null
        destinationScope = shell.scope
        saved.remove<String>("pending")
        return shell.destination
    }
    private val mutableEmail = MutableStateFlow(saved.get<String>("loginEmail") ?: "")
    val loginEmail = mutableEmail.asStateFlow()

    fun owner(tab: RootTab): ViewModelStoreOwner = stores.getOrPut(tab) {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    fun requireLogin(destination: ReturnDestination) {
        coordinator.requireLogin(destination)
        saved["pending"] = Json.encodeToString(destination)
    }
    suspend fun signIn(email: String, password: AuthSecret): AuthResult<Unit> {
        val result = coordinator.signIn(email, password)
        if (result is AuthResult.Success) saved.remove<String>("pending")
        return result
    }
    fun signOut() = viewModelScope.launch {
        saved.remove<String>("pending")
        mutableOpen.value = null
        coordinator.signOut()
    }
    fun prefill(email: String) { mutableEmail.value = email; saved["loginEmail"] = email }
    fun receiveLink(uri: String?, requestId: String = UUID.randomUUID().toString()) {
        queuedLinks.addLast(requestId to uri)
        drainLinks()
    }
    private fun drainLinks() {
        if (processing) return
        processing = true
        viewModelScope.launch {
            try {
                while (queuedLinks.isNotEmpty()) {
                    val delivery = queuedLinks.removeFirst()
                    mutableLink.value = LinkUiState(busy = true)
                    val legacy = DemoLinkParser.parse(delivery.second)
                    val result = if (legacy != null) {
                        mutableOpen.value = OpenLink(delivery.first, demoId = legacy)
                        null
                    } else handler.handle(delivery.first, delivery.second)
                    when (result) {
                        is AuthLinkResult.Event -> mutableOpen.value = OpenLink(delivery.first, result.destination)
                        is AuthLinkResult.EmailConfirmed -> prefill(result.email)
                        else -> Unit
                    }
                    retryDelivery = delivery.takeIf { result is AuthLinkResult.Failed }
                    mutableLink.value = LinkUiState(result = result)
                }
            } finally { processing = false }
        }
    }
    fun retryLink() { retryDelivery?.let { queuedLinks.addLast(it); drainLinks() } }
    fun dismissLink() { retryDelivery = null; mutableLink.value = LinkUiState() }
    fun consumeOpen(id: String) { if (mutableOpen.value?.id == id) mutableOpen.value = null }

    /** Called only while the Activity is STARTED; one deadline, no polling/background service. */
    suspend fun awaitExpiry() {
        val session = (auth.currentSession().state as? SessionState.Authenticated)?.session ?: return
        delay(Duration.between(clock.instant(), session.expiresAt).toMillis().coerceAtLeast(1))
        coordinator.refreshSession()
    }
    override fun onCleared() { clearOwners(); queuedLinks.clear(); retryDelivery = null }
}
