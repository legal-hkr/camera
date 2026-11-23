package sh.hnet.camera.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** One back stack: the viewfinder at its root, Settings and their pages over it (SPEC §3). */
@Serializable
sealed interface Screen : NavKey {
    @Serializable data object Camera : Screen
    @Serializable data object Settings : Screen
    @Serializable data object SettingsPhotos : Screen
    @Serializable data object SettingsVideo : Screen
    @Serializable data object SettingsControls : Screen
    @Serializable data object SettingsBackup : Screen
    @Serializable data object SettingsAbout : Screen
    @Serializable data object SettingsLicenses : Screen
    @Serializable data object SettingsLanguage : Screen
    @Serializable data object SettingsReset : Screen
    @Serializable data object SettingsWhatsNew : Screen
    @Serializable data object SettingsLicense : Screen
}
