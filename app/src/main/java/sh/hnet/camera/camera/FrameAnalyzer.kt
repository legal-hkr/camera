package sh.hnet.camera.camera

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.camera.capabilities.model.Orientation
import sh.hnet.camera.capabilities.model.Point
import sh.hnet.camera.capabilities.model.ProExposure
import zxingcpp.BarcodeReader

/** A QR code in the picture: what it holds and its corners as shares of the preview (as the preview shows it, upright). */
data class QrFound(val content: QrContent, val corners: List<Point>)

/**
 * Reads the analysis frames (LOGIKA §8): QR codes about five times a second (zxing-cpp on a copy of the luma, on a thread of its own). [previewRatio] is the
 * preview's width to height as the sensor sees it (4:3, 16:9): the analysis frame is 4:3 and the preview may show only its middle.
 */
class FrameAnalyzer(private val previewRatio: () -> Double) {
    private val _qr = MutableStateFlow<QrFound?>(null)
    val qr: StateFlow<QrFound?> = _qr.asStateFlow()

    /** QR codes are looked for only in Photo mode with the setting on. */
    @Volatile
    var qrEnabled = true
        set(value) {
            field = value
            if (!value) _qr.value = null
        }

    private val reader = BarcodeReader(BarcodeReader.Options().apply {
        formats = setOf(BarcodeReader.Format.QR_CODE, BarcodeReader.Format.MICRO_QR_CODE)
        tryRotate = true
        tryInvert = false
        tryHarder = false
    })

    // Decoding takes longer than a frame: the luma is copied and the frame let go at once, the code read on a thread of its own (the camera never waits on it)
    private val worker = HandlerThread("camera-qr").apply { start() }
    private val workerHandler = Handler(worker.looper)
    @Volatile
    private var decoding = false
    private var luma = ByteArray(0)
    private var lumaBitmap: Bitmap? = null
    private var lastQr = 0L
    @Volatile
    private var lastSeen = 0L
    /** A reset ends what was in flight: a decode started before it doesn't publish. */
    @Volatile
    private var generation = 0

    fun analyze(frame: AnalysisFrame) {
        measureMovement(frame)
        val now = SystemClock.uptimeMillis()
        if (histogramEnabled && now - histogramAt >= HISTOGRAM_EVERY_MS) {
            histogramAt = now
            measureHistogram(frame)
        }
        if (peakingEnabled && now - peakingAt >= PEAKING_EVERY_MS) {
            peakingAt = now
            findEdges(frame)
        }
        if (qrEnabled && !decoding && now - lastQr >= QR_EVERY_MS) {
            lastQr = now
            startDecode(frame)
        }
    }

    /** Pro's histogram (SPEC §11): the luma in [HISTOGRAM_BINS] bins, a few times a second while [histogramEnabled]. */
    @Volatile
    var histogramEnabled = false
        set(value) {
            if (field == value) return
            synchronized(published) {
                field = value
                if (!value) _histogram.value = null
            }
        }

    /** The photo's aspect (width to height, its long side along the frame's): the histogram counts only that middle part of the frame. */
    @Volatile
    var histogramAspect = 4.0 / 3
    private val _histogram = MutableStateFlow<IntArray?>(null)
    val histogram: StateFlow<IntArray?> = _histogram.asStateFlow()
    private var histogramAt = 0L

    /** Held while a histogram or edges are published, a switch turned off or a reset: none of them is published after those. */
    private val published = Any()

