package sh.hnet.camera.capabilities.model

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** A 3 × 3 matrix, row by row. */
class Matrix3(val values: DoubleArray) {
    init {
        require(values.size == 9)
    }

    operator fun get(row: Int, column: Int) = values[row * 3 + column]

    operator fun times(other: Matrix3) = Matrix3(DoubleArray(9) { i -> (0 until 3).sumOf { k -> this[i / 3, k] * other[k, i % 3] } })

    operator fun times(vector: DoubleArray) = DoubleArray(3) { r -> (0 until 3).sumOf { c -> this[r, c] * vector[c] } }

    fun blend(other: Matrix3, weight: Double) = Matrix3(DoubleArray(9) { values[it] * weight + other.values[it] * (1 - weight) })

    fun inverse(): Matrix3? {
        val a = values
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        if (abs(det) < 1e-12) return null
        return Matrix3(
            doubleArrayOf(
                (a[4] * a[8] - a[5] * a[7]) / det, (a[2] * a[7] - a[1] * a[8]) / det, (a[1] * a[5] - a[2] * a[4]) / det,
                (a[5] * a[6] - a[3] * a[8]) / det, (a[0] * a[8] - a[2] * a[6]) / det, (a[2] * a[3] - a[0] * a[5]) / det,
                (a[3] * a[7] - a[4] * a[6]) / det, (a[1] * a[6] - a[0] * a[7]) / det, (a[0] * a[4] - a[1] * a[3]) / det,
            ),
        )
    }

    override fun equals(other: Any?) = other is Matrix3 && values.contentEquals(other.values)

    override fun hashCode() = values.contentHashCode()

    companion object {
        val IDENTITY = Matrix3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun diagonal(a: Double, b: Double, c: Double) = Matrix3(doubleArrayOf(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c))
    }
}

/**
 * A camera's color calibration as DNG has it, for two illuminants: [colorMatrix1]/[colorMatrix2] from XYZ to the camera's reference RGB, [calibration1]/
 * [calibration2] from that to this unit's RGB, [forwardMatrix1]/[forwardMatrix2] from white-balanced camera RGB to XYZ (D50); [temperature1]/[temperature2]
 * the illuminants' color temperatures.
 */
data class ColorCalibration(
    val colorMatrix1: Matrix3,
    val colorMatrix2: Matrix3,
    val calibration1: Matrix3,
    val calibration2: Matrix3,
    val forwardMatrix1: Matrix3?,
    val forwardMatrix2: Matrix3?,
    val temperature1: Double,
    val temperature2: Double,
)

/** White balance set by hand for a white of [kelvin]: the gains of the sensor's channels (red, the two greens, blue) and the matrix from the balanced camera RGB to sRGB. */
data class ManualWhiteBalance(val kelvin: Double, val red: Double, val green: Double, val blue: Double, val transform: Matrix3)

/**
 * White balance in kelvins (LOGIKA §27): the camera's own matrices interpolated to the temperature as DNG does it, the camera's neutral for a white of that
 * temperature giving the gains, the forward matrix the color matrix. The Pixel has no balance in kelvins of its own (Android 15's CCT mode). Pure, so it is
 * tested without Android.
 */
object WhiteBalance {
    /** The ruler's range, and its presets (SPEC §11): incandescent, fluorescent, daylight, cloudy, shade. */
    const val MIN_K = 2000
    const val MAX_K = 10000
    private const val STEP_K = 100
    val PRESETS = listOf(2800, 4000, 5500, 6500, 7500)

    /** The ruler's steps: 100 K up to 6000 K, where a step is seen, then 500 K (the eye tells warm light's steps apart far better than cool light's). */
    val STEPS: List<Int> = (MIN_K..6000 step STEP_K).toList() + (6500..MAX_K step 500)

