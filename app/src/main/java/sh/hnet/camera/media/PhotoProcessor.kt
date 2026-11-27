package sh.hnet.camera.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Gainmap
import android.graphics.Matrix
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.hnet.camera.camera.SeriesSink
import sh.hnet.camera.camera.SeriesTaken
import sh.hnet.camera.capabilities.model.PixelSize
import sh.hnet.camera.processing.Developed
import sh.hnet.camera.processing.Developer
import sh.hnet.camera.processing.FrameMeta
import sh.hnet.camera.processing.Look

/**
 * Photo's own processing (LOGIKA §38): a series' raw frames go to the GPU as the camera takes them ([shot]), then the series is developed in the
 * background, one at a time, encoded as Ultra HDR with the photo's framing and EXIF, and handed back for saving. The GPU's contexts and compiled
 * programs stay for the process' life (the first photo pays for them once). While anything is queued a foreground service of the media processing kind
 * keeps the process alive (D31), on Android 15+ where that kind exists.
 */
class PhotoProcessor(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)

    private val made = Mutex()
    private var developer: Developer? = null

    /** The GPU can't be used here (no OpenGL ES 3.1, a context refused): photos stay the camera's own for the process' life. */
    @Volatile
    private var unusable = false

    private val _queued = MutableStateFlow(0)

    /** Series taken or being taken and not yet developed: the queue the thumbnail shows, the service runs while it isn't empty. */
    val queued: StateFlow<Int> = _queued.asStateFlow()

    /**
     * Whether the next photo may be a series: not with a full queue, a phone warm enough to be throttled, the battery nearly empty, or a GPU that failed
     * (the camera's own photo then, §38.1 p. 10).
     */
    fun available(): Boolean = unavailable() == null

    /** Why the next photo can't be a series (for the tests' EXIF note, D35); null when it can. */
    fun unavailable(): String? {
        if (unusable) return "no gpu"
        if (_queued.value >= MAX_QUEUED) return "queue"
        val heat = power.currentThermalStatus
        if (heat >= PowerManager.THERMAL_STATUS_MODERATE) return "heat $heat"
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (!battery.isCharging && level in 0..LOW_BATTERY) return "battery $level"
        return null
    }

    /** One photo's way through: the camera's [SeriesSink] while it takes the frames, then [develop]; [close] if the photo turned out the camera's own. */
    fun shot(): Shot = Shot()

    inner class Shot internal constructor() : SeriesSink, AutoCloseable {
        private var series: Developer.Series? = null
        private var counted = false

        /** How many frames the series had, and whether only its reference was developed (the tests' EXIF note, D35). */
        var note: String? = null
            private set

        override suspend fun begin(frames: Int, width: Int, height: Int): Boolean = withContext(Dispatchers.IO) {
            // The queue's place taken first, at once (presses in quick succession all passed [available] before any series began)
            if (!reserve()) return@withContext false
            val gpu = developer()
            series = gpu?.let { runCatching { it.series(frames, width, height) }.onFailure { e -> Log.w(TAG, "Series not started: ${e.javaClass.simpleName}") }.getOrNull() }
            if (series == null) close()
            series != null
        }

        override suspend fun add(samples: ByteBuffer, rowStride: Int, meta: FrameMeta): Boolean {
            val into = series ?: return false
            return withContext(Dispatchers.IO) {
                runCatching { into.add(samples, rowStride, meta) }.onFailure { Log.w(TAG, "Frame not uploaded: ${it.javaClass.simpleName}") }.getOrDefault(false)
            }
        }

        /**
         * The series developed and encoded: the JPEG (Ultra HDR, the photo's framing, [mirror]ed where asked) with its EXIF's values; null when it failed.
         * [progress] hears 0..1 as the steps go.
         */
        suspend fun develop(taken: SeriesTaken, orientation: Int, takenAt: Long, mirror: Boolean, progress: (Float) -> Unit): Processed? = withContext(Dispatchers.IO) {
            val into = series ?: return@withContext null
            series = null
            try {
                val gpu = developer() ?: return@withContext null
                val done = HashSet<String>()
                val developed = gpu.develop(into, Look()) { step, _ ->
                    val name = step.substringBefore(':')
                    if (done.add(name)) progress((STEPS[name] ?: return@develop) * DEVELOPING)
                }
                if (developed.fallback) Log.w(TAG, "Series developed from its reference frame alone")
                note = "own ${into.frames}/${into.capacity}" + (if (developed.fallback) ", reference only" else "") + ", ${(developed.timesMs.values.sum() / 100).toInt() / 10.0} s"
                progress(DEVELOPING)
                val (bytes, size) = encode(developed, taken, orientation, mirror)
                progress(1f)
                Processed(bytes, size, exif(taken, orientation, takenAt, size) + listOfNotNull(note?.let { ExifInterface.TAG_USER_COMMENT to it }))
            } catch (e: Throwable) {
                Log.w(TAG, "Series not developed: ${e.javaClass.simpleName}")
                null
            } finally {
                // The queue's place stays until the photo is saved ([close], after the saving): the badge and the service last as long
                into.close()
            }
        }

        override fun close() {
            series?.close()
            series = null
            if (counted) {
                counted = false
                _queued.update { (it - 1).coerceAtLeast(0) }
            }
        }

        /** A place in the queue, unless it is full; the service started with the first. */
        private fun reserve(): Boolean {
            if (counted) return true
            while (true) {
                val now = _queued.value
                if (now >= MAX_QUEUED) return false
                if (_queued.compareAndSet(now, now + 1)) break
            }
            counted = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                runCatching { context.startForegroundService(Intent(context, ProcessingService::class.java)) }.onFailure { Log.w(TAG, "Service not started: ${it.javaClass.simpleName}") }
            }
            return true
        }
    }

    /** A developed photo: the JPEG, its size and the EXIF tags to write into its file. */
    class Processed(val bytes: ByteArray, val size: PixelSize, val exif: Map<String, String>)

    /** Whether the GPU's programs were made ready ([warmUp]) in this process. */
    @Volatile
    private var warmed = false

    /**
     * Gets the GPU ready before the first photo (once a Photo session with the raw stream is there): its contexts made and every program compiled or
     * loaded from the cache, on a synthetic series developed and thrown away.
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (warmed) return@withContext
        warmed = true
        val gpu = developer() ?: return@withContext
        runCatching { gpu.warmUp() }.onFailure { Log.w(TAG, "GPU not warmed up: ${it.javaClass.simpleName}") }
    }

    private suspend fun developer(): Developer? = made.withLock {
        if (unusable) return null
        // The programs' binaries kept across processes (cache: the system may clear it, they are compiled again then)
        developer ?: runCatching { Developer(File(context.cacheDir, "gpu-programs")) }.onFailure {
            unusable = true
            Log.w(TAG, "No GPU processing: ${it.javaClass.simpleName}")
        }.getOrNull()?.also { developer = it }
    }

    /**
     * The developed picture cut to the photo's framing (the lens' crop or the zoom, then the aspect – as the DNG's crop is, [DngCrop.framing]) and mirrored
     * where asked (left to right as the photo is seen: across the stored rows when the EXIF turns it a quarter), its gain map with it, as a JPEG. The
     * pixels stay as the sensor lies; the EXIF turns them, as the camera's own photos.
     */
    private fun encode(developed: Developed, taken: SeriesTaken, orientation: Int, mirror: Boolean): Pair<ByteArray, PixelSize> {
        val w = developed.width
        val h = developed.height
        val base = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        base.copyPixelsFromBuffer(developed.rgba.position(0))
        val (fx, fy, fw, fh) = DngCrop.framing(0.0, 0.0, w.toDouble(), h.toDouble(), taken.zoom, taken.aspect, taken.cropShare).map { it.toInt() }
        val x = fx.coerceIn(0, w - 1)
        val y = fy.coerceIn(0, h - 1)
        val cw = fw.coerceIn(1, w - x)
        val ch = fh.coerceIn(1, h - y)
        val flip = if (mirror) Matrix().apply { if (orientation % 180 != 0) setScale(1f, -1f) else setScale(-1f, 1f) } else null
        if (taken.ultraHdr) base.gainmap = gainmap(developed)
        val photo = if (x == 0 && y == 0 && cw == w && ch == h && flip == null) base else Bitmap.createBitmap(base, x, y, cw, ch, flip, false).also { cut ->
            base.gainmap?.let { cut.gainmap = StillCrop.editGainmap(it, base, x, y, cw, ch, flip) }
        }
        val out = ByteArrayOutputStream()
        check(photo.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) { "JPEG not encoded" }
        if (photo !== base) photo.recycle()
        base.recycle()
        return out.toByteArray() to PixelSize(cw, ch)
    }

    /** The development's gain map as Android's: one channel, the boost's log2 over its maximum, as the Pixel's camera writes its own (D32). */
    private fun gainmap(developed: Developed): Gainmap {
        val map = developed.gainMap
        val contents = createBitmap(map.width, map.height, Bitmap.Config.ALPHA_8)
        val rows = ByteBuffer.allocate(contents.rowBytes * map.height)
        for (y in 0 until map.height) {
            rows.position(y * contents.rowBytes)
            rows.put(map.values, y * map.width, map.width)
        }
        contents.copyPixelsFromBuffer(rows.position(0))
        val ratio = 2f.pow(map.maxStops)
        return Gainmap(contents).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(ratio, ratio, ratio)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(EPSILON, EPSILON, EPSILON)
            setEpsilonHdr(EPSILON, EPSILON, EPSILON)
            minDisplayRatioForHdrTransition = 1f
            displayRatioForFullHdr = ratio
        }
    }

    /** What the camera's own EXIF would say of the photo: the phone, when, the exposure, the lens, the orientation, its size; no flash. */
    private fun exif(taken: SeriesTaken, orientation: Int, takenAt: Long, size: PixelSize): Map<String, String> = buildMap {
        val zoned = Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault())
        val time = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT).format(zoned)
        // EXIF wants "+00:00", never "Z"
        val offset = DateTimeFormatter.ofPattern("xxx", Locale.ROOT).format(zoned)
        val subsec = String.format(Locale.ROOT, "%03d", takenAt % 1000)
        put(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        put(ExifInterface.TAG_MODEL, Build.MODEL)
        put(ExifInterface.TAG_SOFTWARE, software)
        for (tag in listOf(ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED)) put(tag, time)
        for (tag in listOf(ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED)) put(tag, offset)
        for (tag in listOf(ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED)) put(tag, subsec)
        put(ExifInterface.TAG_ORIENTATION, exifOrientation(orientation).toString())
        put(ExifInterface.TAG_PIXEL_X_DIMENSION, size.width.toString())
        put(ExifInterface.TAG_PIXEL_Y_DIMENSION, size.height.toString())
        taken.exposureNs?.let { put(ExifInterface.TAG_EXPOSURE_TIME, "${it / 1000}/1000000") }
        taken.iso?.let { put(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString()) }
        taken.aperture?.let { put(ExifInterface.TAG_F_NUMBER, "${(it * 100).toInt()}/100") }
        taken.focalLength?.let { put(ExifInterface.TAG_FOCAL_LENGTH, "${(it * 1000).toInt()}/1000") }
        taken.focalLength35?.let { put(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, it.toString()) }
        put(ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.EXPOSURE_PROGRAM_NORMAL.toString())
        put(ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.EXPOSURE_MODE_AUTO.toString())
        put(ExifInterface.TAG_WHITE_BALANCE, ExifInterface.WHITE_BALANCE_AUTO.toString())
        // The flash didn't fire (a series is never taken with it)
        put(ExifInterface.TAG_FLASH, "0")
    }

    /** The app and its version, as the EXIF's software: which photos are the app's own development. */
    private val software: String by lazy {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        listOfNotNull(context.packageName, version).joinToString(" ")
    }

    private fun exifOrientation(degrees: Int) = when (degrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private companion object {
        const val TAG = "PhotoProcessor"
        const val QUALITY = 95

        /** Series at once (LOGIKA §38.1 p. 10): the frames of each wait on the GPU (about 200 MB). */
        const val MAX_QUEUED = 3

        /** The battery's level at or below which photos stay the camera's own (unless charging). */
        const val LOW_BATTERY = 10
        const val EPSILON = 1f / 2048

        /** The development's share of the whole (encoding the JPEG is the rest). */
        const val DEVELOPING = 0.9f

        /** Where each step of the development starts, as a share of it (from the steps' times on the Pixel 9 Pro). */
        val STEPS = mapOf(
            "merge" to 0.02f, "unplane" to 0.33f, "normalize" to 0.33f, "cells" to 0.34f, "neutral" to 0.36f, "demosaic" to 0.37f, "warp" to 0.39f,
            "exposure" to 0.40f, "fusion" to 0.42f, "curve" to 0.46f, "tone" to 0.64f, "finish" to 0.66f, "output" to 0.95f,
        )
    }
}
