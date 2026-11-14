package ru.eventpass.app.feature.editor

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.EventRepository
import ru.eventpass.core.domain.event.*
import ru.eventpass.data.fake.product.*
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    private val covers = object : CoverImporter { override suspend fun importCover(source: String) = CoverImportResult.InvalidImage }
    private fun environment() = FakeEventPassEnvironment(authPolicy = FakeAuthPolicy(passwordIterations = 1000))
    private fun input() = EditorInput(EventFields(title = "Новая конференция", venue = "Зал"), "2026-10-28 12:00")
    @Test fun explicitSaveIncompleteReloadAndPublishKeepsStableReceipt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val e = environment(); e.scenarios.signInAs(FakeFixtures.ALEX)
        val vm = EditorViewModel(e.events, covers); val second = EditorViewModel(e.events, covers)
        try {
            vm.bind("one", null, true); vm.state.first { it.allowed }
            vm.edit(EditorInput(EventFields(title = "Неполное"))); vm.save()
            vm.state.first { it.saved && !it.busy }; assertFalse(vm.state.value.dirty)
            second.bind("one", null, false); second.state.first { it.allowed }
            assertEquals("Неполное", second.state.value.input.fields.title)
            second.edit(input()); second.save(publish = true); second.save(publish = true)
            val id = second.state.first { it.publishedId != null && !it.busy }.publishedId!!
            assertEquals("Новая конференция", (e.events.observePublicEvent(EventId(id)).first() as PublicEventResult.Found).event.title)
            assertTrue((e.events.observeMyEvents().first() as MyEventsResult.Ready).drafts.isEmpty())
            assertEquals(2, (e.events.observeMyEvents().first() as MyEventsResult.Ready).organizing.size)
        } finally { vm.viewModelScope.cancel(); second.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun backSaveDiscardAndFailedWriteNeverClaimSaved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val e = environment(); e.scenarios.signInAs(FakeFixtures.ALEX)
        val vm = EditorViewModel(e.events, covers)
        try {
            vm.bind("one", null, true); vm.state.first { it.allowed }; vm.edit(input()); vm.requestBack()
            assertTrue(vm.state.value.confirmExit); vm.stay(); assertFalse(vm.state.value.exit)
            e.scenarios.failNextEventEdit(EventEditError.StorageUnavailable); vm.save(exit = true)
            vm.state.first { it.error != null && !it.busy }
            assertFalse(vm.state.value.saved); assertTrue(vm.state.value.dirty); assertFalse(vm.state.value.exit)
            vm.save(exit = true); vm.state.first { it.exit }; assertTrue(vm.state.value.saved)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun typedDateErrorsKeepInputAndUseEventZoneRatherThanDeviceZone() {
        val selected = input().resolved(); assertTrue(selected.second.isEmpty())
        assertEquals(Instant.parse("2026-10-28T09:00:00Z"), selected.first.startsAt)
        assertTrue(input().copy(start = "2026-02-30 12:00").resolved().second.contains("startsAt"))
        assertTrue(input().copy(unlimited = false, capacity = "").resolved().second.contains("capacity"))
        assertEquals(0, input().copy(unlimited = false, capacity = "0").resolved().first.capacity)
        val gap = input().copy(fields = EventFields(zoneId = "Europe/Berlin"), start = "2026-03-29 02:30")
        assertTrue(gap.resolved().second.contains("startsAt"))
        val incomplete = EventFields(startsAt = Instant.parse("2026-10-28T09:00:00Z"), zoneId = "invalid zone")
        assertEquals("2026-10-28T09:00:00Z", EditorInput.from(incomplete).start)
    }
    @Test fun foreignEditorIsDeniedAndLogoutClearsVisibleFields() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val e = environment(); e.scenarios.signInAs(FakeFixtures.ALEX)
        val vm = EditorViewModel(e.events, covers)
        try {
            vm.bind(null, FakeFixtures.VOLUNTEER.value, false)
            vm.state.first { !it.loading }; assertEquals(EventEditError.Forbidden, vm.state.value.error)
            vm.bind(null, FakeFixtures.OWNED.value, false); vm.state.first { it.allowed }
            e.scenarios.signOut(); vm.state.first { !it.allowed }
            assertEquals("", vm.state.value.input.fields.title)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun transientReadFailureKeepsDirtyInputAndRetryClearsOnlyReadError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val e = environment(); e.scenarios.signInAs(FakeFixtures.ALEX)
        val vm = EditorViewModel(e.events, covers)
        try {
            vm.bind(null, FakeFixtures.OWNED.value, false); vm.state.first { it.allowed }; vm.edit(input())
            e.scenarios.failNextRead(ProductReadError.NETWORK); vm.refresh()
            vm.state.first { it.error == EventEditError.Network }
            assertEquals(input(), vm.state.value.input); assertTrue(vm.state.value.allowed); assertTrue(vm.state.value.dirty)
            vm.refresh(); vm.state.first { it.error == null }; assertEquals(input(), vm.state.value.input)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun dirtyEditorDoesNotAdoptNewRevisionAndOverwriteExternalChange() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val e = environment(); e.scenarios.signInAs(FakeFixtures.ALEX)
        val vm = EditorViewModel(e.events, covers)
        try {
            vm.bind(null, FakeFixtures.OWNED.value, false); vm.state.first { it.allowed }
            val old = (e.events.observePublicEvent(FakeFixtures.OWNED).first() as PublicEventResult.Found).event
            vm.edit(vm.state.value.input.copy(fields = old.toFields().copy(title = "Моя правка")))
            e.events.updateEvent(old.id, old.revision, old.formVersion, old.toFields().copy(title = "Внешняя правка"))
            runCurrent(); vm.save(); vm.state.first { it.error != null && !it.busy }
            assertEquals(EventEditError.Conflict, vm.state.value.error)
            assertEquals("Моя правка", vm.state.value.input.fields.title)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun cancelledOldSaveAndFinallyCannotPublishOrUnlockNewEntry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val e = environment(); e.scenarios.signInAs(FakeFixtures.ALEX)
        val pending = mutableListOf<Continuation<EventEditResult<EventDraft>>>()
        var publications = 0
        val repo = object : EventRepository by e.events {
            override suspend fun saveDraft(id: DraftId, expectedRevision: Long?, fields: EventFields): EventEditResult<EventDraft> = suspendCoroutine { pending += it }
            override suspend fun publishDraft(id: DraftId, expectedRevision: Long): EventEditResult<EventId> { publications++; return e.events.publishDraft(id, expectedRevision) }
        }
        val vm = EditorViewModel(repo, covers)
        try {
            vm.bind("old", null, true); vm.state.first { it.allowed }; vm.edit(input()); vm.save(publish = true); runCurrent()
            vm.bind("new", null, true); vm.state.first { it.allowed }; vm.edit(input()); vm.save(); runCurrent()
            assertEquals(2, pending.size)
            pending[0].resume(EventEditResult.Saved(EventDraft(DraftId("old"), FakeFixtures.ALEX, 1, input().fields)))
            runCurrent(); assertTrue(vm.state.value.busy); assertNull(vm.state.value.publishedId); assertEquals(0, publications)
            vm.save(); runCurrent(); assertEquals(2, pending.size)
            pending[1].resume(EventEditResult.Failed(EventEditError.Network)); runCurrent()
            assertFalse(vm.state.value.busy); assertEquals(EventEditError.Network, vm.state.value.error)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
