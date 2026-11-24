@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.camera.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import sh.hnet.camera.FirstRun
import sh.hnet.camera.R
import sh.hnet.camera.about.WhatsNew
import sh.hnet.camera.ui.settings.AboutScreen
import sh.hnet.camera.ui.settings.AppLicenseScreen
import sh.hnet.camera.ui.settings.BackupScreen
import sh.hnet.camera.ui.settings.ControlsSettingsScreen
import sh.hnet.camera.ui.settings.LanguageScreen
import sh.hnet.camera.ui.settings.LicensesScreen
import sh.hnet.camera.ui.settings.PhotosSettingsScreen
import sh.hnet.camera.ui.settings.ResetScreen
import sh.hnet.camera.ui.settings.SettingsRootScreen
import sh.hnet.camera.ui.settings.VideoSettingsScreen
import sh.hnet.camera.ui.settings.WhatsNewScreen
import sh.hnet.camera.ui.viewfinder.Entry
import sh.hnet.camera.ui.viewfinder.ViewfinderScreen
import sh.hnet.camera.ui.welcome.WizardScreen

/** How the app was opened: the viewfinder's way in (a shortcut, the system's camera actions), or Settings after unlocking from the lock screen. */
data class Launch(val entry: Entry.App, val settings: Boolean = false)

/** The app's shell: one back stack with the camera at its root (a camera has no navigation bar), Settings opened over it, snackbars. */
@Composable
fun CameraApp(launch: Launch) {
    val stack = rememberNavBackStack(Screen.Camera)
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    // A new way in: the camera it asks for in front, or Settings over it
    // Once per launch: a recreation (a language change) keeps the pages that were open
    var handled by rememberSaveable { mutableLongStateOf(-1L) }
    LaunchedEffect(launch) {
        if (launch.entry.id == handled) return@LaunchedEffect
        handled = launch.entry.id
        if (!launch.settings && launch.entry.start == null) return@LaunchedEffect
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        if (launch.settings) stack.add(Screen.Settings)
    }

    val snackbars = remember { SnackbarHostState() }
    // Messages that outlive the page that started them (a restore ends on another page)
    val messages = rememberCoroutineScope()
    // After an update, once: "Updated to x.y.z" with What's new (as ComfyChair)
    val updatedTo = stringResource(R.string.whats_new_updated_snackbar)
    val whatsNew = stringResource(R.string.whats_new_title)
    val appContext = LocalContext.current.applicationContext
    // The first run (SPEC §16): the wizard instead of the camera until it is done, so the camera isn't opened and nothing is asked behind it
    var wizard by rememberSaveable { mutableStateOf(!FirstRun.wizardDone(appContext)) }
    // Not over the wizard: the notice waits for the camera
    LaunchedEffect(wizard) {
        if (wizard) return@LaunchedEffect
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

    if (wizard) {
        Box(Modifier.fillMaxSize()) {
            WizardScreen(snackbars, messages, onDone = {
                FirstRun.markWizardDone(appContext)
                wizard = false
            })
            // The restore's messages over the wizard, above the system's navigation area
            SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { data ->
                Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        return
    }

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
                entry<Screen.Camera> { ViewfinderScreen(launch.entry, onOpenSettings = { stack.add(Screen.Settings) }) }
                entry<Screen.Settings> {
                    SettingsRootScreen(
                        onBack = back,
                        onOpenPhotos = { stack.add(Screen.SettingsPhotos) },
                        onOpenVideo = { stack.add(Screen.SettingsVideo) },
                        onOpenControls = { stack.add(Screen.SettingsControls) },
                        onOpenLanguage = { stack.add(Screen.SettingsLanguage) },
                        onOpenBackup = { stack.add(Screen.SettingsBackup) },
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
                entry<Screen.SettingsPhotos> { PhotosSettingsScreen(onBack = back) }
                entry<Screen.SettingsVideo> { VideoSettingsScreen(onBack = back) }
                entry<Screen.SettingsControls> { ControlsSettingsScreen(onBack = back) }
                entry<Screen.SettingsBackup> { BackupScreen(onBack = back, snackbars = snackbars, messageScope = messages) }
                entry<Screen.SettingsLicenses> { LicensesScreen(onBack = back) }
                entry<Screen.SettingsLanguage> { LanguageScreen(onBack = back) }
                entry<Screen.SettingsReset> { ResetScreen(onBack = back) }
                entry<Screen.SettingsWhatsNew> { WhatsNewScreen(onBack = back) }
                entry<Screen.SettingsLicense> { AppLicenseScreen(onBack = back) }
            },
        )
    }
}
