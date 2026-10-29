package ru.eventpass.data.fake.product

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

class EventEditingTest {
    private open class Storage : FakeAuthStorage {
        var bytes: ByteArray? = null
        var fail = false
        override fun read() = bytes?.copyOf()
        override fun write(snapshot: ByteArray) { if (fail) throw IOException("Test failure"); bytes = snapshot.copyOf() }
    }
    private class Time(var now: Instant = FakeFixtures.NOW) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
    private val policy = FakeAuthPolicy(passwordIterations = 1000)
    private val fixed = Clock.fixed(FakeFixtures.NOW, ZoneOffset.UTC)
    private fun backend(storage: Storage = Storage(), clock: Clock = fixed) =
        FakeEventPassEnvironment(clock = clock, authClock = fixed, authStorage = storage, authPolicy = policy)
    private val id = DraftId("draft-one")
    private fun fields() = EventFields("Новая конференция", FakeFixtures.NOW.plusSeconds(3600),
        FakeFixtures.NOW.plusSeconds(7200), "Europe/Moscow", "Зал 1", null, listOf(
            DraftQuestion("text", "Организация"), DraftQuestion("check", "Участие", kind = QuestionKind.CHECKBOX),
            DraftQuestion("choice", "Формат", kind = QuestionKind.SINGLE_CHOICE,
                options = listOf(RegistrationQuestion.Option("live", "Очно"), RegistrationQuestion.Option("online", "Онлайн")))),
        "Описание", listOf(EventSection("Открытие", "12:00")), EventCover("cover-one", "image/png", 100))
    private fun <T> saved(result: EventEditResult<T>): T = (result as EventEditResult.Saved<T>).value
    private fun failed(error: EventEditError) = EventEditResult.Failed(error)
    private suspend fun event(e: FakeEventPassEnvironment, eventId: EventId = FakeFixtures.OPEN) =
        (e.events.observePublicEvent(eventId).first() as PublicEventResult.Found).event
    private suspend fun create(e: FakeEventPassEnvironment, input: EventFields = fields()): EventId {
        val draft = saved(e.events.saveDraft(id, null, input))
        return saved(e.events.publishDraft(id, draft.revision))
    }

    @Test fun incompleteDraftSurvivesRestartAndPublishesSameDataExactlyOnce() = runTest {
        val storage = Storage(); val e = backend(storage); e.scenarios.signInAs(FakeFixtures.ALEX)
        val incomplete = EventFields(questions = listOf(DraftQuestion("choice", kind = QuestionKind.SINGLE_CHOICE)))
        val draft = saved(e.events.saveDraft(id, null, incomplete))
        assertNull(draft.fields.startsAt)
        assertTrue((e.events.publishDraft(id, 1) as EventEditResult.Failed).error is EventEditError.Validation)
        assertEquals(PublicEventResult.NotFound, e.events.observePublicEvent(EventId(id.value)).first())
        val next = backend(storage)
        assertEquals(SessionState.Guest, next.auth.observeSession().first().state)
        next.scenarios.signInAs(FakeFixtures.ALEX)
        assertEquals(draft, (next.events.observeEditor(EventEditorTarget.Draft(id)).first() as EventEditorResult.Draft).draft)
        val complete = saved(next.events.saveDraft(id, 1, fields()))
        val eventId = saved(next.events.publishDraft(id, complete.revision))
        assertEquals(eventId, saved(next.events.publishDraft(id, complete.revision)))
        assertEquals(failed(EventEditError.Conflict), next.events.saveDraft(id, complete.revision, fields()))
        val mine = next.events.observeMyEvents().first() as MyEventsResult.Ready
        assertTrue(mine.drafts.isEmpty()); assertEquals(1, mine.organizing.count { it.id == eventId })
        next.scenarios.signOut()
        val public = next.events.observePublicEvent(eventId).first() as PublicEventResult.Found
        assertEquals(fields(), public.event.toFields())
        assertTrue(public.availability is RegistrationAvailability.Open)
        val third = backend(storage); third.scenarios.signInAs(FakeFixtures.ALEX)
        assertEquals(eventId, saved(third.events.publishDraft(id, complete.revision)))
    }

