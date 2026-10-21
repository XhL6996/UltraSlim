package app.ultraslim

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.convert.*
import app.ultraslim.data.ReplacementEntity
import app.ultraslim.data.AppDatabase
import app.ultraslim.data.HistoryEntity
import androidx.room.withTransaction
import app.ultraslim.model.OutputFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PublicationRegressionTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UltraSlimApp

    @Test fun requestIncludesMoreThan100ButExcludesExternallyRemovedOriginals() {
        val token = UUID.randomUUID().toString()
        val file = File(app.cacheDir, "$token.jpg")
        InstrumentationRegistry.getInstrumentation().context.assets.open("minnie-320x240-yuv-icc.jpg").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val uris = mutableListOf<Uri>()
        try {
            repeat(103) { index -> uris += MediaStoreSaver.save(app, file, "$token-$index.jpg", OutputFormat.JPEG)!! }
            app.contentResolver.update(uris[0], ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
            app.contentResolver.delete(uris[1], null, null)
            val request = MediaStoreTrashHelper.prepareTrashRequest(app, uris + uris[2])
            assertNotNull(request.message, request.sender)
            assertEquals(101, request.uris.size)
            assertFalse(uris[0] in request.uris)
            assertFalse(uris[1] in request.uris)
            assertEquals(MediaStoreTrashHelper.OriginalState.TRASHED, MediaStoreTrashHelper.originalState(app, uris[0]))
            assertEquals(MediaStoreTrashHelper.OriginalState.UNAVAILABLE, MediaStoreTrashHelper.originalState(app, uris[1]))
            val fileUri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external_primary"), ContentUris.parseId(uris[2]))
            assertEquals(uris[2], MediaStoreTrashHelper.resolveToMediaStoreImageUri(app, fileUri))
            assertNotNull(MediaStoreTrashHelper.prepareTrashRequest(app, listOf(fileUri)).sender)
            assertNull(MediaStoreTrashHelper.prepareTrashRequest(app, listOf(uris[0], uris[1])).sender)
        } finally { uris.filterIndexed { index, _ -> index != 1 }.forEach { app.contentResolver.delete(it, android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }) }; file.delete() }
    }

    @Test fun pendingJournalCanFinishAboveSQLiteBindLimit() = runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val dao = db.replacementDao()
            repeat(2100) { dao.insert(ReplacementEntity(it.toLong(), "test", "source", "output", "sha", "sha", "READY")) }
            dao.finish((0L until 2100L).toList(), "CANCELLED", "cancel all")
            assertEquals("CANCELLED", dao.byId(2099)?.state)
        } finally { db.close() }
    }

    @Test fun replacementNotesHandleLargeBatchesAndReplacePreviousOutcome() = runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val dao = db.historyDao()
            // Exceeds both older Android's 999 and newer SQLite's 32766 bind limit.
            val ids = (1L..33000L).toList()
            db.withTransaction {
                ids.forEach { id -> dao.insert(HistoryEntity(
                    id = id, displayName = "test.jpg", inputUri = "source", outputUri = "output",
                    inputBytes = 100, outputBytes = 80, width = 1, height = 1,
                    gainmapWidth = 0, gainmapHeight = 0, hadGainMap = false,
                    format = "JPEG", inputFormat = "JPEG", durationMs = 0.0,
                    maxContentBoost = 1f, createdAt = id, status = "DONE",
                    settingsSummary = "原参数 · 替换结果：旧结果",
                )) }
            }
            dao.noteReplacement(ids, "原图保留")
            assertTrue(dao.all().all { it.settingsSummary == "原参数 · 替换结果：原图保留" })
            dao.noteReplacement(emptyList(), "不应更新")
            assertEquals("原参数 · 替换结果：原图保留", dao.byId(33000)!!.settingsSummary)
        } finally { db.close() }
    }
}
