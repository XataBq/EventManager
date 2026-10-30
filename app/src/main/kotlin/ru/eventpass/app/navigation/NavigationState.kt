package ru.eventpass.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class RootTab : NavKey { Tickets, Events, Profile }

@Serializable
data class DemoDetails(val recordId: String, val entryId: String = UUID.randomUUID().toString()) : NavKey

@Serializable
data object GalleryKey : NavKey

@Serializable data object LoginKey : NavKey
@Serializable data object SignUpKey : NavKey
@Serializable data object ResetKey : NavKey
@Serializable data object VerificationKey : NavKey
@Serializable data object DemoToolsKey : NavKey
@Serializable data object OutboxKey : NavKey
@Serializable data class EditorKey(val draftId: String? = null, val eventId: String? = null, val create: Boolean = false,
    val entryId: String = UUID.randomUUID().toString()) : NavKey
@Serializable data class EventKey(val eventId: String, val flowId: String = UUID.randomUUID().toString()) : NavKey
@Serializable enum class EventAuthScreen { Login, SignUp, Reset, Verification }
@Serializable data class EventAuthKey(val flowId: String, val screen: EventAuthScreen, val entryId: String = UUID.randomUUID().toString()) : NavKey

class NavigationState(
    private val selectedTab: MutableState<RootTab>,
    val stacks: Map<RootTab, NavBackStack<NavKey>>,
) {
    val currentTab: RootTab get() = selectedTab.value
    val currentStack: NavBackStack<NavKey> get() = stacks.getValue(currentTab)

    fun select(tab: RootTab) {
        if (tab == currentTab) {
            stacks.getValue(tab).let { while (it.size > 1) it.removeLastOrNull() }
        }
        selectedTab.value = tab
    }

    fun openDemo(recordId: String) {
        selectedTab.value = RootTab.Tickets
        val stack = currentStack
        if (stack.lastOrNull() == GalleryKey && (stack.getOrNull(stack.lastIndex - 1) as? DemoDetails)?.recordId == recordId) {
            stack.removeLastOrNull()
            return
        }
        if ((stack.lastOrNull() as? DemoDetails)?.recordId == recordId) return
        while (stack.size > 1) stack.removeLastOrNull()
        // A new visit owns a new SavedStateHandle, including during a previous pop animation.
        stack.add(DemoDetails(recordId))
    }

    fun openGallery() {
        if (currentStack.lastOrNull() != GalleryKey) currentStack.add(GalleryKey)
    }

    fun openEvent(eventId: String) {
        selectedTab.value = RootTab.Tickets
        if ((currentStack.lastOrNull() as? EventKey)?.eventId == eventId) return
        while (currentStack.size > 1) currentStack.removeLastOrNull()
        currentStack.add(EventKey(eventId))
    }

    fun back() {
        if (currentStack.size > 1) currentStack.removeLastOrNull()
        else selectedTab.value = RootTab.Tickets
    }

    /** Model-level reset. Live session cleanup also destroys scope-owned ViewModel stores. */
    fun clearPrivateState() {
        for (tab in RootTab.entries) {
            stacks.getValue(tab).apply { clear(); add(tab) }
        }
        selectedTab.value = RootTab.Tickets
    }
}

@Composable
fun rememberNavigationState(): NavigationState {
    val selected = rememberSaveable { mutableStateOf(RootTab.Tickets) }
    val tickets = rememberNavBackStack(RootTab.Tickets)
    val events = rememberNavBackStack(RootTab.Events)
    val profile = rememberNavBackStack(RootTab.Profile)
    return remember {
        NavigationState(selected, mapOf(
            RootTab.Tickets to tickets, RootTab.Events to events, RootTab.Profile to profile,
        ))
    }
}
