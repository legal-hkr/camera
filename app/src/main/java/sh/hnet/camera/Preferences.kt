package sh.hnet.camera

import android.content.Context
import androidx.datastore.preferences.core.Preferences as Store
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
)

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
    }
}
