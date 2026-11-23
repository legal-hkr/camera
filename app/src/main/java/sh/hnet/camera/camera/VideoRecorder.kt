package sh.hnet.camera.camera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.camera.capabilities.model.EncoderLimits
import sh.hnet.camera.capabilities.model.PixelSize

/** How a video is encoded: size, rate, HLG or SDR, HEVC or AVC. */
data class VideoFormat(val size: PixelSize, val fps: Int, val hdr: Boolean, val hevc: Boolean) {
    val mime: String get() = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    /** LOGIKA §6.2: 4K30 HEVC about 50 Mb/s, 1080p30 about 16 Mb/s, more for 60 fps, AVC 40 % more. */
    val bitrate: Int
        get() {
            val base = if (size.area >= SessionLimits.UHD_AREA) 50_000_000 else 16_000_000
            val rate = base * fps / 30
            return if (hevc) rate else rate * 7 / 5
        }
}

private object SessionLimits {
    const val UHD_AREA = 3840L * 2160
}

/** What the phone's encoders can do, from the system's codec list. */
object Encoders {
    private val codecs by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder } }

    fun hevc(): Boolean = codecs.any { MediaFormat.MIMETYPE_VIDEO_HEVC in it.supportedTypes }

    /** HLG needs HEVC Main10; SDR takes HEVC, or AVC where HEVC is missing. */
    fun limits(hevc: Boolean): EncoderLimits = EncoderLimits { size, fps, hdr ->
        val mime = if (hevc || hdr) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        codecs.any { info ->
            mime in info.supportedTypes && runCatching {
                val caps = info.getCapabilitiesForType(mime)
                val sizeOk = caps.videoCapabilities?.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) == true
                val profileOk = !hdr || caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
                sizeOk && profileOk
            }.getOrDefault(false)
        }
    }
}

/** Where a recording goes: an open file of a pending MediaStore entry, published or removed by [close]. */
class VideoTarget(val file: ParcelFileDescriptor, val close: suspend (saved: Boolean) -> Unit)

/**
 * The video stream of a session and its recordings (LOGIKA §6.2). The camera writes into a persistent input surface that outlives the encoders, so a recording
 * starts and stops without a new session: each recording gets a fresh encoder on the same surface, prepared ahead (the surface takes one encoder at a time).
 * The encoder's callbacks and the file's writing run on a thread of their own, not the camera's. Fields other than the relay are touched on the camera thread.
 */
class VideoRecorder(val format: VideoFormat, private val audioManager: AudioManager, private val scope: CoroutineScope) {
    private val thread = HandlerThread("camera-video").apply { start() }
    private val handler = Handler(thread.looper)
    val surface: Surface = MediaCodec.createPersistentInputSurface()
    private var prepared: Prepared? = null
    private var take: Take? = null
    /** The session is going: no encoder is prepared any more. */
    private var retired = false

    init {
        try {
            prepared = prepare()
        } catch (e: Exception) {
            surface.release()
            thread.quitSafely()
            throw e
        }
    }

    val recording: Boolean get() = take != null
    val paused: Boolean get() = take?.timeline?.paused == true

    /** The sound is silenced by the system (another app has the microphone, or it is turned off in Quick Settings). */
    @Volatile
    var silenced = false
        private set

    /** An encoder configured on the surface, not started; its callback is set before configuring (asynchronous mode wants that) and handed to a recording later. */
    private class Prepared(val codec: MediaCodec, val relay: Relay)

    private class Relay : MediaCodec.Callback() {
        @Volatile
        var to: MediaCodec.Callback? = null

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            to?.onOutputBufferAvailable(codec, index, info) ?: codec.releaseOutputBuffer(index, false)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            to?.onError(codec, e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            to?.onOutputFormatChanged(codec, format)
        }
    }

