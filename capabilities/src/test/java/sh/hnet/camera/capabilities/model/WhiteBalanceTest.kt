package sh.hnet.camera.capabilities.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteBalanceTest {
    // The Pixel 9 Pro's main camera, as its characteristics give them (diag report, 23 Nov 2026)
    private fun m(vararg v: Double) = Matrix3(v)
    private val pixel = ColorCalibration(
        colorMatrix1 = m(1.1190, -0.4931, -0.0630, -0.3865, 1.4026, 0.0617, -0.0325, 0.2082, 0.7151),
        colorMatrix2 = m(0.7002, -0.0849, -0.1246, -0.5989, 1.5710, -0.0016, -0.2134, 0.4452, 0.4157),
        calibration1 = m(6768.0 / 6797, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 3574.0 / 3633),
        calibration2 = m(4971.0 / 4990, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 6309.0 / 6387),
        forwardMatrix1 = m(0.6116, 0.1737, 0.1790, 0.1363, 0.8365, 0.0272, -0.0417, -0.5848, 1.4516),
        forwardMatrix2 = m(0.7144, -0.0009, 0.2507, 0.2755, 0.6223, 0.1022, 0.0513, -0.5060, 1.2799),
        temperature1 = 2856.0,
        temperature2 = 6504.0,
    )

    @Test fun whiteStaysWhite() {
        for (k in listOf(2800, 4000, 5500, 6500, 9000)) {
            val balance = WhiteBalance.of(k.toDouble(), pixel)
            assertNotNull(balance)
            // The balanced camera's gray is sRGB gray
            val out = balance!!.transform * doubleArrayOf(1.0, 1.0, 1.0)
            out.forEach { assertEquals("at $k K", 1.0, it, 0.03) }
        }
    }

    @Test fun warmerLightTakesLessRedAndMoreBlue() {
        val warm = WhiteBalance.of(2856.0, pixel)!!
        val day = WhiteBalance.of(6504.0, pixel)!!
        assertTrue(warm.red < day.red)
        assertTrue(warm.blue > day.blue)
        assertEquals(1.0, day.green, 0.0)
    }

    @Test fun temperatureOfItsOwnGains() {
        assertTrue(WhiteBalance.PRESETS.all { it in WhiteBalance.STEPS })
        for (k in listOf(3000, 5000, 7500)) {
            val balance = WhiteBalance.of(k.toDouble(), pixel)!!
            assertEquals(k, WhiteBalance.TemperatureReader(pixel).read(balance.red, balance.blue))
        }
    }

    @Test fun aCalibrationThatIsNotDiagonal() {
        // A unit whose calibration mixes the channels: white still comes out white
        val mixed = ColorCalibration(
            pixel.colorMatrix1, pixel.colorMatrix2,
            m(0.97, 0.02, 0.0, 0.01, 1.0, 0.02, 0.0, 0.03, 1.02), m(0.98, 0.01, 0.0, 0.02, 1.0, 0.01, 0.0, 0.02, 0.99),
            pixel.forwardMatrix1, pixel.forwardMatrix2, 2856.0, 6504.0,
        )
        val balance = WhiteBalance.of(4500.0, mixed)!!
        val (x, y) = WhiteBalance.whitePoint(4500.0)
        val weight = (1 / 4500.0 - 1 / 6504.0) / (1 / 2856.0 - 1 / 6504.0)
        val neutral = mixed.calibration1.blend(mixed.calibration2, weight) * mixed.colorMatrix1.blend(mixed.colorMatrix2, weight) * doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        // The camera's white after its gains, through the matrix
        val out = balance.transform * doubleArrayOf(neutral[0] * balance.red, neutral[1], neutral[2] * balance.blue)
        out.forEach { assertEquals(out[1], it, 0.03 * out[1]) }
    }

    @Test fun hdrRedCorrection() {
        // The measured shifts undone: 10.6 % redder at 2600 K, 8 % less red at 9000 K, none near 4250 K
        assertEquals(1 / 1.106, WhiteBalance.hdrRedCorrection(2600.0), 0.005)
        assertEquals(1 / 0.92, WhiteBalance.hdrRedCorrection(9000.0), 0.005)
        assertEquals(1.0, WhiteBalance.hdrRedCorrection(4257.0), 0.002)
        assertEquals(1 / 1.15, WhiteBalance.hdrRedCorrection(1000.0), 1e-9)
    }

    @Test fun planckianLocus() {
        // D65 lies near the locus at about 6500 K: x 0.3127, y 0.3290
        val (x, y) = WhiteBalance.whitePoint(6504.0)
        assertEquals(0.3135, x, 0.003)
        assertEquals(0.3237, y, 0.006)
        assertEquals(2856.0, WhiteBalance.temperatureOfIlluminant(17)!!, 0.0)
    }

    @Test fun focusScale() {
        // The main camera focuses from 10.5 cm (9.52 diopters): 10 cm is too near
        val steps = FocusScale.steps(9.523809)
        assertEquals(100.0 / 11, steps.first(), 1e-9)
        assertTrue(steps.zipWithNext().all { (a, b) -> a > b })
        assertEquals(0.0, steps.last(), 0.0)
        assertEquals(null, FocusScale.centimeters(0.0))
        assertEquals(50.0, FocusScale.centimeters(2.0)!!, 1e-9)
        assertEquals(21, FocusScale.evenSteps(5.0).size)
    }
}
