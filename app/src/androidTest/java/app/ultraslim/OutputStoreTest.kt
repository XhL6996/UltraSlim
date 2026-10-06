package app.ultraslim

import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.data.HistoryEntity
import app.ultraslim.util.outputName
import app.ultraslim.convert.OutputStore
import java.io.File
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class OutputStoreTest {
    @Test fun failedSaveCanRetryWithoutEncodingAndIsIdempotent() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UltraSlimApp
        val task = java.util.UUID.randomUUID().toString()
        val file = File(app.filesDir,"outputs/$task.jpg")
        var row = HistoryEntity(displayName=outputName("IMG_regression.jpg", "jpg", task, 0),inputUri="content://test",outputUri=file.path,
            inputBytes=100,outputBytes=100,width=64,height=64,gainmapWidth=0,gainmapHeight=0,hadGainMap=false,
            format="JPEG",inputFormat="JPEG",durationMs=1.0,maxContentBoost=1f,createdAt=System.currentTimeMillis(),
            status="DONE",taskId=task,saveState="PENDING")
        row = row.copy(id=app.database.historyDao().insert(row))
        var savedUri: Uri? = null
        try {
            val failed = OutputStore.save(app,row)
            assertEquals("FAILED",failed.saveState)
            file.parentFile!!.mkdirs()
            instrumentation.context.assets.open("minnie-320x240-yuv-icc.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
            val shared = OutputStore.uri(app, file.path, row.displayName)
            app.contentResolver.query(shared, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(row.displayName, it.getString(0))
            }
            val saved = OutputStore.save(app,failed)
            assertEquals("SAVED",saved.saveState); assertFalse(file.exists())
            savedUri = Uri.parse(saved.outputUri)
            assertEquals("image/jpeg",app.contentResolver.getType(savedUri))
            assertEquals(saved.outputUri,OutputStore.save(app,failed).outputUri)
            app.contentResolver.query(savedUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(row.displayName, it.getString(0))
            }
            // Recover a publication completed just before the DB checkpoint was written.
            instrumentation.context.assets.open("minnie-320x240-yuv-icc.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
            app.database.historyDao().update(failed)
            assertEquals(saved.outputUri, OutputStore.save(app,failed).outputUri)
        } finally {
            savedUri?.let { app.contentResolver.delete(it,null,null) }
            OutputStore.delete(app,row.id); file.delete()
        }
    }
}
