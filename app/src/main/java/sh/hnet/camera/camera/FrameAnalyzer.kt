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
        if (qrEnabled && !decoding && now - lastQr >= QR_EVERY_MS) {
            lastQr = now
            startDecode(frame)
        }
    }

    /** Clears what was found when the analysis stops (a new mode, the viewfinder leaving). */
    fun reset() {
        generation++
        _qr.value = null
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