    @Test fun privateDraftsAndManagementRequireCurrentVerifiedOwner() = runTest {
        val e = backend()
        assertEquals(failed(EventEditError.AuthenticationRequired), e.events.saveDraft(id, null, fields()))
        e.scenarios.signInAs(FakeFixtures.HOST); saved(e.events.saveDraft(id, null, fields()))
        e.scenarios.signInAs(FakeFixtures.ALEX)
        assertEquals(EventEditorResult.Failed(EventEditError.Forbidden), e.events.observeEditor(EventEditorTarget.Draft(id)).first())
        assertEquals(failed(EventEditError.Forbidden), e.events.saveDraft(id, 1, fields()))
        assertEquals(failed(EventEditError.Forbidden), e.events.publishDraft(id, 1))
        val volunteer = event(e, FakeFixtures.VOLUNTEER)
        assertEquals(EventEditorResult.Failed(EventEditError.Forbidden), e.events.observeEditor(EventEditorTarget.Published(volunteer.id)).first())
        assertEquals(failed(EventEditError.Forbidden), e.events.updateEvent(volunteer.id, 1, 1, volunteer.toFields()))
        assertEquals(failed(EventEditError.Forbidden), e.events.closeRegistration(volunteer.id, 1))
        assertEquals(failed(EventEditError.Forbidden), e.events.finishEvent(volunteer.id, 1))
        assertTrue((e.events.observeMyEvents().first() as MyEventsResult.Ready).drafts.isEmpty())
        e.scenarios.putAccount(Account(UserId("unverified"), "new@example.com", false)); e.scenarios.signInAs(UserId("unverified"))
        assertEquals(failed(EventEditError.AuthenticationRequired), e.events.saveDraft(DraftId("another"), null, fields()))
    }

    @Test fun relationshipsOverlapAndRevocationLeavesOwnTicket() = runTest {
        val e = backend(); e.scenarios.signInAs(FakeFixtures.ALEX)
        e.scenarios.setGrant(ScanGrant(FakeFixtures.OWNED, FakeFixtures.ALEX))
        val lists = e.events.observeMyEvents().first() as MyEventsResult.Ready
        assertTrue(lists.organizing.any { it.id == FakeFixtures.OWNED }); assertTrue(lists.volunteering.any { it.id == FakeFixtures.OWNED })
        e.scenarios.setGrant(ScanGrant(FakeFixtures.MIXED, FakeFixtures.ALEX, false))
        assertFalse((e.events.observeMyEvents().first() as MyEventsResult.Ready).volunteering.any { it.id == FakeFixtures.MIXED })
        assertTrue((e.tickets.observeMyTickets().first() as MyTicketsResult.Ready).tickets.any { it.snapshot.eventId == FakeFixtures.MIXED })
    }

    @Test fun validationPreservesDraftAndKeepsUnlimitedDifferentFromZero() = runTest {
        val e = backend(); e.scenarios.signInAs(FakeFixtures.ALEX)
        val invalid = fields().copy(title = " ", venue = "", startsAt = FakeFixtures.NOW, endsAt = FakeFixtures.NOW,
            zoneId = "invalid zone", capacity = -1, questions = listOf(DraftQuestion("q", " ", kind = QuestionKind.SINGLE_CHOICE)))
        saved(e.events.saveDraft(id, null, invalid))
        val errors = (e.events.publishDraft(id, 1) as EventEditResult.Failed).error as EventEditError.Validation
        assertTrue(errors.fields.containsAll(setOf("title", "venue", "startsAt", "endsAt", "zoneId", "capacity", "questions")))
        assertEquals(invalid, (e.events.observeEditor(EventEditorTarget.Draft(id)).first() as EventEditorResult.Draft).draft.fields)
        saved(e.events.saveDraft(id, 1, fields().copy(capacity = 0)))
        val eventId = saved(e.events.publishDraft(id, 2)); val current = event(e, eventId)
        assertEquals(RegistrationAvailability.Closed(RegistrationClosureReason.FULL), current.registrationAvailability(FakeFixtures.NOW, 0))
        val unlimited = saved(e.events.updateEvent(eventId, 1, 1, current.toFields().copy(capacity = null)))
        assertEquals(RegistrationAvailability.Open(AvailablePlaces.Unlimited), unlimited.registrationAvailability(FakeFixtures.NOW, 500))
    }