    private fun prepare(): Prepared {
        val mediaFormat = MediaFormat.createVideoFormat(format.mime, format.size.width, format.size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, format.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, format.fps)
            setInteger(MediaFormat.KEY_OPERATING_RATE, format.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (format.hdr) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            } else if (format.hevc) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            }
        }
        // The encoder that takes this format (the default one for the type may not), the bitrate within what it can do
        val name = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(mediaFormat) }.getOrNull()
        val codec = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(format.mime)
        try {
            codec.codecInfo.getCapabilitiesForType(format.mime).videoCapabilities?.bitrateRange?.let { mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, it.clamp(format.bitrate)) }
            val relay = Relay()
            codec.setCallback(relay, handler)
            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.setInputSurface(surface)
            return Prepared(codec, relay)
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    /**
     * Starts recording into [target] (the caller closes it when this returns false). Picture and sound share one clock, the monotonic one: the camera stamps
     * frames for an encoder's surface in it (the output's timestamp base is set to it), and the sound is stamped in it too.
     */
    fun start(target: VideoTarget, orientation: Int, location: Pair<Double, Double>?, audio: Boolean): Boolean {
        if (retired || take != null) return false
        val ready = prepared ?: runCatching { prepare() }.getOrNull() ?: return false
        prepared = null
        val codec = ready.codec
        try {
            val muxer = Mux(target, orientation, location, expectAudio = audio)
            val video = CompletableDeferred<Unit>()
            val timeline = PauseTimeline()
            ready.relay.to = object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0) {
                        val time = timeline.map(info.presentationTimeUs * 1000)
                        if (time != null) codec.getOutputBuffer(index)?.let { muxer.write(Track.VIDEO, it, info, time / 1000) }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) video.complete(Unit)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "Video encoder error: ${e.diagnosticInfo}")
                    video.complete(Unit)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = muxer.add(Track.VIDEO, format)
            }
            codec.start()
            val sound = if (audio) runCatching { AudioCapture(timeline, muxer) }.onFailure { Log.w(TAG, "No sound: ${it.javaClass.simpleName}") }.getOrNull() else null
            if (sound == null) muxer.withoutAudio()
            silenced = false
            sound?.let { watchSilence(it) }
            take = Take(codec, muxer, video, timeline, sound)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Recording didn't start: ${e.javaClass.simpleName}")
            runCatching { codec.release() }
            if (!retired) prepared = runCatching { prepare() }.getOrNull()
            return false
        }
    }

    /** The clock of the camera's frames on the encoder's surface (CLOCK_MONOTONIC). */
    private fun clock() = System.nanoTime()

    fun pause() {
        val take = take ?: return
        take.timeline.pause(clock())
    }

    fun resume() {
        val take = take ?: return
        take.timeline.resume(clock())
    }

    /** Ends the recording: the last frames and sound are written, the file closed and published (or removed when it holds nothing). Not cancellable once begun. */
    suspend fun stop(): Boolean = withContext(NonCancellable) {
        val take = take ?: return@withContext false
        this@VideoRecorder.take = null
        stopWatching()
        runCatching { take.codec.signalEndOfInputStream() }
        withTimeoutOrNull(FINISH_TIMEOUT_MS) { take.video.await() }
        take.sound?.stop()
        runCatching { take.codec.stop() }
        runCatching { take.codec.release() }
        if (!retired) prepared = runCatching { prepare() }.getOrNull()
        val saved = take.muxer.finish()
        take.muxer.target.close(saved)
        saved
    }

    /** The session is closing: the prepared encoder goes now (a new session's may need the hardware at once), and no other is prepared. */
    fun retire() {
        retired = true
        runCatching { prepared?.codec?.release() }
        prepared = null
    }

    /** The session has closed: the surface and the thread go; a recording somehow still running is ended and saved first. */
    fun release() {
        retire()
        if (take != null) {
            scope.launch {
                stop()
                releaseSurface()
            }
        } else {
            releaseSurface()
        }
    }

    private fun releaseSurface() {
        runCatching { surface.release() }
        thread.quitSafely()
    }

    private var silenceWatch: AudioManager.AudioRecordingCallback? = null

    private fun watchSilence(sound: AudioCapture) {
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                silenced = configs.firstOrNull { it.clientAudioSessionId == sound.session }?.isClientSilenced == true
            }
        }
        silenceWatch = callback
        audioManager.registerAudioRecordingCallback(callback, handler)
    }

    private fun stopWatching() {
        silenceWatch?.let { audioManager.unregisterAudioRecordingCallback(it) }
        silenceWatch = null
        silenced = false
    }

    private class Take(val codec: MediaCodec, val muxer: Mux, val video: CompletableDeferred<Unit>, val timeline: PauseTimeline, val sound: AudioCapture?)

    companion object {
        private const val TAG = "VideoRecorder"
        private const val FINISH_TIMEOUT_MS = 3_000L
    }
}

private enum class Track { VIDEO, AUDIO }

/**
 * The MP4 of one recording. It starts once the video's format (and the sound's, when there is sound) is known; samples that come before wait, and sound from
 * before the first frame is dropped, so picture and sound start together. When the sound gives no format within a second of the picture, the file goes on
 * without sound rather than losing the picture. Written from the video thread (picture) and the sound's thread.
 */
