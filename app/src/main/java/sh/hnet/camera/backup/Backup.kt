package sh.hnet.camera.backup

import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import sh.hnet.camera.CameraSettings
import sh.hnet.camera.Grid
import sh.hnet.camera.MotionPhoto
import sh.hnet.camera.VolumeKeys
import sh.hnet.camera.capabilities.model.Aspect
import sh.hnet.camera.capabilities.model.ProExposure
import sh.hnet.camera.capabilities.model.WhiteBalance

/** Camera's backup file (LOGIKA §12), shaped as the family's: the settings only, never photos or videos. Pure Kotlin, unit-tested. */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String,
    val appVersionCode: Long,
    /** ISO 8601, UTC. */
    val createdAt: String,
    val settings: BackupSettings = BackupSettings(),
) {
    companion object {
        const val FORMAT = "sh.hnet.camera.backup"
        const val FORMAT_VERSION = 1
    }
}

/**
 * The remembered values (SPEC §17.5). Every one may be absent (a backup from before it existed) or unknown (written by a later version): a restore then leaves
 * the current value as it is (family rule). Values this phone can't use (60 fps, 4K) are kept: the session ladder falls back to what the phone has.
 */
@Serializable
data class BackupSettings(
    /** `FOUR_THREE`, `SIXTEEN_NINE`, `SQUARE`. */
    val aspect: String? = null,
    val ultraHdr: Boolean? = null,
    val hdrVideo: Boolean? = null,
    /** Restored only where the location access is given (else it would search forever). */
    val location: Boolean? = null,
    /** `OFF`, `THIRDS`, `QUARTERS`. */
    val grid: String? = null,
    val level: Boolean? = null,
    val videoUhd: Boolean? = null,
    val videoFps: Int? = null,
    val timerFlash: Boolean? = null,
    /** `SHUTTER`, `ZOOM`, `VOLUME`. */
    val volumeKeys: String? = null,
    val qrCodes: Boolean? = null,
    /** `OFF`, `AUTO`, `ON`. */
    val motionPhoto: String? = null,
    val mirror: Boolean? = null,
    val shutterSound: Boolean? = null,
    val videoHevc: Boolean? = null,
    val nightSuggestion: Boolean? = null,
    /** Pro's values set by hand; 0 is automatic. */
    val proIso: Int? = null,
    val proShutterNs: Long? = null,
    val proEvThirds: Int? = null,
    /** Diopters, −1 automatic (0 is infinity); kelvins, 0 automatic. */
    val proFocusDiopters: Double? = null,
    val proKelvin: Int? = null,
    val proHistogram: Boolean? = null,
    val proPeaking: Boolean? = null,
    /** The mode last chosen from "More" (`PRO`). */
    val moreMode: String? = null,
) {
    /** [current] with every value of this backup that this version knows. */
    fun applyTo(current: CameraSettings, locationAllowed: Boolean = true): CameraSettings = current.copy(
        aspect = aspect?.let { name -> Aspect.entries.firstOrNull { it.name == name } } ?: current.aspect,
        ultraHdr = ultraHdr ?: current.ultraHdr,
        hdrVideo = hdrVideo ?: current.hdrVideo,
        location = location?.let { it && locationAllowed } ?: current.location,
        grid = grid?.let { name -> Grid.entries.firstOrNull { it.name == name } } ?: current.grid,
        level = level ?: current.level,
        videoUhd = videoUhd ?: current.videoUhd,
        videoFps = videoFps?.takeIf { it in FRAME_RATES } ?: current.videoFps,
        timerFlash = timerFlash ?: current.timerFlash,
        volumeKeys = volumeKeys?.let { name -> VolumeKeys.entries.firstOrNull { it.name == name } } ?: current.volumeKeys,
        qrCodes = qrCodes ?: current.qrCodes,
        motionPhoto = motionPhoto?.let { name -> MotionPhoto.entries.firstOrNull { it.name == name } } ?: current.motionPhoto,
        mirror = mirror ?: current.mirror,
        shutterSound = shutterSound ?: current.shutterSound,
        videoHevc = videoHevc ?: current.videoHevc,
        nightSuggestion = nightSuggestion ?: current.nightSuggestion,
        proIso = when (proIso) {
            null -> current.proIso
            0 -> null
            else -> proIso.takeIf { it > 0 }
        },
        proShutterNs = when (proShutterNs) {
            null -> current.proShutterNs
            0L -> null
            // Pro's longest is 16 s; a value out of reason is automatic
            else -> proShutterNs.takeIf { it in 1..ProExposure.SHUTTER_MAX_NS }
        },
        proEvThirds = proEvThirds?.coerceIn(-PRO_EV_THIRDS, PRO_EV_THIRDS) ?: current.proEvThirds,
        proFocusDiopters = when {
            proFocusDiopters == null -> current.proFocusDiopters
            // A lens focuses at most a few centimeters away (100 diopters: 1 cm); a value out of reason is automatic
            proFocusDiopters in 0.0..100.0 -> proFocusDiopters
            else -> null
        },
        proKelvin = when (proKelvin) {
            null -> current.proKelvin
            // On the ruler's steps, as set by hand
            in WhiteBalance.MIN_K..WhiteBalance.MAX_K -> WhiteBalance.STEPS.minBy { abs(it - proKelvin) }
            else -> null
        },
        proHistogram = proHistogram ?: current.proHistogram,
        proPeaking = proPeaking ?: current.proPeaking,
        moreMode = moreMode ?: current.moreMode,
    )

    companion object {
        /** The frame rates a video can be set to (SPEC §17.3). */
        val FRAME_RATES = setOf(24, 30, 60)

        /** Pro's compensation reaches ±2 EV. */
        const val PRO_EV_THIRDS = 6

        fun of(settings: CameraSettings) = BackupSettings(
            aspect = settings.aspect.name,
            ultraHdr = settings.ultraHdr,
            hdrVideo = settings.hdrVideo,
            location = settings.location,
            grid = settings.grid.name,
            level = settings.level,
            videoUhd = settings.videoUhd,
            videoFps = settings.videoFps,
            timerFlash = settings.timerFlash,
            volumeKeys = settings.volumeKeys.name,
            qrCodes = settings.qrCodes,
            motionPhoto = settings.motionPhoto.name,
            mirror = settings.mirror,
            shutterSound = settings.shutterSound,
            videoHevc = settings.videoHevc,
            nightSuggestion = settings.nightSuggestion,
            proIso = settings.proIso ?: 0,
            proShutterNs = settings.proShutterNs ?: 0L,
            proEvThirds = settings.proEvThirds,
            proFocusDiopters = settings.proFocusDiopters ?: -1.0,
            proKelvin = settings.proKelvin ?: 0,
            proHistogram = settings.proHistogram,
            proPeaking = settings.proPeaking,
            moreMode = settings.moreMode,
        )
    }
}

