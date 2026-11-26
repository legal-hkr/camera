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
        val logAverage = exp(blurred.sumOf { ln(maxOf(it.toDouble(), 1e-5)) } / blurred.size)
        val long = key / maxOf(logAverage, 1e-5)
        return short to (long / short).coerceIn(1.0, look.maxRatio.toDouble())
    }

    /** A sample of the mosaic, black taken off and scaled to 0..1 (the frame's white level 1); [range] is white − the mean black. */
    private fun level(frame: RawFrame, x: Int, y: Int, range: Float): Float {
        val position = (y and 1) * 2 + (x and 1)
        val raw = frame.samples.get(y * frame.width + x).toInt() and 0xFFFF
        return (raw - frame.meta.black[position]) / range
    }

    private fun range(meta: FrameMeta) = meta.white - meta.black.average().toFloat()

    /**
     * The scene's brightness in stops: the raw green's log-average (every fourth 2 × 2 cell) over exposure time × ISO / 100, against 0.001. A dimmer scene
     * keeps a darker key and a deeper black (a lamp-lit evening's mood).
     */
    fun sceneEv(frame: RawFrame): Double? {
        val meta = frame.meta
        if (meta.exposureNs <= 0 || meta.iso <= 0) return null
        val greens = (0 until 4).filter { meta.cfa[it] == Cfa.GREEN }
        if (greens.size < 2) return null
        val range = range(meta)
        var sum = 0.0
        var count = 0
        for (cy in 0 until frame.height / 2 step 4) for (cx in 0 until frame.width / 2 step 4) {
            val g = 0.5 * greens.sumOf { level(frame, cx * 2 + it % 2, cy * 2 + it / 2, range).toDouble() }
            sum += ln(maxOf(g, 1e-5))
            count++
        }
        val average = exp(sum / count)
        return log2(maxOf(average / (meta.exposureNs / 1e9 * meta.iso / 100), 1e-9) / 0.001)
    }

    /**
     * The light's color as the picture itself says it (shades of gray, p = 4: the p-norm mean of each color plane over every fourth 2 × 2 cell, lens
     * shading applied, clipped and near-black cells left out), in camera space with green at 1; null with too few cells.
     */
    fun sceneNeutral(frame: RawFrame, shading: (position: Int, x: Int, y: Int) -> Float): DoubleArray? {
        val meta = frame.meta
        val sums = DoubleArray(3)
        var count = 0
        val counts = IntArray(3).also { c -> meta.cfa.forEach { c[it]++ } }
        val range = range(meta)
        for (cy in 0 until frame.height / 2 step 4) for (cx in 0 until frame.width / 2 step 4) {
            val rgb = DoubleArray(3)
            for (position in 0 until 4) {
                val x = cx * 2 + position % 2
                val y = cy * 2 + position / 2
                rgb[meta.cfa[position]] += level(frame, x, y, range) * shading(position, x, y).toDouble() / counts[meta.cfa[position]]
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
