package sh.hnet.camera.capabilities

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Video and audio encoders a camera app can record with: profiles (10-bit, HDR), sizes and frame rates they keep up with, bitrate modes, features, how many can run at once. */
class CodecProbe {

    private val videoTypes = listOfNotNull(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_AV1, MediaFormat.MIMETYPE_VIDEO_VP9, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) MediaFormat.MIMETYPE_VIDEO_APV else null)
    private val audioTypes = listOf(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_OPUS, MediaFormat.MIMETYPE_AUDIO_FLAC, MediaFormat.MIMETYPE_AUDIO_AMR_WB)

    /** Sizes and rates a camera app would record at; areSizeAndRateSupported answers from the codec's declared limits. */
    private val targets = listOf(
        Triple(3840, 2160, 24.0), Triple(3840, 2160, 30.0), Triple(3840, 2160, 60.0),
        Triple(1920, 1080, 30.0), Triple(1920, 1080, 60.0), Triple(1920, 1080, 120.0), Triple(1920, 1080, 240.0),
        Triple(1280, 720, 240.0), Triple(1280, 720, 480.0), Triple(7680, 4320, 30.0),
    )

    private val features = buildList {
        addAll(listOf(CodecCapabilities.FEATURE_HdrEditing, CodecCapabilities.FEATURE_IntraRefresh, CodecCapabilities.FEATURE_QpBounds, CodecCapabilities.FEATURE_EncodingStatistics, CodecCapabilities.FEATURE_MultipleFrames))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) addAll(listOf(CodecCapabilities.FEATURE_HlgEditing, CodecCapabilities.FEATURE_Roi, CodecCapabilities.FEATURE_DynamicColorAspects, CodecCapabilities.FEATURE_DetachedSurface))
    }

    fun run(): JsonObject = buildJsonObject {
        val encoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { it.isEncoder }
        for (type in videoTypes + audioTypes) {
            val list = encoders.filter { info -> info.supportedTypes.any { it.equals(type, ignoreCase = true) } }
            put(type, buildJsonArray { for (info in list) add(probe { describe(info, type) }) })
        }
    }

    private fun describe(info: MediaCodecInfo, type: String): JsonObject = buildJsonObject {
        val caps = info.getCapabilitiesForType(type)
        put("name", info.name)
        put("canonicalName", info.canonicalName)
        put("hardware", info.isHardwareAccelerated)
        put("softwareOnly", info.isSoftwareOnly)
        put("vendor", info.isVendor)
        put("maxInstances", caps.maxSupportedInstances)
        put("profiles", jsonOf(caps.profileLevels.map { Names.codecProfile(type, it.profile) }.distinct()))
        put("features", jsonOf(features.filter { caps.isFeatureSupported(it) }))
        caps.encoderCapabilities?.let { enc ->
            put("bitrateModes", jsonOf(listOf(
                EncoderCapabilities.BITRATE_MODE_CQ to "CQ", EncoderCapabilities.BITRATE_MODE_VBR to "VBR",
                EncoderCapabilities.BITRATE_MODE_CBR to "CBR", EncoderCapabilities.BITRATE_MODE_CBR_FD to "CBR_FD",
            ).filter { enc.isBitrateModeSupported(it.first) }.map { it.second }))
            put("qualityRange", jsonOf(enc.qualityRange))
            put("complexityRange", jsonOf(enc.complexityRange))
        }
        caps.videoCapabilities?.let { video ->
            put("widths", jsonOf(video.supportedWidths))
            put("heights", jsonOf(video.supportedHeights))
            put("bitrateRange", jsonOf(video.bitrateRange))
            put("sizeAndRate", buildJsonObject {
                for ((w, h, fps) in targets) put("${w}x$h@${fps.toInt()}", video.areSizeAndRateSupported(w, h, fps))
            })
            put("performancePoints", jsonOf(video.supportedPerformancePoints?.map { it.toString() }))
        }
        caps.audioCapabilities?.let { audio ->
            put("sampleRates", jsonOf(audio.supportedSampleRates))
            put("maxChannels", audio.maxInputChannelCount)
            put("bitrateRange", jsonOf(audio.bitrateRange))
        }
    }
}
