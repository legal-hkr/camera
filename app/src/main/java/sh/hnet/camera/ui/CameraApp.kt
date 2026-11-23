@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.camera.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import sh.hnet.camera.R
import sh.hnet.camera.about.WhatsNew
import sh.hnet.camera.ui.settings.AboutScreen
import sh.hnet.camera.ui.settings.AppLicenseScreen
import sh.hnet.camera.ui.settings.LanguageScreen
import sh.hnet.camera.ui.settings.LicensesScreen
import sh.hnet.camera.ui.settings.ResetScreen
import sh.hnet.camera.ui.settings.SettingsRootScreen
import sh.hnet.camera.ui.settings.WhatsNewScreen

/** The app's shell: one back stack with the camera at its root (a camera has no navigation bar), Settings opened over it, snackbars. */
@Composable
fun CameraApp() {
    val stack = rememberNavBackStack(Screen.Camera)
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    val snackbars = remember { SnackbarHostState() }
    // After an update, once: "Updated to x.y.z" with What's new (as ComfyChair)
    val updatedTo = stringResource(R.string.whats_new_updated_snackbar)
    val whatsNew = stringResource(R.string.whats_new_title)
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        val version = WhatsNew.takeUpdateNotice(appContext) ?: return@LaunchedEffect
        if (snackbars.showSnackbar(updatedTo.format(version), actionLabel = whatsNew, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
            stack.add(Screen.Settings)
            stack.add(Screen.SettingsAbout)
            stack.add(Screen.SettingsWhatsNew)
        }
    }

    val motion = MaterialTheme.motionScheme
    val spatial: FiniteAnimationSpec<IntOffset> = motion.defaultSpatialSpec()
    val effects: FiniteAnimationSpec<Float> = motion.defaultEffectsSpec()
    val fastEffects: FiniteAnimationSpec<Float> = motion.fastEffectsSpec()
    val shift = with(LocalDensity.current) { 30.dp.roundToPx() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = {
            SnackbarHost(snackbars) { data ->
                Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
            }
        },
    ) { padding ->
        NavDisplay(
            backStack = stack,
            onBack = back,
            // No bars here (the pages bring their own), so this is only the snackbar's room: none
            modifier = Modifier.fillMaxSize().padding(padding),
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator<NavKey>()),
            // Shared axis X between a page and the pages it opens
            transitionSpec = {
                (slideInHorizontally(spatial) { shift } + fadeIn(effects)) togetherWith
                    (slideOutHorizontally(spatial) { -shift } + fadeOut(fastEffects))
            },
            popTransitionSpec = {
                (slideInHorizontally(spatial) { -shift } + fadeIn(effects)) togetherWith
                    (slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects))
            },
            predictivePopTransitionSpec = {
                (slideInHorizontally(spatial) { -shift } + fadeIn(effects)) togetherWith
                    (slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects))
            },
            entryProvider = entryProvider {
                entry<Screen.Camera> { PlaceholderScreen(stringResource(R.string.app_name), onOpenSettings = { stack.add(Screen.Settings) }) }
                entry<Screen.Settings> {
                    SettingsRootScreen(
                        onBack = back,
                        onOpenLanguage = { stack.add(Screen.SettingsLanguage) },
                        onOpenReset = { stack.add(Screen.SettingsReset) },
                        onOpenAbout = { stack.add(Screen.SettingsAbout) },
                    )
                }
                entry<Screen.SettingsAbout> {
                    AboutScreen(
                        onBack = back,
                        onOpenWhatsNew = { stack.add(Screen.SettingsWhatsNew) },
                        onOpenLicense = { stack.add(Screen.SettingsLicense) },
                        onOpenLicenses = { stack.add(Screen.SettingsLicenses) },
                    )
                }
                entry<Screen.SettingsLicenses> { LicensesScreen(onBack = back) }
                entry<Screen.SettingsLanguage> { LanguageScreen(onBack = back) }
                entry<Screen.SettingsReset> { ResetScreen(onBack = back) }
                entry<Screen.SettingsWhatsNew> { WhatsNewScreen(onBack = back) }
                entry<Screen.SettingsLicense> { AppLicenseScreen(onBack = back) }
            },
        )
    }
}
