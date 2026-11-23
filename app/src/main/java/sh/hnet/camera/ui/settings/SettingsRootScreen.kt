package sh.hnet.camera.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import sh.hnet.camera.BuildConfig
import sh.hnet.camera.R
import sh.hnet.camera.ui.components.SettingsGroup
import sh.hnet.camera.ui.components.SettingsNavigationRow
import sh.hnet.camera.ui.components.SettingsPage

/** The Settings root (opened from the camera, so with Back), grouped as the family's: App (language), Data (reset), then About on its own. The app's own groups come with the handoff. */
@Composable
fun SettingsRootScreen(onBack: () -> Unit, onOpenLanguage: () -> Unit, onOpenReset: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME.removePrefix("v")
    val language = appLanguages.firstOrNull { it.tag == currentLanguageTag(context) } ?: appLanguages.first()
    SettingsPage(title = stringResource(R.string.title_settings), onBack = onBack) {
        SettingsGroup(stringResource(R.string.title_settings_group_app)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_translate, stringResource(R.string.title_settings_language), stringResource(language.name), onClick = onOpenLanguage)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_data)) {
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
