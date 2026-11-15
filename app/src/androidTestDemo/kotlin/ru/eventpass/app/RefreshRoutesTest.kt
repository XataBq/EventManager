package ru.eventpass.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.zIndex
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.espresso.Espresso
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import ru.eventpass.app.feature.event.*
import ru.eventpass.app.feature.tickets.*
import ru.eventpass.app.feature.myevents.*
import ru.eventpass.app.feature.profile.*
import ru.eventpass.app.feature.dashboard.*
import ru.eventpass.app.feature.volunteers.*
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.attendance.*
import ru.eventpass.data.fake.product.FakeFixtures

/** Real Routes/lifecycle and swipes; spies count initiating port reads, not shared-store emissions. */
class RefreshRoutesTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val owners=mutableListOf<ViewModel>()
    private fun <T:ViewModel> own(vm:T)=vm.also {owners+=it}
    @Before fun reset() {runBlocking {compose.backend().environment().scenarios.reset()}}
    @After fun clear() {compose.runOnUiThread {owners.forEach {it.viewModelScope.cancel()}}}
    private fun await(check:()->Boolean)=compose.waitUntil(10_000,check)
    private fun pull(tag:String)=compose.onNodeWithTag(tag).performTouchInput {swipeDown()}
    private fun idle(tag:String)=compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Обновление не выполняется"))

    @Test fun retainedRootRoutesReadOnlyVisibleTabAndLateTicketReadCannotAffectEvents() {
        val e=compose.backend().environment();runBlocking {e.scenarios.signInAs(FakeFixtures.ALEX)}
        var tickets=0;var events=0;var hold:CompletableDeferred<Unit>?=null
        val ticketRepo=object:TicketRepository by e.tickets {
            override suspend fun refreshMyTickets() {tickets++;val gate=hold;if(gate!=null) withContext(NonCancellable){gate.await()};e.tickets.refreshMyTickets()}
        }
        val eventRepo=object:EventRepository by e.events {override suspend fun refreshMyEvents(){events++;e.events.refreshMyEvents()}}
        val tv=own(TicketsViewModel(ticketRepo,SavedStateHandle()));val ev=own(MyEventsViewModel(eventRepo,SavedStateHandle()))
        var ticketTab by mutableStateOf(true)
        compose.runOnUiThread {compose.activity.setContent {EventPassTheme {Column {
            Row {TextAction("Билеты",{ticketTab=true},Modifier.testTag("choose-tickets"));TextAction("События",{ticketTab=false},Modifier.testTag("choose-events"))}
            Box(Modifier.weight(1f)) {
                Box(Modifier.fillMaxSize().alpha(if(ticketTab)1f else 0f).zIndex(if(ticketTab)1f else 0f)) {
                    TicketsRoute(null,{},{},{},{},{},visible=ticketTab,vm=tv)
                }
                Box(Modifier.fillMaxSize().alpha(if(ticketTab)0f else 1f).zIndex(if(ticketTab)0f else 1f)) {
                    MyEventsRoute({},{},{},{},{},{},{},visible=!ticketTab,vm=ev)
                }
            }
        }}}}
        await {tv.state.value.data!=null && tickets==1};compose.runOnIdle {assertEquals(0,events);hold=CompletableDeferred()}
        pull("root-Tickets");await {tv.state.value.refreshing};assertEquals(2,tickets)
        pull("root-Tickets");assertEquals(2,tickets)
        compose.onNodeWithTag("choose-events").performClick();await {events==1 && ev.data.value.content!=null}
        compose.runOnIdle {hold!!.complete(Unit);hold=null};compose.waitForIdle();idle("refresh-events")
        compose.runOnIdle {ev.select(2)};pull("root-Events");await {events==2};assertEquals(2,tickets);assertEquals(2,ev.tab.value)
        compose.onNodeWithTag("choose-tickets").performClick();await {tickets==3};pull("root-Tickets");await {tickets==4}
        assertEquals(2,events);idle("refresh-tickets")
        compose.onAllNodesWithText("Обновить").assertCountEquals(0)
    }

    @Test fun differentEventEntriesKeepRefreshResourceAndFormIncludingReadFailure() {
        val e=compose.backend().environment();val calls=mutableListOf<EventId>()
        val repo=object:EventRepository by e.events {override suspend fun refreshPublicEvent(reference: PublicEventReference) { val eventId = (reference as PublicEventReference.Internal).id;calls+=eventId;e.events.refreshPublicEvent(eventId)}}
        val one=own(EventViewModel(repo,e.registrations,e.tickets,SavedStateHandle()))
        val two=own(EventViewModel(repo,e.registrations,e.tickets,SavedStateHandle()))
        var first by mutableStateOf(true)
        compose.runOnUiThread {compose.activity.setContent {EventPassTheme {Column {
            Row {TextAction("E1",{first=true},Modifier.testTag("one"));TextAction("E2",{first=false},Modifier.testTag("two"))}
            Box(Modifier.weight(1f)) {
                Box(Modifier.alpha(if(first)1f else 0f).zIndex(if(first)1f else 0f)) {EventRoute(FakeFixtures.OPEN.value,{_,_,_->},{},{},first,one)}
                Box(Modifier.alpha(if(first)0f else 1f).zIndex(if(first)0f else 1f)) {EventRoute(FakeFixtures.MIXED.value,{_,_,_->},{},{},!first,two)}
            }
        }}}}
        await {one.state.value.data!=null && calls.size==1}
        compose.runOnIdle {one.edit(RegistrationForm("Local","local@example.com",consent=true))}
        runBlocking {e.scenarios.failNextRead(ProductReadError.NETWORK)}
        pull("event-screen-${FakeFixtures.OPEN.value}")
        await {one.state.value.readError==ProductReadError.NETWORK};assertEquals("Local",one.state.value.form.name)
        assertEquals(listOf(FakeFixtures.OPEN,FakeFixtures.OPEN),calls);assertNull(two.state.value.data)
        compose.onNodeWithTag("two").performClick();await {two.state.value.data!=null && calls.size==3}
        pull("event-screen-${FakeFixtures.MIXED.value}")
        await {calls.size==4};assertEquals(listOf(FakeFixtures.OPEN,FakeFixtures.OPEN,FakeFixtures.MIXED,FakeFixtures.MIXED),calls)
        compose.onNodeWithTag("one").performClick();await {calls.size==5};assertEquals("Local",one.state.value.form.name)
    }

    @Test fun dashboardAndVolunteersReadSameEventWithoutRefreshingRetainedParent() {
        val e=compose.backend().environment();runBlocking {e.scenarios.signInAs(FakeFixtures.HOST)}
        var myReads=0;val dashboardReads=mutableListOf<EventId>();val grantReads=mutableListOf<EventId>()
        val events=object:EventRepository by e.events {override suspend fun refreshMyEvents(){myReads++;e.events.refreshMyEvents()}}
        val attendance=object:AttendanceRepository by e.attendance {override suspend fun refreshDashboard(eventId:EventId):DashboardResult {dashboardReads+=eventId;return e.attendance.refreshDashboard(eventId)}}
        val grants=object:ru.eventpass.core.domain.checkin.VolunteerRepository by e.volunteers {
            override suspend fun refreshVolunteers(eventId:EventId):ru.eventpass.core.domain.checkin.VolunteersResult {grantReads+=eventId;return e.volunteers.refreshVolunteers(eventId)}
        }
        val my=own(MyEventsViewModel(events,SavedStateHandle()));val dash=own(DashboardViewModel(attendance,SavedStateHandle()));val volunteers=own(VolunteersViewModel(grants))
        var page by mutableIntStateOf(0)
        compose.runOnUiThread {compose.activity.setContent {EventPassTheme {Column {
            TextAction("Назад",{page=(page-1).coerceAtLeast(0)},Modifier.testTag("back"))
            Box(Modifier.weight(1f)) {
                Box(Modifier.alpha(if(page==0)1f else 0f).zIndex(if(page==0)2f else 0f)) {MyEventsRoute({},{},{},{},{},{},{page=1},page==0,my)}
                Box(Modifier.alpha(if(page==1)1f else 0f).zIndex(if(page==1)2f else 0f)) {DashboardRoute(FakeFixtures.MIXED.value,page==1,{},{page=2},{},dash)}
                Box(Modifier.alpha(if(page==2)1f else 0f).zIndex(if(page==2)2f else 0f)) {VolunteersRoute(FakeFixtures.MIXED.value,page==2,{},volunteers)}
            }
        }}}}
        await {my.data.value.content!=null && myReads==1};assertTrue(dashboardReads.isEmpty());assertTrue(grantReads.isEmpty())
        compose.onNodeWithTag("dashboard-${FakeFixtures.MIXED.value}").performScrollTo().performClick()
        await {dash.state.value.dashboard!=null && !dash.state.value.refreshing};compose.onNodeWithTag("dashboard-screen").performScrollToIndex(0)
        pull("dashboard-screen");await {dashboardReads.size==2};assertEquals(1,myReads)
        compose.runOnIdle {dash.query("alex")}
        compose.onNodeWithTag("dashboard-screen").performScrollToNode(hasTestTag("dashboard-volunteers"))
        compose.onNodeWithTag("dashboard-volunteers").performClick();await {!volunteers.state.value.loading && grantReads.size==1}
        compose.onNodeWithTag("volunteer-input").performTextReplacement("keep@example.com");Espresso.closeSoftKeyboard()
        // Scroll the single existing AuthColumn to its top before pulling.
        compose.onNode(hasText(compose.activity.getString(R.string.volunteers_title)) and hasAnyAncestor(hasTestTag("volunteers-screen"))).performScrollTo()
        pull("volunteers-screen");await {grantReads.size==2};assertEquals("keep@example.com",volunteers.state.value.input)
        assertEquals(2,dashboardReads.size);assertEquals(1,myReads)
        compose.onNodeWithTag("back").performClick();await {dash.state.value.dashboard!=null && dashboardReads.size==3}
        assertEquals("alex",dash.state.value.query);assertEquals(1,myReads);assertTrue(dashboardReads.all {it==FakeFixtures.MIXED});assertTrue(grantReads.all {it==FakeFixtures.MIXED})
        compose.onNodeWithTag("back").performClick();await {myReads==2}
    }

    @Test fun profilePullKeepsDirtyFieldsAndShowsReadRetryWithoutSave() {
        val e=compose.backend().environment();runBlocking {e.scenarios.signInAs(FakeFixtures.ALEX)};var reads=0;var saves=0
        val repo=object:ru.eventpass.core.domain.profile.ProfileRepository by e.profiles {
            override suspend fun refreshProfile():ru.eventpass.core.domain.profile.ProfileResult {reads++;return e.profiles.refreshProfile()}
            override suspend fun saveProfile(fields:ru.eventpass.core.domain.profile.ProfileFields):ru.eventpass.core.domain.profile.ProfileResult {saves++;return e.profiles.saveProfile(fields)}
        }
        val vm=own(ProfileViewModel(repo))
        compose.runOnUiThread {compose.activity.setContent {EventPassTheme {ProfileRoute(true,{}, {},{},{},vm)}}}
        await {vm.state.value.profile!=null && reads==1}
        compose.onNodeWithTag("profile-name").performTextReplacement("Local profile");Espresso.closeSoftKeyboard()
        compose.onNodeWithText(compose.activity.getString(R.string.profile_title)).performScrollTo()
        runBlocking {e.scenarios.failNextRead(ProductReadError.TIMEOUT)};pull("root-Profile")
        await {vm.state.value.readError!=null};assertEquals(2,reads);assertEquals(0,saves);assertEquals("Local profile",vm.state.value.fields.name);idle("refresh-profile")
        compose.onNodeWithText(compose.activity.getString(R.string.ticket_retry)).assertExists()
        pull("root-Profile");await {reads==3 && vm.state.value.readError==null};assertTrue(vm.state.value.dirty);assertEquals(0,saves)
    }

    @Test fun selectedTicketUsesSameReadAndFullscreenAndConfirmationDoNotPassPullBelow() {
        val e=compose.backend().environment();runBlocking {e.scenarios.signInAs(FakeFixtures.ALEX)};var reads=0
        val repo=object:TicketRepository by e.tickets {override suspend fun refreshMyTickets(){reads++;e.tickets.refreshMyTickets()}}
        val vm=own(TicketsViewModel(repo,SavedStateHandle()))
        compose.runOnUiThread {compose.activity.setContent {EventPassTheme {TicketsRoute(null,{},{},{},{},{},vm=vm)}}}
        await {vm.state.value.data!=null && reads==1}
        compose.onNodeWithTag("ticket-seed-ticket-alex-mixed").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.tickets_title)).performScrollTo();pull("root-Tickets")
        await {reads==2 && !vm.state.value.refreshing};assertEquals("seed-ticket-alex-mixed",vm.state.value.selected)
        compose.onNodeWithTag("open-fullscreen").performScrollTo().performClick()
        compose.onNode(isDialog()).performTouchInput {swipeDown()}
        assertEquals(2,reads);compose.onNodeWithTag("refresh-tickets").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
        compose.onNodeWithTag("close-fullscreen").performClick()
        compose.onNodeWithTag("cancel-ticket").performScrollTo().performClick()
        compose.onNode(isDialog()).performTouchInput {swipeDown()};assertEquals(2,reads)
        compose.onNodeWithTag("refresh-tickets").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
        compose.onNodeWithText(compose.activity.getString(R.string.ticket_keep)).performClick()
        assertEquals("seed-ticket-alex-mixed",vm.state.value.selected)
    }

    @Test fun guestReceiptAndScannerNeverRefreshUnderlyingEventOrRepeatCommand() {
        val e=compose.backend().environment();var reads=0
        val repo=object:EventRepository by e.events {override suspend fun refreshPublicEvent(reference: PublicEventReference) { val eventId = (reference as PublicEventReference.Internal).id;reads++;e.events.refreshPublicEvent(eventId)}}
        val vm=own(EventViewModel(repo,e.registrations,e.tickets,SavedStateHandle()))
        compose.runOnUiThread {compose.activity.setContent {EventPassTheme {EventRoute(FakeFixtures.OPEN.value,{_,_,_->},{},{},vm=vm)}}}
        await {vm.state.value.data!=null && reads==1}
        compose.runOnIdle {vm.edit(RegistrationForm("Guest","pull-guest@example.com",consent=true));vm.submit()}
        await {vm.state.value.receipt!=null};val receipt=vm.state.value.receipt
        compose.onNodeWithTag("guest-receipt").performScrollTo()
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("guest-receipt"))).performTouchInput {swipeDown()}
        assertEquals(1,reads);assertEquals(receipt,vm.state.value.receipt);assertEquals(1,e.scenarios.outbox().size)
        compose.onNodeWithTag("refresh-event").assertDoesNotExist()
        runBlocking {e.scenarios.signInAs(FakeFixtures.HOST)}
        compose.runOnUiThread {compose.activity.setContent {EventPassTheme {ru.eventpass.app.feature.checkin.ScannerRoute(FakeFixtures.MIXED.value,true,{},{},{})}}}
        await {compose.onAllNodesWithTag("scan-input").fetchSemanticsNodes().isNotEmpty()}
        pull("scanner-screen");compose.onNodeWithTag("scan-result").assertDoesNotExist();assertEquals(1,reads)
        compose.onNodeWithTag("refresh-event").assertDoesNotExist();compose.onNodeWithTag("refresh-events").assertDoesNotExist()
        assertEquals(1,e.scenarios.outbox().size)
    }
}
