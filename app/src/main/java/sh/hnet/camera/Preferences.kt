package sh.hnet.camera

import android.content.Context
import androidx.datastore.preferences.core.Preferences as Store
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import sh.hnet.camera.capabilities.model.Aspect

private val Context.settings by preferencesDataStore("settings")

enum class Grid { OFF, THIRDS, QUARTERS }

enum class VolumeKeys { SHUTTER, ZOOM, VOLUME }

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
    }
}
