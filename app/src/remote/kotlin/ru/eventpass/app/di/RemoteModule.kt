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
 @Provides @Singleton fun auth(http: AuthHttp, tokens: SessionTokenStore) = RemoteAuthRepository(http,tokens)
 @Provides fun authPort(auth: RemoteAuthRepository): AuthRepository = auth
 @Provides fun profiles(auth: RemoteAuthRepository): ProfileRepository = auth.profileRepository
 @Provides fun clock(): java.time.Clock = java.time.Clock.systemUTC()
 @Provides @Singleton fun events(http: AuthHttp, auth: RemoteAuthRepository): ru.eventpass.core.domain.EventRepository = RemoteEventRepository(http,auth,RemoteOrganizerRepository(http,auth,UnavailableRemoteFeatures))
 @Provides @Singleton fun registrations(http: AuthHttp, auth: RemoteAuthRepository): ru.eventpass.core.domain.RegistrationRepository = RemoteRegistrations(http, auth)
 @Provides @Singleton fun tickets(http: AuthHttp, auth: RemoteAuthRepository): ru.eventpass.core.domain.TicketRepository = RemoteTickets(http, auth)
 @Provides fun checkins(): ru.eventpass.core.domain.checkin.CheckInRepository = UnavailableRemoteFeatures
 @Provides fun volunteers(): ru.eventpass.core.domain.checkin.VolunteerRepository = UnavailableRemoteFeatures
 @Provides fun attendance(): ru.eventpass.core.domain.attendance.AttendanceRepository = UnavailableRemoteFeatures
 @Provides @Singleton fun documents(http: AuthHttp, auth: RemoteAuthRepository): ru.eventpass.core.domain.documents.DocumentsRepository = RemoteTicketDocuments(http, auth)
 @Provides @Singleton fun documentFiles(@ApplicationContext context: Context): ru.eventpass.core.domain.documents.DocumentFiles = ru.eventpass.core.platform.AndroidDocumentFiles(context)
 @Provides @Singleton fun coverStore(@ApplicationContext context: Context) = ru.eventpass.core.platform.AndroidEventCoverStore(context)
 @Provides @Singleton fun coverTransfer(http: AuthHttp,auth: RemoteAuthRepository,store: ru.eventpass.core.platform.AndroidEventCoverStore) = RemoteCoverTransfer(http,auth,store)
 @Provides fun covers(store: ru.eventpass.core.platform.AndroidEventCoverStore,transfer: RemoteCoverTransfer): ru.eventpass.core.domain.event.CoverImporter = object : ru.eventpass.core.domain.event.CoverImporter {
  override suspend fun importCover(source: String) = transfer.importCover(source,store)
 }
 @Provides fun preview(store: ru.eventpass.core.platform.AndroidEventCoverStore,transfer: RemoteCoverTransfer): ru.eventpass.core.platform.EventCoverPreview = object : ru.eventpass.core.platform.EventCoverPreview {
  override suspend fun preview(cover: ru.eventpass.core.domain.event.EventCover) = cover.remoteAssetId?.let { previewRemote(it) } ?: store.preview(cover)
  override suspend fun previewRemote(assetId: String): android.graphics.Bitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
   val bytes = transfer.read(assetId) ?: return@withContext null
   store.previewBytes(bytes)
  }
 }
}