    @Test fun revisionsPreventLostUpdatesAndSchemaVersionChangesOnlyForQuestions() = runTest {
        val e = backend(); e.scenarios.signInAs(FakeFixtures.HOST); val original = event(e)
        val renamed = saved(e.events.updateEvent(original.id, 1, 1, original.toFields().copy(title = "Новое название")))
        assertEquals(1, renamed.formVersion); assertEquals(2L, renamed.revision)
        assertEquals(original.description, renamed.description); assertEquals(original.sections, renamed.sections)
        assertEquals(failed(EventEditError.Conflict), e.events.updateEvent(original.id, 1, 1, original.toFields()))
        val schema = original.toFields().copy(questions = fields().questions.reversed())
        val changed = saved(e.events.updateEvent(original.id, 2, 1, schema))
        assertEquals(2, changed.formVersion); assertEquals(fields().questions.reversed().map { it.id }, changed.questions.map { it.id })
        assertEquals(failed(EventEditError.StaleForm), e.events.updateEvent(original.id, 3, 1, changed.toFields()))
        val stale = e.registrations.submitGuest(GuestRegistrationCommand(original.id, 1, "Мария", "guest@example.com", consentAccepted = true))
        assertEquals(GuestRegistrationOutcome.Failed(RegistrationError.StaleForm), stale)
    }

    @Test fun guestRegistrationLocksSchemaAfterAllCancelAndRestartWithoutChangingAnswers() = runTest {
        val storage = Storage(); val e = backend(storage); e.scenarios.signInAs(FakeFixtures.HOST)
        val before = event(e)
        val ticket = (e.registrations.submitGuest(GuestRegistrationCommand(before.id, 1, "Guest", "guest@example.com",
            answers = mapOf("organization" to Answer.Text("Университет")), consentAccepted = true)) as GuestRegistrationOutcome.Issued).ticket
        val registration = e.scenarios.registrationFor(ticket.ticketId)
        assertEquals(failed(EventEditError.QuestionsLocked), e.events.updateEvent(before.id, 1, 1,
            before.toFields().copy(questions = before.toFields().questions.reversed())))
        e.scenarios.setTicketStatus(ticket.ticketId, TicketStatus.CANCELLED)
        val restarted = backend(storage); restarted.scenarios.signInAs(FakeFixtures.HOST)
        assertTrue((restarted.events.observeEditor(EventEditorTarget.Published(before.id)).first() as EventEditorResult.Published).questionsLocked)
        for (schema in listOf(emptyList(), before.toFields().questions.map { it.copy(required = !it.required) },
            before.toFields().questions.map { it.copy(title = "Другая формулировка") },
            before.toFields().questions.map { q -> if (q.kind == QuestionKind.SINGLE_CHOICE) q.copy(options = q.options.map { it.copy(title = "Иной вариант") }) else q })) {
            assertEquals(failed(EventEditError.QuestionsLocked), restarted.events.updateEvent(before.id, 1, 1, before.toFields().copy(questions = schema)))
        }
        saved(restarted.events.updateEvent(before.id, 1, 1, before.toFields().copy(venue = "Новый зал")))
        assertEquals(registration, restarted.scenarios.registrationFor(ticket.ticketId))
    }

