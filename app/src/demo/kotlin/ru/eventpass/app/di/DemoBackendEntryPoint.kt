package ru.eventpass.app.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.eventpass.core.domain.auth.AuthRepository
import ru.eventpass.data.fake.product.FakeEventPassEnvironment

/** Demo tools/tests only; production UI and ViewModels use repository ports. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DemoBackendEntryPoint {
    fun auth(): AuthRepository
    fun environment(): FakeEventPassEnvironment
}
