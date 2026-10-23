package app.ultraslim

import app.ultraslim.ui.history.OriginalUriCache
import org.junit.Assert.*
import org.junit.Test

class OriginalUriCacheTest {
    @Test fun relinkDropsOldOriginalAndRejectsLateResolution() {
        val cache = OriginalUriCache()
        val old = OriginalUriCache.Source(1, "content://old")
        val replacement = OriginalUriCache.Source(1, "content://replacement")
        val oldWork = cache.plan(listOf(old))
        val newWork = cache.plan(listOf(replacement))
        cache.complete(newWork, mapOf(replacement to "content://media/new"))
        cache.complete(oldWork, mapOf(old to "content://media/old"))
        assertEquals(mapOf(1L to "content://media/new"), cache.snapshot())

        val third = OriginalUriCache.Source(1, "content://third")
        assertEquals(listOf(third), cache.plan(listOf(third)))
        assertTrue(cache.snapshot().isEmpty())
    }

    @Test fun repeatedRefreshSharesWorkAndUnresolvableRelinkNeverKeepsOldTarget() {
        val cache = OriginalUriCache()
        val source = OriginalUriCache.Source(1, "content://source")
        val pending = cache.plan(listOf(source))
        assertTrue(cache.plan(listOf(source)).isEmpty())
        cache.complete(pending, mapOf(source to "content://media/1"))
        assertTrue(cache.plan(listOf(source)).isEmpty())
        val missing = OriginalUriCache.Source(1, "content://unavailable")
        val work = cache.plan(listOf(missing))
        cache.complete(work, emptyMap())
        assertTrue(cache.snapshot().isEmpty())
        assertEquals(listOf(missing), cache.plan(listOf(missing)))
    }
}
