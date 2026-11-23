package sh.hnet.camera.capabilities

import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.params.ColorSpaceProfiles
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MandatoryStreamCombination
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Turns a value read from the platform (camera metadata, codec capabilities) into JSON; types without a known shape fall back to toString(). */
fun jsonOf(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
    is LongArray -> JsonArray(value.map { JsonPrimitive(it) })
    is FloatArray -> JsonArray(value.map { JsonPrimitive(it) })
    is DoubleArray -> JsonArray(value.map { JsonPrimitive(it) })
    is BooleanArray -> JsonArray(value.map { JsonPrimitive(it) })
    is ByteArray -> JsonArray(value.map { JsonPrimitive(it) })
    is Array<*> -> JsonArray(value.map { jsonOf(it) })
    is Collection<*> -> JsonArray(value.map { jsonOf(it) })
    is Range<*> -> buildJsonArray { add(jsonOf(value.lower)); add(jsonOf(value.upper)) }
    is Size -> JsonPrimitive(sizeText(value))
    is SizeF -> JsonPrimitive("${value.width}x${value.height}")
    is Rational -> JsonPrimitive("${value.numerator}/${value.denominator}")
    is Rect -> buildJsonArray { add(value.left); add(value.top); add(value.right); add(value.bottom) }
    is Point -> buildJsonArray { add(value.x); add(value.y) }
    is StreamConfigurationMap -> streamConfigurationJson(value)
    is DynamicRangeProfiles -> dynamicRangeJson(value)
    is ColorSpaceProfiles -> colorSpaceJson(value)
    is MandatoryStreamCombination -> mandatoryCombinationJson(value)
    else -> JsonPrimitive(value.toString())
}

private fun JsonArrayBuilder.add(value: Int) = add(JsonPrimitive(value))

fun sizeText(size: Size) = "${size.width}x${size.height}"

fun area(size: Size) = size.width.toLong() * size.height

/** Every output format with its sizes (largest first), each with its minimum frame duration and stall in milliseconds; high-resolution sizes (slower than 20 fps) listed apart. */
fun streamConfigurationJson(map: StreamConfigurationMap): JsonObject = buildJsonObject {
    put("outputs", buildJsonObject {
        for (format in map.outputFormats.sorted()) {
            putJsonArray(Names.format(format)) {
                val sizes = (map.getOutputSizes(format) ?: emptyArray()).sortedByDescending { area(it) }
                for (size in sizes) add(sizeTiming(map, format, size))
            }
            val high = map.getHighResolutionOutputSizes(format)
            if (!high.isNullOrEmpty()) {
                putJsonArray(Names.format(format) + " (high resolution)") {
                    for (size in high.sortedByDescending { area(it) }) add(sizeTiming(map, format, size))
                }
            }
        }
    })
    put("inputs", buildJsonObject {
        for (format in map.inputFormats.sorted()) {
            put(Names.format(format), jsonOf(map.getInputSizes(format)?.map { sizeText(it) }))
            put(Names.format(format) + " reprocess outputs", jsonOf(map.getValidOutputFormatsForInput(format).map { Names.format(it) }))
        }
    })
    put("highSpeed", buildJsonArray {
        for (size in map.highSpeedVideoSizes.sortedByDescending { area(it) }) {
            add(buildJsonObject {
                put("size", sizeText(size))
                put("fpsRanges", jsonOf(map.getHighSpeedVideoFpsRangesFor(size).map { "${it.lower}-${it.upper}" }))
            })
        }
    })
}

private fun sizeTiming(map: StreamConfigurationMap, format: Int, size: Size): JsonObject = buildJsonObject {
    put("size", sizeText(size))
    put("minFrameMs", map.getOutputMinFrameDuration(format, size) / 1_000_000.0)
    put("stallMs", map.getOutputStallDuration(format, size) / 1_000_000.0)
}

fun dynamicRangeJson(profiles: DynamicRangeProfiles): JsonObject = buildJsonObject {
    for (profile in profiles.supportedProfiles.sorted()) {
        put(Names.dynamicRange(profile), buildJsonObject {
            put("concurrentWith", jsonOf(profiles.getProfileCaptureRequestConstraints(profile).sorted().map { Names.dynamicRange(it) }))
            put("extraLatency", profiles.isExtraLatencyPresent(profile))
        })
    }
}

fun colorSpaceJson(profiles: ColorSpaceProfiles): JsonObject = buildJsonObject {
    // UNKNOWN: color spaces of every format
    for (colorSpace in profiles.getSupportedColorSpaces(ImageFormat.UNKNOWN)) {
        put(colorSpace.name, buildJsonObject {
            for (format in profiles.getSupportedImageFormatsForColorSpace(colorSpace)) {
                put(Names.format(format), jsonOf(profiles.getSupportedDynamicRangeProfiles(colorSpace, format).map { Names.dynamicRange(it) }))
            }
        })
    }
}

fun mandatoryCombinationJson(combination: MandatoryStreamCombination): JsonObject = buildJsonObject {
    put("description", combination.description.toString())
    put("reprocessable", combination.isReprocessable)
    put("streams", buildJsonArray {
        for (stream in combination.streamsInformation) {
            add(JsonPrimitive(buildString {
                append(Names.format(stream.format)).append(' ')
                append(stream.availableSizes.maxByOrNull { area(it) }?.let { sizeText(it) } ?: "?")
                if (stream.isInput) append(" input")
                if (stream.isMaximumSize) append(" max")
                if (stream.is10BitCapable) append(" 10-bit")
                if (stream.isUltraHighResolution) append(" ultra-high-resolution")
                if (stream.streamUseCase != 0L) append(" use:").append(Names.streamUseCase(stream.streamUseCase))
            }))
        }
    })
}

/** Runs a probe step; a failure becomes {"error": "..."} in the report instead of ending the whole run. */
inline fun probe(block: () -> JsonElement): JsonElement = try {
    block()
} catch (e: Exception) {
    buildJsonObject { put("error", "${e.javaClass.simpleName}: ${e.message}") }
}
