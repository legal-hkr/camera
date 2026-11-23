package sh.hnet.camera.capabilities

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.view.Display
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The phone around the cameras: system version, the display (HDR viewfinder), microphones (stereo, unprocessed source) and motion sensors (level, horizon). */
class DeviceProbe(private val context: Context) {

    fun device(): JsonObject = buildJsonObject {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("socManufacturer", Build.SOC_MANUFACTURER)
        put("socModel", Build.SOC_MODEL)
        put("release", Build.VERSION.RELEASE)
        put("sdk", Build.VERSION.SDK_INT)
        put("sdkFull", Build.VERSION.SDK_INT_FULL)
        put("securityPatch", Build.VERSION.SECURITY_PATCH)
        put("mediaPerformanceClass", Build.VERSION.MEDIA_PERFORMANCE_CLASS)
        put("fingerprint", Build.FINGERPRINT)
    }

    fun display(display: Display): JsonObject = buildJsonObject {
        put("hdr", display.isHdr)
        put("wideColorGamut", display.isWideColorGamut)
        put("preferredWideGamutColorSpace", display.preferredWideGamutColorSpace?.name)
        put("hdrSdrRatioAvailable", display.isHdrSdrRatioAvailable)
        if (display.isHdrSdrRatioAvailable) put("hdrSdrRatio", display.hdrSdrRatio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) put("highestHdrSdrRatio", display.highestHdrSdrRatio)
        display.hdrCapabilities?.let { hdr ->
            put("desiredMaxLuminance", hdr.desiredMaxLuminance)
            put("desiredMaxAverageLuminance", hdr.desiredMaxAverageLuminance)
            put("desiredMinLuminance", hdr.desiredMinLuminance)
        }
        put("mode", "${display.mode.physicalWidth}x${display.mode.physicalHeight}@${display.mode.refreshRate}")
        put("modeHdrTypes", jsonOf(display.mode.supportedHdrTypes.map { hdrType(it) }))
        put("modes", jsonOf(display.supportedModes.map { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" }.distinct()))
    }

    private fun hdrType(type: Int) = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DOLBY_VISION"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10_PLUS"
        else -> type.toString()
    }

    fun audio(): JsonObject = buildJsonObject {
        val audio = context.getSystemService(AudioManager::class.java)
        put("unprocessedSource", audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))
        put("micNearUltrasound", audio.getProperty(AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND))
        put("microphones", probe {
            buildJsonArray {
                for (mic in audio.microphones) add(buildJsonObject {
                    put("description", mic.description)
                    put("id", mic.id)
                    put("type", mic.type)
                    put("location", mic.location)
                    put("group", mic.group)
                    put("directionality", mic.directionality)
                    put("position", "${mic.position.x}, ${mic.position.y}, ${mic.position.z}")
                    put("orientation", "${mic.orientation.x}, ${mic.orientation.y}, ${mic.orientation.z}")
                    put("sensitivity", mic.sensitivity)
                    put("maxSpl", mic.maxSpl)
                    put("channelMapping", jsonOf(mic.channelMapping.map { "${it.first}:${it.second}" }))
                })
            }
        })
        put("inputs", buildJsonArray {
            for (device in audio.getDevices(AudioManager.GET_DEVICES_INPUTS)) add(buildJsonObject {
                put("type", device.type)
                put("builtIn", device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC)
                put("address", device.address)
                put("channelCounts", jsonOf(device.channelCounts))
                put("channelMasks", jsonOf(device.channelMasks))
                put("sampleRates", jsonOf(device.sampleRates))
                put("encodings", jsonOf(device.encodings))
            })
        })
    }

    fun sensors(): JsonObject = buildJsonObject {
        val manager = context.getSystemService(SensorManager::class.java)
        val types = listOf(
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY, Sensor.TYPE_HINGE_ANGLE,
        )
        for (type in types) {
            val sensor = manager.getDefaultSensor(type) ?: continue
            put(sensor.stringType, "${sensor.name} (${sensor.vendor}, min delay: ${sensor.minDelay} µs, fifo: ${sensor.fifoMaxEventCount})")
        }
    }
}
