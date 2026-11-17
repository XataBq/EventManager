package ru.eventpass.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.Dispatchers
import ru.eventpass.core.domain.event.DraftStore

object AndroidDraftStoreFactory {
    fun open(context:Context):DraftStore {
        val path=File(context.noBackupFilesDir,"drafts/eventpass-drafts.db")
        path.parentFile?.mkdirs()
        return RoomDraftStore(Room.databaseBuilder<DraftDatabase>(context.applicationContext,path.absolutePath)
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build())
    }
}
