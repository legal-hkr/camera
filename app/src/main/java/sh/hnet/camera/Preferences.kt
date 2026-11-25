package sh.hnet.camera

import android.content.Context
import androidx.datastore.preferences.core.Preferences as Store
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import sh.hnet.camera.backup.BackupSettings
import sh.hnet.camera.capabilities.model.Aspect

private val Context.settings by preferencesDataStore("settings")

enum class Grid { OFF, THIRDS, QUARTERS }

enum class VolumeKeys { SHUTTER, ZOOM, VOLUME }

/** Pro's raw image (SPEC §8.4, D21): none, a DNG next to each photo, or a 50 MP DNG alone. */
enum class RawMode { OFF, RAW, MP50 }

/** Motion photos (SPEC §7.6): off, kept only when something moved, or always. */
enum class MotionPhoto { OFF, AUTO, ON }

/** The values remembered between starts (SPEC §17.5); one value each, whether changed in the viewfinder or in Settings. */
data class CameraSettings(
    val aspect: Aspect = Aspect.FOUR_THREE,
    /** Photos in Ultra HDR (else plain JPEG). */
    val ultraHdr: Boolean = true,
    /** Videos in HLG where the rate allows (else SDR). */
    val hdrVideo: Boolean = true,
    val location: Boolean = false,
    val grid: Grid = Grid.OFF,
    val level: Boolean = false,
    val videoUhd: Boolean = true,
    val videoFps: Int = 30,
    /** The back flash's LED counts the timer down (owner, 23 Nov 2026). */
    val timerFlash: Boolean = false,
    val volumeKeys: VolumeKeys = VolumeKeys.SHUTTER,
    /** QR codes recognized in Photo mode (Settings › Photos). */
    val qrCodes: Boolean = true,
    val motionPhoto: MotionPhoto = MotionPhoto.AUTO,
    /** Front camera photos saved as the preview shows them, mirrored (Settings › Photos). */
    val mirror: Boolean = false,
    /** The shutter's click (Settings › Photos); played anyway where the law wants it. */
    val shutterSound: Boolean = true,
    /** Videos in HEVC, else AVC for older devices (Settings › Video); HLG needs HEVC. */
    val videoHevc: Boolean = true,
    /** The "Night mode" suggestion when it gets dark (Settings › Controls). */
    val nightSuggestion: Boolean = true,
    /** Pro's values set by hand, kept until reset (SPEC §11, D20): ISO and shutter (null: automatic), the compensation in thirds of a stop. */
    val proIso: Int? = null,
    val proShutterNs: Long? = null,
    val proEvThirds: Int = 0,
    /** Pro's focus set by hand in diopters (0: infinity) and white balance in kelvins; null: automatic. Kept until reset as the exposure. */
    val proFocusDiopters: Double? = null,
    val proKelvin: Int? = null,
    /** Pro's histogram plate (SPEC §11, the panel), and focus peaking (the switch at the focus ruler's end). */
    val proHistogram: Boolean = true,
    val proPeaking: Boolean = true,
    /** Pro's raw image (the panel and Pro's top row; owner, 25 Nov 2026: in Pro only, not in Photo). */
    val rawMode: RawMode = RawMode.OFF,
)

/** Pro's values set by hand, together (its reset and the reset's undo); null: automatic. */
data class ProValues(val iso: Int?, val shutterNs: Long?, val evThirds: Int, val focusDiopters: Double?, val kelvin: Int?) {
    companion object {
        val AUTO = ProValues(null, null, 0, null, null)
    }
}

/** Choices the user made in the UI that outlive the process (app-private, not backed up by the system). */
class Preferences(private val context: Context) {
    val camera: Flow<CameraSettings> = context.settings.data.map { it.camera() }

