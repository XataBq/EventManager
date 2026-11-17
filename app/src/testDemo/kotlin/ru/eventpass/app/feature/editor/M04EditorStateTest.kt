package ru.eventpass.app.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.EventRepository
import ru.eventpass.core.domain.event.*
import ru.eventpass.data.fake.product.*

@OptIn(ExperimentalCoroutinesApi::class)
class M04EditorStateTest {
    @Test fun confirmedServerDraftIdentityRestoresReadWithoutStartingAnotherCreate()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val e=environment()
        var newDrafts=0; val targets=mutableListOf<EventEditorTarget>()
        val repo=object:EventRepository by e.events {
            override fun beginDraft(id:DraftId){newDrafts++}
            override fun observeEditor(target:EventEditorTarget):Flow<EventEditorResult> {
                targets+=target
                return flowOf(EventEditorResult.Draft(EventDraft(DraftId("server"),UserId("actor"),null,EventFields())))
            }
        }
        val vm=EditorViewModel(repo,covers,SavedStateHandle(mapOf("editorServerDraft" to "server","editorLocalDraft" to "local")))
        try {
            vm.bind("local",null,true);runCurrent()
            assertEquals(listOf(EventEditorTarget.Draft(DraftId("server"))),targets)
            assertEquals(0,newDrafts);assertTrue(vm.state.value.allowed)
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
    private val covers=object:CoverImporter { override suspend fun importCover(source: String)=CoverImportResult.Unavailable }
    private fun environment()=FakeEventPassEnvironment(authPolicy=FakeAuthPolicy(passwordIterations=1000))
    @Test fun uncertainCreateBlocksEditingAndDoublePressThenReplaysOriginalFields()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val e=environment()
        val calls=mutableListOf<EventFields>();val saved=SavedStateHandle()
        val repo=object:EventRepository by e.events {
            override fun observeEditor(target:EventEditorTarget)=flowOf(EventEditorResult.Failed(EventEditError.NotFound))
            override suspend fun saveDraft(id:DraftId,expectedRevision:Long?,fields:EventFields):EventEditResult<EventDraft> {
                calls+=fields
                return if(calls.size==1) EventEditResult.Failed(EventEditError.Timeout)
                else EventEditResult.Saved(EventDraft(DraftId("server"),UserId("actor"),null,fields))
            }
        }
        val vm=EditorViewModel(repo,covers,saved)
        try {
            vm.bind("local",null,true);runCurrent();vm.edit(EditorInput(EventFields(title="Original")))
            vm.save();vm.save();runCurrent()
            assertEquals(1,calls.size);assertTrue(vm.state.value.pendingCreate)
            vm.edit(EditorInput(EventFields(title="Changed")));assertEquals("Original",vm.state.value.input.fields.title)
            vm.save();runCurrent();assertEquals(listOf(calls[0],calls[0]),calls)
            assertTrue(vm.state.value.saved);assertFalse(vm.state.value.busy);assertFalse(vm.state.value.pendingCreate)
            assertNull(saved.get<Boolean>("editorCreatePending"))
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
    @Test fun restoredUnknownCreateWithoutBodyNeverSubmitsAgain()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val e=environment();var calls=0
        val repo=object:EventRepository by e.events {
            override suspend fun saveDraft(id:DraftId,expectedRevision:Long?,fields:EventFields):EventEditResult<EventDraft> {calls++;error("Unexpected create")}
        }
        val vm=EditorViewModel(repo,covers,SavedStateHandle(mapOf("editorCreatePending" to true)))
        try {
            vm.bind("restored",null,true);vm.resumeReading();vm.save();runCurrent()
            assertFalse(vm.state.value.allowed);assertEquals(0,calls);assertEquals(EventEditError.Conflict,vm.state.value.error)
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
    @Test fun refreshedNullRevisionDraftCannotReplaceDirtyInputAcrossPauseResume()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val e=environment()
        val stream=MutableStateFlow<EventEditorResult>(EventEditorResult.Draft(EventDraft(DraftId("server"),UserId("actor"),null,EventFields(title="Baseline"))))
        val repo=object:EventRepository by e.events {override fun observeEditor(target:EventEditorTarget)=stream}
        val vm=EditorViewModel(repo,covers,androidx.lifecycle.SavedStateHandle())
        try {
            vm.bind("server",null,false);runCurrent();vm.edit(EditorInput(EventFields(title="Dirty")))
            vm.pauseReading();stream.value=EventEditorResult.Draft(EventDraft(DraftId("server"),UserId("actor"),null,EventFields(title="Server updated")))
            vm.resumeReading();runCurrent()
            assertEquals("Dirty",vm.state.value.input.fields.title);assertTrue(vm.state.value.dirty)
            stream.value=EventEditorResult.Failed(EventEditError.Forbidden);runCurrent()
            assertFalse(vm.state.value.allowed);assertEquals("",vm.state.value.input.fields.title)
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
}
