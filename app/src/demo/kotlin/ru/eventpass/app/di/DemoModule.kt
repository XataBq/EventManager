package ru.eventpass.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import ru.eventpass.core.domain.DemoRepository
import ru.eventpass.data.fake.InMemoryDemoRepository

@Module
@InstallIn(SingletonComponent::class)
object DemoModule {
    @Provides
    @Singleton
    fun provideDemoRepository(): DemoRepository = InMemoryDemoRepository()
}
