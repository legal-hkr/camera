package sh.hnet.camera.processing

import kotlin.math.abs

/** 3 × 3 matrices as nine doubles row by row, and the DNG color math on them. */
internal object ColorMath {
    /** XYZ (D50) to linear sRGB (D65), Bradford-adapted. */
    val XYZ_D50_TO_SRGB = doubleArrayOf(3.1338561, -1.6168667, -0.4906146, -0.9787684, 1.9161415, 0.0334540, 0.0719453, -0.2289914, 1.4052427)
    val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    private val D50 = doubleArrayOf(0.9642, 1.0, 0.8251)

    fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i -> (0 until 3).sumOf { k -> a[i / 3 * 3 + k] * b[k * 3 + i % 3] } }

    fun apply(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { r -> m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2] }

    fun diagonal(v: DoubleArray) = doubleArrayOf(v[0], 0.0, 0.0, 0.0, v[1], 0.0, 0.0, 0.0, v[2])

    fun mix(w: Double, a: DoubleArray, b: DoubleArray) = DoubleArray(9) { w * a[it] + (1 - w) * b[it] }

    fun inverse(m: DoubleArray): DoubleArray {
        val (a, b, c) = Triple(m[0], m[1], m[2])
        val (d, e, f) = Triple(m[3], m[4], m[5])
        val (g, h, i) = Triple(m[6], m[7], m[8])
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    /** McCamy's color temperature of a chromaticity. */
    fun cct(x: Double, y: Double): Double {
        val n = (x - 0.3320) / (0.1858 - y)
        return 449 * n * n * n + 3525 * n * n + 6823.3 * n + 5520.33
    }

    /**
     * White-balanced camera RGB to XYZ D50, as the DNG spec interpolates the two calibrations by the as-shot neutral's color temperature (inverse
     * temperature, iterated to a fixed point).
     */
    fun cameraToXyz(meta: FrameMeta, neutral: DoubleArray): DoubleArray {
        val (t1, t2) = meta.illuminantK[0] to meta.illuminantK[1]
        val cm1 = meta.colorMatrix[0] ?: error("no color matrix")
        val cm2 = meta.colorMatrix.getOrNull(1)
        val cc1 = meta.calibration.getOrNull(0) ?: IDENTITY
        val cc2 = meta.calibration.getOrNull(1) ?: IDENTITY
        var weight = 0.5
        if (cm2 == null) {
            weight = 1.0
        } else {
            var steps = 0
            while (steps++ < 30) {
                val cm = mix(weight, cm1, cm2)
                val cc = mix(weight, cc1, cc2)
                val xyz = apply(inverse(multiply(cc, cm)), neutral)
                val sum = xyz.sum()
                val temperature = cct(xyz[0] / sum, xyz[1] / sum).coerceIn(minOf(t1, t2), maxOf(t1, t2))
                val next = (1 / temperature - 1 / t2) / (1 / t1 - 1 / t2)
                if (abs(next - weight) < 1e-6) break
                weight = next
            }
        }
        val cc = mix(weight, cc1, cc2)
        val fm1 = meta.forwardMatrix.getOrNull(0)
        if (fm1 != null) {
            val fm2 = meta.forwardMatrix.getOrNull(1)
            val fm = if (fm2 == null) fm1 else mix(weight, fm1, fm2)
            val reference = apply(inverse(cc), neutral)
            return multiply(multiply(multiply(fm, diagonal(DoubleArray(3) { 1 / reference[it] })), inverse(cc)), diagonal(neutral))
        }
        // Without forward matrices: the inverse of the color matrix, scaled so the neutral maps to D50's white
        val cm = mix(weight, cm1, cm2 ?: cm1)
        val m = inverse(multiply(cc, cm))
        val white = apply(m, neutral)
        return multiply(multiply(diagonal(DoubleArray(3) { D50[it] / white[it] }), m), diagonal(neutral))
    }

    /**
     * The matrix from the white-balanced mosaic's RGB to linear sRGB with the look's tint: camera→XYZ takes camera / neutral, the balanced mosaic is that
     * times the green neutral.
     */
    fun cameraToSrgb(meta: FrameMeta, neutral: DoubleArray, look: Look): DoubleArray {
        val toXyz = cameraToXyz(meta, neutral)
        var m = multiply(XYZ_D50_TO_SRGB, toXyz).map { it / neutral[1] }.toDoubleArray()
        look.color?.let { tint -> m = multiply(DoubleArray(9) { tint[it].toDouble() }, m) }
        return m
    }
}