    private fun Store.camera() = CameraSettings(
        aspect = this[ASPECT]?.let { name -> Aspect.entries.firstOrNull { it.name == name } } ?: Aspect.FOUR_THREE,
        ultraHdr = this[ULTRA_HDR] ?: true,
        hdrVideo = this[HDR_VIDEO] ?: true,
        location = this[LOCATION] ?: false,
        grid = this[GRID]?.let { name -> Grid.entries.firstOrNull { it.name == name } } ?: Grid.OFF,
        level = this[LEVEL] ?: false,
        videoUhd = this[VIDEO_UHD] ?: true,
        videoFps = this[VIDEO_FPS] ?: 30,
        timerFlash = this[TIMER_FLASH] ?: false,
        volumeKeys = this[VOLUME_KEYS]?.let { name -> VolumeKeys.entries.firstOrNull { it.name == name } } ?: VolumeKeys.SHUTTER,
        qrCodes = this[QR_CODES] ?: true,
        motionPhoto = this[MOTION_PHOTO]?.let { name -> MotionPhoto.entries.firstOrNull { it.name == name } } ?: MotionPhoto.AUTO,
        mirror = this[MIRROR] ?: false,
        shutterSound = this[SHUTTER_SOUND] ?: true,
        videoHevc = this[VIDEO_HEVC] ?: true,
        nightSuggestion = this[NIGHT_SUGGESTION] ?: true,
        proIso = this[PRO_ISO]?.takeIf { it > 0 },
        proShutterNs = this[PRO_SHUTTER]?.takeIf { it > 0 },
        proEvThirds = this[PRO_EV] ?: 0,
        proFocusDiopters = this[PRO_FOCUS]?.takeIf { it >= 0 }?.toDouble(),
        proKelvin = this[PRO_KELVIN]?.takeIf { it > 0 },
        proHistogram = this[PRO_HISTOGRAM] ?: true,
        proPeaking = this[PRO_PEAKING] ?: true,
        rawMode = this[RAW_MODE]?.let { name -> RawMode.entries.firstOrNull { it.name == name } } ?: RawMode.OFF,
    )

    suspend fun setAspect(value: Aspect) = context.settings.edit { it[ASPECT] = value.name }
    suspend fun setUltraHdr(value: Boolean) = context.settings.edit { it[ULTRA_HDR] = value }
    suspend fun setHdrVideo(value: Boolean) = context.settings.edit { it[HDR_VIDEO] = value }
    suspend fun setLocation(value: Boolean) = context.settings.edit { it[LOCATION] = value }
    suspend fun setGrid(value: Grid) = context.settings.edit { it[GRID] = value.name }
    suspend fun setLevel(value: Boolean) = context.settings.edit { it[LEVEL] = value }
    suspend fun setVideoUhd(value: Boolean) = context.settings.edit { it[VIDEO_UHD] = value }
    suspend fun setVideoFps(value: Int) = context.settings.edit { it[VIDEO_FPS] = value }
    suspend fun setTimerFlash(value: Boolean) = context.settings.edit { it[TIMER_FLASH] = value }
    suspend fun setVolumeKeys(value: VolumeKeys) = context.settings.edit { it[VOLUME_KEYS] = value.name }
    suspend fun setQrCodes(value: Boolean) = context.settings.edit { it[QR_CODES] = value }
    suspend fun setMotionPhoto(value: MotionPhoto) = context.settings.edit { it[MOTION_PHOTO] = value.name }
    suspend fun setMirror(value: Boolean) = context.settings.edit { it[MIRROR] = value }
    suspend fun setShutterSound(value: Boolean) = context.settings.edit { it[SHUTTER_SOUND] = value }
    suspend fun setVideoHevc(value: Boolean) = context.settings.edit { it[VIDEO_HEVC] = value }
    suspend fun setNightSuggestion(value: Boolean) = context.settings.edit { it[NIGHT_SUGGESTION] = value }
    suspend fun setProIso(value: Int?) = context.settings.edit { it[PRO_ISO] = value ?: 0 }
    suspend fun setProShutter(value: Long?) = context.settings.edit { it[PRO_SHUTTER] = value ?: 0L }
    suspend fun setProEv(thirds: Int) = context.settings.edit { it[PRO_EV] = thirds }
    suspend fun setProFocus(diopters: Double?) = context.settings.edit { it[PRO_FOCUS] = diopters?.toFloat() ?: -1f }
    suspend fun setProKelvin(kelvin: Int?) = context.settings.edit { it[PRO_KELVIN] = kelvin ?: 0 }
    suspend fun setProHistogram(value: Boolean) = context.settings.edit { it[PRO_HISTOGRAM] = value }
    suspend fun setProPeaking(value: Boolean) = context.settings.edit { it[PRO_PEAKING] = value }
    suspend fun setRawMode(value: RawMode) = context.settings.edit { it[RAW_MODE] = value.name }

