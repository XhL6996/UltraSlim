package app.ultraslim

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.convert.ImageWork
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ImageWorkTest {
    @Test fun probeTraceIsSampledButFailuresAndHeavyWorkAreAlwaysRecorded() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.cacheDir, "trace-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val trace = java.io.File(directory, "last-image-work.json")
        try {
            ImageWork.initialize(directory)
            ImageWork.run { Unit }
            val first = trace.readText()
            assertEquals("completed", org.json.JSONObject(first).getString("state"))
            repeat(10) { ImageWork.run { Unit } }
            assertEquals(first, trace.readText())
            try {
                ImageWork.run { error("test probe failure") }
                fail("Expected probe failure")
            } catch (_: IllegalStateException) { }
            assertEquals("failed", org.json.JSONObject(trace.readText()).getString("state"))
            ImageWork.run("preview test") {
                assertEquals("started", org.json.JSONObject(trace.readText()).getString("state"))
            }
            assertEquals("preview test", org.json.JSONObject(trace.readText()).getString("stage"))
            assertEquals("completed", org.json.JSONObject(trace.readText()).getString("state"))
        } finally {
            ImageWork.initialize(context.filesDir)
            ImageWork.run("restore trace") { Unit }
            directory.deleteRecursively()
        }
    }

    @Test fun nativeJobsAreSerialAndDoNotBlockMainThread() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val first = async(Dispatchers.Default) {
            ImageWork.run("test encoder") {
                assertEquals(Process.THREAD_PRIORITY_BACKGROUND, Process.getThreadPriority(Process.myTid()))
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val waiting = async(Dispatchers.Default) { ImageWork.run("test preview") { calls.incrementAndGet() } }
            delay(100)
            assertEquals(0, calls.get())
            var mainResponsive = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { mainResponsive = true }
            assertTrue(mainResponsive)
            release.countDown()
            withTimeout(5000) { first.await(); waiting.await() }
            assertEquals(1, calls.get())
        } finally { release.countDown() }
    }
}
