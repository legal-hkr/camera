package sh.hnet.camera.processing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The processor's share of the development against the computer prototype's numbers (NumPy/OpenCV on the owner's Pixel 9 Pro DNG of 26 Nov 2026). */
class MathTest {
    private fun d(vararg v: Double) = v

    /** The Pixel 9 Pro main camera's calibration from the DNG DngCreator wrote. */
    private val meta = FrameMeta(
        cfa = intArrayOf(Cfa.GREEN, Cfa.BLUE, Cfa.RED, Cfa.GREEN),
        black = floatArrayOf(63.97f, 63.96f, 63.96f, 63.97f),
        white = 1023f,
        neutral = d(0.591796875, 1.0, 0.5634765625),
        colorMatrix = listOf(d(1.119, -0.4931, -0.063, -0.3865, 1.4026, 0.0617, -0.0325, 0.2082, 0.7151), d(0.7002, -0.0849, -0.1246, -0.5989, 1.571, -0.0016, -0.2134, 0.4452, 0.4157)),
        calibration = listOf(d(0.9957334118, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.983759978), d(0.9961923848, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.9877876938)),
        forwardMatrix = listOf(d(0.6116, 0.1737, 0.179, 0.1363, 0.8365, 0.0272, -0.0417, -0.5848, 1.4516), d(0.7144, -0.0009, 0.2507, 0.2755, 0.6223, 0.1022, 0.0513, -0.506, 1.2799)),
        illuminantK = d(2856.0, 6504.0),
        noiseS = 2.4e-4,
        noiseO = 1.8e-6,
        shading = null,
        warp = null,
        crop = intArrayOf(8, 8, 4064, 3056),
        orientation = 6,
        exposureNs = 33_500_000,
        iso = 326,
    )

    @Test
    fun cameraToXyzMatchesThePrototype() {
        val expected = d(0.6585102163, 0.09402564424, 0.2117185069, 0.1998204486, 0.7387551718, 0.06142437962, 0.0007382307313, -0.5488415851, 1.373248987)
        assertArrayEquals(expected, ColorMath.cameraToXyz(meta, d(0.591796875, 1.0, 0.5634765625)), 1e-6)
    }

    @Test
    fun cameraToSrgbWithTheLooksTintMatchesThePrototype() {
        val expected = d(1.829504895, -0.6628826053, -0.1151759095, -0.2577742254, 1.285983181, -0.04294421158, 0.00264339373, -0.9288978453, 1.921066946)
        assertArrayEquals(expected, ColorMath.cameraToSrgb(meta, d(0.591796875, 1.0, 0.5634765625), Look()), 1e-5)
    }

    @Test
    fun gaussianKernelsAreOpenCvs() {
        val sigma15 = floatArrayOf(8.922106864e-05f, 0.001028196578f, 0.007597402197f, 0.03599434807f, 0.109341175f, 0.2129675285f, 0.2659642572f, 0.2129675285f, 0.109341175f, 0.03599434807f, 0.007597402197f, 0.001028196578f, 8.922106864e-05f)
        assertArrayEquals(sigma15, Stats.gaussianKernel(1.5), 1e-7f)
        val sigma4 = Stats.gaussianKernel(4.0)
        assertEquals(33, sigma4.size)
        assertEquals(0.09973909953f, sigma4[16], 1e-7f)
        assertEquals(3.345874043e-05f, sigma4[0], 1e-9f)
    }

    @Test
    fun percentilesAreNumPys() {
        val values = floatArrayOf(0.3f, 0.1f, 0.9f, 0.5f, 0.2f, 0.7f, 0.05f)
        assertEquals(0.8964, Stats.percentile(values, 99.7), 1e-6)
        assertEquals(0.053, Stats.percentile(values, 1.0), 1e-6)
        assertEquals(0.3, Stats.percentile(values, 50.0), 1e-6)
    }

    @Test
    fun mirroredIndicesAreOpenCvsReflect101() {
        assertEquals(listOf(2, 1, 0, 1, 2, 3, 4, 3, 2), (-2..6).map { Stats.reflect(it, 5) })
    }

    @Test
    fun lensDistortionIsConvertedAsDngCreatorDoes() {
        // The Pixel 9 Pro main camera's coefficients (dumpsys); DngCreator's own code, run on them by the reviewer, gives the zoom 0.972 and these radial terms
        val warp = dngWarp(floatArrayOf(0.0923f, -0.1685f, 0.1247f, 0f, 0f), floatArrayOf(2844.5f, 2844.5f, 2044.9f, 1536.1f, 0f), 0, 0, 4080, 3072, 4080, 3072)
        assertArrayEquals(d(0.972, 0.06854, -0.09555, 0.05400), warp.radial, 2e-5)
        assertEquals(2044.9 / 4080, warp.centerX, 1e-6)
    }

    @Test
    fun noDistortionIsNotZoomed() {
        val d = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f)
        assertEquals(1.0, LensNormalization.normalize(d, 200f, 150f, 300f, 400, 300, 0, 0)!!, 1e-12)
    }
}
