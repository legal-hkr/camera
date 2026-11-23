package sh.hnet.camera.camera

import android.media.Image

/**
 * One frame of the analysis stream (QR codes, the finder glass), landscape as the sensor sees it: YUV 4:2:0 in 8 bits, or P010 (10 bits in the high bits of
 * 16-bit words, chroma interleaved) next to an HLG preview. Valid only during the analyzer's call.
 */
class AnalysisFrame(val image: Image, val tenBit: Boolean, val sensorOrientation: Int, val front: Boolean)
