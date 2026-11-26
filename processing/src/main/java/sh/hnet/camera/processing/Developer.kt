package sh.hnet.camera.processing

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A developed picture: [rgba] (8 bits per channel, row by row, not yet turned by the frame's orientation), its Ultra HDR [gainMap], the numbers the
 * development measured and how long each step took. For a series also its [merge]: the reference frame, the share of the other frames used, each other
 * frame's tile shifts.
 */
class Developed(
    val width: Int,
    val height: Int,
    val rgba: ByteBuffer,
    val gainMap: GainMap,
    val stats: Map<String, Double>,
    val timesMs: Map<String, Double>,
    val merge: MergeReport?,
)

/**
 * An Ultra HDR gain map (ISO 21496-1 / Android's `Gainmap`): [values] one byte per pixel, [width] × [height] (a quarter of the picture), encoded as
 * log2 boost / [maxStops] – gain map min 0 stops, max [maxStops], gamma 1, offsets 1/2048 for SDR and HDR, HDR capacity 0 to [maxStops].
 */
class GainMap(val width: Int, val height: Int, val values: ByteArray, val maxStops: Float)

/**
 * How a series was merged: [reference] (index in the series), [used] (0..1), [shifts] per other frame (in the series' order, the reference left out):
 * each finest tile's (y, x) shift in half-size pixels, [tilesX] × [tilesY] tiles row by row; [sharpness] of the first frames.
 */
class MergeReport(val reference: Int, val used: Double, val shifts: List<IntArray>, val tilesX: Int, val tilesY: Int, val sharpness: List<Double>)

/**
 * Develops RAW frames on the GPU (OpenGL ES 3.1 compute) the way the computer prototype does (`develop.py`, the owner's accepted look). One GPU context
 * on a thread of its own; [develop] blocks the caller until the picture is done. Close it to free the context.
 */
class Developer : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "processing-gpu") }
    private val gpu: Gpu = try {
        executor.submit(Callable { Gpu() }).get()
    } catch (e: ExecutionException) {
        executor.shutdown()
        throw e.cause ?: e
    }

    /** The GPU's name, for reports. */
    val renderer: String get() = executor.submit(Callable { gpu.renderer }).get()

    /**
     * [frames] (one, or a series of the same size and exposure) developed with [look]: a series is merged first (the sharpest of its first three the
     * reference, whose metadata the development takes). [progress] hears each step's name and the GPU memory the development holds as it starts (on the
     * GPU's thread).
     */
    fun develop(frames: List<RawFrame>, look: Look = Look(), progress: (step: String, gpuBytes: Long) -> Unit = { _, _ -> }): Developed = try {
        require(frames.isNotEmpty() && frames.size <= 8) { "1 to 8 frames" }
        require(frames.all { it.width == frames[0].width && it.height == frames[0].height }) { "frames of different sizes" }
        executor.submit(Callable {
            try {
                Pass(gpu, frames, look, progress).run()
            } catch (e: Throwable) {
                // Whatever the failed step left on the GPU goes now, not with the context
                gpu.closeAll()
                throw e
            }
        }).get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    override fun close() {
        runCatching { executor.submit { gpu.close() }.get() }
        executor.shutdown()
    }
}

/** One development, step by step; every texture is closed as soon as the next step has what it needs. */
private class Pass(private val gpu: Gpu, private val series: List<RawFrame>, private val look: Look, private val progress: (String, Long) -> Unit) {
    private val times = LinkedHashMap<String, Double>()
    private val stats = LinkedHashMap<String, Double>()
    private val width = series[0].width
    private val height = series[0].height

    /** The frame whose metadata the development takes: the series' reference once it is known. */
    private var meta = series[0].meta
    private val frames = series.size

    private var peakBytes = 0L

    private inline fun <T> timed(name: String, block: () -> T): T {
        progress(name.removeSuffix("Ms"), gpu.liveBytes)
        val start = System.nanoTime()
        val result = block()
        gpu.finish()
        times[name] = (times[name] ?: 0.0) + (System.nanoTime() - start) / 1e6
        return result
    }

