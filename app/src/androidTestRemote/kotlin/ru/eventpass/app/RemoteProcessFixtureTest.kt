package ru.eventpass.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.eventpass.app.di.UnavailableRemoteFeatures
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.profile.DeleteAccountResult
import ru.eventpass.core.platform.AndroidEventCoverStore
import ru.eventpass.core.platform.AndroidSessionTokenStore
import ru.eventpass.data.local.AndroidDraftStoreFactory
import ru.eventpass.data.remote.*

/** Two opt-in fixture phases around a host-driven OS process kill; neither pretends to be that kill. */
class RemoteProcessFixtureTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val record get()=File(context.noBackupFilesDir,"m09-process-fixture.private.json")
    @Test fun seedDurableDraftForExternalProcessCheck()=runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m09ProcessFixture")=="seed")
        check(!record.exists()) {"An owned process fixture must be cleaned before creating another"}
        val email="android.m09.process.${UUID.randomUUID()}@example.com";val password=UUID.randomUUID().toString()
        record.writeText(buildJsonObject {put("email",email);put("password",password)}.toString())
        val api=AuthHttp("https://staging.eventpass.dikanskii.ru/api/")
        val auth=RemoteAuthRepository(api,AndroidSessionTokenStore(context))
        assertTrue(auth.signUp("M09 process",email,AuthSecret(password),true) is AuthResult.Success)
        val store=AndroidDraftStoreFactory.open(context);val covers=AndroidEventCoverStore(context)
        val repository=LocalDraftRepository(api,auth,RemoteOrganizerRepository(api,auth,UnavailableRemoteFeatures),store,RemoteCoverTransfer(api,auth,covers),covers)
        val id=DraftId(UUID.randomUUID().toString());repository.beginDraft(id)
        assertTrue(repository.saveDraft(id,null,EventFields(title="M09 durable process draft")) is EventEditResult.Saved)
        val account=(auth.currentSession().state as SessionState.Authenticated).session.account.id
        val stored=store.read(store.activate(account),id)!!
        assertNotNull(stored.command);assertNull(stored.command!!.firstAttemptAt)
        record.writeText(buildJsonObject {put("email",email);put("password",password);put("accountId",account.value);put("draftId",id.value)}.toString())
    }
    @Test fun cleanupOnlyOwnedProcessFixture()=runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m09ProcessFixture")=="cleanup")
        if(!record.exists()) return@runBlocking
        val data=Json.parseToJsonElement(record.readText()).jsonObject
        val email=data.getValue("email").jsonPrimitive.content
        require(email.startsWith("android.m09.process.") && email.endsWith("@example.com"))
        val store=AndroidDraftStoreFactory.open(context)
        val auth=RemoteAuthRepository(AuthHttp("https://staging.eventpass.dikanskii.ru/api/"),AndroidSessionTokenStore(context)) {store.purge(it)}
        assertTrue(auth.signIn(email,AuthSecret(data.getValue("password").jsonPrimitive.content)) is AuthResult.Success)
        assertTrue(auth.profileRepository.deleteAccount() is DeleteAccountResult.Deleted)
        assertTrue(record.delete())
    }
}
