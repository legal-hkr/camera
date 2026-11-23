package sh.hnet.camera.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import sh.hnet.camera.BuildConfig
import sh.hnet.camera.R
import sh.hnet.camera.ui.components.SettingsGroup
import sh.hnet.camera.ui.components.SettingsNavigationRow
import sh.hnet.camera.ui.components.SettingsPage

/**
 * The Settings root (opened from the camera, so with Back; SPEC §17.1 grouped as the family's): Camera (Photos, Video, Controls), App (language), Data (backup,
 * reset), then About on its own.
 */
@Composable
fun SettingsRootScreen(
    onBack: () -> Unit,
    onOpenPhotos: () -> Unit,
    onOpenVideo: () -> Unit,
    onOpenControls: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenReset: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME.removePrefix("v")
    val language = appLanguages.firstOrNull { it.tag == currentLanguageTag(context) } ?: appLanguages.first()
    SettingsPage(title = stringResource(R.string.title_settings), onBack = onBack) {
        SettingsGroup(stringResource(R.string.title_settings_group_camera)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_photo_camera, stringResource(R.string.title_settings_photos), stringResource(R.string.desc_settings_photos), onClick = onOpenPhotos)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_videocam, stringResource(R.string.title_settings_video), stringResource(R.string.desc_settings_video), onClick = onOpenVideo)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_touch_app, stringResource(R.string.title_settings_controls), stringResource(R.string.desc_settings_controls), onClick = onOpenControls)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_app)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_translate, stringResource(R.string.title_settings_language), stringResource(language.name), onClick = onOpenLanguage)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_data)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_settings_backup_restore, stringResource(R.string.title_backup), stringResource(R.string.desc_backup_row), onClick = onOpenBackup)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_restart_alt, stringResource(R.string.title_settings_reset), stringResource(R.string.desc_settings_reset_row), onClick = onOpenReset)
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_info, stringResource(R.string.title_settings_about), stringResource(R.string.label_version_value, version), onClick = onOpenAbout)
            }
        }
    }
}