    private fun measureHistogram(frame: AnalysisFrame) {
        val generation = generation
        val image = frame.image
        val plane = image.planes[0]
        val buffer = plane.buffer
        val high = if (frame.tenBit) 1 else 0
        val bins = IntArray(HISTOGRAM_BINS)
        val frameAspect = image.width.toDouble() / image.height
        val aspect = histogramAspect
        val keptW = if (aspect < frameAspect) (image.height * aspect).toInt() else image.width
        val keptH = if (aspect > frameAspect) (image.width / aspect).toInt() else image.height
        val xs = (image.width - keptW) / 2 until (image.width + keptW) / 2
        val ys = (image.height - keptH) / 2 until (image.height + keptH) / 2
        for (y in ys step HISTOGRAM_STEP) {
            val row = y * plane.rowStride + high
            for (x in xs step HISTOGRAM_STEP) bins[(buffer.get(row + x * plane.pixelStride).toInt() and 0xFF) * HISTOGRAM_BINS / 256]++
        }
        synchronized(published) { if (histogramEnabled && generation == this.generation) _histogram.value = bins }
    }

    /** Focus peaking's edges (LOGIKA §27): a mask of the sharp edges at about 640 pixels across, as the sensor sees it, ten times a second while [peakingEnabled]. */
    class Edges(val mask: Bitmap, val sensorOrientation: Int, val front: Boolean, val frameRatio: Double)

    @Volatile
    var peakingEnabled = false
        set(value) {
            if (field == value) return
            synchronized(published) {
                field = value
                if (!value) _edges.value = null
            }
        }
    private val _edges = MutableStateFlow<Edges?>(null)
    val edges: StateFlow<Edges?> = _edges.asStateFlow()
    private var peakingAt = 0L

    /** The edge search's working arrays, kept from frame to frame (the analysis thread's only). */
    private var edgeLuma = IntArray(0)
    private var gradientX = IntArray(0)
    private var gradientY = IntArray(0)
    private var magnitude = IntArray(0)
    private var wideMagnitude = IntArray(0)