    fun run(): Developed {
        // The frames as raw 16-bit layers; a series merged into one normalized mosaic, a single frame only normalized
        val raws = gpu.textureArray(width, height, frames, GLES30.GL_R16UI)
        var report: MergeReport? = null
        val normalized = raws.use {
            timed("uploadMs") { series.forEachIndexed { layer, frame -> gpu.uploadLayer(it, layer, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, frame.samples, alignment = 2) } }
            if (frames == 1) {
                timed("normalizeMs") { normalize(it) }
            } else {
                val merged = timed("mergeMs") { Merge(gpu, series, ::mark).run(it) }
                meta = series[merged.reference].meta
                report = MergeReport(merged.reference, merged.used, merged.shifts, merged.tiles.first, merged.tiles.second, merged.sharpness)
                stats["reference"] = merged.reference.toDouble()
                stats["otherFramesUsed"] = merged.used
                it.close()
                timed("unplaneMs") { unplane(merged.planes) }
            }
        }
        val (cells, cellsX, cellsY) = timed("cellsMs") { cells(normalized) }
        val neutral = timed("neutralMs") { neutral(cells, cellsX, cellsY) }
        val balance = DoubleArray(3) { 1 / neutral[it] }.also { it[1] *= look.green.toDouble() }
        val matrix = ColorMath.cameraToSrgb(meta, neutral, look)
        val rgbFull = timed("demosaicMs") { normalized.use { demosaic(it, balance, matrix) } }
        val (cx, cy, cw, ch) = meta.crop.toList()
        val rgb = timed("warpMs") { rgbFull.use { warp(it, cx, cy, cw, ch) } }

        // The scene's brightness: a dimmer scene keeps a darker key and a deeper black
        val ev = Stats.sceneEv(cells, meta)
        val dim = if (ev == null) 0.0 else (look.keyEv - ev).coerceIn(0.0, 1.0)
        val key = look.key + (look.keyDark - look.key) * dim
        val blackPercentile = look.blackPercentile + (look.blackDark - look.blackPercentile) * dim
        ev?.let { stats["sceneEv"] = it }
        val (short, ratio) = timed("exposureMs") {
            val small = downMean(rgb, 8, luminance = true)
            val values = small.use { gpu.read(it, 1) }
            Stats.exposure(values, cw / 8, ch / 8, look, key)
        }
        stats["short"] = short
        stats["ratio"] = ratio

        val fused = timed("fusionMs") { fuse(rgb, short, ratio) }
        val curve = timed("curveMs") { fused.use { curve(it, blackPercentile) } }
        val (display, noise) = timed("toneMs") {
            curve.use { c ->
                val d = gpu.texture(cw, ch, GLES30.GL_RGBA16F)
                val n = gpu.texture(cw, ch, GLES30.GL_R32F)
                rgb.use {
                    gpu.run(Shaders.TONE_OUT, cw, ch, samplers = listOf(it, c), images = listOf(d, n)) {
                        float("gain", short.toFloat())
                        float("noiseS", meta.noiseS.toFloat())
                        float("noiseO", meta.noiseO.toFloat())
                        float("frames", frames.toFloat())
                        ints("size", cw, ch)
                    }
                }
                d to n
            }
        }
        val finished = timed("finishMs") { finish(display, noise) }
        val (bytes, gainMap) = timed("outputMs") {
            finished.use { f ->
                val out = gpu.texture(cw, ch, GLES30.GL_RGBA8)
                val rgba = out.use {
                    gpu.run(Shaders.TO_BYTES, cw, ch, samplers = listOf(f), images = listOf(it)) { ints("size", cw, ch) }
                    gpu.readRgba8(it)
                }
                rgba to gainMap(f)
            }
        }
        stats["glError"] = gpu.error().toDouble()
        stats["gpuPeakMB"] = peakBytes / 1e6
        return Developed(cw, ch, bytes, gainMap, stats, times, report)
    }

