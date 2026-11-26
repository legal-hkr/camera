package sh.hnet.camera.processing

import android.opengl.GLES30

/**
 * A series merged into one mosaic as the computer prototype does it (`pipeline.py`, `align.py`, `merge.py`; HDR+, Hasinoff et al. 2016): the sharpest of
 * the first three frames is the reference, every other frame is aligned to it tile by tile from the coarsest pyramid level to the finest, then each
 * 2 × 2 position's plane is merged in the frequency domain. The result is the merged planes (padded, one per position).
 */
internal class Merge(private val gpu: Gpu, private val frames: List<RawFrame>, private val mark: (String) -> Unit) {
    /**
     * The merged series: [parities] (four padded images, one per tile parity, the four positions' planes in their channels – [Shaders.UNPLANE] adds
     * them), the reference's index, how much of the other frames the tiles used, each other frame's shifts.
     */
    class Result(val parities: List<Texture>, val reference: Int, val used: Double, val shifts: List<IntArray>, val tiles: Pair<Int, Int>, val sharpness: List<Double>)

    private class Level(val factor: Int, val tile: Int, val stride: Int, val radius: Int, val l2: Boolean)

    private val meta = frames.first().meta
    private val width = frames.first().width
    private val height = frames.first().height
    private val planeWidth = width / 2
    private val planeHeight = height / 2
    private val paddedWidth = planeWidth + 2 * PAD + Math.floorMod(-(planeWidth + 2 * PAD), STRIDE)
    private val paddedHeight = planeHeight + 2 * PAD + Math.floorMod(-(planeHeight + 2 * PAD), STRIDE)
    /** Each frame's own black level and range (the prototype normalizes each DNG by its own tags). */
    private fun range(layer: Int) = frames[layer].meta.let { it.white - it.black.average().toFloat() }

    fun run(raws: Texture): Result {
        mark("merge: pyramids")
        val pyramids = frames.indices.map { pyramid(raws, it) }
        try {
            mark("merge: reference")
            val sharpness = (0 until minOf(3, frames.size)).map { sharpness(pyramids[it][0]) }
            val reference = sharpness.indices.maxBy { sharpness[it] }
            val others = frames.indices.filter { it != reference }
            mark("merge: alignment")
            val (tilesX, tilesY) = grid(paddedWidth, paddedHeight, TILE, STRIDE)
            // Every other frame's finest shifts written straight into one texture (frame a's rows from a × tilesY), which the merge reads
            val shifts = gpu.texture(tilesX, maxOf(tilesY * others.size, 1), GLES30.GL_RGBA32F)
            try {
                others.forEachIndexed { a, other -> align(pyramids[reference], pyramids[other], shifts, a * tilesY) }
                pyramids.flatten().forEach { it.close() }
                mark("merge: shifts read")
                val values = gpu.read(shifts, 2)
                val per = tilesX * tilesY * 2
                val read = others.indices.map { a -> IntArray(per) { values[a * per + it].toInt() } }
                mark("merge: frequency domain")
                val (parities, used) = merge(raws, shifts, reference, others, tilesX, tilesY)
                return Result(parities, reference, used, read, tilesX to tilesY, sharpness)
            } finally {
                shifts.close()
            }
        } finally {
            pyramids.flatten().forEach { it.close() }
        }
    }

    /** The frame's padded half-size gray and three coarser levels (Gaussian blur of σ = factor / 2, then the mean of factor × factor blocks). */
    private fun pyramid(raws: Texture, layer: Int): List<Texture> {
        mark("merge: gray")
        val gray = gpu.texture(paddedWidth, paddedHeight, GLES30.GL_R32F)
        gpu.run(Shaders.GRAY_PAD, paddedWidth, paddedHeight, samplers = listOf(raws), images = listOf(gray)) {
            int("layer", layer)
            vec("black", *frames[layer].meta.black)
            float("range", range(layer))
            int("pad", PAD)
            ints("planeSize", planeWidth, planeHeight)
            ints("outSize", paddedWidth, paddedHeight)
        }
        val levels = mutableListOf(gray)
        mark("merge: pyramid levels")
        for (level in LEVELS.drop(1)) {
            val below = levels.last()
            val blurred = blur(below, 0.5 * level.factor)
            val out = gpu.texture(below.width / level.factor, below.height / level.factor, GLES30.GL_R32F)
            blurred.use {
                gpu.run(Shaders.DOWN_MEAN_SINGLE, out.width, out.height, samplers = listOf(it), images = listOf(out)) {
                    int("factor", level.factor)
                    int("luminance", 0)
                    ints("outSize", out.width, out.height)
                }
            }
            levels += out
        }
        return levels
    }

    private fun blur(src: Texture, sigma: Double): Texture {
        val kernel = Stats.gaussianKernel(sigma)
        val across = gpu.texture(src.width, src.height, GLES30.GL_R32F)
        gpu.runGroups(Shaders.gaussRows("r32f"), (src.width + 63) / 64, (src.height + 3) / 4, samplers = listOf(src), images = listOf(across)) {
            ints("size", src.width, src.height)
            int("taps", kernel.size)
            floats("kernel", kernel.copyOf(33))
        }
        val out = gpu.texture(src.width, src.height, GLES30.GL_R32F)
        across.use {
            gpu.runGroups(Shaders.gaussColumns("r32f"), (src.width + 3) / 4, (src.height + 63) / 64, samplers = listOf(it), images = listOf(out)) {
                ints("size", src.width, src.height)
                int("taps", kernel.size)
                floats("kernel", kernel.copyOf(33))
            }
        }
        return out
    }