    @Test fun capacityIncludesUsedAndExcludesCancelledAndMetadataKeepsCredentials() = runTest {
        val storage = Storage(); val e = backend(storage); e.scenarios.signInAs(FakeFixtures.ALEX)
        val issued = e.registrations.submitAuthenticated(AuthenticatedRegistrationCommand(FakeFixtures.OPEN, 1, "Alex", consentAccepted = true)) as AuthenticatedRegistrationOutcome.Registered
        val old = (e.tickets.observeOwnTicket(issued.ticketId).first() as OwnTicketResult.Found).ticket
        e.scenarios.setTicketStatus(issued.ticketId, TicketStatus.USED); e.scenarios.signInAs(FakeFixtures.HOST)
        val original = event(e)
        assertEquals(failed(EventEditError.Validation(setOf("capacity"))), e.events.updateEvent(original.id, 1, 1, original.toFields().copy(capacity = 0)))
        val updated = saved(e.events.updateEvent(original.id, 1, 1, original.toFields().copy(title = "Изменено", venue = "Новый зал", capacity = 1)))
        e.scenarios.signInAs(FakeFixtures.ALEX)
        val changed = (e.tickets.observeOwnTicket(issued.ticketId).first() as OwnTicketResult.Found).ticket
        assertEquals(old.snapshot.qrPayload, changed.snapshot.qrPayload); assertEquals(old.registrationId, changed.registrationId)
        assertEquals(TicketStatus.USED, changed.status); assertEquals(updated.title, changed.snapshot.eventTitle); assertEquals(updated.venue, changed.snapshot.venue)
        e.scenarios.setTicketStatus(issued.ticketId, TicketStatus.CANCELLED); e.scenarios.signInAs(FakeFixtures.HOST)
        assertTrue(e.events.updateEvent(original.id, 2, 1, updated.toFields().copy(capacity = 0)) is EventEditResult.Saved)
        val restarted = backend(storage); restarted.scenarios.signInAs(FakeFixtures.ALEX)
        assertEquals(changed.snapshot, (restarted.tickets.observeOwnTicket(issued.ticketId).first() as OwnTicketResult.Found).ticket.snapshot)
    }

    @Test fun closeStartEndAndManualFinishUseSharedClockAndCannotReopen() = runTest {
        val time = Time(); val e = backend(clock = time); e.scenarios.signInAs(FakeFixtures.ALEX)
        val eventId = create(e); val original = event(e, eventId)
        val closed = saved(e.events.closeRegistration(eventId, 1))
        assertEquals(RegistrationAvailability.Closed(RegistrationClosureReason.CLOSED),
            (e.events.observePublicEvent(eventId).first() as PublicEventResult.Found).availability)
        assertEquals(original.title, event(e, eventId).title)
        time.now = original.startsAt
        assertEquals(RegistrationAvailability.Closed(RegistrationClosureReason.STARTED), event(e, eventId).registrationAvailability(time.now, 0))
        assertEquals(failed(EventEditError.Validation(setOf("datesLocked"))), e.events.updateEvent(eventId, closed.revision, 1,
            closed.toFields().copy(startsAt = time.now.plusSeconds(1))))
        val metadata = saved(e.events.updateEvent(eventId, closed.revision, 1, closed.toFields().copy(title = "Идёт")))
        time.now = original.endsAt!!
        assertEquals(RegistrationAvailability.Closed(RegistrationClosureReason.FINISHED), event(e, eventId).registrationAvailability(time.now, 0))
        assertEquals(failed(EventEditError.EventFinished), e.events.updateEvent(eventId, metadata.revision, 1, fields().copy(startsAt = time.now.plusSeconds(1))))
        saved(e.events.finishEvent(eventId, metadata.revision))
        assertEquals(EventLifecycle.FINISHED, event(e, eventId).lifecycle)
    }

