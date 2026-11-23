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
}
