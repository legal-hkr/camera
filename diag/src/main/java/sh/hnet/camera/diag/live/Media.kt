package sh.hnet.camera.diag.live

import android.content.ContentResolver
import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Test captures go to DCIM/CameraDiag (outside the camera folder, so the gallery shows them as an album), reports to Download/CameraDiag. */
object Media {
    const val MEDIA_DIR = "DCIM/CameraDiag"
    const val REPORT_DIR = "Download/CameraDiag"

    fun stamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))

    fun saveImage(resolver: ContentResolver, name: String, mime: String, bytes: ByteArray): Uri {
        val uri = pending(resolver, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), name, mime, MEDIA_DIR)
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        publish(resolver, uri)
        return uri
    }

    fun newImage(resolver: ContentResolver, name: String, mime: String): Uri =
        pending(resolver, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), name, mime, MEDIA_DIR)

    fun newVideo(resolver: ContentResolver, name: String): Uri =
        pending(resolver, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), name, "video/mp4", MEDIA_DIR)

    fun newReport(resolver: ContentResolver, name: String): Uri =
        pending(resolver, MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), name, "application/json", REPORT_DIR)

    /** A file in a folder of Download/CameraDiag: raw dumps and DNG series meant to be pulled to a computer, not shown in the gallery. */
    fun writeDownload(resolver: ContentResolver, folder: String, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val uri = pending(resolver, MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), name, mime, "$REPORT_DIR/$folder")
        try {
            resolver.openOutputStream(uri)!!.use(write)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        publish(resolver, uri)
        return uri
    }

    private fun pending(resolver: ContentResolver, collection: Uri, name: String, mime: String, dir: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection, values) ?: error("MediaStore refused $name")
    }

    fun publish(resolver: ContentResolver, uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    /** What a recorded file says about itself: the video track's codec, profile, color description and frame rate as the container stores them. */
    fun describeVideo(resolver: ContentResolver, uri: Uri): JsonObject = buildJsonObject {
        val extractor = MediaExtractor()
        try {
            resolver.openFileDescriptor(uri, "r")!!.use { fd -> extractor.setDataSource(fd.fileDescriptor) }
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                put("mime", mime)
                for (key in listOf(MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_PROFILE, MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_FRAME_RATE)) {
                    if (format.containsKey(key)) put(key, format.getNumber(key)?.toString())
                }
                if (format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) put("hdrStaticInfo", true)
                if (format.containsKey(MediaFormat.KEY_DURATION)) put("durationMs", format.getLong(MediaFormat.KEY_DURATION) / 1000)
            }
        } finally {
            extractor.release()
        }
    }
}

/** How a test video is encoded: HEVC, 10-bit HLG or 8-bit SDR. */
data class VideoSpec(val width: Int, val height: Int, val fps: Int, val hlg: Boolean, val bitrate: Int)

/**
 * A surface encoder whose output is either muxed into an MP4 in MediaStore or only counted; it records every frame's presentation time so a test can measure the frame
 * rate and find gaps (a still capture that stalls the video).
 */
class SurfaceEncoder(spec: VideoSpec, thread: CameraThread, private val output: ParcelFileDescriptor?) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
    val surface: Surface
    val timestampsUs = mutableListOf<Long>()
    var outputFormat: MediaFormat? = null
        private set
    private var muxer: MediaMuxer? = null
    private var track = -1
    private val done = CompletableDeferred<Unit>()

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, spec.width, spec.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
            setInteger(MediaFormat.KEY_OPERATING_RATE, spec.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (spec.hlg) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            } else {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            }
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!isConfig && info.size > 0) {
                    timestampsUs += info.presentationTimeUs
                    muxer?.let { m -> codec.getOutputBuffer(index)?.let { m.writeSampleData(track, it, info) } }
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done.complete(Unit)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                done.completeExceptionally(e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                outputFormat = format
                if (output != null) {
                    muxer = MediaMuxer(output.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                        track = it.addTrack(format)
                        it.start()
                    }
                }
            }
        }, thread.handler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }

    /** Ends the stream and waits for the last frame; the muxer is closed so the file is complete. */
    suspend fun finish() {
        codec.signalEndOfInputStream()
        done.await()
        codec.stop()
        codec.release()
        surface.release()
        muxer?.stop()
        muxer?.release()
        output?.close()
    }

    fun abort() {
        runCatching { codec.release() }
        runCatching { surface.release() }
        runCatching { muxer?.release() }
        runCatching { output?.close() }
    }

    /** Frame rate and the largest gap between frames, from the presentation times. */
    fun timing(): JsonObject = buildJsonObject {
        val t = timestampsUs.sorted()
        put("frames", t.size)
        if (t.size > 1) {
            put("fps", (t.size - 1) * 1_000_000.0 / (t.last() - t.first()))
            put("maxGapMs", t.zipWithNext { a, b -> b - a }.max() / 1000.0)
        }
    }
}