    /** XYZ (D50) to linear sRGB, Bradford-adapted to sRGB's D65 white. */
    private val XYZ_D50_TO_SRGB = Matrix3(doubleArrayOf(3.1338561, -1.6168667, -0.4906146, -0.9787684, 1.9161415, 0.0334540, 0.0719453, -0.2289914, 1.4052427))

    /** The gains and matrix for a white of [kelvin]; null when the calibration can't give them (a matrix that can't be inverted). */
    fun of(kelvin: Double, calibration: ColorCalibration): ManualWhiteBalance? {
        val weight = weight(kelvin, calibration)
        val calibrationMatrix = calibration.calibration1.blend(calibration.calibration2, weight)
        val neutral = neutral(kelvin, calibration, weight, calibrationMatrix) ?: return null
        // Green kept at one: the gains are the neutral's inverse
        val red = neutral[1] / neutral[0]
        val blue = neutral[1] / neutral[2]
        // DNG: XYZ = FM · diag(1 / reference neutral) · CC⁻¹ · camera, the reference neutral being CC⁻¹ · the neutral. The camera applies the gains first
        // (camera = diag(neutral) · balanced, up to green's scale), so its matrix is S · FM · diag(1 / (CC⁻¹ · neutral)) · CC⁻¹ · diag(neutral) – FM alone
        // when the calibration is diagonal, as on the Pixel
        val forward = calibration.forwardMatrix1?.let { first -> calibration.forwardMatrix2?.let { first.blend(it, weight) } } ?: return null
        val inverse = calibrationMatrix.inverse() ?: return null
        val reference = inverse * neutral
        if (reference.any { it <= 0 }) return null
        val transform = XYZ_D50_TO_SRGB * forward * Matrix3.diagonal(1 / reference[0], 1 / reference[1], 1 / reference[2]) * inverse * Matrix3.diagonal(neutral[0], neutral[1], neutral[2])
        return ManualWhiteBalance(kelvin, red, 1.0, blue, transform)
    }

    /**
     * The red gain's correction in an HDR (HLG) session (Pixel 9 Pro, diag 25 Nov 2026): with the camera's own balance off, its HDR processing leaves a
     * photo redder than the automatic balance under warm light and less red under cool light, even with the camera's own gains and matrix (SDR matches) –
     * as if it took the light for about 4250 K. Measured at 2600 K (10.6 % redder) and 9000 K (8 % less red), linear in mireds, blue unaffected; held
     * within a factor of 1.15 either way. A gain's change reached the photo about one to one in the same runs (SDR: +1.8 % red gain, +2 % red), so the
     * photo's shift is undone by the gain's. Measured on a Pixel: other phones' HDR processing is unknown, so only Google's take it.
     */
    fun hdrRedCorrection(kelvin: Double): Double =
        exp(HDR_RED_PER_MIRED * (1e6 / kelvin - HDR_NEUTRAL_MIRED)).coerceIn(1 / HDR_RED_LIMIT, HDR_RED_LIMIT)

    private const val HDR_RED_PER_MIRED = -0.000675
    private const val HDR_NEUTRAL_MIRED = 234.9
    private const val HDR_RED_LIMIT = 1.15

    /** The camera's RGB for a white of [kelvin] (the color matrices interpolated, the unit's calibration applied); null when it isn't positive. */
    private fun neutral(kelvin: Double, calibration: ColorCalibration, weight: Double, calibrationMatrix: Matrix3): DoubleArray? {
        val (x, y) = whitePoint(kelvin)
        val xyz = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        val neutral = calibrationMatrix * calibration.colorMatrix1.blend(calibration.colorMatrix2, weight) * xyz
        return neutral.takeIf { n -> n.all { it > 0 } }
    }

    /**
     * Reads the temperature a camera's own gains stand for (the automatic balance's, to show it): the ruler's step whose gains have the nearest red-to-blue
     * ratio, from a table worked out once for the calibration.
     */
    class TemperatureReader(calibration: ColorCalibration) {
        private val ratios = STEPS.map { k ->
            val weight = weight(k.toDouble(), calibration)
            neutral(k.toDouble(), calibration, weight, calibration.calibration1.blend(calibration.calibration2, weight))?.let { ln(it[2] / it[0]) }
        }

