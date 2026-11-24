@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.camera.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaActionSound
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.hnet.camera.CameraApplication
import sh.hnet.camera.CameraSettings
import sh.hnet.camera.FirstRun
import sh.hnet.camera.Grid
import sh.hnet.camera.MotionPhoto
import sh.hnet.camera.R
import sh.hnet.camera.VolumeKeys
import sh.hnet.camera.camera.Encoders
import sh.hnet.camera.capabilities.CameraSpec
import sh.hnet.camera.capabilities.model.SessionLadder
import sh.hnet.camera.ui.components.SettingsGroup
import sh.hnet.camera.ui.components.SettingsPage
import sh.hnet.camera.ui.components.SettingsRadioRow
import sh.hnet.camera.ui.components.SettingsSwitchRow
import sh.hnet.camera.ui.components.settingsRowColors
import sh.hnet.camera.ui.viewfinder.gridIcon
import sh.hnet.camera.ui.viewfinder.gridName
import sh.hnet.camera.ui.viewfinder.motionIcon
import sh.hnet.camera.ui.viewfinder.motionName
import sh.hnet.camera.ui.viewfinder.videoRates

/** The remembered values, the same ones the viewfinder shows (SPEC §17.5: one value each), and the phone's cameras for what to show (SPEC §19). */
@Composable
private fun cameraSettings(): Pair<CameraSettings, List<CameraSpec>> {
    val app = LocalContext.current.applicationContext as CameraApplication
    val settings by app.preferences.camera.collectAsStateWithLifecycle(CameraSettings())
    val cameras = remember { app.camera.cameras }
    return settings to cameras
}

/**
 * Settings › Photos (SPEC §17.2): motion photo, Ultra HDR, mirrored selfies, the shutter's sound, QR codes, location. RAW comes with Pro. What the phone can't do
 * isn't shown: Ultra HDR without JPEG_R, the mirror without a front camera, the sound where the law plays it anyway.
 */
