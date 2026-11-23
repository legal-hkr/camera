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
}
