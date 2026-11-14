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
 @Provides fun events(): ru.eventpass.core.domain.EventRepository = UnavailableRemoteFeatures
 @Provides fun registrations(): ru.eventpass.core.domain.RegistrationRepository = UnavailableRemoteFeatures
 @Provides fun tickets(): ru.eventpass.core.domain.TicketRepository = UnavailableRemoteFeatures
 @Provides fun checkins(): ru.eventpass.core.domain.checkin.CheckInRepository = UnavailableRemoteFeatures
 @Provides fun volunteers(): ru.eventpass.core.domain.checkin.VolunteerRepository = UnavailableRemoteFeatures
 @Provides fun attendance(): ru.eventpass.core.domain.attendance.AttendanceRepository = UnavailableRemoteFeatures
 @Provides fun documents(): ru.eventpass.core.domain.documents.DocumentsRepository = UnavailableRemoteFeatures
 @Provides fun documentFiles(): ru.eventpass.core.domain.documents.DocumentFiles = object : ru.eventpass.core.domain.documents.DocumentFiles {
  override suspend fun save(snapshot: ru.eventpass.core.domain.documents.DocumentSnapshot, destination: String, authorize: suspend () -> ru.eventpass.core.domain.documents.DocumentError?) = ru.eventpass.core.domain.documents.DocumentError.FEATURE_UNAVAILABLE
  override suspend fun cleanup() = Unit
 }
 @Provides fun covers(): ru.eventpass.core.domain.event.CoverImporter = object : ru.eventpass.core.domain.event.CoverImporter { override suspend fun importCover(source: String) = ru.eventpass.core.domain.event.CoverImportResult.Unavailable }
 @Provides fun preview(): ru.eventpass.core.platform.EventCoverPreview = object : ru.eventpass.core.platform.EventCoverPreview { override suspend fun preview(cover: ru.eventpass.core.domain.event.EventCover): android.graphics.Bitmap? = null }
}
