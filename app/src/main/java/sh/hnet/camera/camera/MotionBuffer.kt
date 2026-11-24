package sh.hnet.camera.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.camera.capabilities.model.PixelSize

/** A motion photo's video: the MP4's bytes and the still's time in it. */
class MotionClip(val bytes: ByteArray, val presentationUs: Long)

/**
 * The motion photo's video stream (LOGIKA §3.3): the camera writes into this encoder all the time in Photo mode, and the last few seconds of encoded frames are
 * kept in memory (key frames every half second). A photo takes the frames from 1.5 s before it to 1.5 s after it into a short MP4. The frames are timed in the
 * sensor's clock (the stream's timestamp base), as the photo's own timestamp is. No sound (D7).
 */
class MotionBuffer(size: PixelSize, hdr: Boolean, private val cacheDir: File) {
    private val thread = HandlerThread("camera-motion").apply { start() }
    private val handler = Handler(thread.looper)
    private val codec: MediaCodec
    val surface: Surface

    private class Frame(val data: ByteArray, val timeUs: Long, val key: Boolean)

    private val frames = ArrayDeque<Frame>()
    private val lock = Any()
    @Volatile
    private var format: MediaFormat? = null
    @Volatile
    private var newestUs = 0L
    @Volatile
    private var released = false
    /** The encoder failed: the ring holds nothing current any more, no clip is made from it. */
    @Volatile
    private var dead = false

    init {
        val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val mediaFormat = MediaFormat.createVideoFormat(mime, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_INTERVAL_S)
            if (hdr) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }
        val created = runCatching { MediaCodec.createEncoderByType(mime) }
        codec = created.getOrElse {
            thread.quitSafely()
            throw it
        }
        try {
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    // An encoder being released under a frame throws: the frame is dropped, not the app
                    try {
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0) {
                            codec.getOutputBuffer(index)?.let { buffer ->
                                val data = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.get(data)
                                keep(Frame(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0))
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Motion frame dropped: ${e.javaClass.simpleName}")
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "Motion encoder error: ${e.diagnosticInfo}")
                    dead = true
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    this@MotionBuffer.format = format
                }
            }, handler)
            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
        } catch (e: Exception) {
            codec.release()
            thread.quitSafely()
            throw e
        }
    }

    /** Keeps the last few seconds, starting at a key frame. */
    private fun keep(frame: Frame) = synchronized(lock) {
        // A frame whose time doesn't move on (a repeated or older one) would break the video's track
        if (frames.isNotEmpty() && frame.timeUs <= newestUs) return@synchronized
        frames.addLast(frame)
        newestUs = frame.timeUs
        while (frames.size > 1 && frames[1].timeUs < frame.timeUs - KEEP_US) frames.removeFirst()
        while (frames.size > 1 && !frames.first().key) frames.removeFirst()
    }

    /**
     * The clip around a photo taken at [shotUs] (the sensor's time): waits for the frames after it, then writes the MP4 from the last key frame at least 1.5 s
     * before the shot to 1.5 s after it. Null when the stream has nothing usable (the session closed, the encoder failed).
     */
    suspend fun clip(shotUs: Long, orientation: Int): MotionClip? {
        withTimeoutOrNull(AFTER_US / 1000 + WAIT_SLACK_MS) {
            while (!released && !dead && newestUs < shotUs + AFTER_US) delay(POLL_MS)
        }
        if (dead) return null
        val format = format ?: return null
        val picked = synchronized(lock) {
            // From a key frame at or before the shot (1.5 s before where the ring has it)
            val keys = frames.filter { it.key && it.timeUs <= shotUs }
            val start = keys.lastOrNull { it.timeUs <= shotUs - BEFORE_US } ?: keys.firstOrNull() ?: return null
            frames.filter { it.timeUs >= start.timeUs && it.timeUs <= shotUs + AFTER_US }
        }
        // The video must surround the shot: frames from before it and at least a second after (a stalled encoder or another session's ring has neither)
        if (picked.size < MIN_FRAMES || picked.first().timeUs > shotUs || picked.last().timeUs < shotUs + MIN_AFTER_US) return null
        return withContext(Dispatchers.IO) { mux(picked, format, shotUs, orientation) }
    }

    private fun mux(picked: List<Frame>, format: MediaFormat, shotUs: Long, orientation: Int): MotionClip? {
        val file = File.createTempFile("motion", ".mp4", cacheDir)
        return try {
            val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val first = picked.first().timeUs
            try {
                muxer.setOrientationHint(orientation)
                val track = muxer.addTrack(format)
                muxer.start()
                val info = MediaCodec.BufferInfo()
                for (frame in picked) {
                    info.set(0, frame.data.size, frame.timeUs - first, if (frame.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(track, ByteBuffer.wrap(frame.data), info)
                }
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
            }
            MotionClip(file.readBytes(), (shotUs - first).coerceAtLeast(0))
        } catch (e: Exception) {
            Log.w(TAG, "Motion clip not written: ${e.javaClass.simpleName}")
            null
        } finally {
            file.delete()
        }
    }

    /** The session is closing: the encoder, its surface and the frames go – on the encoder's own thread, after any frame being copied there. */
    fun release() {
        if (released) return
        released = true
        handler.post {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
            synchronized(lock) { frames.clear() }
            thread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "MotionBuffer"
        const val BITRATE = 10_000_000
        const val FPS = 30
        const val KEY_INTERVAL_S = 0.5f
        const val BEFORE_US = 1_500_000L
        const val AFTER_US = 1_500_000L
        /** Kept while waiting for the 1.5 s after a shot: the 1.5 s before it, from a key frame up to half a second earlier, and a margin. */
        const val KEEP_US = 4_000_000L
        const val WAIT_SLACK_MS = 1_000L
        const val POLL_MS = 50L
        const val MIN_FRAMES = 10
        const val MIN_AFTER_US = 1_000_000L
    }
}
