package sh.hnet.camera.media

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")

    @Test fun localTimeToTheMillisecond() {
        val at = ZonedDateTime.of(2026, 11, 23, 18, 30, 5, 42_000_000, warsaw).toInstant().toEpochMilli()
        assertEquals("IMG_20261123_183005042.jpg", FileNames.photo(at, warsaw))
        assertEquals("VID_20261123_183005042.mp4", FileNames.video(at, warsaw))
    }

    @Test fun burstAsGooglesCamera() {
        val at = ZonedDateTime.of(2026, 11, 25, 9, 0, 1, 5_000_000, warsaw).toInstant().toEpochMilli()
        assertEquals("IMG_20261125_090001005_BURST000_COVER.jpg", FileNames.burst(at, 0, warsaw))
        assertEquals("IMG_20261125_090001005_BURST012.jpg", FileNames.burst(at, 12, warsaw))
        assertEquals("IMG_20261125_090001005_BURST", FileNames.burstPrefix("IMG_20261125_090001005_BURST000_COVER.jpg"))
        assertEquals("IMG_20261125_090001005_BURST", FileNames.burstPrefix("IMG_20261125_090001005_BURST149.jpg"))
        assertEquals(null, FileNames.burstPrefix("IMG_20261125_090001005.jpg"))
    }
}
