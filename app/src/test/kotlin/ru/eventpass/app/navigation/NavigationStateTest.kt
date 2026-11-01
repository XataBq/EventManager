package ru.eventpass.app.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationStateTest {
    private fun navigation() = NavigationState(
        mutableStateOf(RootTab.Tickets),
        RootTab.entries.associateWith { NavBackStack<NavKey>(it) },
    )

    @Test fun tabsRetainTheirStackAndRepeatedClickReturnsToRoot() {
        val state = navigation()
        state.openDemo("demo")
        state.select(RootTab.Events)
        state.select(RootTab.Profile)
        state.select(RootTab.Tickets)
        assertEquals(2, state.currentStack.size)
        assertEquals("demo", (state.currentStack.last() as DemoDetails).recordId)
        state.select(RootTab.Tickets)
        assertEquals(listOf(RootTab.Tickets), state.currentStack.toList())
    }

    @Test fun repeatedLinksDoNotDuplicateAndBackHasSafeParent() {
        val state = navigation()
        repeat(10) { state.openDemo("demo") }
        assertEquals(2, state.currentStack.size)
        state.openDemo("missing")
        assertEquals(2, state.currentStack.size)
        assertEquals("missing", (state.currentStack.last() as DemoDetails).recordId)
        state.back()
        assertEquals(listOf(RootTab.Tickets), state.currentStack.toList())
        state.select(RootTab.Profile)
        state.back()
        assertEquals(RootTab.Tickets, state.currentTab)
    }

    @Test fun allRouteKeysRoundTripWithoutPayloadOrPlatformTypes() {
        RootTab.entries.forEach { tab ->
            assertEquals(tab, Json.decodeFromString<RootTab>(Json.encodeToString(tab)))
        }
        val route = DemoDetails("demo")
        assertEquals(route, Json.decodeFromString<DemoDetails>(Json.encodeToString(route)))
        assertEquals(GalleryKey, Json.decodeFromString<GalleryKey>(Json.encodeToString(GalleryKey)))
    }

    @Test fun galleryDoesNotDuplicateOrReplaceTheUnderlyingDemoEntry() {
        val state = navigation()
        state.openDemo("demo")
        val entry = state.currentStack.last()
        repeat(3) { state.openGallery() }
        assertEquals(listOf(RootTab.Tickets, entry, GalleryKey), state.currentStack.toList())
        state.select(RootTab.Profile)
        state.select(RootTab.Tickets)
        assertEquals(GalleryKey, state.currentStack.last())
        state.back()
        assertEquals(entry, state.currentStack.last())
        state.openGallery()
        state.openDemo("demo")
        assertEquals(listOf(RootTab.Tickets, entry), state.currentStack.toList())
    }

    @Test fun eventOpenedFromMyEventsReturnsToItsOwnTab() {
        val state = navigation()
        state.openDemo("demo")
        val tickets = state.currentStack.toList()
        state.select(RootTab.Events)
        repeat(3) { state.openEvent("owned-event") }
        assertEquals(RootTab.Events, state.currentTab)
        assertEquals(2, state.currentStack.size)
        assertEquals(tickets, state.stacks.getValue(RootTab.Tickets).toList())
        state.back()
        assertEquals(RootTab.Events, state.currentTab)
        assertEquals(listOf(RootTab.Events), state.currentStack.toList())
    }

    @Test fun openingPublishedPageKeepsTheEditorEntryAndOtherEventParents() {
        val state = navigation()
        state.select(RootTab.Events)
        val editor = EditorKey(draftId = "draft")
        state.currentStack.add(editor)
        state.openEvent("published-event")
        val event = state.currentStack.last()
        state.openEvent("another-event")
        state.back()
        assertEquals(event, state.currentStack.last())
        state.back()
        assertEquals(listOf(RootTab.Events, editor), state.currentStack.toList())
    }

    @Test fun externalEventLinksKeepTicketsParentWithoutReplacingMyEventsStack() {
        val state = navigation()
        state.select(RootTab.Events)
        state.openEvent("owned-event")
        val origin = state.currentStack.toList()
        state.select(RootTab.Profile)
        state.openLinkedEvent("linked-event")
        val linked = state.currentStack.last()
        repeat(3) { state.openLinkedEvent("linked-event") }
        assertEquals(listOf(RootTab.Tickets, linked), state.currentStack.toList())
        assertEquals(origin, state.stacks.getValue(RootTab.Events).toList())
        state.back()
        assertEquals(listOf(RootTab.Tickets), state.currentStack.toList())
        state.select(RootTab.Events)
        assertEquals(origin, state.currentStack.toList())
    }

    @Test fun eventReopenedAfterBackHasANewOwnerWithoutChangingItsParent() {
        val state = navigation()
        state.openEvent("event")
        val first = state.currentStack.last()
        state.back()
        state.openEvent("event")
        org.junit.Assert.assertNotEquals(first, state.currentStack.last())
        state.back()
        assertEquals(listOf(RootTab.Tickets), state.currentStack.toList())
    }
}
