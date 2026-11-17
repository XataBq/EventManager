package ru.eventpass.app.di

import kotlinx.coroutines.flow.flowOf
import ru.eventpass.core.domain.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.core.domain.attendance.*
import ru.eventpass.core.domain.documents.*

/** Temporary DI boundary for existing ports only. No remote success or false network failure. */
internal object UnavailableRemoteFeatures : EventRepository, RegistrationRepository, TicketRepository, CheckInRepository, VolunteerRepository, AttendanceRepository, DocumentsRepository {
 override fun observePublicEvent(reference: PublicEventReference) = flowOf(PublicEventResult.Failed(ProductReadError.FEATURE_UNAVAILABLE))
 override suspend fun refreshPublicEvent(reference: PublicEventReference) = Unit
 override fun observeMyEvents() = flowOf(MyEventsResult.Failed(EventEditError.FeatureUnavailable))
 override fun observeEditor(target: EventEditorTarget) = flowOf(EventEditorResult.Failed(EventEditError.FeatureUnavailable))
 override suspend fun refreshMyEvents() = Unit
 override suspend fun saveDraft(id: DraftId, expectedRevision: Long?, fields: EventFields) = EventEditResult.Failed(EventEditError.FeatureUnavailable)
 override suspend fun publishDraft(id: DraftId, expectedRevision: Long?) = EventEditResult.Failed(EventEditError.FeatureUnavailable)
 override suspend fun updateEvent(id: EventId, expectedRevision: Long?, expectedLocalSchemaRevision: Int?, fields: EventFields) = EventEditResult.Failed(EventEditError.FeatureUnavailable)
 override suspend fun finishEvent(id: EventId, expectedRevision: Long?) = EventEditResult.Failed(EventEditError.FeatureUnavailable)
 override suspend fun submitGuest(command: GuestRegistrationCommand) = GuestRegistrationOutcome.Failed(RegistrationError.FeatureUnavailable)
 override suspend fun submitAuthenticated(command: AuthenticatedRegistrationCommand) = AuthenticatedRegistrationOutcome.Failed(RegistrationError.FeatureUnavailable)
 override fun observeMyTickets() = flowOf(MyTicketsResult.Failed(ProductReadError.FEATURE_UNAVAILABLE))
 override fun observeOwnTicket(ticketId: TicketId) = flowOf(OwnTicketResult.Failed(ProductReadError.FEATURE_UNAVAILABLE))
 override suspend fun refreshMyTickets() = Unit
 override suspend fun cancelOwnTicket(ticketId: TicketId) = CancelTicketOutcome.Failed(TicketError.FEATURE_UNAVAILABLE)
 override fun observeAccess(eventId: EventId) = flowOf(ScannerAccess.Denied(CheckInError.FEATURE_UNAVAILABLE))
 override suspend fun refreshAccess(eventId: EventId) = Unit
 override suspend fun checkIn(eventId: EventId, credential: TicketCredential) = CheckInOutcome.Failed(CheckInError.FEATURE_UNAVAILABLE)
 override fun observeVolunteers(eventId: EventId) = flowOf(VolunteersResult.Failed(VolunteerError.FEATURE_UNAVAILABLE))
 override suspend fun refreshVolunteers(eventId: EventId) = VolunteersResult.Failed(VolunteerError.FEATURE_UNAVAILABLE)
 override suspend fun grant(eventId: EventId, emailOrId: String) = VolunteerOutcome.Failed(VolunteerError.FEATURE_UNAVAILABLE)
 override suspend fun revoke(eventId: EventId, userId: UserId) = VolunteerOutcome.Failed(VolunteerError.FEATURE_UNAVAILABLE)
 override fun observeDashboard(eventId: EventId) = flowOf(DashboardResult.Failed(AttendanceError.FEATURE_UNAVAILABLE))
 override suspend fun refreshDashboard(eventId: EventId) = DashboardResult.Failed(AttendanceError.FEATURE_UNAVAILABLE)
 override suspend fun attendees(eventId: EventId, query: String, cursor: AttendanceCursor?) = AttendeesResult.Failed(AttendanceError.FEATURE_UNAVAILABLE)
 override suspend fun cancelRegistration(eventId: EventId, registrationId: String) = OwnerCancelResult.Failed(AttendanceError.FEATURE_UNAVAILABLE)
 override suspend fun prepare(request: DocumentRequest) = DocumentResult.Failed(DocumentError.FEATURE_UNAVAILABLE)
 override suspend fun validate(snapshot: DocumentSnapshot) = DocumentError.FEATURE_UNAVAILABLE
}
