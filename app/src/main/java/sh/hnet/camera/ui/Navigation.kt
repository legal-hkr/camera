package sh.hnet.camera.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** One back stack: the camera at its root, Settings and their pages over it. Placeholder set until the handoff settles the camera's own pages. */
@Serializable
sealed interface Screen : NavKey {
    @Serializable data object Camera : Screen
    @Serializable data object Settings : Screen
    @Serializable data object SettingsAbout : Screen
    @Serializable data object SettingsLicenses : Screen
    @Serializable data object SettingsLanguage : Screen
    @Serializable data object SettingsReset : Screen
    @Serializable data object SettingsWhatsNew : Screen
    @Serializable data object SettingsLicense : Screen
}
