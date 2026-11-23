package sh.hnet.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseTimelineTest {
    @Test fun samplesAfterPausesMoveBack() {
        val t = PauseTimeline()
        assertEquals(100L, t.map(100))
        t.pause(1_000)
        assertTrue(t.paused)
        assertNull(t.map(1_500))
        t.resume(3_000)
        assertNull(t.map(2_999))
        assertEquals(1_000L, t.map(3_000))
        assertEquals(1_500L, t.map(3_500))
        t.pause(4_000)
        t.resume(5_000)
        assertEquals(2_500L, t.map(5_500))
        // A late sample from before the first pause keeps its time
        assertEquals(900L, t.map(900))
    }
}
