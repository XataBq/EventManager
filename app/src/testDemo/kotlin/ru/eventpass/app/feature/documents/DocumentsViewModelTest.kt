package ru.eventpass.app.feature.documents

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.documents.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentsViewModelTest {
    private val request=DocumentRequest.TicketPdf(TicketId("ticket"))
    private val snapshot=DocumentSnapshot.Ticket(DocumentStamp(UserId("alex"),1),request,
        GuestTicketSnapshot(TicketId("ticket"),EventId("event"),"Title",Instant.EPOCH,"UTC","Hall","Alex","N","opaque"))
    private class AuthFixture : AuthRepository by ru.eventpass.data.fake.product.FakeEventPassEnvironment().auth {
        private val account=Account(UserId("alex"),"alex@example.com",true)
        private val values=MutableStateFlow(SessionSnapshot(1,SessionState.Authenticated(Session("one",account,Instant.MAX))))
        override fun observeSession()=values
        override fun currentSession()=values.value
        fun logout() {values.value=SessionSnapshot(values.value.generation+1,SessionState.Guest)}
        fun login() {val n=values.value.generation+1;values.value=SessionSnapshot(n,SessionState.Authenticated(Session("session-$n",account,Instant.MAX)))}
    }
    private inner class Repo : DocumentsRepository {
        var prepares=0;var sends=0;var prepare: suspend()->DocumentResult={DocumentResult.Ready(snapshot)}
        override suspend fun prepare(request: DocumentRequest): DocumentResult {prepares++;return prepare()}
        override suspend fun validate(snapshot: DocumentSnapshot): DocumentError?=null
        override suspend fun resendTicket(ticketId: TicketId): ResendResult {sends++;return ResendResult.Queued(Instant.EPOCH)}
    }
    private class Files : DocumentFiles {
        var writes=0;var result: DocumentError?=null
        var saveAction: (suspend () -> DocumentError?)?=null
        override suspend fun cleanup()=Unit
        override suspend fun save(snapshot: DocumentSnapshot,destination: String,authorize: suspend()->DocumentError?): DocumentError? {authorize()?.let{return it};writes++;return saveAction?.invoke() ?: result}
    }
    @Test fun configurationRebindPreservesLaunchedPickerAndInFlightWriterForSameResource()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val auth=AuthFixture();val repo=Repo();val files=Files();val vm=DocumentsViewModel(repo,files,auth)
        try {
            for(resource in listOf(request,DocumentRequest.AttendanceXlsx(EventId("event")))) {
                vm.bind(resource,true);vm.begin(resource);val id=vm.state.value.picker!!.id;vm.launched(id)
                vm.unbind(recreating=true);vm.bind(resource,true);runCurrent()
                assertEquals(id,vm.state.value.picker!!.id);assertTrue(vm.state.value.picker!!.launched);assertTrue(vm.state.value.busy)
                val write=CompletableDeferred<DocumentError?>();files.saveAction={write.await()}
                vm.destination(id,"content://test/export");runCurrent();val prepares=repo.prepares;val writes=files.writes
                vm.unbind(recreating=true);vm.bind(resource,true);runCurrent()
                assertTrue(vm.state.value.busy);assertEquals(prepares,repo.prepares);assertEquals(writes,files.writes)
                write.complete(null);runCurrent();assertTrue(vm.state.value.saved);assertFalse(vm.state.value.busy)
                assertEquals(prepares,repo.prepares);assertEquals(writes,files.writes)
                vm.destination(id,"content://test/duplicate");runCurrent();assertEquals(writes,files.writes)
                vm.unbind(recreating=false)
            }
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
    @Test fun realExitHiddenAndChangedResourceStillDiscardOldPicker()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val repo=Repo();val files=Files();val vm=DocumentsViewModel(repo,files,AuthFixture())
        try {
            for(reason in 0..2) {
                vm.bind(request,true);vm.begin(request);val id=vm.state.value.picker!!.id;vm.launched(id)
                when(reason) {0->vm.unbind(recreating=false);1->vm.bind(request,false);else->{vm.unbind(recreating=true);vm.bind(DocumentRequest.TicketPdf(TicketId("other")),true)}}
                vm.destination(id,"content://test/late");runCurrent()
                assertFalse(vm.state.value.busy);assertNull(vm.state.value.picker);assertEquals(0,repo.prepares);assertEquals(0,files.writes)
            }
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
    @Test fun pickerCancelDoesNotReadDataAndRetrySuccessRequiresCompletedWriter()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val auth=AuthFixture();val repo=Repo();val files=Files();val vm=DocumentsViewModel(repo,files,auth)
        try {
            vm.begin(request);vm.begin(request);val first=vm.state.value.picker!!.id;vm.destination(first,null);runCurrent()
            assertEquals(0,repo.prepares);assertFalse(vm.state.value.saved);assertFalse(vm.state.value.busy)
            files.result=DocumentError.WRITE_FAILED;vm.begin(request);vm.destination(vm.state.value.picker!!.id,"content://test/one");runCurrent()
            assertEquals(DocumentError.WRITE_FAILED,vm.state.value.error);assertFalse(vm.state.value.saved)
            files.result=null;vm.begin(request);vm.destination(vm.state.value.picker!!.id,"content://test/two");runCurrent()
            assertTrue(vm.state.value.saved);assertFalse(vm.state.value.busy);assertEquals(2,files.writes)
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
    @Test fun latePickerOrPrepareAfterLogoutCannotWriteOrUnlockNewOperation()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val auth=AuthFixture();val repo=Repo();val files=Files()
        val pending=mutableListOf<Continuation<DocumentResult>>();repo.prepare={suspendCoroutine{pending+=it}};val vm=DocumentsViewModel(repo,files,auth)
        try {
            vm.begin(request);val old=vm.state.value.picker!!.id;vm.destination(old,"content://test/old");runCurrent()
            auth.logout();runCurrent();assertFalse(vm.state.value.busy);assertNull(vm.state.value.picker)
            auth.login();runCurrent();vm.begin(request);val next=vm.state.value.picker!!.id
            vm.destination(old,"content://test/late");assertEquals(next,vm.state.value.picker!!.id)
            vm.destination(next,"content://test/new");runCurrent();pending[0].resume(DocumentResult.Ready(snapshot));runCurrent()
            assertTrue(vm.state.value.busy);assertEquals(0,files.writes)
            pending[1].resume(DocumentResult.Ready(snapshot));runCurrent();assertTrue(vm.state.value.saved);assertEquals(1,files.writes)
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
    @Test fun resendBusyAndSessionCleanupDoNotRetainOldMessages()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val auth=AuthFixture();val repo=Repo();val vm=DocumentsViewModel(repo,Files(),auth)
        try {
            vm.resend(request.ticketId);vm.resend(request.ticketId);runCurrent();assertEquals(1,repo.sends);assertTrue(vm.state.value.mailQueued)
            auth.logout();runCurrent();assertFalse(vm.state.value.mailQueued);assertNull(vm.state.value.retryAt)
            vm.begin(request);assertEquals(DocumentError.AUTHENTICATION_REQUIRED,vm.state.value.error)
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
}