    /** Pro's values all at once (its reset, and its undo). */
    suspend fun setPro(values: ProValues) = context.settings.edit {
        it[PRO_ISO] = values.iso ?: 0
        it[PRO_SHUTTER] = values.shutterNs ?: 0L
        it[PRO_EV] = values.evThirds
        it[PRO_FOCUS] = values.focusDiopters?.toFloat() ?: -1f
        it[PRO_KELVIN] = values.kelvin ?: 0
    }

    /** Every remembered value as the backup holds it (LOGIKA §12). */
    suspend fun snapshot(): BackupSettings = BackupSettings.of(camera.first())

    /** A backup's settings: each one the file holds replaces the current value; one it lacks stays as it is (family rule). */
    suspend fun restore(backup: BackupSettings, locationAllowed: Boolean) {
        val current = camera.first()
        val restored = backup.applyTo(current, locationAllowed)
        context.settings.edit { p ->
            p[ASPECT] = restored.aspect.name
            p[ULTRA_HDR] = restored.ultraHdr
            p[HDR_VIDEO] = restored.hdrVideo
            p[LOCATION] = restored.location
            p[GRID] = restored.grid.name
            p[LEVEL] = restored.level
            p[VIDEO_UHD] = restored.videoUhd
            p[VIDEO_FPS] = restored.videoFps
            p[TIMER_FLASH] = restored.timerFlash
            p[VOLUME_KEYS] = restored.volumeKeys.name
            p[QR_CODES] = restored.qrCodes
            p[MOTION_PHOTO] = restored.motionPhoto.name
            p[MIRROR] = restored.mirror
            p[SHUTTER_SOUND] = restored.shutterSound
            p[VIDEO_HEVC] = restored.videoHevc
            p[NIGHT_SUGGESTION] = restored.nightSuggestion
            p[PRO_ISO] = restored.proIso ?: 0
            p[PRO_SHUTTER] = restored.proShutterNs ?: 0L
            p[PRO_EV] = restored.proEvThirds
            p[PRO_FOCUS] = restored.proFocusDiopters?.toFloat() ?: -1f
            p[PRO_KELVIN] = restored.proKelvin ?: 0
            p[PRO_HISTOGRAM] = restored.proHistogram
            p[PRO_PEAKING] = restored.proPeaking
            p[RAW_MODE] = restored.rawMode.name
        }
    }

    /** Restore defaults (Settings › Reset): every setting of this store back to its default. */
    suspend fun restoreDefaults() = context.settings.edit { it.clear() }

    private companion object {
        val ASPECT = stringPreferencesKey("aspect")
        val ULTRA_HDR = booleanPreferencesKey("ultra_hdr")
        val HDR_VIDEO = booleanPreferencesKey("hdr_video")
        val LOCATION = booleanPreferencesKey("location")
        val GRID = stringPreferencesKey("grid")
        val LEVEL = booleanPreferencesKey("level")
        val VIDEO_UHD = booleanPreferencesKey("video_uhd")
        val VIDEO_FPS = intPreferencesKey("video_fps")
        val TIMER_FLASH = booleanPreferencesKey("timer_flash")
        val VOLUME_KEYS = stringPreferencesKey("volume_keys")
        val QR_CODES = booleanPreferencesKey("qr_codes")
        val MOTION_PHOTO = stringPreferencesKey("motion_photo")
        val MIRROR = booleanPreferencesKey("mirror")
        val SHUTTER_SOUND = booleanPreferencesKey("shutter_sound")
        val VIDEO_HEVC = booleanPreferencesKey("video_hevc")
        val NIGHT_SUGGESTION = booleanPreferencesKey("night_suggestion")
        val PRO_ISO = intPreferencesKey("pro_iso")
        val PRO_SHUTTER = longPreferencesKey("pro_shutter")
        val PRO_EV = intPreferencesKey("pro_ev")
        val PRO_FOCUS = floatPreferencesKey("pro_focus")
        val PRO_KELVIN = intPreferencesKey("pro_kelvin")
        val PRO_HISTOGRAM = booleanPreferencesKey("pro_histogram")
        val PRO_PEAKING = booleanPreferencesKey("pro_peaking")
        val RAW_MODE = stringPreferencesKey("raw_mode")
    }
}
