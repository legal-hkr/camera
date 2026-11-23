package sh.hnet.camera.diag

import android.content.Context
import androidx.camera.camera2.interop.cameraId
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.UseCase
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionSessionConfig
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapabilities
import androidx.camera.video.VideoCapture
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sh.hnet.camera.capabilities.jsonOf
import sh.hnet.camera.capabilities.probe

/**
 * CameraX's view of the same cameras: dynamic ranges and video qualities as its Recorder computes them, image formats (Ultra HDR, RAW), its own extensions (the
 * vendor library may differ from Camera2's), and which feature groups (HLG, 60 fps, preview stabilization, Ultra HDR) it can bind together.
 */
class CameraXProbe(private val context: Context) {

    private val qualities = listOf(Quality.UHD to "UHD", Quality.FHD to "FHD", Quality.HD to "HD", Quality.SD to "SD")
    private val modes = listOf(ExtensionMode.AUTO to "AUTO", ExtensionMode.NIGHT to "NIGHT", ExtensionMode.HDR to "HDR", ExtensionMode.BOKEH to "BOKEH", ExtensionMode.FACE_RETOUCH to "FACE_RETOUCH")
    private val features = listOf(
        GroupableFeature.HDR_HLG10 to "HDR_HLG10", GroupableFeature.FPS_60 to "FPS_60",
        GroupableFeature.PREVIEW_STABILIZATION to "PREVIEW_STABILIZATION", GroupableFeature.IMAGE_ULTRA_HDR to "IMAGE_ULTRA_HDR",
    )

    suspend fun run(): JsonObject {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val extensions = runCatching { ExtensionsManager.getInstance(context, provider) }.getOrNull()
        return buildJsonObject {
            for (info in provider.availableCameraInfos) {
                val id = runCatching { info.cameraId }.getOrDefault("?")
                put(id, probe { camera(provider, extensions, info) })
            }
        }
    }

    private fun camera(provider: ProcessCameraProvider, extensions: ExtensionsManager?, info: CameraInfo): JsonObject = buildJsonObject {
        put("lensFacing", info.lensFacing)
        put("intrinsicZoomRatio", info.intrinsicZoomRatio)
        put("logicalMultiCamera", info.isLogicalMultiCameraSupported)
        put("physicalCameras", jsonOf(info.physicalCameraInfos.map { runCatching { it.cameraId }.getOrDefault("?") }))
        put("frameRateRanges", jsonOf(info.supportedFrameRateRanges.map { "${it.lower}-${it.upper}" }))
        put("dynamicRanges", jsonOf(info.querySupportedDynamicRanges(setOf(DynamicRange.UNSPECIFIED)).map { it.toString() }))
        put("lowLightBoost", info.isLowLightBoostSupported)
        put("nightModeIndicator", info.isNightModeIndicatorSupported)
        put("torchStrength", info.isTorchStrengthSupported)
        put("maxTorchStrength", info.maxTorchStrengthLevel)
        put("exposureCompensation", "${info.exposureState.exposureCompensationRange} step ${info.exposureState.exposureCompensationStep}")
        put("imageCapture", probe {
            val caps = ImageCapture.getImageCaptureCapabilities(info)
            buildJsonObject {
                put("outputFormats", jsonOf(caps.supportedOutputFormats.map { outputFormat(it) }))
                put("postview", caps.isPostviewSupported)
                put("captureProgress", caps.isCaptureProcessProgressSupported)
            }
        })
        put("video", probe { video(Recorder.getVideoCapabilities(info)) })
        put("highSpeedVideo", probe { Recorder.getHighSpeedVideoCapabilities(info)?.let { video(it) } ?: jsonOf(null) })
        put("featureGroups", probe { featureGroups(info) })
        if (extensions != null) put("extensions", probe { extensions(provider, extensions, info) })
    }

    private fun outputFormat(format: Int) = when (format) {
        ImageCapture.OUTPUT_FORMAT_JPEG -> "JPEG"
        ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR -> "JPEG_ULTRA_HDR"
        ImageCapture.OUTPUT_FORMAT_RAW -> "RAW"
        ImageCapture.OUTPUT_FORMAT_RAW_JPEG -> "RAW_JPEG"
        else -> format.toString()
    }

    private fun video(caps: VideoCapabilities): JsonObject = buildJsonObject {
        put("stabilization", caps.isStabilizationSupported)
        for (range in caps.supportedDynamicRanges) {
            put(range.toString(), jsonOf(caps.getSupportedQualities(range).map { quality ->
                qualities.firstOrNull { it.first == quality }?.second ?: quality.toString()
            }))
        }
    }

    /** Each feature alone, each pair, and all four, with Preview + ImageCapture + VideoCapture as a camera app would bind them. */
    private fun featureGroups(info: CameraInfo): JsonObject = buildJsonObject {
        val groups = buildList {
            features.forEach { add(listOf(it)) }
            for (i in features.indices) for (j in i + 1 until features.size) add(listOf(features[i], features[j]))
            add(features)
        }
        for (group in groups) {
            val name = group.joinToString(" + ") { it.second }
            put(name, probe {
                val useCases: List<UseCase> = listOf(Preview.Builder().build(), ImageCapture.Builder().build(), VideoCapture.withOutput(Recorder.Builder().build()))
                val config = SessionConfig.Builder(useCases).setRequiredFeatureGroup(*group.map { it.first }.toTypedArray()).build()
                jsonOf(info.isSessionConfigSupported(config))
            })
        }
    }

    private fun extensions(provider: ProcessCameraProvider, manager: ExtensionsManager, info: CameraInfo): JsonObject = buildJsonObject {
        val selector = info.cameraSelector
        for ((mode, name) in modes) {
            put(name, probe {
                if (!manager.isExtensionAvailable(selector, mode)) return@probe jsonOf(false)
                val extensionInfo = provider.getCameraInfo(selector, ExtensionSessionConfig(mode, manager))
                val caps = ImageCapture.getImageCaptureCapabilities(extensionInfo)
                buildJsonObject {
                    put("available", true)
                    put("latencyMs", jsonOf(manager.getEstimatedCaptureLatencyRange(selector, mode)))
                    put("imageAnalysis", manager.isImageAnalysisSupported(selector, mode))
                    put("outputFormats", jsonOf(caps.supportedOutputFormats.map { outputFormat(it) }))
                    put("postview", caps.isPostviewSupported)
                    put("captureProgress", caps.isCaptureProcessProgressSupported)
                }
            })
        }
    }
}
