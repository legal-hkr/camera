package sh.hnet.camera.processing

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.util.Rational
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** The calibration illuminants' color temperatures (EXIF LightSource codes, as Camera2's reference illuminants use them). */
private val ILLUMINANT_K = mapOf(
    1 to 5500.0, 2 to 4150.0, 3 to 2850.0, 4 to 5500.0, 9 to 5500.0, 10 to 6500.0, 11 to 7500.0, 12 to 6430.0, 13 to 5000.0, 14 to 4150.0, 15 to 3450.0,
    17 to 2856.0, 18 to 4874.0, 19 to 6774.0, 20 to 5503.0, 21 to 6504.0, 22 to 7504.0, 23 to 5003.0, 24 to 3200.0,
)

/** DngCreator's margin: the developed picture leaves 8 pixels on every side, where demosaicing has no full neighbourhood. */
private const val MARGIN = 8

/**
 * A RAW frame's metadata from the camera that took it ([characteristics] of the physical lens when the stream was a physical one) and its capture
 * result: the numbers DngCreator writes into the frame's DNG, so the computer prototype reading that DNG develops it the same way. Null for a sensor
 * without a Bayer pattern.
 */
fun FrameMeta.Companion.from(characteristics: CameraCharacteristics, result: CaptureResult, width: Int, height: Int, orientation: Int): FrameMeta? {
    val cfa = when (characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> intArrayOf(Cfa.RED, Cfa.GREEN, Cfa.GREEN, Cfa.BLUE)
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> intArrayOf(Cfa.GREEN, Cfa.RED, Cfa.BLUE, Cfa.GREEN)
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> intArrayOf(Cfa.GREEN, Cfa.BLUE, Cfa.RED, Cfa.GREEN)
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> intArrayOf(Cfa.BLUE, Cfa.GREEN, Cfa.GREEN, Cfa.RED)
        else -> return null
    }
    val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.takeIf { it.size == 4 }
        ?: characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p -> FloatArray(4) { p.getOffsetForIndex(it % 2, it / 2).toFloat() } }
        ?: return null
    // The static white level, as DngCreator writes it (the dynamic one the Pixel also reports isn't in the DNG)
    val white = (characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: return null).toFloat()
    val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.map(Rational::toDouble)?.toDoubleArray() ?: return null
    val colorMatrix = listOf(matrix(characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)), matrix(characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)))
    if (colorMatrix[0] == null) return null
    val calibration = listOf(matrix(characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)), matrix(characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)))
    val forward = listOf(matrix(characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)), matrix(characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)))
    val illuminants = doubleArrayOf(
        // Unknown codes as the prototype reads a DNG without the tags: D65 first, A second
        ILLUMINANT_K[characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)] ?: 6504.0,
        ILLUMINANT_K[characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()] ?: 2856.0,
    )
    // The frame as the pre-correction array (as RAW_SENSOR gives it on the phones we know): DngCreator's crop, GainMaps and warp are relative to it; a
    // frame of the whole pixel array (optical black included) would need their offsets, not handled
    val array = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) ?: return null
    if (array.width() != width || array.height() != height) return null
    // The noise of each color channel: they differ by about a percent, their mean stands for all
    val profile = result.get(CaptureResult.SENSOR_NOISE_PROFILE).orEmpty()
    val noiseS = if (profile.isEmpty()) 0.0 else profile.sumOf { it.first } / profile.size
    val noiseO = if (profile.isEmpty()) 0.0 else profile.sumOf { it.second } / profile.size
    return FrameMeta(
        cfa = cfa,
        black = black,
        white = white,
        neutral = neutral,
        colorMatrix = colorMatrix,
        calibration = calibration,
        forwardMatrix = forward,
        illuminantK = illuminants,
        noiseS = noiseS,
        noiseO = noiseO,
        shading = shading(result, cfa),
        warp = warp(characteristics, result, width, height),
        crop = intArrayOf(MARGIN, MARGIN, width - 2 * MARGIN, height - 2 * MARGIN),
        orientation = orientation,
        exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
        iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
    )
}

private fun matrix(transform: ColorSpaceTransform?): DoubleArray? = transform?.let { t -> DoubleArray(9) { t.getElement(it % 3, it / 3).toDouble() } }

/**
 * The lens shading map per 2 × 2 position, as DngCreator writes its GainMaps: Camera2's channels are red, the green on even rows, the green on odd rows
 * and blue.
 */
private fun shading(result: CaptureResult, cfa: IntArray): ShadingMap? {
    val map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) ?: return null
    val gains = List(4) { position ->
        val channel = when (cfa[position]) {
            Cfa.RED -> 0
            Cfa.BLUE -> 3
            else -> if (position / 2 == 0) 1 else 2
        }
        FloatArray(map.rowCount * map.columnCount) { map.getGainFactor(channel, it % map.columnCount, it / map.columnCount) }
    }
    return ShadingMap(map.rowCount, map.columnCount, gains)
}

/**
 * Camera2's lens distortion as the DNG WarpRectilinear DngCreator writes (AOSP `android_hardware_camera2_DngCreator.cpp`, opcode list 3, and
 * `normalizeLensDistortion`/`findPostCorrectionScale`, ported literally – float coefficients, the 0.002 steps, the box cut to whole pixels): the
 * coefficients from the capture result only (five each), zoomed in by the largest scale whose box, mapped through the distortion, stays inside the
 * pre-correction array (each coefficient by its own power of it), then converted from Camera2's space (focal length 1) to DNG's (farthest corner 1).
 */