    /** The mean squared Sobel gradient of the frame's (unpadded) gray: HDR+ takes the sharpest of the first frames as the reference. */
    private fun sharpness(gray: Texture): Double {
        val energy = gpu.texture(planeWidth, planeHeight, GLES30.GL_R32F)
        val rows = gpu.texture(1, planeHeight, GLES30.GL_R32F)
        try {
            gpu.run(Shaders.SOBEL, planeWidth, planeHeight, samplers = listOf(gray), images = listOf(energy)) {
                int("pad", PAD)
                ints("size", planeWidth, planeHeight)
            }
            gpu.runLines(Shaders.ROW_SUM, planeHeight, samplers = listOf(energy), images = listOf(rows)) { ints("size", planeWidth, planeHeight) }
            return gpu.read(rows, 1).sumOf { it.toDouble() } / (planeWidth.toDouble() * planeHeight)
        } finally {
            energy.close()
            rows.close()
        }
    }

    /** The alternate's shift for every finest tile, coarse to fine, written into [shifts] from row [row] on (shift y, shift x). */
    private fun align(reference: List<Texture>, alternate: List<Texture>, shifts: Texture, row: Int) {
        var coarse: Texture? = null
        var coarseGrid = 0 to 0
        for (index in LEVELS.indices.reversed()) {
            mark("merge: align level $index")
            val level = LEVELS[index]
            val factorUp = if (index + 1 < LEVELS.size) LEVELS[index + 1].factor else 1
            val r = reference[index]
            val (tilesX, tilesY) = grid(r.width, r.height, level.tile, level.stride)
            val out = if (index == 0) shifts else gpu.texture(tilesX, tilesY, GLES30.GL_RGBA32F)
            val prior = coarse ?: gpu.texture(1, 1, GLES30.GL_RGBA32F)
            try {
                gpu.runGroups(Shaders.ALIGN, tilesX, tilesY, samplers = listOf(r, alternate[index], prior), images = listOf(out)) {
                    ints("levelSize", r.width, r.height)
                    ints("tiles", tilesX, tilesY)
                    int("tile", level.tile)
                    int("stride", level.stride)
                    int("radius", level.radius)
                    int("l2", if (level.l2) 1 else 0)
                    int("subpixel", if (level.l2 && index > 0) 1 else 0)
                    int("hasPrior", if (coarse == null) 0 else 1)
                    ints("coarseTiles", coarseGrid.first, coarseGrid.second)
                    int("coarseTile", if (index + 1 < LEVELS.size) LEVELS[index + 1].tile else 1)
                    int("coarseStride", if (index + 1 < LEVELS.size) LEVELS[index + 1].stride else 1)
                    int("factorUp", factorUp)
                    int("outRow", if (index == 0) row else 0)
                }
            } finally {
                prior.close()
            }
            coarse = out.takeIf { index > 0 }
            coarseGrid = tilesX to tilesY
        }
    }

    private fun merge(raws: Texture, shifts: Texture, reference: Int, others: List<Int>, tilesX: Int, tilesY: Int): Pair<List<Texture>, Double> {
        val parities = ArrayList<Texture>()
        val used = gpu.texture(tilesX, tilesY, GLES30.GL_R32F)
        try {
            for (parity in listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) {
                mark("merge: parity")
                val out = gpu.texture(paddedWidth, paddedHeight, GLES30.GL_RGBA32F)
                parities += out
                gpu.runGroups(Shaders.MERGE, (tilesX + 1) / 2, (tilesY + 1) / 2, samplers = listOf(raws, shifts), images = listOf(out, used)) {
                    ints("parity", parity.first, parity.second)
                    ints("tiles", tilesX, tilesY)
                    ints("planeSize", planeWidth, planeHeight)
                    ints("padded", paddedWidth, paddedHeight)
                    int("pad", PAD)
                    int("count", frames.size)
                    int("referenceLayer", reference)
                    ints("order", *IntArray(8) { others.getOrElse(it) { 0 } })
                    vec4s("blacks", FloatArray(32) { frames.getOrNull(it / 4)?.meta?.black?.get(it % 4) ?: 0f })
                    floats("ranges", FloatArray(8) { if (it < frames.size) range(it) else 1f })
                    // The reference's noise profile, as the prototype's merge takes it
                    float("noiseS", frames[reference].meta.noiseS.toFloat())
                    float("noiseO", frames[reference].meta.noiseO.toFloat())
                    float("temporal", TEMPORAL)
                    float("spatial", SPATIAL)
                }
            }
            return parities to gpu.read(used, 1).average()
        } catch (e: Throwable) {
            parities.forEach { it.close() }
            throw e
        } finally {
            used.close()
        }
    }

    private fun grid(w: Int, h: Int, tile: Int, stride: Int) = ((w - tile) / stride + 1) to ((h - tile) / stride + 1)

    companion object {
        const val TILE = 16
        const val STRIDE = 8
        const val PAD = 8

        /** HDR+'s c: how much difference counts as motion; and the spatial denoising after the merge (the prototype's defaults). */
        const val TEMPORAL = 8f
        const val SPATIAL = 1f

        /** Coarse to fine as the prototype: (factor from the level below, tile, stride, search radius, distance); level 0 is the merge's grid. */
        private val LEVELS = listOf(Level(1, TILE, STRIDE, 1, false), Level(2, 16, 16, 4, true), Level(4, 16, 16, 4, true), Level(4, 8, 8, 4, true))
    }
}