    /** The Ultra HDR gain map of the finished SDR picture (a quarter of its size, one byte per pixel). */
    private fun gainMap(finished: Texture): GainMap {
        val w = finished.width / 4
        val h = finished.height / 4
        val out = gpu.texture(w, h, GLES30.GL_RGBA8)
        return out.use {
            gpu.run(Shaders.GAIN_MAP, w, h, samplers = listOf(finished), images = listOf(it)) {
                ints("outSize", w, h)
                float("a", look.gainLinear)
                float("b", look.gainQuadratic)
                float("maxStops", look.gainMaxStops)
            }
            val rgba = gpu.readRgba8(it)
            GainMap(w, h, ByteArray(w * h) { i -> rgba.get(i * 4) }, look.gainMaxStops)
        }
    }

    /**
     * The neutral the picture is balanced to (green 1): the camera's, moved part of the way toward the picture's own where the two clearly disagree (a
     * lamp's warmth the camera keeps: from 4 % apart, fully from 8 %).
     */
    private fun neutral(cells: FloatArray, cellsX: Int, cellsY: Int): DoubleArray {
        val shot = DoubleArray(3) { meta.neutral[it] / meta.neutral[1] }
        if (look.neutralize <= 0f) return shot
        val own = Stats.sceneNeutral(cells, cellsX, cellsY, meta) { position, x, y -> shading(position, x, y) } ?: return shot
        val apart = (0 until 3).maxOf { abs(ln(own[it] / shot[it])) }
        val weight = look.neutralize * ((apart - 0.04) / 0.04).coerceIn(0.0, 1.0)
        stats["neutralWeight"] = weight
        return DoubleArray(3) { shot[it] * (own[it] / shot[it]).pow(weight) }
    }

    /** One frame's normalized mosaic (black off, 0..1). */
    private fun normalize(raws: Texture): Texture {
        val out = gpu.texture(width, height, GLES30.GL_R32F)
        gpu.run(Shaders.NORMALIZE, width, height, samplers = listOf(raws), images = listOf(out)) {
            int("layer", 0)
            vec("black", *meta.black)
            float("range", meta.white - meta.black.average().toFloat())
            ints("size", width, height)
        }
        return out
    }

    /** The merged planes back into one mosaic (the planes closed). */
    private fun unplane(planes: List<Texture>): Texture {
        val out = gpu.texture(width, height, GLES30.GL_R32F)
        try {
            gpu.run(Shaders.UNPLANE, width, height, samplers = planes, images = listOf(out)) {
                int("pad", Merge.PAD)
                ints("size", width, height)
            }
        } finally {
            planes.forEach { it.close() }
        }
        return out
    }

    /** Every fourth cell of each position's plane, read back for the scene's statistics: (values, cells across, cells down). */
    private fun cells(normalized: Texture): Triple<FloatArray, Int, Int> {
        val cellsX = (width / 2 + 3) / 4
        val cellsY = (height / 2 + 3) / 4
        val out = gpu.texture(cellsX, cellsY, GLES30.GL_RGBA32F)
        return out.use {
            gpu.run(Shaders.SUBSAMPLE, cellsX, cellsY, samplers = listOf(normalized), images = listOf(it)) { ints("outSize", cellsX, cellsY) }
            Triple(gpu.read(it, 4), cellsX, cellsY)
        }
    }

    /** The lens shading gain of a 2 × 2 position's pixel, as the GPU's PREPARE takes it. */
    private fun shading(position: Int, x: Int, y: Int): Float {
        val map = meta.shading ?: return 1f
        val dy = y and 1
        val dx = x and 1
        val gv = ((y - dy).toFloat() / (height - dy) * (map.rows - 1)).coerceIn(0f, (map.rows - 1).toFloat())
        val gu = ((x - dx).toFloat() / (width - dx) * (map.columns - 1)).coerceIn(0f, (map.columns - 1).toFloat())
        val v0 = gv.toInt()
        val u0 = gu.toInt()
        val v1 = minOf(v0 + 1, map.rows - 1)
        val u1 = minOf(u0 + 1, map.columns - 1)
        val g = map.gains[position]
        fun at(u: Int, v: Int) = g[v * map.columns + u]
        val a = at(u0, v0) + (at(u1, v0) - at(u0, v0)) * (gu - u0)
        val b = at(u0, v1) + (at(u1, v1) - at(u0, v1)) * (gu - u0)
        return a + (b - a) * (gv - v0)
    }