private fun warp(characteristics: CameraCharacteristics, result: CaptureResult, width: Int, height: Int): Warp? {
    val intrinsics = result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)?.takeIf { it.size == 5 } ?: return null
    val lens = result.get(CaptureResult.LENS_DISTORTION)?.takeIf { it.size == 5 } ?: return null
    val array = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) ?: return null
    return dngWarp(lens, intrinsics, array.left, array.top, array.width(), array.height(), width, height)
}

/** The conversion itself, on Camera2's five distortion coefficients and five intrinsics, the pre-correction array and the frame's size. */
internal fun dngWarp(lens: FloatArray, intrinsics: FloatArray, preLeft: Int, preTop: Int, preWidth: Int, preHeight: Int, width: Int, height: Int): Warp {
    val cx = intrinsics[2]
    val cy = intrinsics[3]
    val f = intrinsics[0]
    val distortion = floatArrayOf(1f, lens[0], lens[1], lens[2], lens[3], lens[4])
    if (preWidth == width && preHeight == height) {
        LensNormalization.normalize(distortion, cx, cy, f, preWidth, preHeight, 0, 0)
    } else {
        LensNormalization.normalize(distortion, cx + preLeft, cy + preTop, f, preWidth, preHeight, preLeft, preTop)
    }
    val mx = max(preWidth - cx, cx)
    val my = max(preHeight - cy, cy)
    val m2 = mx * mx + my * my
    val m = sqrt(m2)
    val f2 = f * f
    val conversion = doubleArrayOf(m2.toDouble() / f2, (m2.toDouble() / f2).pow(2), (m2.toDouble() / f2).pow(3), m.toDouble() / f, m.toDouble() / f)
    for (i in 0 until 5) distortion[i + 1] = (distortion[i + 1] * conversion[i]).toFloat()
    return Warp(
        radial = DoubleArray(4) { distortion[it].toDouble() },
        tangential = doubleArrayOf(distortion[4].toDouble(), distortion[5].toDouble()),
        centerX = (cx.toDouble() / preWidth).coerceIn(0.0, 1.0),
        centerY = (cy.toDouble() / preHeight).coerceIn(0.0, 1.0),
    )
}

/** DngCreator's `normalizeLensDistortion` and the functions it calls, on Camera2's coefficients `[1, k1, k2, k3, p1, p2]`. */
internal object LensNormalization {
    private fun undistort(x: Double, y: Double, d: FloatArray, cx: Float, cy: Float, f: Float): Pair<Double, Double> {
        val xp = (x - cx) / f
        val yp = (y - cy) / f
        val x2 = xp * xp
        val y2 = yp * yp
        val r2 = x2 + y2
        val xy2 = 2.0 * xp * yp
        val kr = d[0] + ((d[3] * r2 + d[2]) * r2 + d[1]) * r2
        val xpp = xp * kr + d[4] * xy2 + d[5] * (r2 + 2.0 * x2)
        val ypp = yp * kr + d[4] * (r2 + 2.0 * y2) + d[5] * xy2
        return (xpp * f + cx) to (ypp * f + cy)
    }

    private fun within(x: Int, y: Int, d: FloatArray, cx: Float, cy: Float, f: Float, w: Int, h: Int, xMin: Int, yMin: Int): Boolean {
        val (ux, uy) = undistort(x.toDouble(), y.toDouble(), d, cx, cy, f)
        // xMin and yMin inclusive, xMax and yMax exclusive
        return !(ux < xMin || uy < yMin || ux >= xMin + w || uy >= yMin + h)
    }

    /** The scaled box's corners and edge middles (C++ passes them as int: cut toward zero) all map inside the array. */
    private fun scaledBoxWithin(scale: Double, d: FloatArray, cx: Float, cy: Float, f: Float, w: Int, h: Int, xMin: Int, yMin: Int): Boolean {
        val left = (cx * (1.0 - scale)).toInt()
        val right = ((w - 1) * scale + cx * (1.0 - scale)).toInt()
        val top = (cy * (1.0 - scale)).toInt()
        val bottom = ((h - 1) * scale + cy * (1.0 - scale)).toInt()
        val mx = cx.toInt()
        val my = cy.toInt()
        return listOf(left to top, mx to top, right to top, left to my, right to my, left to bottom, mx to bottom, right to bottom)
            .all { (x, y) -> within(x, y, d, cx, cy, f, w, h, xMin, yMin) }
    }

    /** Scales [d] in place for the found zoom (none found above 0.5: unchanged); returns the scale. */
    fun normalize(d: FloatArray, cx: Float, cy: Float, f: Float, w: Int, h: Int, xMin: Int, yMin: Int): Double? {
        var scale = 1.0
        while (scale > 0.5) {
            if (scaledBoxWithin(scale, d, cx, cy, f, w, h, xMin, yMin)) {
                val powers = intArrayOf(1, 3, 5, 7, 2, 2)
                for (i in 0 until 6) d[i] = (d[i] * scale.pow(powers[i])).toFloat()
                return scale
            }
            scale -= 0.002
        }
        return null
    }
}
