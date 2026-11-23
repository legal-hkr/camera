package sh.hnet.camera.capabilities

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Camera2 extensions (Night, HDR, Bokeh, Auto, Face retouch) per logical camera: sizes per format, postview, progress, latency, the request and result keys they accept. */
class ExtensionProbe(private val manager: CameraManager) {

    fun run(): JsonObject = buildJsonObject {
        for (id in manager.cameraIdList) {
            put(id, probe {
                val ext = manager.getCameraExtensionCharacteristics(id)
                buildJsonObject {
                    put("supported", jsonOf(ext.supportedExtensions.map { Names.extension(it) }))
                    for (extension in ext.supportedExtensions) {
                        put(Names.extension(extension), probe { describe(ext, extension) })
                    }
                }
            })
        }
    }

    private fun describe(ext: CameraExtensionCharacteristics, extension: Int): JsonObject = buildJsonObject {
        val formats = listOfNotNull(ImageFormat.JPEG, ImageFormat.JPEG_R, ImageFormat.YUV_420_888, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) ImageFormat.HEIC_ULTRAHDR else null)
        put("previewSizes", probe { jsonOf(ext.getExtensionSupportedSizes(extension, SurfaceTexture::class.java).sortedByDescending { area(it) }.map { sizeText(it) }) })
        put("captureSizes", buildJsonObject {
            for (format in formats) {
                val sizes = runCatching { ext.getExtensionSupportedSizes(extension, format) }.getOrNull().orEmpty()
                if (sizes.isNotEmpty()) put(Names.format(format), jsonOf(sizes.sortedByDescending { area(it) }.map { sizeText(it) }))
            }
        })
        val jpeg = ext.getExtensionSupportedSizes(extension, ImageFormat.JPEG).maxByOrNull { area(it) }
        put("postview", ext.isPostviewAvailable(extension))
        if (jpeg != null) {
            put("latencyMs", probe { jsonOf(ext.getEstimatedCaptureLatencyRangeMillis(extension, jpeg, ImageFormat.JPEG)) })
            if (ext.isPostviewAvailable(extension)) {
                put("postviewSizes", probe { jsonOf(ext.getPostviewSupportedSizes(extension, jpeg, ImageFormat.JPEG).map { sizeText(it) }) })
            }
        }
        put("captureProgress", ext.isCaptureProcessProgressAvailable(extension))
        put("requestKeys", jsonOf(ext.getAvailableCaptureRequestKeys(extension).map { it.name }.sorted()))
        put("resultKeys", jsonOf(ext.getAvailableCaptureResultKeys(extension).map { it.name }.sorted()))
        // Per-extension characteristics are Android 15's
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) put("characteristics", probe {
            buildJsonObject { for (key in ext.getKeys(extension).sortedBy { it.name }) put(key.name, probe { jsonOf(ext.get(extension, key)) }) }
        })
    }
}