@Composable
fun PhotosSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as CameraApplication
    val scope = rememberCoroutineScope()
    val (settings, cameras) = cameraSettings()
    val preferences = app.preferences
    // Location's access, read again on the way back from the system's settings; refused, the switch stays off and says why
    fun locationGranted() = LOCATION.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var locationAllowed by remember { mutableStateOf(locationGranted()) }
    LifecycleResumeEffect(Unit) {
        locationAllowed = locationGranted()
        onPauseOrDispose { }
    }
    val activity = LocalActivity.current
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val final = FirstRun.refusedForGood(activity, result.keys.toTypedArray())
        FirstRun.markRequested(context, result.keys.toTypedArray())
        locationAllowed = result.values.any { it }
        if (locationAllowed) scope.launch { preferences.setLocation(true) }
        // Refused for good: the dialog no longer shows, so the system's settings
        else if (final) FirstRun.openAppSettings(context)
    }
    SettingsPage(title = stringResource(R.string.title_settings_photos), onBack = onBack) {
        SettingsGroup {
            row { shapes ->
                ChoiceRow(shapes, stringResource(R.string.label_setting_motion_photo), MotionPhoto.entries, settings.motionPhoto, { stringResource(motionName(it)) }, { scope.launch { preferences.setMotionPhoto(it) } }) {
                    Icon(painterResource(motionIcon(it)), contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            if (cameras.isEmpty() || cameras.any { it.jpegR.isNotEmpty() }) row { shapes ->
                SettingsSwitchRow(shapes, R.drawable.ic_hdr_on, stringResource(R.string.label_setting_ultra_hdr), settings.ultraHdr, { scope.launch { preferences.setUltraHdr(it) } }, stringResource(R.string.desc_setting_ultra_hdr))
            }
            if (cameras.isEmpty() || cameras.any { it.front }) row { shapes ->
                SettingsSwitchRow(shapes, R.drawable.ic_flip, stringResource(R.string.label_setting_mirror), settings.mirror, { scope.launch { preferences.setMirror(it) } })
            }
            if (!MediaActionSound.mustPlayShutterSound()) row { shapes ->
                SettingsSwitchRow(shapes, R.drawable.ic_volume_up, stringResource(R.string.label_setting_shutter_sound), settings.shutterSound, { scope.launch { preferences.setShutterSound(it) } })
            }
            row { shapes ->
                SettingsSwitchRow(shapes, R.drawable.ic_qr_code_scanner, stringResource(R.string.label_setting_qr), settings.qrCodes, { scope.launch { preferences.setQrCodes(it) } }, stringResource(R.string.desc_setting_qr))
            }
            row { shapes ->
                SettingsSwitchRow(
                    shapes, R.drawable.ic_location_on, stringResource(R.string.label_setting_location), settings.location && locationAllowed,
                    { on -> if (!on || locationAllowed) scope.launch { preferences.setLocation(on) } else locationLauncher.launch(LOCATION.toTypedArray()) },
                    if (locationAllowed) null else stringResource(R.string.desc_setting_location_denied),
                )
            }
        }
    }
}

/**
 * Settings › Video (SPEC §17.3): resolution, frame rate, HDR (HLG), codec. Stabilization has no choice in v0.1.0 (D6). What no camera of the phone can do isn't
 * shown; HLG is HEVC only, so with AVC its switch is off and says so.
 */
@Composable
fun VideoSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as CameraApplication
    val scope = rememberCoroutineScope()
    val (settings, cameras) = cameraSettings()
    val preferences = app.preferences
    val uhd = cameras.isEmpty() || cameras.any { SessionLadder.UHD in it.video }
    val rates = videoRates(cameras.flatMap { it.fixedFps }.toSet())
    val hlg = cameras.isEmpty() || cameras.any { it.hlg }
    val hevc = remember { Encoders.hevc() }
    SettingsPage(title = stringResource(R.string.title_settings_video), onBack = onBack) {
        SettingsGroup {
            if (uhd) row { shapes ->
                ChoiceRow(shapes, stringResource(R.string.label_setting_resolution), listOf(true, false), settings.videoUhd, { stringResource(if (it) R.string.menu_resolution_4k else R.string.menu_resolution_1080p) }, { scope.launch { preferences.setVideoUhd(it) } })
            }
            if (rates.size > 1) row { shapes ->
                ChoiceRow(shapes, stringResource(R.string.label_setting_frame_rate), rates, settings.videoFps.takeIf { it in rates } ?: 30, { stringResource(R.string.value_fps, it) }, { scope.launch { preferences.setVideoFps(it) } })
            }
            if (hlg && hevc) row { shapes ->
                SettingsSwitchRow(
                    shapes, R.drawable.ic_hdr_on, stringResource(R.string.label_setting_hdr_video), settings.hdrVideo && settings.videoHevc,
                    { scope.launch { if (it && !settings.videoHevc) preferences.setVideoHevc(true); preferences.setHdrVideo(it) } },
                    stringResource(if (settings.videoHevc) R.string.desc_setting_hdr_video else R.string.desc_setting_hdr_video_avc),
                )
            }
            if (hevc) row { shapes ->
                ChoiceRow(shapes, stringResource(R.string.label_setting_codec), listOf(true, false), settings.videoHevc, { stringResource(if (it) R.string.label_codec_hevc else R.string.label_codec_avc) }, { scope.launch { preferences.setVideoHevc(it) } }, stringResource(R.string.desc_setting_codec))
            }
        }
    }
}

/**
 * Settings › Controls (SPEC §17.4, LOGIKA §12): the volume keys, the grid and the level, and the timer's countdown on the flash's LED (where the back camera has
 * a flash). The Night suggestion comes with Night.
 */
@Composable
fun ControlsSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as CameraApplication
    val scope = rememberCoroutineScope()
    val (settings, cameras) = cameraSettings()
    val preferences = app.preferences
    SettingsPage(title = stringResource(R.string.title_settings_controls), onBack = onBack) {
        Column(Modifier.selectableGroup()) {
            SettingsGroup(stringResource(R.string.title_setting_volume_keys)) {
                for (keys in VolumeKeys.entries) row { shapes ->
                    val (title, supporting) = when (keys) {
                        VolumeKeys.SHUTTER -> R.string.label_volume_keys_shutter to R.string.desc_volume_keys_shutter
                        VolumeKeys.ZOOM -> R.string.label_volume_keys_zoom to R.string.desc_volume_keys_zoom
                        VolumeKeys.VOLUME -> R.string.label_volume_keys_volume to R.string.desc_volume_keys_volume
                    }
                    SettingsRadioRow(shapes, stringResource(title), settings.volumeKeys == keys, { scope.launch { preferences.setVolumeKeys(keys) } }, stringResource(supporting))
                }
            }
        }
        SettingsGroup(stringResource(R.string.title_setting_on_picture)) {
            row { shapes ->
                ChoiceRow(shapes, stringResource(R.string.label_setting_grid), Grid.entries, settings.grid, { stringResource(gridName(it)) }, { scope.launch { preferences.setGrid(it) } }) {
                    Icon(painterResource(gridIcon(it)), contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            row { shapes ->
                SettingsSwitchRow(shapes, R.drawable.ic_straighten, stringResource(R.string.label_setting_level), settings.level, { scope.launch { preferences.setLevel(it) } })
            }
            if (cameras.isEmpty() || cameras.any { it.night != null }) row { shapes ->
                SettingsSwitchRow(shapes, R.drawable.ic_bedtime, stringResource(R.string.label_setting_night_suggestion), settings.nightSuggestion, { scope.launch { preferences.setNightSuggestion(it) } }, stringResource(R.string.desc_setting_night_suggestion))
            }
        }
        if (cameras.isEmpty() || cameras.any { !it.front && it.flash }) SettingsGroup(stringResource(R.string.title_setting_timer)) {
            row { shapes ->
                SettingsSwitchRow(shapes, R.drawable.ic_flashlight_on, stringResource(R.string.label_setting_timer_flash), settings.timerFlash, { scope.launch { preferences.setTimerFlash(it) } }, stringResource(R.string.desc_setting_timer_flash))
            }
        }
    }
}

/**
 * A setting's title, then its choices as connected toggle buttons, one chosen (Gallery's slideshow rows). With [icon] the buttons show icons, their names
 * kept for the screen reader; else the names.
 */
@Composable
private fun <T> ChoiceRow(
    shapes: ListItemShapes,
    title: String,
    values: List<T>,
    chosen: T,
    name: @Composable (T) -> String,
    onChoose: (T) -> Unit,
    supporting: String? = null,
    icon: (@Composable (T) -> Unit)? = null,
) {
    SegmentedListItem(shapes = shapes, colors = settingsRowColors()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            Text(title)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                values.forEachIndexed { index, value ->
                    val label = name(value)
                    ToggleButton(
                        checked = value == chosen,
                        // Also the shown choice when the remembered value isn't offered here (60 fps from a backup): a tap writes it
                        onCheckedChange = { onChoose(value) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            values.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        modifier = Modifier.weight(1f).semantics {
                            role = Role.RadioButton
                            if (icon != null) contentDescription = label
                        },
                    ) {
                        if (icon != null) icon(value) else Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private val LOCATION = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
