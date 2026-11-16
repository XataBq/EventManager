package ru.eventpass.app.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.app.feature.event.*
import ru.eventpass.app.feature.myevents.MyEventsViewModel
import ru.eventpass.app.feature.tickets.TicketsViewModel
import ru.eventpass.app.feature.profile.ProfileViewModel
import ru.eventpass.app.feature.volunteers.VolunteersViewModel
import ru.eventpass.core.domain.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.core.domain.profile.*
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.data.fake.product.*

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshOwnersTest {
    private fun environment()=FakeEventPassEnvironment(authPolicy=FakeAuthPolicy(passwordIterations=1000))

    @Test fun ticketsRefreshOnlyItsPortRetainsSelectionAndOldFinallyCannotUnlockNewRead()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val e=environment();e.scenarios.signInAs(FakeFixtures.ALEX)
        val values=MutableStateFlow(e.tickets.observeMyTickets().first())
        val pending=mutableListOf<Continuation<Unit>>();var calls=0
        val repo=object: TicketRepository by e.tickets {
            override fun observeMyTickets(scope: TicketScope)=values
            override suspend fun refreshMyTickets(scope: TicketScope) = refreshMyTickets()
            override fun observeMyTickets()=values
            override suspend fun refreshMyTickets() {calls++;suspendCoroutine<Unit>{pending+=it}}
        }
        val vm=TicketsViewModel(repo,SavedStateHandle())
        try {
            vm.start();runCurrent();val data=vm.state.value.data!!;val id=data.tickets.first().snapshot.ticketId.value
            vm.history(true);vm.select(id);vm.refresh();repeat(5){vm.refresh()};runCurrent()
            assertEquals(1,calls);assertTrue(vm.state.value.refreshing);assertFalse(vm.state.value.loading)
            assertEquals(id,vm.state.value.selected);assertTrue(vm.state.value.history);assertEquals(data,vm.state.value.data)
            values.value=MyTicketsResult.Failed(ProductReadError.TIMEOUT);runCurrent()
            assertEquals(data,vm.state.value.data);assertEquals(ProductReadError.TIMEOUT,vm.state.value.error)
            vm.stop();vm.refresh();runCurrent();assertEquals(1,calls)
            vm.start();runCurrent();vm.refresh();runCurrent()
            pending[0].resume(Unit);runCurrent();assertTrue(vm.state.value.refreshing)
            pending[1].resume(Unit);runCurrent();assertFalse(vm.state.value.refreshing)
            assertEquals(2,calls);assertEquals(id,vm.state.value.selected)
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }

    @Test fun myEventsUsesOneCollectionKeepsSegmentAndTransientErrorData()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val e=environment();e.scenarios.signInAs(FakeFixtures.ALEX)
        val values=MutableStateFlow(e.events.observeMyEvents().first());val replies=mutableListOf<Continuation<Unit>>();var calls=0
        val repo=object: EventRepository by e.events {
            override fun observeMyEvents()=values
            override suspend fun refreshMyEvents() {calls++;suspendCoroutine<Unit>{replies+=it}}
            override suspend fun refreshPublicEvent(reference: PublicEventReference)=error("Hidden public read")
        }
        val vm=MyEventsViewModel(repo,SavedStateHandle())
        try {
            vm.start();runCurrent();vm.select(2);val data=vm.data.value.content
            vm.refresh();repeat(5){vm.refresh()};runCurrent();assertEquals(1,calls)
            values.value=MyEventsResult.Failed(EventEditError.Network);runCurrent()
            assertEquals(data,vm.data.value.content);assertEquals(EventEditError.Network,vm.data.value.error)
            assertEquals(2,vm.tab.value);assertFalse(vm.data.value.loading)
            vm.stop();vm.refresh();runCurrent();assertEquals(1,calls)
            vm.start();runCurrent();vm.refresh();runCurrent();replies[0].resume(Unit);runCurrent()
            assertTrue(vm.data.value.refreshing);replies[1].resume(Unit);runCurrent();assertFalse(vm.data.value.refreshing)
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }

    @Test fun publicEventReadsOnlyCurrentIdPreservesFormAndIgnoresOldFinallyAfterRebind()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val e=environment()
        val first=e.events.observePublicEvent(FakeFixtures.OPEN).first() as PublicEventResult.Found
        val second=e.events.observePublicEvent(FakeFixtures.MIXED).first() as PublicEventResult.Found
        val streams=mapOf(first.event.id to MutableStateFlow<PublicEventResult>(first),second.event.id to MutableStateFlow<PublicEventResult>(second))
        val calls=mutableListOf<EventId>();val replies=mutableListOf<Continuation<Unit>>()
        val repo=object: EventRepository by e.events {
            override fun observePublicEvent(reference: PublicEventReference)=streams.getValue((reference as PublicEventReference.Internal).id)
            override suspend fun refreshPublicEvent(reference: PublicEventReference) { val eventId = (reference as PublicEventReference.Internal).id;calls+=eventId;suspendCoroutine<Unit>{replies+=it}}
            override suspend fun refreshMyEvents()=error("Hidden MyEvents read")
        }
        val vm=EventViewModel(repo,e.registrations,e.tickets,SavedStateHandle())
        val hidden=EventViewModel(repo,e.registrations,e.tickets,SavedStateHandle())
        try {
            vm.bind(first.event.id.value);runCurrent();val form=RegistrationForm("Ж","new@example.com",answers=mapOf("old" to Answer.Text("Keep")),consent=true)
            vm.edit(form);vm.refresh();repeat(4){vm.refresh()};runCurrent()
            hidden.bind(second.event.id.value);runCurrent();hidden.stopReading();hidden.refresh();runCurrent()
            assertEquals(listOf(first.event.id),calls)
            streams.getValue(first.event.id).value=first.copy(event=first.event.copy(title="Fresh"));runCurrent()
            assertEquals(form,vm.state.value.form)
            streams.getValue(first.event.id).value=PublicEventResult.Failed(ProductReadError.NETWORK);runCurrent()
            assertEquals(form,vm.state.value.form);assertEquals("Fresh",vm.state.value.data!!.event.title)
            vm.bind(second.event.id.value);runCurrent();vm.refresh();runCurrent();replies[0].resume(Unit);runCurrent()
            assertTrue(vm.state.value.refreshing);assertEquals(second.event.id,vm.state.value.data!!.event.id)
            replies[1].resume(Unit);runCurrent();assertFalse(vm.state.value.refreshing)
            assertEquals(listOf(first.event.id,second.event.id),calls)
        } finally {vm.viewModelScope.cancel();hidden.viewModelScope.cancel();Dispatchers.resetMain()}
    }

    @Test fun changedSchemaDropsIncompatibleAnswersButKeepsContactAndConsent()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val e=environment()
        val value=e.events.observePublicEvent(FakeFixtures.OPEN).first() as PublicEventResult.Found
        val stream=MutableStateFlow<PublicEventResult>(value);var calls=0
        val repo=object: EventRepository by e.events {
            override fun observePublicEvent(reference: PublicEventReference)=stream
            override suspend fun refreshPublicEvent(reference: PublicEventReference) { val eventId = (reference as PublicEventReference.Internal).id;calls++}
        }
        val vm=EventViewModel(repo,e.registrations,e.tickets,SavedStateHandle())
        try {
            vm.bind(value.event.id.value);runCurrent();vm.edit(RegistrationForm("Ж","refresh@example.com",answers=mapOf("old" to Answer.Text("Do not migrate")),consent=true))
            stream.value=value.copy(event=value.event.copy(questions=value.event.questions + RegistrationQuestion.Text("new-required", "Новый вопрос", true)));runCurrent()
            assertEquals(RegistrationError.StaleForm,vm.state.value.error);assertTrue(vm.state.value.form.answers.isEmpty());assertTrue(vm.state.value.form.consent)
            stream.value=value;runCurrent();vm.edit(RegistrationForm("Ж","refresh@example.com",consent=true));vm.submit()
            vm.state.first {it.receipt!=null};vm.refresh();runCurrent();assertEquals(0,calls);assertNotNull(vm.state.value.receipt)
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }

    @Test fun volunteersRefreshKeepsInputDoesNotMutateAndNewObserverWins()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val e=environment()
        val stream=MutableStateFlow<VolunteersResult>(VolunteersResult.Ready(emptyList()));val calls=mutableListOf<EventId>()
        val replies=mutableListOf<Continuation<VolunteersResult>>()
        val repo=object: VolunteerRepository by e.volunteers {
            override fun observeVolunteers(eventId: EventId)=stream
            override suspend fun refreshVolunteers(eventId: EventId): VolunteersResult {calls+=eventId;return suspendCoroutine {replies+=it}}
            override suspend fun grant(eventId: EventId,emailOrId: String)=error("Pull mutated grant")
            override suspend fun revoke(eventId: EventId,userId: UserId)=error("Pull revoked")
        }
        val vm=VolunteersViewModel(repo)
        try {
            vm.start("one");runCurrent();vm.edit("keep@example.com");vm.refresh();repeat(5){vm.refresh()};runCurrent();assertEquals(listOf(EventId("one")),calls)
            replies[0].resume(VolunteersResult.Failed(VolunteerError.NETWORK));runCurrent()
            assertEquals("keep@example.com",vm.state.value.input);assertFalse(vm.state.value.refreshing);assertEquals(VolunteerError.NETWORK,vm.state.value.readError)
            vm.refresh();runCurrent();val volunteer=Volunteer(ScanGrant(EventId("one"),UserId("new")),"new@example.com")
            stream.value=VolunteersResult.Ready(listOf(volunteer));runCurrent();replies[1].resume(VolunteersResult.Ready(emptyList()));runCurrent()
            assertEquals(listOf(volunteer),vm.state.value.volunteers);assertEquals("keep@example.com",vm.state.value.input)
            vm.refresh();runCurrent();vm.stop();vm.refresh();vm.start("two");runCurrent();vm.refresh();runCurrent()
            replies[2].resume(VolunteersResult.Failed(VolunteerError.TIMEOUT));runCurrent();assertTrue(vm.state.value.refreshing);assertNull(vm.state.value.readError)
            replies[3].resume(VolunteersResult.Ready(emptyList()));runCurrent();assertFalse(vm.state.value.refreshing);assertEquals(EventId("two"),calls.last())
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }

    @Test fun profileRefreshAppliesCleanBaselinePreservesDirtyFieldsAndCannotLeakOldActor()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val e=environment()
        val initial=Profile(Account(UserId("alex"),"alex@example.com",true),ProfileFields("Alex"))
        val stream=MutableStateFlow<ProfileResult>(ProfileResult.Ready(initial));val replies=mutableListOf<Continuation<ProfileResult>>();var calls=0
        val repo=object: ProfileRepository by e.profiles {
            override fun observeProfile()=stream
            override suspend fun refreshProfile(): ProfileResult {calls++;return suspendCoroutine {replies+=it}}
            override suspend fun saveProfile(fields: ProfileFields)=error("Pull saved profile")
        }
        val vm=ProfileViewModel(repo)
        try {
            vm.start();runCurrent();vm.refresh();repeat(4){vm.refresh()};runCurrent();assertEquals(1,calls)
            val fresh=initial.copy(fields=ProfileFields("New"));replies[0].resume(ProfileResult.Ready(fresh));runCurrent();assertEquals(fresh.fields,vm.state.value.fields)
            val local=ProfileFields("Local","+79991234567","Org",false);vm.edit(local);vm.refresh();runCurrent()
            val newer=fresh.copy(fields=ProfileFields("Other saved"));replies[1].resume(ProfileResult.Ready(newer));runCurrent()
            assertEquals(local,vm.state.value.fields);assertEquals(newer,vm.state.value.profile);assertTrue(vm.state.value.conflict)
            vm.refresh();runCurrent();stream.value=ProfileResult.Ready(newer.copy(fields=ProfileFields("Observer")));runCurrent()
            replies[2].resume(ProfileResult.Failed(ProfileError.NETWORK));runCurrent();assertNull(vm.state.value.readError);assertEquals(local,vm.state.value.fields)
            vm.refresh();runCurrent();vm.stop();stream.value=ProfileResult.Ready(initial.copy(account=Account(UserId("host"),"host@example.com",true)))
            vm.start();runCurrent();vm.refresh();runCurrent();replies[3].resume(ProfileResult.Ready(newer));runCurrent()
            assertEquals("host",vm.state.value.profile!!.account.id.value);assertTrue(vm.state.value.refreshing)
            replies[4].resume(ProfileResult.Failed(ProfileError.TIMEOUT));runCurrent();assertFalse(vm.state.value.refreshing);assertEquals(ProfileError.TIMEOUT,vm.state.value.readError)
            assertFalse(vm.state.value.dirty);assertFalse(vm.state.value.conflict)
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
}
