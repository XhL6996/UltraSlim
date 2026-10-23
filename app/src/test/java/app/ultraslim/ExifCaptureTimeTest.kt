package app.ultraslim

import app.ultraslim.convert.ExifCaptureTime
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ExifCaptureTimeTest {
    @Test fun offsetAndSubsecondsPreserveTheInstant() {
        assertEquals(Instant.parse("2021-07-08T03:40:11.345Z").toEpochMilli(),
            ExifCaptureTime.parse("2021:07:08 09:10:11", "+05:30", "345678", ZoneId.of("America/New_York")))
        assertEquals(Instant.parse("2021-07-08T16:10:11.400Z").toEpochMilli(),
            ExifCaptureTime.parse("2021:07:08 09:10:11", "-07:00", "4"))
    }

    @Test fun missingOffsetUsesExplicitLocalZoneAndInvalidDatesStayUnknown() {
        assertEquals(Instant.parse("2021-07-08T01:10:11Z").toEpochMilli(),
            ExifCaptureTime.parse("2021:07:08 09:10:11", null, null, ZoneId.of("Asia/Shanghai")))
        assertNull(ExifCaptureTime.parse("2021:02:30 09:10:11", "+08:00", null))
        assertNull(ExifCaptureTime.parse("2021:07:08 09:10:11", "broken", null))
        assertNull(ExifCaptureTime.parse(null, null, null))
    }
}