        fun read(red: Double, blue: Double): Int? {
            if (!(red > 0 && blue > 0) || red.isInfinite() || blue.isInfinite()) return null
            val wanted = ln(red / blue)
            return STEPS.indices.filter { ratios[it] != null }.minByOrNull { abs(ratios[it]!! - wanted) }?.let { STEPS[it] }
        }
    }

    /** How much of the first illuminant's matrices a temperature takes: by the inverse temperatures (DNG), held within the two. */
    private fun weight(kelvin: Double, calibration: ColorCalibration): Double {
        val t1 = calibration.temperature1
        val t2 = calibration.temperature2
        if (abs(t1 - t2) < 1) return 1.0
        return ((1 / kelvin - 1 / t2) / (1 / t1 - 1 / t2)).coerceIn(0.0, 1.0)
    }

    /** The chromaticity (x, y) of a black body at [kelvin] (Kim et al.'s cubic fit of the Planckian locus, 1667–25000 K). */
    fun whitePoint(kelvin: Double): Pair<Double, Double> {
        val t = kelvin.coerceIn(1667.0, 25000.0)
        val x = if (t <= 4000) {
            -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
        }
        val y = when {
            t <= 2222 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4000 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return x to y
    }

    /** The color temperature of an EXIF/DNG light source (Android's reference illuminants): the common ones, else null. */
    fun temperatureOfIlluminant(illuminant: Int): Double? = when (illuminant) {
        17, 3 -> 2856.0 // Standard A, tungsten
        4 -> 5500.0 // flash, as the DNG SDK takes it
        21 -> 6504.0 // D65
        20 -> 5503.0 // D55
        22 -> 7504.0 // D75
        23 -> 5003.0 // D50
        18 -> 4874.0 // Standard B
        19 -> 6774.0 // Standard C
        24 -> 3200.0 // ISO studio tungsten
        1, 9 -> 5500.0 // daylight, fine weather
        10 -> 6500.0 // cloudy
        11 -> 7500.0 // shade
        12 -> 6430.0 // daylight fluorescent
        13 -> 5000.0 // day white fluorescent
        14 -> 4230.0 // cool white fluorescent
        15 -> 3450.0 // white fluorescent
        2 -> 4150.0 // fluorescent
        else -> null
    }
}

/**
 * Manual focus's scale (LOGIKA §27): distances a photographer thinks in, as the lens' diopters (1 / meters), from the nearest the lens focuses at to infinity.
 * A lens whose distances aren't calibrated gets an even scale without units.
 */
object FocusScale {
    /** Distances in centimeters, then infinity: dense where the depth of field is thin. */
    private val DISTANCES_CM = listOf(
        2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 17.0, 20.0, 22.0, 25.0, 30.0, 35.0, 40.0, 45.0, 50.0, 60.0,
        70.0, 80.0, 90.0, 100.0, 120.0, 150.0, 200.0, 250.0, 300.0, 400.0, 500.0, 700.0, 1000.0, 2000.0,
    )

    /** The distances labeled under the ruler. */
    val LABELED_CM = setOf(2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0)

    /** The steps as diopters, near to far (the last one 0: infinity), from as near as [minimumDiopters] reaches. */
    fun steps(minimumDiopters: Double): List<Double> =
        DISTANCES_CM.map { 100.0 / it }.filter { it <= minimumDiopters + 1e-6 } + 0.0

    /** Uncalibrated distances: twenty even steps from the nearest to infinity. */
    fun evenSteps(minimumDiopters: Double): List<Double> = (20 downTo 0).map { minimumDiopters * it / 20 }

    /** The distance in centimeters for diopters; null for infinity. */
    fun centimeters(diopters: Double): Double? = if (diopters <= 1e-3) null else 100 / diopters
}
