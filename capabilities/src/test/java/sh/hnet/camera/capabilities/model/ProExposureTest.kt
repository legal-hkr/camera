package sh.hnet.camera.capabilities.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProExposureTest {
    @Test fun scalesWithinTheCamera() {
        val iso = ProExposure.isoSteps(22..11277)
        assertEquals(25, iso.first())
        assertEquals(10000, iso.last())
        assertTrue(400 in iso && 3200 in iso)
        val shutter = ProExposure.shutterSteps(26_345L..16_000_001_084L)
        assertEquals(125_000L, shutter.first())
        assertEquals(16_000_000_000L, shutter.last())
        // The front camera's longest is a second
        assertEquals(1_000_000_000L, ProExposure.shutterSteps(68_360L..1_000_000_628L).last())
    }

    @Test fun nearestStep() {
        val iso = ProExposure.isoSteps(25..12800)
        assertEquals(400, ProExposure.nearest(iso, 430.0))
        assertEquals(100, ProExposure.nearest(iso, 104.0))
    }

    @Test fun previewOfALongTimeKeepsTheBrightness() {
        val photo = Exposure(100, 4_000_000_000L)
        val preview = ProExposure.preview(photo, 11277)
        assertEquals(ProExposure.PREVIEW_MAX_NS, preview.timeNs)
        assertEquals(photo.product, preview.product, photo.product * 0.01)
        // A gain the sensor hasn't: the preview is darker but moves
        val dim = ProExposure.preview(Exposure(3200, 16_000_000_000L), 11277)
        assertEquals(11277, dim.iso)
        // A short time is the preview's as it is
        val short = photo.copy(timeNs = 10_000_000L)
        assertEquals(short, ProExposure.preview(short, 11277))
    }

    @Test fun hlgCurveIsContinuous() {
        val below = ProExposure.linear(0.4999, hlg = true)
        val above = ProExposure.linear(0.5001, hlg = true)
        assertEquals(below, above, 0.001)
        assertEquals(1.0, ProExposure.linear(1.0, hlg = true), 0.01)
    }

    @Test fun isoPriorityFindsTheTime() {
        val metering = Metering(0.18)
        val preview = Exposure(100, 10_000_000L)
        // The picture at twice the target: the photo wants half the product; ISO 200 set by hand
        var photo: Exposure? = null
        repeat(20) { photo = metering.step(0.36, preview, 0, iso = 200, timeNs = null, isoRange = 25..10000, timeRange = 100_000L..16_000_000_000L) }
        assertNotNull(photo)
        // 100 × 0.01 s / 2 = 0.5 → at ISO 200: 2.5 ms
        assertEquals(2_500_000.0, photo!!.timeNs.toDouble(), 50_000.0)
    }

    @Test fun shutterPriorityFindsTheIsoAndEvBrightens() {
        val metering = Metering(0.18)
        val preview = Exposure(100, 10_000_000L)
        var photo: Exposure? = null
        repeat(30) { photo = metering.step(0.18, preview, 3, iso = null, timeNs = 5_000_000L, isoRange = 25..10000, timeRange = 100_000L..16_000_000_000L) }
        // +1 EV: twice 100 × 0.01 = 2 → at 5 ms: ISO 400
        assertEquals(400.0, photo!!.iso.toDouble(), 8.0)
    }

    @Test fun automaticTimeStaysShort() {
        val metering = Metering(0.18)
        var photo: Exposure? = null
        repeat(30) { photo = metering.step(0.001, Exposure(100, 10_000_000L), 0, iso = 100, timeNs = null, isoRange = 25..10000, timeRange = 100_000L..16_000_000_000L) }
        assertEquals(ProExposure.AUTO_SHUTTER_MAX_NS, photo!!.timeNs)
    }

    @Test fun meterSaysHowFarFromTheMetering() {
        val metering = Metering(0.18)
        val preview = Exposure(100, 10_000_000L)
        // The metering wants 100 × 0.01; the photo set to half of it
        assertEquals(-1.0, metering.meter(0.18, preview, Exposure(100, 5_000_000L))!!, 0.01)
    }

    @Test fun learnsTheCamerasExposure() {
        val metering = Metering(0.18)
        repeat(100) { metering.learn(0.30) }
        val preview = Exposure(100, 10_000_000L)
        assertEquals(0.0, metering.meter(0.30, preview, preview)!!, 0.01)
    }
}
