package ru.eventpass.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import ru.eventpass.core.domain.DemoRepository
import ru.eventpass.data.fake.InMemoryDemoRepository
import ru.eventpass.core.domain.EventRepository
import ru.eventpass.core.domain.RegistrationRepository
import ru.eventpass.core.domain.TicketRepository
import ru.eventpass.data.fake.product.FakeEventPassEnvironment

@Module
@InstallIn(SingletonComponent::class)
object DemoModule {
    @Provides fun provideAttendance(environment: FakeEventPassEnvironment): ru.eventpass.core.domain.attendance.AttendanceRepository = environment.attendance
    @Provides fun provideCheckIns(environment: FakeEventPassEnvironment): ru.eventpass.core.domain.checkin.CheckInRepository = environment.checkIns
    @Provides fun provideVolunteers(environment: FakeEventPassEnvironment): ru.eventpass.core.domain.checkin.VolunteerRepository = environment.volunteers
    @Provides @Singleton fun provideCoverPreview(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context):
        ru.eventpass.core.platform.EventCoverPreview = ru.eventpass.core.platform.AndroidEventCoverStore(context)
    @Provides @Singleton fun provideCovers(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        environment: FakeEventPassEnvironment): ru.eventpass.core.domain.event.CoverImporter {
        val store = ru.eventpass.core.platform.AndroidEventCoverStore(context)
        return object : ru.eventpass.core.domain.event.CoverImporter {
            private val initialization = kotlinx.coroutines.sync.Mutex()
            private var initialized = false
            override suspend fun importCover(source: String): ru.eventpass.core.domain.event.CoverImportResult {
                initialization.lock()
                try { if (!initialized) {
                    environment.retainedCoverAssetIds()?.let { store.cleanupUnreferenced(it) }
                    initialized = true
                } } finally { initialization.unlock() }
                return store.importCover(source)
            }
        }
    }
    @Provides @Singleton fun provideDemoClock(): DemoClock = DemoClock()
    @Provides @Singleton fun provideEventClock(): DemoEventClock = DemoEventClock()
    @Provides fun provideAuthClock(clock: DemoClock): java.time.Clock = clock
    @Provides
    @Singleton
    fun provideDemoRepository(): DemoRepository = InMemoryDemoRepository()

    @Provides
    @Singleton
    fun provideProductEnvironment(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context, clock: DemoClock, eventClock: DemoEventClock): FakeEventPassEnvironment =
        FakeEventPassEnvironment(clock = eventClock, authStorage = AndroidFakeAuthStorage(context.noBackupFilesDir), authClock = clock)

    @Provides
    fun provideAuthRepository(environment: FakeEventPassEnvironment): ru.eventpass.core.domain.auth.AuthRepository = environment.auth

    @Provides
    fun provideEventRepository(environment: FakeEventPassEnvironment): EventRepository = environment.events

    @Provides
    fun provideRegistrationRepository(environment: FakeEventPassEnvironment): RegistrationRepository =
        environment.registrations

    @Provides
    fun provideTicketRepository(environment: FakeEventPassEnvironment): TicketRepository = environment.tickets
}
