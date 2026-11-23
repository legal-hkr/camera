package sh.hnet.camera.capabilities.model

/** The picture's dynamic range of the preview and the streams that go with it in one request (Pixel 9 Pro: HLG10 only with HLG10, STANDARD only with STANDARD). */
enum class PictureRange { HLG, SDR }

enum class StillFormat { JPEG_R, JPEG }

/**
 * One photo session (LOGIKA §2.1): preview, the still, and optionally the motion photo's video stream (in the preview's size and range) and the small analysis stream
 * (P010 next to an HLG preview, YUV next to an SDR one).
 */
data class PhotoSession(val range: PictureRange, val still: StillFormat, val motion: Boolean, val analysis: Boolean)

/** A video session: preview and the video stream in one range, at one size and frame rate, with a still stream for photos while recording. */
data class VideoSession(val range: PictureRange, val size: PixelSize, val fps: Int, val still: StillFormat?)

/** What the user chose for video (Settings › Video, the top row's "4K 30"): the session falls back from it when the camera or the encoder can't. */
data class VideoChoice(val uhd: Boolean = true, val fps: Int = 30, val hdr: Boolean = true)

/** The encoder's side: which sizes, rates and HDR it can take (HEVC Main10 for HLG). */
fun interface EncoderLimits {
    fun canEncode(size: PixelSize, fps: Int, hdr: Boolean): Boolean
}
object SessionLadder {
    /**
     * The photo sessions to try, best first; the first one the camera confirms is used. Analysis goes first (the finder glass falls back to plain translucency,
     * QR codes stop), then the HDR preview, then Ultra HDR. [wantMotion] and [wantAnalysis] leave out what the current settings don't need at all.
     */
    fun photo(hlg: Boolean, jpegR: Boolean, wantMotion: Boolean, wantAnalysis: Boolean, wantUltraHdr: Boolean = true): List<PhotoSession> = buildList {
        val ranges = if (hlg) listOf(PictureRange.HLG, PictureRange.SDR) else listOf(PictureRange.SDR)
        val stills = if (jpegR && wantUltraHdr) listOf(StillFormat.JPEG_R, StillFormat.JPEG) else listOf(StillFormat.JPEG)
        for (still in stills) for (range in ranges) {
            if (wantAnalysis) add(PhotoSession(range, still, wantMotion, analysis = true))
            add(PhotoSession(range, still, wantMotion, analysis = false))
            // Motion photos go last before the next range: a photo without its short video beats no HDR at all
            if (wantMotion) add(PhotoSession(range, still, motion = false, analysis = false))
        }
    }.distinct()

    val UHD = PixelSize(3840, 2160)
    val FHD = PixelSize(1920, 1080)

    /** HLG only up to 30 fps (LOGIKA §6.2; the Pixel refuses 60 fps HLG sessions). */
    const val HLG_MAX_FPS = 30

    /**
     * The video sessions to try, best first: the chosen size, rate and HDR, then without the photo stream's Ultra HDR, without a photo stream, at 1080p, and only
     * then without HDR (HDR matters more than the size) – each as far as the camera has the size and rate and the encoder can take it.
     */
    fun video(choice: VideoChoice, hlg: Boolean, jpegR: Boolean, sizes: List<PixelSize>, fixedFps: Set<Int>, encoder: EncoderLimits): List<VideoSession> = buildList {
        val wanted = if (choice.uhd) listOf(UHD, FHD) else listOf(FHD)
        val rates = listOf(choice.fps, 30).distinct().filter { it in fixedFps || it == 30 }
        for (fps in rates) {
            val ranges = if (hlg && choice.hdr && fps <= HLG_MAX_FPS) listOf(PictureRange.HLG, PictureRange.SDR) else listOf(PictureRange.SDR)
            for (range in ranges) for (size in wanted.filter { it in sizes }) {
                if (!encoder.canEncode(size, fps, range == PictureRange.HLG)) continue
                if (jpegR) add(VideoSession(range, size, fps, StillFormat.JPEG_R))
                add(VideoSession(range, size, fps, StillFormat.JPEG))
                add(VideoSession(range, size, fps, null))
            }
        }
    }.distinct()
}
