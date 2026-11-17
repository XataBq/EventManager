package ru.eventpass.app.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.app.feature.myevents.MyEventsViewModel
import ru.eventpass.core.domain.EventRepository
import ru.eventpass.core.domain.event.*
import ru.eventpass.data.fake.product.*

@OptIn(ExperimentalCoroutinesApi::class)
class M04MyEventsStateTest {
    @Test fun tabReadinessAndLatePreviousTabCannotReplaceCurrentContentAndRefreshIsScoped()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val e=FakeEventPassEnvironment(authPolicy=FakeAuthPolicy(passwordIterations=1000))
        val streams=MyEventsTab.entries.associateWith { MutableSharedFlow<MyEventsResult>(replay=1) }
        val observed=mutableListOf<MyEventsTab>();val refreshed=mutableListOf<MyEventsTab>()
        val repo=object:EventRepository by e.events {
            override fun observeMyEvents(tab:MyEventsTab):Flow<MyEventsResult> {observed+=tab;return streams.getValue(tab)}
            override suspend fun refreshMyEvents(tab:MyEventsTab){refreshed+=tab;delay(50)}
        }
        val vm=MyEventsViewModel(repo,SavedStateHandle())
        fun ready(tab:MyEventsTab)=MyEventsResult.Ready(emptyList(),emptyList(),emptyList(),java.time.Instant.EPOCH,tab,counters=mapOf(tab to 0))
        try {
            vm.start();runCurrent();assertTrue(vm.data.value.loading);assertNull(vm.data.value.content)
            streams.getValue(MyEventsTab.ORGANIZING).emit(ready(MyEventsTab.ORGANIZING));runCurrent()
            vm.select(2);runCurrent();assertTrue(vm.data.value.loading);assertNull(vm.data.value.content)
            streams.getValue(MyEventsTab.ORGANIZING).emit(MyEventsResult.Failed(EventEditError.Network));runCurrent()
            assertNull(vm.data.value.content)
            val drafts=ready(MyEventsTab.DRAFTS);streams.getValue(MyEventsTab.DRAFTS).emit(drafts);runCurrent()
            assertEquals(drafts,vm.data.value.content);assertFalse(vm.data.value.loading)
            repeat(4){vm.refresh()};runCurrent();assertEquals(listOf(MyEventsTab.DRAFTS),refreshed)
            advanceUntilIdle();assertFalse(vm.data.value.refreshing)
            vm.stop();vm.refresh();runCurrent();assertEquals(1,refreshed.size)
            assertEquals(listOf(MyEventsTab.ORGANIZING,MyEventsTab.DRAFTS),observed)
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
}
