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
    }
}
