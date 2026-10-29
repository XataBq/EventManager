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
    @Provides @Singleton fun provideDemoClock(): DemoClock = DemoClock()
    @Provides fun provideAuthClock(clock: DemoClock): java.time.Clock = clock
    @Provides
    @Singleton
    fun provideDemoRepository(): DemoRepository = InMemoryDemoRepository()

    @Provides
    @Singleton
    fun provideProductEnvironment(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context, clock: DemoClock): FakeEventPassEnvironment =
        FakeEventPassEnvironment(authStorage = AndroidFakeAuthStorage(context.noBackupFilesDir), authClock = clock)

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
