package ru.eventpass.data.fake.product

import java.time.Instant
import ru.eventpass.core.domain.event.Account
import ru.eventpass.core.domain.event.Event
import ru.eventpass.core.domain.event.EventId
import ru.eventpass.core.domain.event.EventLifecycle
import ru.eventpass.core.domain.event.ScanGrant
import ru.eventpass.core.domain.event.TicketId
import ru.eventpass.core.domain.event.UserId
import ru.eventpass.core.domain.registration.Answer
import ru.eventpass.core.domain.registration.GuestTicketSnapshot
import ru.eventpass.core.domain.registration.Registration
import ru.eventpass.core.domain.registration.RegistrationQuestion
import ru.eventpass.core.domain.registration.Ticket
import ru.eventpass.core.domain.registration.TicketStatus

object FakeFixtures {
    const val SEED = "t02-v1"
    val NOW: Instant = Instant.parse("2026-10-27T09:00:00Z")
    val ALEX = UserId("alex")
    val HOST = UserId("host")
    val OPEN = EventId("open-registration")
    val OWNED = EventId("alex-owned")
    val VOLUNTEER = EventId("alex-volunteer")
    val ATTENDING = EventId("alex-attending")
    val MIXED = EventId("alex-mixed")

    fun accounts(): List<Account> = listOf(
        Account(ALEX, "alex@example.com", emailVerified = true),
        Account(HOST, "host@example.com", emailVerified = true),
    )

    fun events(): List<Event> = listOf(OPEN, OWNED, VOLUNTEER, ATTENDING, MIXED).map { id ->
        Event(
            id = id,
            ownerId = if (id == OWNED) ALEX else HOST,
            title = when (id) {
                OPEN -> "IT & Data Science 2026"
                OWNED -> "Проектная конференция"
                VOLUNTEER -> "День открытых дверей"
                ATTENDING -> "Мастер-класс Kotlin"
                else -> "Командный хакатон"
            },
            startsAt = NOW.plusSeconds(86_400),
            endsAt = NOW.plusSeconds(100_800),
            zoneId = "Europe/Moscow",
            venue = "Демонстрационный зал",
            capacity = 20,
            lifecycle = EventLifecycle.PUBLISHED,
            registrationOpen = true,
            formVersion = 1,
            questions = listOf(
                RegistrationQuestion.Text("organization", "Вуз / компания", required = false),
                RegistrationQuestion.Checkbox("workshop", "Участвовать в мастер-классе", required = false),
                RegistrationQuestion.SingleChoice(
                    "format", "Формат участия", required = false,
                    options = listOf(RegistrationQuestion.Option("listener", "Слушатель")),
                ),
            ),
            description = "Студенческая научно-практическая конференция: исследования, прикладные модели и мастер-классы.",
            sections = listOf(ru.eventpass.core.domain.event.EventSection("LLM & Generative AI", "10:30 — 13:00"),
                ru.eventpass.core.domain.event.EventSection("Data Engineering & ML Ops", "14:00 — 17:30")),
        )
    }

    fun grants(): List<ScanGrant> = listOf(ScanGrant(VOLUNTEER, ALEX), ScanGrant(MIXED, ALEX))

    internal fun registrations(): List<Registration> = listOf(ATTENDING, MIXED).map {
        Registration(
            id = "seed-registration-${it.value}", eventId = it, userId = ALEX,
            attendeeEmail = "alex@example.com", attendeeName = "Alex", phone = null,
            formVersion = 1, answers = mapOf("organization" to Answer.Text("Demo")),
            consentAcceptedAt = NOW,
        )
    }

    internal fun tickets(events: List<Event>): List<Ticket> = registrations().map { registration ->
        val event = events.single { it.id == registration.eventId }
        Ticket(
            snapshot = GuestTicketSnapshot(
                TicketId("seed-ticket-${event.id.value}"), event.id, event.title, event.startsAt,
                event.zoneId, event.venue, registration.attendeeName, "DEMO-${event.id.value}",
                "demo-token-${event.id.value}",
            ),
            registrationId = registration.id,
            status = TicketStatus.ACTIVE,
        )
    }
}