    private fun demosaic(normalized: Texture, balance: DoubleArray, matrix: DoubleArray): Texture {
        val mosaic = gpu.texture(width, height, GLES30.GL_R32F)
        val map = meta.shading
        val shading = if (map == null) gpu.texture(1, 1, GLES30.GL_RGBA32F) else gpu.texture(map.columns, map.rows, GLES30.GL_RGBA32F).also { t ->
            val data = ByteBuffer.allocateDirect(map.rows * map.columns * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until map.rows * map.columns) for (p in 0 until 4) data.put(map.gains[p][i])
            gpu.upload(t, GLES30.GL_RGBA, GLES30.GL_FLOAT, data)
        }
        shading.use {
            gpu.run(Shaders.PREPARE, width, height, samplers = listOf(normalized, it), images = listOf(mosaic)) {
                ints("cfa", *meta.cfa)
                vec("balance", balance[0].toFloat(), balance[1].toFloat(), balance[2].toFloat())
                int("hasShading", if (map == null) 0 else 1)
                ints("mapSize", map?.columns ?: 1, map?.rows ?: 1)
                ints("size", width, height)
            }
        }
        // Half floats: a relative step of 1/2048 at every level (down to 6e-8 absolute), far finer than the sensor's noise
        val rgb = gpu.texture(width, height, GLES30.GL_RGBA16F)
        mosaic.use {
            gpu.run(Shaders.DEMOSAIC, width, height, samplers = listOf(it), images = listOf(rgb)) {
                ints("cfa", *meta.cfa)
                mat3("matrix", FloatArray(9) { i -> matrix[i].toFloat() })
                ints("size", width, height)
            }
        }
        return rgb
    }

    private fun warp(full: Texture, x: Int, y: Int, w: Int, h: Int): Texture {
        val out = gpu.texture(w, h, GLES30.GL_RGBA16F)
        val warp = meta.warp
        val x0 = (warp?.centerX ?: 0.5) * (full.width - 1)
        val y0 = (warp?.centerY ?: 0.5) * (full.height - 1)
        val corner = listOf(0.0 to 0.0, full.width - 1.0 to 0.0, 0.0 to full.height - 1.0, full.width - 1.0 to full.height - 1.0).maxOf { (cx, cy) -> hypot(cx - x0, cy - y0) }
        gpu.run(Shaders.WARP, w, h, samplers = listOf(full), images = listOf(out)) {
            ints("size", full.width, full.height)
            ints("outSize", w, h)
            ints("origin", x, y)
            int("hasWarp", if (warp == null) 0 else 1)
            if (warp != null) {
                vec("radial", *FloatArray(4) { warp.radial[it].toFloat() })
                vec("tangential", warp.tangential[0].toFloat(), warp.tangential[1].toFloat())
                vec("center", warp.centerX.toFloat(), warp.centerY.toFloat())
                float("corner", corner.toFloat())
            }
        }
        warp?.let { stats["warpK0"] = it.radial[0] }
        return out
    }

    /** The mean of each [factor] × [factor] block: of the luminance of RGB, or of the first channel. */
    private fun downMean(src: Texture, factor: Int, luminance: Boolean): Texture {
        val out = gpu.texture(src.width / factor, src.height / factor, GLES30.GL_RGBA32F)
        gpu.run(Shaders.DOWN_MEAN, out.width, out.height, samplers = listOf(src), images = listOf(out)) {
            int("factor", factor)
            int("luminance", if (luminance) 1 else 0)
            ints("outSize", out.width, out.height)
        }
        return out
    }

