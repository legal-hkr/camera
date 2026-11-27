package sh.hnet.camera.processing

import java.nio.ShortBuffer

/**
 * A RAW frame (Bayer mosaic, 16-bit samples row after row, no padding) with what its development needs. Filled from Camera2 by [FrameMeta.from]; the
 * same numbers a DNG of the frame would carry, so the computer prototype reads the frame's DNG and gets the same development.
 */
class RawFrame(val width: Int, val height: Int, val samples: ShortBuffer, val meta: FrameMeta)

/** A 2 × 2 position's color in [FrameMeta.cfa]. */
object Cfa {
    const val RED = 0
    const val GREEN = 1
    const val BLUE = 2
}

/**
 * The frame's metadata, in the DNG's terms.
 *
 * @property cfa the color of each 2 × 2 position, row by row ([Cfa])
 * @property black the black level of each 2 × 2 position, row by row
 * @property white the white level
 * @property neutral the as-shot neutral in camera space
 * @property colorMatrix XYZ to camera per calibration illuminant (row by row); the second may be missing
 * @property calibration the per-unit calibration per illuminant (identity when missing)
 * @property forwardMatrix white-balanced camera to XYZ D50 per illuminant, or none
 * @property illuminantK the two calibration illuminants' color temperatures
 * @property noise the sensor's noise for a 0..1 green signal x: variance = S·x + O
 * @property shading the lens shading map, or none
 * @property warp the lens distortion as a DNG WarpRectilinear, or none
 * @property crop the default crop (left, top, width, height) of the developed picture
 * @property orientation the EXIF orientation the picture is shown with
 */
class FrameMeta(
    val cfa: IntArray,
    val black: FloatArray,
    val white: Float,
    val neutral: DoubleArray,
    val colorMatrix: List<DoubleArray?>,
    val calibration: List<DoubleArray?>,
    val forwardMatrix: List<DoubleArray?>,
    val illuminantK: DoubleArray,
    val noiseS: Double,
    val noiseO: Double,
    val shading: ShadingMap?,
    val warp: Warp?,
    val crop: IntArray,
    val orientation: Int,
    val exposureNs: Long,
    val iso: Int,
) {
    companion object
}

/** A plausible frame's metadata (the Pixel 9 Pro main camera's calibration) for [Developer.warmUp]'s synthetic 1024 × 768 frames. */
internal fun FrameMeta.Companion.synthetic() = FrameMeta(
    cfa = intArrayOf(Cfa.GREEN, Cfa.BLUE, Cfa.RED, Cfa.GREEN),
    black = floatArrayOf(64f, 64f, 64f, 64f),
    white = 1023f,
    neutral = doubleArrayOf(0.59, 1.0, 0.56),
    colorMatrix = listOf(doubleArrayOf(1.119, -0.4931, -0.063, -0.3865, 1.4026, 0.0617, -0.0325, 0.2082, 0.7151), doubleArrayOf(0.7002, -0.0849, -0.1246, -0.5989, 1.571, -0.0016, -0.2134, 0.4452, 0.4157)),
    calibration = listOf(null, null),
    forwardMatrix = listOf(doubleArrayOf(0.6116, 0.1737, 0.179, 0.1363, 0.8365, 0.0272, -0.0417, -0.5848, 1.4516), doubleArrayOf(0.7144, -0.0009, 0.2507, 0.2755, 0.6223, 0.1022, 0.0513, -0.506, 1.2799)),
    illuminantK = doubleArrayOf(2856.0, 6504.0),
    noiseS = 2.4e-4,
    noiseO = 1.8e-6,
    shading = null,
    warp = null,
    crop = intArrayOf(8, 8, 1008, 752),
    orientation = 1,
    exposureNs = 33_000_000,
    iso = 100,
)

/**
 * The lens shading gains, one grid per 2 × 2 position ([gains] row by row, `rows × columns` each, in the order of [FrameMeta.cfa]'s positions), spanning
 * the frame as a DNG GainMap does: position (dy, dx)'s pixel at row y lies at grid row (y − dy) / (height − dy) · (rows − 1).
 */
class ShadingMap(val rows: Int, val columns: Int, val gains: List<FloatArray>)

/**
 * DNG WarpRectilinear: an output pixel reads the input at center + (p − center)·(k0 + k1 r² + k2 r⁴ + k3 r⁶) plus the tangential terms, r from the optical
 * center ([centerX], [centerY] as fractions of the frame) with the farthest corner at 1.
 */
class Warp(val radial: DoubleArray, val tangential: DoubleArray, val centerX: Double, val centerY: Double)