/** What a picked file turned out to be, before anything changes. */
sealed interface BackupCheck {
    data class Valid(val backup: BackupFile) : BackupCheck

    /** Not JSON, or JSON of something else. */
    data class NotOurs(val details: String) : BackupCheck

    /** Made by a later version with a format this one doesn't know. */
    data class Newer(val formatVersion: Int) : BackupCheck
}

object Backup {
    /** Lenient on what it reads (unknown fields from later versions within the format), plain on what it writes. */
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /** Checks [text] in order: JSON at all, our format, a version we read, then the content. */
    fun check(text: String): BackupCheck {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return BackupCheck.NotOurs(e.message ?: "Not JSON")
        } catch (e: IllegalArgumentException) {
            return BackupCheck.NotOurs(e.message ?: "Not a JSON object")
        }
        if (root.string("format") != BackupFile.FORMAT) return BackupCheck.NotOurs("format: ${root.string("format") ?: "absent"}")
        // Not a number (an object, a list) is as good as absent; jsonPrimitive would throw on it
        val version = (root["formatVersion"] as? JsonPrimitive)?.intOrNull ?: return BackupCheck.NotOurs("formatVersion absent")
        if (version > BackupFile.FORMAT_VERSION) return BackupCheck.Newer(version)
        return try {
            BackupCheck.Valid(json.decodeFromJsonElement(BackupFile.serializer(), root))
        } catch (e: SerializationException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        } catch (e: IllegalArgumentException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        }
    }

    private fun JsonObject.string(key: String): String? = try {
        this[key]?.jsonPrimitive?.contentOrNull
    } catch (_: IllegalArgumentException) {
        null
    }
}