    /**
     * Exposures of the luminance from the highlights' gain up to the shadows' fused through Laplacian pyramids of 7 levels (Mertens et al.), each part
     * taking the brightest exposure that still shows it; one exposure when the shadows need no lift. Returns the fused 0..1 picture (first channel).
     */
    private fun fuse(rgb: Texture, short: Double, ratio: Double): Texture {
        val w = rgb.width
        val h = rgb.height
        val count = if (ratio > 1.05) 3 else 1
        val images = gpu.texture(w, h, GLES30.GL_RGBA16F)
        val weights = gpu.texture(w, h, GLES30.GL_RGBA16F)
        gpu.run(Shaders.FUSE_INPUT, w, h, samplers = listOf(rgb), images = listOf(images, weights)) {
            float("gain", short.toFloat())
            vec("exposures", 1f, sqrt(ratio).toFloat(), ratio.toFloat())
            int("count", count)
            float("bias", look.fuseBias)
            float("high", look.fuseHigh)
            float("soft", look.fuseSoft)
            ints("size", w, h)
        }
        if (count == 1) {
            weights.close()
            return images.use { downMean(it, 1, luminance = false) }
        }
        val levels = 7
        val gi = mutableListOf(images)
        val gw = mutableListOf(weights)
        try {
            for (l in 1..levels) {
                gi += pyrDown(gi[l - 1])
                gw += pyrDown(gw[l - 1])
            }
            mark("fusion: blend")
            val blends = (0..levels).map { l ->
                val out = gpu.texture(gi[l].width, gi[l].height, GLES30.GL_R32F)
                val coarser = if (l < levels) gi[l + 1] else gi[l]
                gpu.run(Shaders.LAPLACE_BLEND, out.width, out.height, samplers = listOf(gi[l], coarser, gw[l]), images = listOf(out)) {
                    ints("size", out.width, out.height)
                    ints("coarserSize", coarser.width, coarser.height)
                    int("top", if (l == levels) 1 else 0)
                }
                out
            }
            gi.forEach { it.close() }
            gw.forEach { it.close() }
            gi.clear()
            gw.clear()
            mark("fusion: collapse")
            var out = blends[levels]
            for (l in levels - 1 downTo 0) {
                val next = gpu.texture(blends[l].width, blends[l].height, GLES30.GL_R32F)
                gpu.run(Shaders.COLLAPSE, next.width, next.height, samplers = listOf(blends[l], out), images = listOf(next)) {
                    ints("size", next.width, next.height)
                    ints("coarserSize", out.width, out.height)
                    int("finest", if (l == 0) 1 else 0)
                }
                out.close()
                blends[l].close()
                out = next
            }
            return out
        } finally {
            gi.forEach { it.close() }
            gw.forEach { it.close() }
        }
    }

    private fun pyrDown(src: Texture): Texture {
        val out = gpu.texture((src.width + 1) / 2, (src.height + 1) / 2, GLES30.GL_RGBA16F)
        gpu.run(Shaders.PYR_DOWN, out.width, out.height, samplers = listOf(src), images = listOf(out)) {
            ints("srcSize", src.width, src.height)
            ints("outSize", out.width, out.height)
        }
        return out
    }

    /** The global curve (black point from [blackPercentile], white stretch, S curve, mid-tones) and the local contrast in the bright parts. */
    private fun curve(fused: Texture, blackPercentile: Double): Texture {
        val w = fused.width
        val h = fused.height
        val small = downMean(fused, 8, luminance = false)
        val values = small.use { gpu.read(it, 1) }
        val black = Stats.percentile(values, blackPercentile)
        val top = Stats.percentile(values, look.stretchPercentile.toDouble())
        val scale = (look.stretchWhite / maxOf(top - black, 1e-3)).coerceIn(1.0, look.maxStretch.toDouble())
        stats["black"] = black
        stats["top"] = top
        stats["stretch"] = scale
        val curve = gpu.texture(w, h, GLES30.GL_R32F)
        val moments = gpu.texture(w, h, GLES30.GL_RGBA32F)
        gpu.run(Shaders.CURVE, w, h, samplers = listOf(fused), images = listOf(curve, moments)) {
            float("black", black.toFloat())
            float("scale", scale.toFloat())
            float("contrast", look.contrast)
            float("midtones", look.midtones)
            ints("size", w, h)
        }
        if (look.clarity == 0f) {
            moments.close()
            return curve
        }
        // Python's round: half to even (81.28 → 81 here)
        val radius = Math.rint(look.clarityRadius * maxOf(w, h) / 1000.0).toInt()
        val means = moments.use { box(it, radius, GLES30.GL_RGBA32F) }
        val coefficients = gpu.texture(w, h, GLES30.GL_RGBA16F)
        means.use {
            gpu.run(Shaders.GUIDED_SELF, w, h, samplers = listOf(it), images = listOf(coefficients)) {
                float("eps", look.clarityEdge)
                ints("size", w, h)
            }
        }
        val boxed = coefficients.use { box(it, radius, GLES30.GL_RGBA16F) }
        val out = gpu.texture(w, h, GLES30.GL_R32F)
        curve.use { c ->
            boxed.use { b ->
                gpu.run(Shaders.CLARITY, w, h, samplers = listOf(c, b), images = listOf(out)) {
                    float("clarity", look.clarity)
                    float("low", look.clarityLow)
                    ints("size", w, h)
                }
            }
        }
        return out
    }

