package ru.eventpass.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.profile.*
import ru.eventpass.core.platform.AndroidSessionTokenStore
import ru.eventpass.data.remote.*

@Module @InstallIn(SingletonComponent::class)
object RemoteModule {
 @Provides @Singleton fun tokens(@ApplicationContext context: Context): SessionTokenStore = AndroidSessionTokenStore(context)
 @Provides @Singleton fun http() = AuthHttp("https://staging.eventpass.dikanskii.ru/api/")
 @Provides @Singleton fun drafts(@ApplicationContext context: Context): ru.eventpass.core.domain.event.DraftStore = ru.eventpass.data.local.AndroidDraftStoreFactory.open(context)
 @Provides @Singleton fun auth(http: AuthHttp, tokens: SessionTokenStore, drafts: ru.eventpass.core.domain.event.DraftStore, store: ru.eventpass.core.platform.AndroidEventCoverStore) = RemoteAuthRepository(http,tokens) { account ->
  val removed=drafts.purge(account)
  val retained=drafts.retainedCovers()
  removed.filterNot { it in retained }.forEach { store.releaseId(it) }
 }
 @Provides fun authPort(auth: RemoteAuthRepository): AuthRepository = auth
 @Provides fun profiles(auth: RemoteAuthRepository): ProfileRepository = auth.profileRepository
 @Provides fun clock(): java.time.Clock = java.time.Clock.systemUTC()
 @Provides @Singleton fun organizer(http:AuthHttp,auth:RemoteAuthRepository)=RemoteOrganizerRepository(http,auth,UnavailableRemoteFeatures)
 @Provides @Singleton fun events(http: AuthHttp, auth: RemoteAuthRepository, drafts: ru.eventpass.core.domain.event.DraftStore,
  transfer: RemoteCoverTransfer, store: ru.eventpass.core.platform.AndroidEventCoverStore,organizer:RemoteOrganizerRepository): ru.eventpass.core.domain.EventRepository =
  RemoteEventRepository(http,auth,LocalDraftRepository(http,auth,organizer,drafts,transfer,store))
 @Provides @Singleton fun registrations(http: AuthHttp, auth: RemoteAuthRepository): ru.eventpass.core.domain.RegistrationRepository = RemoteRegistrations(http, auth)
 @Provides @Singleton fun remoteTickets(http:AuthHttp,auth:RemoteAuthRepository)=RemoteTickets(http,auth)
 @Provides fun tickets(remote:RemoteTickets):ru.eventpass.core.domain.TicketRepository=remote
 @Provides @Singleton fun remoteScanner(http:AuthHttp,auth:RemoteAuthRepository)=RemoteCheckInRepository(http,auth)
 @Provides fun checkins(remote:RemoteCheckInRepository):ru.eventpass.core.domain.checkin.CheckInRepository=remote
 @Provides @Singleton fun volunteers(http:AuthHttp,auth:RemoteAuthRepository): ru.eventpass.core.domain.checkin.VolunteerRepository = RemoteVolunteersRepository(http,auth)
 @Provides @Singleton fun attendance(http:AuthHttp,auth:RemoteAuthRepository,organizer:RemoteOrganizerRepository,
  volunteers:ru.eventpass.core.domain.checkin.VolunteerRepository,tickets:RemoteTickets,scanner:RemoteCheckInRepository):ru.eventpass.core.domain.attendance.AttendanceRepository =
  RemoteAttendanceRepository(http,auth,organizer,volunteers,tickets,scanner)
 @Provides @Singleton fun documents(http: AuthHttp, auth: RemoteAuthRepository): ru.eventpass.core.domain.documents.DocumentsRepository = RemoteTicketDocuments(http, auth)
 @Provides @Singleton fun documentFiles(@ApplicationContext context: Context): ru.eventpass.core.domain.documents.DocumentFiles = ru.eventpass.core.platform.AndroidDocumentFiles(context)
 @Provides @Singleton fun coverStore(@ApplicationContext context: Context) = ru.eventpass.core.platform.AndroidEventCoverStore(context)
 @Provides @Singleton fun coverTransfer(http: AuthHttp,auth: RemoteAuthRepository,store: ru.eventpass.core.platform.AndroidEventCoverStore) = RemoteCoverTransfer(http,auth,store)
 @Provides @Singleton fun covers(store: ru.eventpass.core.platform.AndroidEventCoverStore,drafts: ru.eventpass.core.domain.event.DraftStore): ru.eventpass.core.domain.event.CoverImporter = object : ru.eventpass.core.domain.event.CoverImporter {
  private val lock=kotlinx.coroutines.sync.Mutex()
  private var swept=false
  override suspend fun importCover(source: String): ru.eventpass.core.domain.event.CoverImportResult {
   lock.lock()
   try {
    if(!swept) { store.cleanupUnreferenced(drafts.retainedCovers()); swept=true }
    return store.importCover(source)
   } finally { lock.unlock() }
  }
 }
 @Provides fun preview(store: ru.eventpass.core.platform.AndroidEventCoverStore,transfer: RemoteCoverTransfer): ru.eventpass.core.platform.EventCoverPreview = object : ru.eventpass.core.platform.EventCoverPreview {
  override suspend fun preview(cover: ru.eventpass.core.domain.event.EventCover) = cover.remoteAssetId?.let { previewRemote(it) } ?: store.preview(cover)
  override suspend fun previewRemote(assetId: String): android.graphics.Bitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
   val bytes = transfer.read(assetId) ?: return@withContext null
   store.previewBytes(bytes)
  }
 }
}
