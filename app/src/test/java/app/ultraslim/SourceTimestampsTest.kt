package app.ultraslim

import app.ultraslim.convert.SourceTimestamps
import org.junit.Assert.*
import org.junit.Test

class SourceTimestampsTest {
    @Test fun outputMtimePrefersModificationTimeAndKeepsMillisecondPrecision() {
        assertEquals(1_600_000_000_123L,
            SourceTimestamps(dateTaken = 1_500_000_000_345L, modifiedAt = 1_600_000_000_123L).outputModifiedAt)
    }

    @Test fun captureTimeIsOnlyUsedWhenModificationTimeIsUnknown() {
        assertEquals(1_500_000_000_345L, SourceTimestamps(dateTaken = 1_500_000_000_345L).outputModifiedAt)
        assertNull(SourceTimestamps().outputModifiedAt)
        assertNull(SourceTimestamps(modifiedAt = 1_600_000_000_123L).dateTaken)
    }
}
