package ru.eventpass.data.fake.product

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

class ProductSchema6Test {
    private fun seed():FakeProductState {
        val events=FakeFixtures.events()
        return FakeProductState(FakeFixtures.accounts().associateBy{it.id},events.associateBy{it.id},FakeFixtures.registrations(),FakeFixtures.tickets(events),FakeFixtures.grants())
    }
    private class Disk(initial:ByteArray):FakeAuthStorage {
        var bytes=initial.copyOf(); var backup:ByteArray?=null; var writes=0; var failBackup=false
        override fun read()=bytes.copyOf()
        override fun write(snapshot:ByteArray){ writes++; bytes=snapshot.copyOf() }
        override fun backupLegacy(snapshot:ByteArray){ if(failBackup)throw IOException("backup failed");backup=snapshot.copyOf() }
    }
    @Test fun schema6RoundTripsAllQuestionMetadataAnswersAndLargeUnicode() {
        val original=seed(); val event=original.events.getValue(FakeFixtures.OPEN)
        val options=listOf(RegistrationQuestion.Option("a","Repeated","hint a"),RegistrationQuestion.Option("b","Repeated","hint b"))
        val questions=TextFormat.entries.map{RegistrationQuestion.Text(it.name,it.name,false,it,"placeholder","help")} +
            listOf(RegistrationQuestion.SingleChoice("choice","Choice",false,options),RegistrationQuestion.SingleChoice("select","Select",false,options,ChoiceFormat.SELECT,"placeholder","help"),RegistrationQuestion.Checkbox("check","Boolean",false,"help","placeholder"),RegistrationQuestion.MultipleChoice("multi","Multiple",false,options,"help","placeholder"),RegistrationQuestion.Unsupported("future","Future",false,"future_type","help","placeholder"))
        val updated=event.copy(description="😀".repeat(20000),questions=questions,priceType=PriceType.PAID,finishedAt=FakeFixtures.NOW,coverUrl="https://example.com/cover",remoteCoverAssetId="remote-cover")
        val draft=EventDraft(DraftId("all"),FakeFixtures.HOST,1,updated.toFields())
        val registration=original.registrations.first().copy(answers=mapOf("organization" to Answer.MultipleChoice(listOf("a","b"))))
        val state=original.copy(events=original.events+(event.id to updated),drafts=mapOf(draft.id to draft),registrations=listOf(registration)+original.registrations.drop(1))
        val bytes=ProductSnapshotCodec.encode(state);assertEquals(6,java.io.DataInputStream(bytes.inputStream()).readInt())
        val decoded=ProductSnapshotCodec.restore(bytes,seed())
        assertEquals(state.events,decoded.events);assertEquals(state.drafts,decoded.drafts);assertEquals(state.registrations,decoded.registrations)
        assertEquals(state.tickets,decoded.tickets);assertEquals(state.grants,decoded.grants)
    }
    @Test fun compatibleLegacyPreservesKnownValuesAndStableLocalReferences() {
        val state=seed()
        for(bytes in listOf(LegacyV2Fixture.encode(state),LegacyV3Fixture.encode(state),LegacyV4Fixture.encode(state),LegacyV5Fixture.encode(state))) {
            val decoded=ProductSnapshotCodec.restore(bytes,seed())
            assertEquals(state.tickets,decoded.tickets);assertEquals(state.registrations,decoded.registrations);assertEquals(state.grants,decoded.grants)
            assertEquals(decoded.events,ProductSnapshotCodec.restore(bytes,seed()).events)
            decoded.events.values.forEach{assertNotNull(it.slug);assertEquals(20,it.capacity);assertEquals(1L,it.revision!!)}
            assertEquals(decoded.events,ProductSnapshotCodec.restore(ProductSnapshotCodec.encode(decoded),seed()).events)
        }
    }
    @Test fun incompatibleLegacyRequiresExplicitResetAndSuccessfulBackup() = runTest {
        val state=seed();val event=state.events.getValue(FakeFixtures.OPEN)
        val one=event.copy(questions=listOf(RegistrationQuestion.SingleChoice("legacy","One",false,listOf(RegistrationQuestion.Option("a","A")))))
        val raw=LegacyV3Fixture.encode(state.copy(events=state.events+(event.id to one)));val disk=Disk(raw)
        val env=FakeEventPassEnvironment(authStorage=disk,authPolicy=FakeAuthPolicy(passwordIterations=1000))
        assertTrue(env.requiresLegacyReset());assertEquals(SessionState.Unknown,env.auth.currentSession().state)
        assertEquals(AuthResult.Failed(AuthError.StorageUnavailable),env.auth.signIn("alex@example.com",AuthSecret("DemoPass123!")))
        assertArrayEquals(raw,disk.bytes);assertEquals(0,disk.writes)
        disk.failBackup=true
        try{env.scenarios.reset();fail("Backup must precede reset")}catch(_:IOException){}
        assertArrayEquals(raw,disk.bytes);assertTrue(env.requiresLegacyReset())
        disk.failBackup=false;env.scenarios.reset();assertArrayEquals(raw,disk.backup);assertFalse(env.requiresLegacyReset());assertEquals(6,java.io.DataInputStream(disk.bytes.inputStream()).readInt())
    }
    @Test fun truncatedLegacyIsCorruptNotResettableIncompatibility() = runTest {
        val state=seed();val event=state.events.getValue(FakeFixtures.OPEN)
        val one=event.copy(questions=listOf(RegistrationQuestion.SingleChoice("legacy","One",false,listOf(RegistrationQuestion.Option("a","A")))))
        val raw=LegacyV3Fixture.encode(state.copy(events=state.events+(event.id to one))).dropLast(1).toByteArray();val disk=Disk(raw)
        val env=FakeEventPassEnvironment(authStorage=disk,authPolicy=FakeAuthPolicy(passwordIterations=1000))
        assertFalse(env.requiresLegacyReset());assertEquals(SessionState.Unknown,env.auth.currentSession().state);assertEquals(0,disk.writes);assertArrayEquals(raw,disk.bytes)
        val fresh=ProductSnapshotCodec.encode(seed())
        try{ProductSnapshotCodec.restore(fresh+byteArrayOf(1),seed());fail("Trailing data must be rejected")}catch(_:IllegalArgumentException){}
    }
    @Test fun schema5MigrationKeepsProfileWithoutInventingCheckinHistory() {
        val before=seed().copy(profileDetails=mapOf(FakeFixtures.ALEX to ProfileDetails("+79991234567","Legacy organization",false)))
        val restored=ProductSnapshotCodec.restore(LegacyV5Fixture.encode(before),seed())
        assertEquals(before.profileDetails,restored.profileDetails);assertEquals(before.tickets,restored.tickets)
        assertEquals(before.checkIns,restored.checkIns)
        assertEquals(before.profileDetails,ProductSnapshotCodec.restore(ProductSnapshotCodec.encode(restored),seed()).profileDetails)
    }

}