    /**
     * A normalized box filter of radius [radius] (rows, then columns), four channels, stored as [format]: full floats for moments (a variance is a
     * difference of two of them), half floats for coefficients within 0..1.
     */
    private fun box(src: Texture, radius: Int, format: Int): Texture {
        val name = imageFormat(format)
        val across = gpu.texture(src.width, src.height, format)
        gpu.runLines(Shaders.boxRows(name), src.height, samplers = listOf(src), images = listOf(across)) {
            ints("size", src.width, src.height)
            int("radius", radius)
        }
        val out = gpu.texture(src.width, src.height, format)
        across.use {
            gpu.runLines(Shaders.boxColumns(name), src.width, samplers = listOf(it), images = listOf(out)) {
                ints("size", src.width, src.height)
                int("radius", radius)
            }
        }
        trackPeak()
        return out
    }

    /** A Gaussian blur (OpenCV's kernel for floats: 8σ + 1 taps), rows then columns, stored as [format] (single-channel by default). */
    private fun gauss(src: Texture, sigma: Double, format: Int = GLES30.GL_R32F): Texture {
        val kernel = Stats.gaussianKernel(sigma)
        check(kernel.size <= 33) { "blur too wide" }
        val program = Shaders.gauss(imageFormat(format))
        var current = src
        var owned = false
        for (axis in 0..1) {
            val out = gpu.texture(src.width, src.height, format)
            gpu.run(program, src.width, src.height, samplers = listOf(current), images = listOf(out)) {
                ints("size", src.width, src.height)
                int("axis", axis)
                int("taps", kernel.size)
                floats("kernel", kernel.copyOf(33))
            }
            trackPeak()
            if (owned) current.close()
            current = out
            owned = true
        }
        return current
    }

    private fun imageFormat(format: Int) = when (format) {
        GLES30.GL_R32F -> "r32f"
        GLES30.GL_RGBA16F -> "rgba16f"
        else -> "rgba32f"
    }

    private fun trackPeak() {
        peakBytes = maxOf(peakBytes, gpu.liveBytes)
    }

    /** A finer step inside a timed one, for the progress (where a failure stopped). */
    private fun mark(name: String) {
        trackPeak()
        progress(name, gpu.liveBytes)
    }