private class Mux(val target: VideoTarget, orientation: Int, location: Pair<Double, Double>?, expectAudio: Boolean) {
    private val muxer = MediaMuxer(target.file.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
        setOrientationHint(orientation)
        location?.let { setLocation(it.first.toFloat(), it.second.toFloat()) }
    }
    private var expectAudio = expectAudio
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var firstVideoUs: Long? = null
    private var videoWaitingSince = 0L
    private var written = 0
    private val lastUs = mutableMapOf(Track.VIDEO to -1L, Track.AUDIO to -1L)
    private val waiting = mutableListOf<Triple<Track, ByteArray, Pair<Long, Int>>>()
    private var waitingBytes = 0L

    @Synchronized
    fun add(track: Track, format: MediaFormat) {
        if (started) return
        when (track) {
            Track.VIDEO -> if (videoTrack < 0) videoTrack = muxer.addTrack(format)
            Track.AUDIO -> if (audioTrack < 0 && expectAudio) audioTrack = muxer.addTrack(format)
        }
        startIfReady()
    }

    /** No sound: the file is made without it. */
    @Synchronized
    fun withoutAudio() {
        expectAudio = false
        startIfReady()
    }

    private fun startIfReady() {
        if (started || videoTrack < 0 || (expectAudio && audioTrack < 0)) return
        muxer.start()
        started = true
    }

    @Synchronized
    fun write(track: Track, buffer: ByteBuffer, info: MediaCodec.BufferInfo, timeUs: Long) {
        if (track == Track.VIDEO && firstVideoUs == null) firstVideoUs = timeUs
        if (!started && track == Track.VIDEO && expectAudio && audioTrack < 0) {
            val now = SystemClock.elapsedRealtime()
            if (videoWaitingSince == 0L) videoWaitingSince = now
            else if (now - videoWaitingSince > AUDIO_WAIT_MS) {
                Log.w(TAG, "No sound format: recording without sound")
                expectAudio = false
                startIfReady()
            }
        }
        if (!started || firstVideoUs == null) {
            // Kept until the file starts (a fraction of a second), within a limit; sound before the first frame isn't kept
            if (waitingBytes + info.size <= MAX_WAITING_BYTES) {
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes, 0, info.size)
                waiting += Triple(track, bytes, timeUs to info.flags)
                waitingBytes += info.size
            }
            return
        }
        flush()
        put(track, buffer, info.offset, info.size, timeUs, info.flags)
    }

    private fun flush() {
        if (waiting.isEmpty()) return
        for ((track, bytes, meta) in waiting) put(track, ByteBuffer.wrap(bytes), 0, bytes.size, meta.first, meta.second)
        waiting.clear()
        waitingBytes = 0
    }

    private fun put(track: Track, buffer: ByteBuffer, offset: Int, size: Int, timeUs: Long, flags: Int) {
        val index = if (track == Track.VIDEO) videoTrack else audioTrack
        if (index < 0) return
        val time = if (track == Track.AUDIO) audioTime(timeUs) else timeUs
        if (track == Track.AUDIO && time < (firstVideoUs ?: 0)) return
        // Each track's times only go forward (the encoders make no B-frames)
        if (time <= lastUs.getValue(track)) return
        lastUs[track] = time
        val info = MediaCodec.BufferInfo().apply { set(offset, size, time, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) }
        runCatching { muxer.writeSampleData(index, buffer, info) }.onSuccess { if (track == Track.VIDEO) written++ }
    }

    private var audioShiftUs: Long? = null

    /**
     * A safety net for a phone whose camera stamps frames in another clock than the sound's: the first sound more than a few seconds away from the first frame
     * fixes a shift that brings it to the frame (a file whose tracks start hours apart plays only its first frame).
     */
    private fun audioTime(timeUs: Long): Long {
        val first = firstVideoUs ?: return timeUs
        val shift = audioShiftUs ?: (if (abs(timeUs - first) > CLOCK_GAP_US) first - timeUs else 0L).also {
            if (it != 0L) Log.w(TAG, "Sound in another clock than the picture: shifted")
            audioShiftUs = it
        }
        return timeUs + shift
    }

    /** Closes the file; false when no frame got into it (the file is then removed). */
    @Synchronized
    fun finish(): Boolean {
        var ok = false
        if (started) {
            flush()
            ok = written > 0 && runCatching { muxer.stop() }.isSuccess
        }
        // A muxer never started (or whose stop failed) must not be stopped again by release
        runCatching { muxer.release() }
        runCatching { target.file.close() }
        return ok
    }

    companion object {
        const val TAG = "Mux"
        const val AUDIO_WAIT_MS = 1_000L
        const val CLOCK_GAP_US = 3_000_000L
        const val MAX_WAITING_BYTES = 48L * 1024 * 1024
    }
}