    @Test fun failedWritesAndTransientFailuresPublishNoFalseDraftOrEvent() = runTest {
        val storage = Storage(); val e = backend(storage); e.scenarios.signInAs(FakeFixtures.ALEX)
        storage.fail = true
        assertEquals(failed(EventEditError.StorageUnavailable), e.events.saveDraft(id, null, fields()))
        assertTrue((e.events.observeMyEvents().first() as MyEventsResult.Ready).drafts.isEmpty())
        storage.fail = false; saved(e.events.saveDraft(id, null, fields())); val before = storage.bytes!!.copyOf()
        storage.fail = true
        assertEquals(failed(EventEditError.StorageUnavailable), e.events.publishDraft(id, 1))
        assertArrayEquals(before, storage.bytes); assertEquals(1, (e.events.observeMyEvents().first() as MyEventsResult.Ready).organizing.size)
        storage.fail = false
        for (failure in listOf(EventEditError.Network, EventEditError.Timeout)) {
            e.scenarios.failNextEventEdit(failure); assertEquals(failed(failure), e.events.publishDraft(id, 1))
        }
        val eventId = saved(e.events.publishDraft(id, 1)); val old = event(e, eventId)
        storage.fail = true
        assertEquals(failed(EventEditError.StorageUnavailable), e.events.updateEvent(eventId, 1, 1, fields().copy(title = "Не сохранено")))
        assertEquals(failed(EventEditError.StorageUnavailable), e.events.closeRegistration(eventId, 1))
        assertEquals(failed(EventEditError.StorageUnavailable), e.events.finishEvent(eventId, 1))
        assertEquals(old, event(e, eventId))
    }

    @Test fun duplicateConcurrentPublicationReturnsOneEventAndStaleSaveFails() = runTest {
        val e = backend(); e.scenarios.signInAs(FakeFixtures.ALEX); saved(e.events.saveDraft(id, null, fields()))
        val results = (1..5).map { async { saved(e.events.publishDraft(id, 1)) } }.awaitAll()
        assertEquals(1, results.distinct().size)
        val another = DraftId("incomplete"); saved(e.events.saveDraft(another, null, EventFields()))
        val updates = (1..2).map { async { e.events.saveDraft(another, 1, fields()) } }.awaitAll()
        assertEquals(1, updates.count { it is EventEditResult.Saved }); assertEquals(1, updates.count { it == failed(EventEditError.Conflict) })
    }

    @Test fun readErrorsCanBeRetriedAndExpiredSessionCannotReadOrWrite() = runTest {
        val time = Time(); val e = FakeEventPassEnvironment(clock = fixed, authClock = time, authPolicy = policy)
        e.scenarios.signInAs(FakeFixtures.ALEX)
        e.scenarios.failNextRead(ProductReadError.NETWORK); e.events.refreshMyEvents()
        assertEquals(MyEventsResult.Failed(EventEditError.Network), e.events.observeMyEvents().first())
        e.events.refreshMyEvents(); assertTrue(e.events.observeMyEvents().first() is MyEventsResult.Ready)
        time.now = time.now.plusSeconds(1800)
        assertEquals(MyEventsResult.Failed(EventEditError.AuthenticationRequired), e.events.observeMyEvents().first())
        assertEquals(failed(EventEditError.AuthenticationRequired), e.events.saveDraft(id, null, fields()))
    }

    @Test fun authAndTicketWritesRetainSavedDraftAndPublishedMetadata() = runTest {
        val storage = Storage(); val e = backend(storage); e.scenarios.signInAs(FakeFixtures.ALEX)
        val eventId = create(e)
        saved(e.events.saveDraft(DraftId("other"), null, EventFields(title = "Неполное")))
        e.auth.signUp("Другой", "other@example.com", AuthSecret("OtherPass123!"))
        e.registrations.submitGuest(GuestRegistrationCommand(FakeFixtures.OPEN, 1, "Guest", "guest@example.com", consentAccepted = true))
        val restarted = backend(storage); restarted.scenarios.signInAs(FakeFixtures.ALEX)
        val lists = restarted.events.observeMyEvents().first() as MyEventsResult.Ready
        assertEquals(listOf("Неполное"), lists.drafts.map { it.fields.title })
        assertEquals(fields(), event(restarted, eventId).toFields())
    }