    /**
     * Chroma denoising, saturation, luma denoising, sharpening and the hue curve, as the prototype's `finish` and `hue_curve`: the color as each
     * channel's ratio to the luma, wide (guided by the luma) unless the local color differs from it by more than the noise. Takes [display] and
     * [noise] over and closes them as soon as they are read for the last time (the memory's peak is here).
     */
    private fun finish(display: Texture, noise: Texture): Texture {
        val w = display.width
        val h = display.height
        val hw = w / 2
        val hh = h / 2
        val inputA = gpu.texture(hw, hh, GLES30.GL_RGBA32F)
        val inputB = gpu.texture(hw, hh, GLES30.GL_RGBA32F)
        val ratios = gpu.texture(hw, hh, GLES30.GL_RGBA32F)
        gpu.run(Shaders.CHROMA_INPUT, hw, hh, samplers = listOf(display), images = listOf(inputA, inputB, ratios)) { ints("size", hw, hh) }
        mark("finish: color")
        val meanA = box(inputA, look.chromaRadius, GLES30.GL_RGBA32F)
        val meanB = inputB.use { box(it, look.chromaRadius, GLES30.GL_RGBA32F) }
        val coefficientsA = gpu.texture(hw, hh, GLES30.GL_RGBA32F)
        val coefficientsB = gpu.texture(hw, hh, GLES30.GL_RGBA32F)
        meanA.use { a ->
            meanB.use { b ->
                gpu.run(Shaders.CHROMA_COEFFICIENTS, hw, hh, samplers = listOf(a, b), images = listOf(coefficientsA, coefficientsB)) {
                    float("eps", look.chromaEdge * look.chromaEdge)
                    ints("size", hw, hh)
                }
            }
        }
        val boxedA = coefficientsA.use { box(it, look.chromaRadius, GLES30.GL_RGBA32F) }
        val boxedB = coefficientsB.use { box(it, look.chromaRadius, GLES30.GL_RGBA32F) }
        val wide = gpu.texture(hw, hh, GLES30.GL_RGBA32F)
        val local = gpu.texture(hw, hh, GLES30.GL_RGBA32F)
        val apart = gpu.texture(hw, hh, GLES30.GL_R32F)
        boxedA.use { a ->
            boxedB.use { b ->
                inputA.use { i ->
                    ratios.use { r ->
                        gpu.run(Shaders.CHROMA_MIX, hw, hh, samplers = listOf(a, b, i, r), images = listOf(wide, local, apart)) { ints("size", hw, hh) }
                    }
                }
            }
        }
        val apartBlurred = apart.use { gauss(it, 1.0) }
        val noise2 = gauss(noise, 2.0)
        val ratioHalf = gpu.texture(hw, hh, GLES30.GL_RGBA32F)
        wide.use { wd ->
            local.use { lc ->
                apartBlurred.use { ap ->
                    noise2.use { n2 ->
                        gpu.run(Shaders.CHROMA_KEEP, hw, hh, samplers = listOf(wd, lc, ap, n2), images = listOf(ratioHalf)) {
                            float("keep", look.chromaKeep)
                            ints("size", hw, hh)
                        }
                    }
                }
            }
        }

        mark("finish: luma")
        val noise4 = noise.use { gauss(it, 4.0) }
        val moments = gpu.texture(w, h, GLES30.GL_RGBA32F)
        display.use { gpu.run(Shaders.LUMA_INPUT, w, h, samplers = listOf(it), images = listOf(moments)) { ints("size", w, h) } }
        val momentMeans = box(moments, 2, GLES30.GL_RGBA32F)
        val coefficients = gpu.texture(w, h, GLES30.GL_RGBA16F)
        momentMeans.use { m ->
            gpu.run(Shaders.LUMA_COEFFICIENTS, w, h, samplers = listOf(m, noise4), images = listOf(coefficients)) {
                float("edge", look.lumaEdge)
                float("noisyAt", look.lumaNoise)
                ints("size", w, h)
            }
        }
        val boxed = coefficients.use { box(it, 2, GLES30.GL_RGBA16F) }
        val luma = gpu.texture(w, h, GLES30.GL_R32F)
        moments.use { m ->
            boxed.use { b ->
                gpu.run(Shaders.LUMA_DENOISE, w, h, samplers = listOf(m, b, noise4), images = listOf(luma)) {
                    float("noisyAt", look.lumaNoise)
                    float("strength", look.lumaDenoise)
                    ints("size", w, h)
                }
            }
        }
        mark("finish: sharpening and hues")
        val blurred = gauss(luma, look.sharpenRadius.toDouble())
        // Full floats before the 8 bits: half floats would move values near white across a step
        val out = gpu.texture(w, h, GLES30.GL_RGBA32F)
        val hues = look.hues ?: FloatArray(0)
        ratioHalf.use { r ->
            luma.use { l ->
                blurred.use { b ->
                    noise4.use { n4 ->
                        gpu.run(Shaders.FINISH, w, h, samplers = listOf(r, l, b, n4), images = listOf(out)) {
                            ints("size", w, h)
                            ints("halfSize", hw, hh)
                            float("saturation", look.saturation)
                            float("sharpen", look.sharpen)
                            int("hueCount", hues.size / 2)
                            floats("hueAt", FloatArray(16) { if (it < hues.size / 2) hues[it * 2] else 0f })
                            floats("hueTurn", FloatArray(16) { if (it < hues.size / 2) hues[it * 2 + 1] else 0f })
                        }
                    }
                }
            }
        }
        return out
    }
}
