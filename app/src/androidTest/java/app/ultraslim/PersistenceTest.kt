package app.ultraslim

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import android.content.Context
import app.ultraslim.data.*
import app.ultraslim.convert.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @Test fun migrateV1PreservesPendingHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val old = context.openOrCreateDatabase(name, 0, null)
        old.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, displayName TEXT NOT NULL, inputUri TEXT NOT NULL, outputUri TEXT NOT NULL, inputBytes INTEGER NOT NULL, outputBytes INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, gainmapWidth INTEGER NOT NULL, gainmapHeight INTEGER NOT NULL, hadGainMap INTEGER NOT NULL, format TEXT NOT NULL, inputFormat TEXT NOT NULL, durationMs REAL NOT NULL, maxContentBoost REAL NOT NULL, createdAt INTEGER NOT NULL, status TEXT NOT NULL)")
        old.execSQL("INSERT INTO history VALUES (7, 'photo.heic', 'content://input', '/cache/output.heic', 100, 80, 32, 32, 8, 8, 1, 'HEIC', 'JPEG', 12.5, 4.0, 1, 'DONE')")
        old.version = 1; old.close()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2).build()
        try { val row = db.historyDao().byId(7)!!; assertEquals("PENDING",row.saveState); assertEquals(80L,row.outputBytes); assertTrue(db.taskDao().all().isEmpty()) }
        finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun durableQueueAndCancellation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val repo=ConversionRepository(db.taskDao(),scope);repo.awaitReady()
            repo.addAll(listOf(QueueItem("a","content://a","a.jpg",100), QueueItem("b","content://b","b.jpg",200,status=QueueStatus.FAILED),QueueItem("c","content://c","c.jpg",200,status=QueueStatus.DONE)))
            repo.flush(); repo.clearFinished(); repo.flush()
            assertEquals(setOf("a","b"),db.taskDao().all().map {it.id}.toSet())
            repo.update("a"){it.copy(status=QueueStatus.CONVERTING)};repo.remove("a");repo.flush()
            assertEquals(2,db.taskDao().all().size)
            val restored=ConversionRepository(db.taskDao(),scope);restored.awaitReady()
            assertEquals(QueueStatus.READY,restored.find("a")?.status)
            val options=ConversionDefaults(onlySmaller=true,keepLocation=false)
            db.taskDao().putBatch(BatchEntity("batch","[\"a\"]",options.encode()))
            db.taskDao().cancel("batch")
            assertTrue(db.taskDao().batch("batch")!!.cancelled)
            assertEquals(options,decodeDefaults(db.taskDao().batch("batch")!!.settings))
        } finally {scope.cancel();db.close()}
    }
}