    @Test fun queuedEditorDoesNotBlockSingleCallerWhileAnotherOperationWritesDisk() = runTest {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val storage = object : Storage() {
            @Volatile var block = false
            override fun write(snapshot: ByteArray) {
                if (block) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                super.write(snapshot)
            }
        }
        val e = backend(storage); e.scenarios.signInAs(FakeFixtures.ALEX)
        storage.block = true
        val writer = launch(Dispatchers.Default) { e.scenarios.putEvent(FakeFixtures.events().first()) }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val caller = executor.asCoroutineDispatcher()
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val queued = async(caller) { e.events.saveDraft(id, null, fields()) }
            val marker = async(caller) { "responsive" }
            assertEquals("responsive", withContext(Dispatchers.Default) { withTimeout(2000) { marker.await() } })
            withContext(Dispatchers.Default) { withTimeout(2000) { queued.cancelAndJoin() } }
            assertTrue(!writer.isCompleted)
            release.countDown(); writer.join()
            assertTrue((e.events.observeMyEvents().first() as MyEventsResult.Ready).drafts.isEmpty())
        } finally { release.countDown(); caller.close(); executor.shutdownNow() }
    }

    @Test fun v2MigrationPreservesTicketsAnswersProofsAndPendingChallengeThenWritesV3() = runTest {
        val storage = Storage(); val e = backend(storage)
        val receipt = e.registrations.submitGuest(GuestRegistrationCommand(FakeFixtures.OPEN, 1, "Guest", "guest@example.com",
            answers = mapOf("organization" to Answer.Text("Сохранить ответ")), consentAccepted = true)) as GuestRegistrationOutcome.Issued
        val challenge = (e.auth.signUp("Новый", "pending@example.com", AuthSecret("Pending123!")) as AuthResult.Success).value
        val secret = e.scenarios.authOutbox().last().secret
        val state = ProductSnapshotCodec.restore(storage.bytes!!, seed())
        storage.bytes = LegacyV2Fixture.encode(state)
        val decodedV2 = ProductSnapshotCodec.restore(storage.bytes!!, seed())
        assertEquals(state.accounts, decodedV2.accounts); assertEquals(state.auth.passwords, decodedV2.auth.passwords)
        assertEquals(state.auth.challenges, decodedV2.auth.challenges); assertEquals(state.auth.names, decodedV2.auth.names)
        assertEquals(state.events, decodedV2.events); assertEquals(state.registrations, decodedV2.registrations)
        assertEquals(state.tickets, decodedV2.tickets); assertEquals(state.grants, decodedV2.grants)
        assertEquals(state.policy, decodedV2.policy); assertEquals(state.nextId, decodedV2.nextId)
        val restarted = backend(storage)
        assertEquals(SessionState.Guest, restarted.auth.observeSession().first().state)
        assertTrue(restarted.auth.confirmEmail(challenge.id, secret) is AuthResult.Success)
        assertTrue(restarted.auth.signIn("pending@example.com", AuthSecret("Pending123!")) is AuthResult.Success)
        saved(restarted.events.saveDraft(id, null, EventFields()))
        val migrated = ProductSnapshotCodec.restore(storage.bytes!!, seed())
        assertEquals(3, java.io.DataInputStream(storage.bytes!!.inputStream()).readInt())
        assertEquals(state.registrations, migrated.registrations); assertEquals(state.tickets, migrated.tickets)
        assertEquals(state.auth.passwords, migrated.auth.passwords)
        assertEquals(state.auth.challenges.keys, migrated.auth.challenges.keys)
        assertEquals(e.scenarios.registrationFor(receipt.ticket.ticketId), restarted.scenarios.registrationFor(receipt.ticket.ticketId))
        assertEquals(1, migrated.drafts.size)
    }

    @Test fun v1MigrationKeepsAuthAndCorruptSnapshotNeverOverwritesData() = runTest {
        val storage = Storage(); val e = backend(storage)
        val challenge = (e.auth.signUp("Новый", "pending@example.com", AuthSecret("Pending123!")) as AuthResult.Success).value
        val secret = e.scenarios.authOutbox().last().secret
        val old = ProductSnapshotCodec.restore(storage.bytes!!, seed())
        storage.bytes = AuthSnapshotCodec.encode(old.accounts, old.auth)
        val restored = backend(storage); assertTrue(restored.auth.confirmEmail(challenge.id, secret) is AuthResult.Success)
        assertTrue(restored.auth.signIn("pending@example.com", AuthSecret("Pending123!")) is AuthResult.Success)
        saved(restored.events.saveDraft(id, null, EventFields()))
        val migrated = ProductSnapshotCodec.restore(storage.bytes!!, seed())
        assertEquals(old.auth.passwords, migrated.auth.passwords); assertEquals(1, migrated.drafts.size)
        storage.bytes = byteArrayOf(0, 0, 0, 3, 1); val bad = storage.bytes!!.copyOf()
        val broken = backend(storage)
        assertEquals(failed(EventEditError.StorageUnavailable), broken.events.saveDraft(id, null, fields()))
        assertArrayEquals(bad, storage.bytes)
    }

    @Test fun schemaV3TruncationAndTrailingDataAreRejectedWithoutReset() = runTest {
        val storage = Storage(); val e = backend(storage); e.scenarios.signInAs(FakeFixtures.ALEX)
        create(e); saved(e.events.saveDraft(DraftId("partial"), null, EventFields()))
        val valid = storage.bytes!!.copyOf()
        for (invalid in listOf(valid.copyOf(valid.size - 1), valid + byteArrayOf(1))) {
            storage.bytes = invalid
            val restarted = backend(storage)
            assertEquals(MyEventsResult.Failed(EventEditError.StorageUnavailable), restarted.events.observeMyEvents().first())
            assertArrayEquals(invalid, storage.bytes)
        }
    }

    @Test fun queuedMutationRechecksSessionAndCancellationDoesNotBlockCallerThread() = runTest {
        val state = kotlinx.coroutines.flow.MutableStateFlow(seed().let { s -> s.copy(auth = s.auth.copy(
            session = Session("first", s.accounts.getValue(FakeFixtures.ALEX), FakeFixtures.NOW.plusSeconds(1800)))) })
        val mutex = Mutex(); val storage = Storage()
        val auth = FakeAuthRepository(state, mutex, fixed, storage, policy, RandomAuthSecrets()) {}
        auth.initialize()
        state.value = state.value.copy(auth = state.value.auth.copy(session = Session("first",
            state.value.accounts.getValue(FakeFixtures.ALEX), FakeFixtures.NOW.plusSeconds(1800))))
        val operations = FakeEventEditingOperations(state, mutex, fixed, fixed, storage, auth)
        mutex.lock()
        try {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { operations.saveDraft(id, null, fields()) }
            // Entry runs through generation capture before dispatching to IO, then suspends at the shared Mutex.
            state.value = state.value.copy(auth = state.value.auth.copy(generation = state.value.auth.generation + 1))
            mutex.unlock()
            assertEquals(failed(EventEditError.SessionChanged), waiting.await())
            mutex.lock()
            val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { operations.saveDraft(id, null, fields()) }
            withContext(Dispatchers.Default) { withTimeout(2000) { cancelled.cancelAndJoin() } }
            assertTrue(state.value.drafts.isEmpty())
        } finally { if (mutex.isLocked) mutex.unlock() }
    }

    private fun seed(): FakeProductState {
        val events = FakeFixtures.events()
        return FakeProductState(FakeFixtures.accounts().associateBy { it.id }, events.associateBy { it.id },
            FakeFixtures.registrations(), FakeFixtures.tickets(events), FakeFixtures.grants())
    }
}