    /**
     * The sharp edges as thin lines (LOGIKA §30.3). How sharp an edge is doesn't depend on its contrast: across a sharp edge the brightness changes as much
     * over 2 pixels as over 4, across a blurred one half as much – so an edge shows where the Sobel operator over ±1 pixel is nearly as strong as the same
     * operator over ±2 ([SHARP_RATIO]), and strong at all over ±1 ([EDGE_MIN]: a flat picture's noise shows none); of it only the ridge (a pixel stronger than both
     * its neighbors across the edge). Not a share of the picture: a blurred picture then shows nothing (the owner, 25 Nov 2026: the strongest share of a
     * blurred picture lit as sharp lines, a sharp one only as dashes). At about 640 pixels across.
     */
    private fun findEdges(frame: AnalysisFrame) {
        val generation = generation
        val image = frame.image
        val plane = image.planes[0]
        val buffer = plane.buffer
        val high = if (frame.tenBit) 1 else 0
        val step = (image.width / EDGE_WIDTH).coerceAtLeast(1)
        val w = image.width / step
        val h = image.height / step
        if (edgeLuma.size != w * h) {
            edgeLuma = IntArray(w * h)
            gradientX = IntArray(w * h)
            gradientY = IntArray(w * h)
            magnitude = IntArray(w * h)
            wideMagnitude = IntArray(w * h)
        }
        val luma = edgeLuma
        val gxs = gradientX
        val gys = gradientY
        val near = magnitude
        val wide = wideMagnitude
        near.fill(0)
        for (y in 0 until h) {
            val row = step * y * plane.rowStride + high
            for (x in 0 until w) luma[y * w + x] = buffer.get(row + step * x * plane.pixelStride).toInt() and 0xFF
        }
        val w2 = 2 * w
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val i = y * w + x
            val gx = luma[i - w + 1] + 2 * luma[i + 1] + luma[i + w + 1] - luma[i - w - 1] - 2 * luma[i - 1] - luma[i + w - 1]
            val gy = luma[i + w - 1] + 2 * luma[i + w] + luma[i + w + 1] - luma[i - w - 1] - 2 * luma[i - w] - luma[i - w + 1]
            // The same operator two pixels out
            val gx2 = luma[i - w2 + 2] + 2 * luma[i + 2] + luma[i + w2 + 2] - luma[i - w2 - 2] - 2 * luma[i - 2] - luma[i + w2 - 2]
            val gy2 = luma[i + w2 - 2] + 2 * luma[i + w2] + luma[i + w2 + 2] - luma[i - w2 - 2] - 2 * luma[i - w2] - luma[i - w2 + 2]
            gxs[i] = gx
            gys[i] = gy
            near[i] = abs(gx) + abs(gy)
            wide[i] = abs(gx2) + abs(gy2)
        }
        // A new bitmap each time (the one shown is being drawn); its rows may be padded
        val bitmap = createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val stride = bitmap.rowBytes
        val out = ByteArray(stride * h)
        for (y in 3 until h - 3) for (x in 3 until w - 3) {
            val i = y * w + x
            val m = near[i]
            val m2 = wide[i]
            // The threshold on the near change: fine detail (a thin line, a texture of a few pixels) cancels out in the wide one
            if (m < EDGE_MIN || m < m2 * SHARP_RATIO) continue
            // The ridge: across the edge (the gradient's direction, in four) neither neighbor is stronger
            val ax = abs(gxs[i])
            val ay = abs(gys[i])
            val across = when {
                ay * 5 < ax * 2 -> 1
                ax * 5 < ay * 2 -> w
                (gxs[i] > 0) == (gys[i] > 0) -> w + 1
                else -> w - 1
            }
            if (m >= near[i - across] && m > near[i + across]) out[y * stride + x] = 0xFF.toByte()
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(out))
        synchronized(published) { if (peakingEnabled && generation == this.generation) _edges.value = Edges(bitmap, frame.sensorOrientation, frame.front, image.width.toDouble() / image.height) }
    }

    /** A point of the analysis frame (shares of it, as the sensor sees it) where the upright preview shows it, as shares of the preview. */
    fun viewOf(x: Double, y: Double, sensorOrientation: Int, front: Boolean, frameRatio: Double): Point = toView(x, y, sensorOrientation, front, frameRatio)

    /** Clears what was found when the analysis stops (a new mode, the viewfinder leaving). */
    fun reset() {
        generation++
        _qr.value = null
        // A new session or camera: the last picture's brightness says nothing about the next, nor its histogram or edges (another lens, turned or cut otherwise)
        synchronized(brightnessFrames) { brightnessFrames.clear() }
        synchronized(published) {
            _histogram.value = null
            _edges.value = null
        }
    }

    fun close() {
        worker.quitSafely()
    }

    private fun startDecode(frame: AnalysisFrame) {
        val image = frame.image
        val w = image.width
        val h = image.height
        if (luma.size != w * h) {
            luma = ByteArray(w * h)
            lumaBitmap = createBitmap(w, h, Bitmap.Config.ALPHA_8)
        }
        val plane = image.planes[0]
        val buffer = plane.buffer
        val row = plane.rowStride
        val step = plane.pixelStride
        // P010 keeps the 10 bits at the top of a little-endian 16-bit word: its high byte is the luma in 8 bits
        val high = if (frame.tenBit) 1 else 0
        for (y in 0 until h) {
            val base = y * row + high
            val out = y * w
            for (x in 0 until w) luma[out + x] = buffer.get(base + x * step)
        }
        decoding = true
        val started = generation
        val sensorOrientation = frame.sensorOrientation
        val front = frame.front
        workerHandler.post {
            try {
                decode(w, h, sensorOrientation, front, started)
            } finally {
                decoding = false
            }
        }
    }

    private fun decode(w: Int, h: Int, sensorOrientation: Int, front: Boolean, started: Int) {
        val bitmap = lumaBitmap ?: return
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(luma, 0, w * h))
        val found = runCatching { reader.read(bitmap) }.getOrNull()?.firstOrNull { !it.text.isNullOrEmpty() }
        if (started != generation || !qrEnabled) return
        val now = SystemClock.uptimeMillis()
        val shown = _qr.value
        if (found == null) {
            // A code that left the frame: its corners go after a moment, the chip after two seconds
            when {
                shown == null -> Unit
                now - lastSeen > QR_KEEP_MS -> _qr.value = null
                now - lastSeen > MARKS_KEEP_MS && shown.corners.isNotEmpty() -> _qr.value = shown.copy(corners = emptyList())
            }
            return
        }
        lastSeen = now
        val p = found.position
        val corners = listOf(p.topLeft, p.topRight, p.bottomRight, p.bottomLeft).map { toView(it.x.toDouble() / w, it.y.toDouble() / h, sensorOrientation, front, w.toDouble() / h) }
        val content = QrContent.of(found.text ?: return)
        // The same code barely moved: nothing new to draw (the viewfinder doesn't recompose five times a second for it)
        if (shown != null && shown.content == content && shown.corners.size == corners.size && shown.corners.zip(corners).all { (a, b) -> abs(a.x - b.x) < STEADY && abs(a.y - b.y) < STEADY }) return
        _qr.value = QrFound(content, corners)
    }

    // Movement in the scene for the automatic motion photo: the luma averaged over a coarse grid, compared with the grid of about a third of a second earlier;
    // the change common to the whole picture (exposure steps, flicker) is taken out, and it counts as movement when enough cells changed on their own
    private val grids = ArrayDeque<Pair<Long, IntArray>>()
    private val movement = ArrayDeque<Pair<Long, Boolean>>()
    private val movementLock = Any()

    private fun measureMovement(frame: AnalysisFrame) {
        val image = frame.image
        val time = image.timestamp
        val plane = image.planes[0]
        val buffer = plane.buffer
        val high = if (frame.tenBit) 1 else 0
        val cellW = image.width / GRID_W
        val cellH = image.height / GRID_H
        val stepX = maxOf(1, cellW / CELL_SAMPLES)
        val stepY = maxOf(1, cellH / CELL_SAMPLES)
        val now = IntArray(GRID_W * GRID_H)
        for (gy in 0 until GRID_H) for (gx in 0 until GRID_W) {
            var sum = 0
            var count = 0
            var y = gy * cellH + stepY / 2
            while (y < (gy + 1) * cellH) {
                var x = gx * cellW + stepX / 2
                while (x < (gx + 1) * cellW) {
                    sum += buffer.get(y * plane.rowStride + x * plane.pixelStride + high).toInt() and 0xFF
                    count++
                    x += stepX
                }
                y += stepY
            }
            now[gy * GRID_W + gx] = if (count > 0) sum / count else 0
        }
        measureBrightness(now, frame.tenBit, time)
        // A gap in the frames (a new session, another camera) starts the comparison over
        val last = grids.lastOrNull()?.first
        if (last != null && (time < last || time - last > GAP_NS)) grids.clear()
        val before = grids.lastOrNull { it.first <= time - COMPARE_NS }?.second
        grids.addLast(time to now)
        while (grids.size > 1 && grids[1].first <= time - COMPARE_NS) grids.removeFirst()
        if (before == null) return
        val diffs = IntArray(now.size) { now[it] - before[it] }
        val common = diffs.sorted()[diffs.size / 2]
        val changed = diffs.count { abs(it - common) > CELL_CHANGE }
        synchronized(movementLock) {
            movement.addLast(time to (changed >= MIN_CHANGED_CELLS))
            while (movement.size > 1 && movement.first().first < time - MOVEMENT_KEEP_NS) movement.removeFirst()
        }
    }

    /**
     * A frame's brightness for Pro's metering (LOGIKA §26): the grid's cells made linear (SDR or HLG, as the analysis frame is) and averaged with more weight
     * to the middle; [hlg]: measured on an HLG picture (another target); [frameNs]: the frame's sensor time (the engine knows its exposure by it).
     */
    class Brightness(val value: Double, val hlg: Boolean, val frameNs: Long)

    /** The last few frames' brightness, newest first: the metering takes the newest whose exposure the camera has reported already (its result may come later). */
    private val brightnessFrames = ArrayDeque<Brightness>()

    fun recentBrightness(): List<Brightness> = synchronized(brightnessFrames) { brightnessFrames.toList() }

    private fun measureBrightness(grid: IntArray, hlg: Boolean, frameNs: Long) {
        var sum = 0.0
        var weights = 0.0
        for (gy in 0 until GRID_H) for (gx in 0 until GRID_W) {
            // Centre-weighted: a cell at the middle counts about three times one at the edge
            val dx = (gx + 0.5) / GRID_W - 0.5
            val dy = (gy + 0.5) / GRID_H - 0.5
            val weight = 1.0 / (1.0 + 8.0 * (dx * dx + dy * dy))
            sum += ProExposure.linear(grid[gy * GRID_W + gx] / 255.0, hlg) * weight
            weights += weight
        }
        synchronized(brightnessFrames) {
            brightnessFrames.addFirst(Brightness(sum / weights, hlg, frameNs))
            while (brightnessFrames.size > BRIGHTNESS_KEPT) brightnessFrames.removeLast()
        }
    }

    /** Whether something moved in the scene between two of the sensor's times (the automatic motion photo keeps its video then). */
    fun moved(fromNs: Long, toNs: Long): Boolean = synchronized(movementLock) {
        movement.any { (time, moving) -> moving && time in fromNs..toNs }
    }

    /** A point of the (landscape, 4:3) analysis frame on the upright preview: into the part the preview shows, then turned as the camera shows it. */
    private fun toView(x: Double, y: Double, sensorOrientation: Int, front: Boolean, frameRatio: Double): Point {
        val ratio = previewRatio()
        val sensor = if (ratio > frameRatio) {
            val k = frameRatio / ratio
            Point(x, (y - (1 - k) / 2) / k)
        } else {
            val k = ratio / frameRatio
            Point((x - (1 - k) / 2) / k, y)
        }
        return Orientation.sensorToView(sensor, sensorOrientation, front)
    }

    companion object {
        private const val QR_EVERY_MS = 200L
        /** Brightness of this many recent frames kept for the metering (half a second at 15 fps; a frame's result comes a frame or a few later). */
        private const val BRIGHTNESS_KEPT = 8
        const val HISTOGRAM_BINS = 64
        private const val HISTOGRAM_EVERY_MS = 200L
        /** Every fourth pixel each way: enough for a histogram's shape. */
        private const val HISTOGRAM_STEP = 4
        private const val PEAKING_EVERY_MS = 100L
        /** The edges are looked for at about this many pixels across. */
        private const val EDGE_WIDTH = 640

        /** How strong the change over ±1 pixel must be against the one over ±2 for a sharp edge (1: a step, 0.5: blurred over 4 pixels or more). */
        private const val SHARP_RATIO = 0.75f

        /** The least change over ±1 pixel that is an edge at all (the Sobel weights sum to 4: a step of 16 levels of 255). */
        private const val EDGE_MIN = 64
        private const val QR_KEEP_MS = 2_000L
        private const val MARKS_KEEP_MS = 300L
        /** Corners that moved less than this share of the picture aren't published again. */
        private const val STEADY = 0.02
        private const val GRID_W = 16
        private const val GRID_H = 12
        /** Samples per cell along each side, averaged (the sensor's noise cancels out). */
        private const val CELL_SAMPLES = 8
        private const val COMPARE_NS = 330_000_000L
        private const val GAP_NS = 500_000_000L
        private const val MOVEMENT_KEEP_NS = 6_000_000_000L
        /** A cell's own change of its mean luma (0–255) that counts, and how many cells (of 192) must change: about a fiftieth of the picture. */
        private const val CELL_CHANGE = 12
        private const val MIN_CHANGED_CELLS = 4
    }
}