/** Stereo sound at 48 kHz from the camcorder microphone setup, encoded to AAC on a thread of its own and timed in the picture's clock (monotonic). */
private class AudioCapture(private val timeline: PauseTimeline, private val muxer: Mux) {
    private val record: AudioRecord
    private val encoder: MediaCodec
    @Volatile
    private var running = true
    private val worker: Thread
    val session: Int

    init {
        val format = AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        // Half a second, so the thread waiting a moment on the file doesn't lose sound
        record = build(format, maxOf(minBuffer * 2, RATE * FRAME_BYTES / 2))
        session = record.audioSessionId
        encoder = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { codec ->
                try {
                    val aac = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES)
                    }
                    codec.configure(aac, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec.start()
                    record.startRecording()
                } catch (e: Exception) {
                    codec.release()
                    throw e
                }
            }
        } catch (e: Exception) {
            record.release()
            throw e
        }
        worker = thread(name = "camera-audio") {
            try {
                loop()
            } catch (e: Exception) {
                Log.w(TAG, "Sound stopped: ${e.javaClass.simpleName}")
            }
        }
    }

    // The engine records sound only after checking RECORD_AUDIO
    @SuppressLint("MissingPermission")
    private fun build(format: AudioFormat, buffer: Int) = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.CAMCORDER).setAudioFormat(format).setBufferSizeInBytes(buffer).build()

    /** The monotonic clock, as the camera's frames on the encoder's surface. */
    private fun now() = System.nanoTime()

    private fun loop() {
        val pcm = ByteArray(CHUNK_BYTES)
        val stamp = AudioTimestamp()
        val timebase = AudioTimestamp.TIMEBASE_MONOTONIC
        var frames = 0L
        val info = MediaCodec.BufferInfo()
        while (running) {
            val read = record.read(pcm, 0, pcm.size)
            if (read == AudioRecord.ERROR_DEAD_OBJECT) break
            if (read <= 0) {
                Thread.sleep(ERROR_PAUSE_MS)
                continue
            }
            // The chunk's first frame in the camera's clock, from the system's latest timestamp of a frame (read for every chunk, so drift and lost sound don't pile up)
            val start = if (record.getTimestamp(stamp, timebase) == AudioRecord.SUCCESS) stamp.nanoTime + (frames - stamp.framePosition) * 1_000_000_000L / RATE
            else now() - read / FRAME_BYTES * 1_000_000_000L / RATE
            frames += read / FRAME_BYTES
            val index = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                encoder.getInputBuffer(index)?.let { it.clear(); it.put(pcm, 0, read) }
                encoder.queueInputBuffer(index, 0, read, start / 1000, 0)
            }
            drain(info)
        }
        val index = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (index >= 0) encoder.queueInputBuffer(index, 0, 0, now() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(info, toEnd = true)
    }

    private fun drain(info: MediaCodec.BufferInfo, toEnd: Boolean = false) {
        var idle = 0
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (toEnd) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> muxer.add(Track.AUDIO, encoder.outputFormat)
                index >= 0 -> {
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0) {
                        val time = timeline.map(info.presentationTimeUs * 1000)
                        if (time != null) encoder.getOutputBuffer(index)?.let { muxer.write(Track.AUDIO, it, info, time / 1000) }
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> if (!toEnd || ++idle > END_TRIES) return
            }
        }
    }

    fun stop() {
        running = false
        // Stopping the recording also ends a read waiting for sound
        runCatching { record.stop() }
        worker.join(JOIN_MS)
        runCatching { record.release() }
        // A thread that didn't end in time may still use the encoder: it is left to the process rather than released under it
        if (!worker.isAlive) {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    companion object {
        const val TAG = "AudioCapture"
        const val RATE = 48_000
        const val FRAME_BYTES = 4
        /** 20 ms of stereo 16-bit sound. */
        const val CHUNK_BYTES = RATE / 50 * FRAME_BYTES
        const val BITRATE = 256_000
        const val TIMEOUT_US = 10_000L
        const val END_TRIES = 50
        const val JOIN_MS = 2_000L
        const val ERROR_PAUSE_MS = 10L
    }
}
