package app.ultraslim

import app.ultraslim.convert.selectReplacementPage
import app.ultraslim.data.ReplacementEntity
import org.junit.Assert.*
import org.junit.Test

class ReplacementSelectionTest {
    @Test fun largeRequestKeepsEveryOriginalInExactlyOnePage() {
        val rows = (1L..4001L).map { ReplacementEntity(it, "batch", "source/$it", "output/$it", "a", "b") }
        val first = selectReplacementPage(rows)
        assertEquals(2000, first.size)
        assertEquals(rows.take(2000), first)
        val second = selectReplacementPage(rows - first.toSet())
        assertEquals(rows.subList(2000, 4000), second)
        assertEquals(rows.takeLast(1), selectReplacementPage(rows - first.toSet() - second.toSet()))
        assertTrue(selectReplacementPage(emptyList()).isEmpty())
    }

    @Test fun duplicateOriginalsStayTogetherEvenAcrossThePageBoundary() {
        val rows = (1L..2001L).map { ReplacementEntity(it, "batch", "source/$it", "output/$it", "a", "b") }
        val duplicate = rows.first().copy(historyId = 3000, batchId = "other-batch")
        val page = selectReplacementPage(rows + duplicate)
        assertEquals(2000, page.map { it.sourceUri }.distinct().size)
        assertEquals(2001, page.size)
        assertTrue(duplicate in page)
        assertFalse(rows.last() in page)
    }
}
