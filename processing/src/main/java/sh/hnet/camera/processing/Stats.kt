package sh.hnet.camera.processing

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The numbers the development measures on the processor: from small copies of the picture (exposure, black and white points) and from the mosaic itself
 * (the scene's brightness and the light's color). Each follows the prototype's NumPy/OpenCV step exactly, so the two develop alike.
 */
internal object Stats {
    /** OpenCV's Gaussian kernel for a float image: 8σ + 1 taps (odd), normalized. */
    fun gaussianKernel(sigma: Double): FloatArray {
        val size = (sigma * 8 + 1).roundToInt() or 1
        val half = (size - 1) / 2.0
        val raw = DoubleArray(size) { exp(-(it - half) * (it - half) / (2 * sigma * sigma)) }
        val sum = raw.sum()
        return FloatArray(size) { (raw[it] / sum).toFloat() }
    }

    /** Mirrored index (OpenCV's BORDER_REFLECT_101: … 2 1 | 0 1 2 … n−1 | n−2 …). */
    fun reflect(i: Int, n: Int): Int {
        if (n == 1) return 0
        var j = i
        while (j < 0 || j >= n) j = if (j < 0) -j else 2 * (n - 1) - j
        return j
    }

    /** A Gaussian blur of a small single-channel picture (row by row). */
    fun blur(values: FloatArray, width: Int, height: Int, sigma: Double): FloatArray {
        val kernel = gaussianKernel(sigma)
        val r = kernel.size / 2
        val across = FloatArray(values.size)
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0f
            for (k in kernel.indices) sum += kernel[k] * values[y * width + reflect(x + k - r, width)]
            across[y * width + x] = sum
        }
        val out = FloatArray(values.size)
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0f
            for (k in kernel.indices) sum += kernel[k] * across[reflect(y + k - r, height) * width + x]
            out[y * width + x] = sum
        }
        return out
    }

    /** NumPy's percentile (linear between the two nearest ranks). */
    fun percentile(values: FloatArray, q: Double): Double {
        val sorted = values.copyOf().also { it.sort() }
        val at = q / 100 * (sorted.size - 1)
        val low = at.toInt().coerceIn(0, sorted.size - 1)
        val high = (low + 1).coerceAtMost(sorted.size - 1)
        return sorted[low] + (sorted[high] - sorted[low]) * (at - low)
    }

    /**
     * Two gains as HDR+ picks them: the highlights' one keeps the brightest part below clipping, the shadows' one brings the log-average to [key]; their
     * ratio (at least 1, at most [Look.maxRatio]) is how far the local tone mapping lifts the dark parts. [small]: the luminance at 1/8 size.
     */
    fun exposure(small: FloatArray, width: Int, height: Int, look: Look, key: Double): Pair<Double, Double> {
        val blurred = blur(small, width, height, 1.5)
        val bright = maxOf(percentile(blurred, look.whitePercentile.toDouble()), 1e-5)
        val short = look.white / bright
        // In single precision as NumPy takes the logs of a float32 picture (the darker part's threshold is a percentile of them)
        val logs = FloatArray(blurred.size) { ln(maxOf(blurred[it], 1e-5f)) }
        val logAverage = exp(logs.sumOf { it.toDouble() } / logs.size)
        var long = key / maxOf(logAverage, 1e-5)
        if (look.shadowKey > 0f) {
            // The darker part of the scene on its own (snow and white walls made the whole-scene average bright), lifted at most shadowRatio over the highlights
            val threshold = percentile(logs, look.shadowShare.toDouble())
            var sum = 0.0
            var count = 0
            for (v in logs) if (v.toDouble() <= threshold) {
                sum += v
                count++
            }
            val darkAverage = exp(sum / maxOf(count, 1))
            long = maxOf(long, minOf(look.shadowKey / maxOf(darkAverage, 1e-5), short * look.shadowRatio))
        }
        return short to (long / short).coerceIn(1.0, look.maxRatio.toDouble())
    }

    /**
     * The scene's brightness in stops: the normalized mosaic's green log-average ([cells]: every fourth cell of each position's plane, the four
     * positions per cell) over exposure time × ISO / 100, against 0.001. A dimmer scene keeps a darker key and a deeper black (a lamp-lit evening's mood).
     */
    fun sceneEv(cells: FloatArray, meta: FrameMeta): Double? {
        if (meta.exposureNs <= 0 || meta.iso <= 0) return null
        val greens = (0 until 4).filter { meta.cfa[it] == Cfa.GREEN }
        if (greens.size < 2) return null
        var sum = 0.0
        val count = cells.size / 4
        for (k in 0 until count) sum += ln(maxOf(0.5 * (cells[k * 4 + greens[0]] + cells[k * 4 + greens[1]]), 1e-5))
        val average = exp(sum / count)
        return log2(maxOf(average / (meta.exposureNs / 1e9 * meta.iso / 100), 1e-9) / 0.001)
    }

    /**
     * The light's color as the picture itself says it (shades of gray, p = 4: the p-norm mean of each color plane over [cells] – [cellsX] × [cellsY],
     * cell (i, j) holding the four positions at (8i + dx, 8j + dy) – lens shading applied, clipped and near-black cells left out), in camera space with
     * green at 1; null with too few cells.
     */
    fun sceneNeutral(cells: FloatArray, cellsX: Int, cellsY: Int, meta: FrameMeta, shading: (position: Int, x: Int, y: Int) -> Float): DoubleArray? {
        val sums = DoubleArray(3)
        var count = 0
        val counts = IntArray(3).also { c -> meta.cfa.forEach { c[it]++ } }
        for (cy in 0 until cellsY) for (cx in 0 until cellsX) {
            val rgb = DoubleArray(3)
            val k = cy * cellsX + cx
            for (position in 0 until 4) {
                val x = cx * 8 + position % 2
                val y = cy * 8 + position / 2
                rgb[meta.cfa[position]] += cells[k * 4 + position] * shading(position, x, y).toDouble() / counts[meta.cfa[position]]
            }
            if (rgb.max() >= 0.9 || rgb.min() <= 0.002) continue
            for (c in 0 until 3) sums[c] += rgb[c].pow(4)
            count++
        }
        if (count < 100) return null
        val v = DoubleArray(3) { (sums[it] / count).pow(0.25) }
        return DoubleArray(3) { v[it] / v[1] }
    }
}
