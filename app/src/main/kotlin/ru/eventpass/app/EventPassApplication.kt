package ru.eventpass.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.*

@HiltAndroidApp
class EventPassApplication : Application() {
    @Inject lateinit var documents: ru.eventpass.core.domain.documents.DocumentFiles
    private val applicationScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onCreate() { super.onCreate(); applicationScope.launch { documents.cleanup() } }
}
