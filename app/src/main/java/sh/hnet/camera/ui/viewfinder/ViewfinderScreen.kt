@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.camera.ui.viewfinder

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import sh.hnet.camera.FirstRun
import sh.hnet.camera.Grid
import sh.hnet.camera.MotionPhoto
import sh.hnet.camera.R
import sh.hnet.camera.camera.CameraStatus
import sh.hnet.camera.camera.Flash
import sh.hnet.camera.camera.Mode
import sh.hnet.camera.camera.PhoneState
import sh.hnet.camera.camera.QrContent
import sh.hnet.camera.camera.Recording
import sh.hnet.camera.capabilities.model.Aspect
import sh.hnet.camera.capabilities.model.Orientation
import sh.hnet.camera.capabilities.model.PictureRange
import sh.hnet.camera.capabilities.model.PixelSize
import sh.hnet.camera.capabilities.model.Point
import sh.hnet.camera.capabilities.model.SessionLadder
import sh.hnet.camera.capabilities.model.Sizes
import sh.hnet.camera.media.LastShot
import sh.hnet.camera.media.MediaFiles
import sh.hnet.camera.ui.components.EmptyState
import sh.hnet.camera.ui.components.ErrorDetails
import sh.hnet.camera.ui.components.ErrorDetailsDialog
import sh.hnet.camera.ui.components.reportFailure

/** The exposure ruler closes this long after the last touch (SPEC §5.3). */
private const val EXPOSURE_RULER_MS = 3_000L

/** Exposure compensation shown and set in thirds of a stop, ±2 EV (SPEC §7.1). */
private const val EV_RANGE = 2.0

/** Without use the viewfinder lets the screen sleep after this (the system's own timeout follows). */
private const val IDLE_MS = 120_000L

/**
 * The viewfinder (SPEC §4): built from the bottom up – the mode row above the navigation bar, the shutter row, the zoom row lying over the bottom of the preview
 * area – with the picture placed by [FinderGeometry] (4:3 fitted, 16:9 filling the width, the square as bands over 4:3) and `surface` around it: Photo
 * and Video's preview, zoom, focus and exposure, photos with the thumbnail.
 */
@Composable
fun ViewfinderScreen(
    entry: Entry,
    onOpenSettings: (() -> Unit)?,
    onCancel: (() -> Unit)? = null,
    onResult: ((Intent) -> Unit)? = null,
    viewModel: ViewfinderViewModel = viewModel(),
) {
    // The content color of the viewfinder's surface for everything on it: the lock screen's camera and another app's shot have no app shell (its Scaffold)
    // that would provide it, and the default is black
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        ViewfinderContent(entry, onOpenSettings, onCancel, onResult, viewModel)
    }
}

@Composable
private fun ViewfinderContent(
    entry: Entry,
    onOpenSettings: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onResult: ((Intent) -> Unit)?,
    viewModel: ViewfinderViewModel,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    // Told before anything below starts the camera, so the first session is the way in's (a selfie opens on the front camera)
    remember(entry) {
        viewModel.enter(entry)
        entry
    }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    // Asked by itself only once ever (not again after the wizard: a second refusal would be for good); Allow always asks, and the system's settings take over
    // only when the system has just refused without its dialog
    val camera = arrayOf(Manifest.permission.CAMERA)
    var asked by rememberSaveable { mutableStateOf(FirstRun.requested(context, Manifest.permission.CAMERA)) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
        permanentlyDenied = !ok && FirstRun.refusedForGood(activity, camera)
        FirstRun.markRequested(context, camera)
    }
    // Access may have been given meanwhile; back from the system's settings (which stop the activity, unlike the permission dialog, whose answer comes
    // before the resume) it may be set to ask again
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { permanentlyDenied = false }
    if (!granted) {
        NoCameraAccess(
            permanentlyDenied = permanentlyDenied,
            onAllow = {
                // Over the lock screen the system's dialog waits for the phone to be unlocked
                unlockThen(activity, entry.locked) {
                    if (permanentlyDenied) FirstRun.openAppSettings(context)
                    else launcher.launch(Manifest.permission.CAMERA)
                }
            },
            onOpenSettings = onOpenSettings,
        )
        LaunchedEffect(Unit) { if (!asked && !entry.locked) launcher.launch(Manifest.permission.CAMERA) }
        return
    }
    Viewfinder(viewModel, entry, onOpenSettings, onCancel, onResult)
}

/**
 * While the viewfinder is shown: no status bar (a swipe brings it for a moment), the display's HDR headroom while the preview is HDR ([hdr]; an SDR preview
 * doesn't need the display's brighter mode), the screen kept on.
 */
@Composable
private fun ViewfinderWindow(keepOn: Boolean, hdr: Boolean) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    // The screen held on only while the viewfinder is in use (see [Viewfinder]); let go, the system's own timeout turns it off
    LaunchedEffect(keepOn) { view.keepScreenOn = keepOn }
    LaunchedEffect(hdr) { activity.window.colorMode = if (hdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT }
    DisposableEffect(activity) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.statusBars())
            window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            view.keepScreenOn = false
        }
    }
}

@Composable
private fun Viewfinder(viewModel: ViewfinderViewModel, entry: Entry, onOpenSettings: (() -> Unit)?, onCancel: (() -> Unit)?, onResult: ((Intent) -> Unit)?) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val numbers = rememberNumbers()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    // The screen stays on while the viewfinder is used: a touch, a volume key or any change of its state or settings counts (not how the phone is held:
    // carried in a hand with the camera open, it still goes to sleep); a recording, a countdown, a motion photo's video and Review hold it all the time.
    // Two minutes after the last use the hold ends and the system's own timeout takes over (owner, 24 Nov 2026)
    var usedAt by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val used = { usedAt = SystemClock.uptimeMillis() }
    val reviewing by viewModel.review.collectAsStateWithLifecycle()
    // A burst's count and its frames being saved change many times a second: read as states where they are shown, here only whether there are any
    val burstCount = viewModel.burstCount.collectAsStateWithLifecycle()
    val burstsSaving = viewModel.burstsSaving.collectAsStateWithLifecycle()
    val bursting by remember { derivedStateOf { burstCount.value != null } }
    val saving by remember { derivedStateOf { burstsSaving.value > 0 } }
    val busy = engine.recording != null || ui.countdown != null || ui.motionWaiting > 0 || reviewing != null || ui.nightGathering || ui.processing > 0 || bursting || saving
    var keepOn by remember { mutableStateOf(true) }
    // A tapped focus that ends because the phone turned isn't use either
    // Use is what the user changes: not what the scene or the phone changes by itself (a dark scene's suggestion coming and going, a code found, progress)
    LaunchedEffect(ui.copy(rotation = 0, focus = null, nightSuggested = false, pointAtCode = false, gatherProgress = null, nightProgress = null, processing = 0), busy) { used() }
    // Back in front (the screen on again, back from another app): a use, so the hold starts over
    LifecycleEventEffect(Lifecycle.Event.ON_START) { used() }
    // The time of the last use read in the effect only: a touch mustn't recompose the whole viewfinder
    LaunchedEffect(busy) {
        if (busy) {
            keepOn = true
            return@LaunchedEffect
        }
        snapshotFlow { usedAt }.collectLatest {
            keepOn = true
            delay(IDLE_MS)
            keepOn = false
        }
    }
    // The screen kept on and HDR only while there is a viewfinder (not over the no-access screen)
    // The last session's range, kept while the next one is made (no switch of the display's mode between two HDR sessions); off while the camera has a
    // problem instead of a picture. A photo for another app is reviewed with its Ultra HDR whatever the preview was
    var hdrWindow by remember { mutableStateOf(engine.range == PictureRange.HLG) }
    LaunchedEffect(engine.range, engine.status) {
        val status = engine.status
        when {
            engine.range != null -> hdrWindow = engine.range == PictureRange.HLG
            status is CameraStatus.Busy || status is CameraStatus.Blocked || status is CameraStatus.Error -> hdrWindow = false
        }
    }
    ViewfinderWindow(keepOn, hdrWindow || reviewing is Review.Photo)
    val last by viewModel.lastShot.collectAsStateWithLifecycle()
    val camera = viewModel.camera
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    val previewSize = remember(shown) { viewModel.previewSize() }
    val frozen by viewModel.frozen.collectAsStateWithLifecycle()

    LifecycleStartEffect(Unit) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    // How the phone is held: the photo's orientation, and the controls turn with it
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                viewModel.rotation = Orientation.rotation(if (orientation == ORIENTATION_UNKNOWN) -1 else orientation, viewModel.rotation)
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    var zoomRulerOpen by remember { mutableStateOf(false) }
    var exposureOpen by remember { mutableStateOf(false) }
    var exposureTouch by remember { mutableLongStateOf(0L) }
    var quickSettings by remember { mutableStateOf(false) }
    LaunchedEffect(exposureOpen, exposureTouch) {
        if (!exposureOpen) return@LaunchedEffect
        delay(EXPOSURE_RULER_MS)
        exposureOpen = false
    }
    LaunchedEffect(ui.focus) { if (ui.focus == null) exposureOpen = false }

    // A burst's tick every ten frames, a stronger one at its last possible frame (LOGIKA §18)
    LaunchedEffect(Unit) { viewModel.burstTick.collect { last -> if (last) haptics.end() else haptics.step() } }
    val blink = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        viewModel.shutter.collect {
            haptics.photo()
            blink.snapTo(1f)
            blink.animateTo(0f, tween(100))
        }
    }
    // Read only where it is drawn, as the blink: an animation frame doesn't recompose the viewfinder. Under a frozen frame it isn't needed (the frame lies
    // over the picture until the new session's first frame); under a change without one the live picture fades out to it (the view model waits for that
    // before the engine is told)
    val covering by viewModel.covering.collectAsStateWithLifecycle()
    val uncovered = (engine.frameShown && !covering) || frozen != null
    val cover = animateFloatAsState(if (uncovered) 0f else 1f, if (uncovered) tween(300) else if (covering) tween(COVER_FADE_MS) else snap(), label = "cover")

    val recording = engine.recording
    // A countdown or a recording closes the panel: its values would rebuild the session under them
    LaunchedEffect(ui.countdown != null, recording != null) { if (ui.countdown != null || recording != null) quickSettings = false }
    // Back while recording ends it and saves, during a countdown cancels it; a second Back leaves (SPEC §3)
    BackHandler(enabled = recording != null || ui.countdown != null) { if (!viewModel.cancelTimer()) viewModel.record() }
    // The volume keys go to the viewfinder while it is shown (SPEC §7.9)
    // Only while this viewfinder is in front, and cleared only when still its own (another activity's may have taken over)
    val keys = remember(viewModel) {
        { event: KeyEvent ->
            used()
            viewModel.onVolumeKey(event)
        }
    }
    LifecycleResumeEffect(keys) {
        HardwareKeys.handler = keys
        onPauseOrDispose { if (HardwareKeys.handler === keys) HardwareKeys.handler = null }
    }
    LaunchedEffect(Unit) { viewModel.countdownTick.collect { haptics.mode() } }
    val turn = controlTurn(ui.rotation)
    val fix by viewModel.location.fix.collectAsStateWithLifecycle()

    // The microphone is asked for on the first entry into Video (SPEC §16); without it videos have no sound
    val activity = LocalActivity.current
    var micGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    // Asked by itself once ever (the wizard's question counts); the chip asks again, or opens the system's settings when the system just refused for good
    val mic = arrayOf(Manifest.permission.RECORD_AUDIO)
    var micAsked by rememberSaveable { mutableStateOf(FirstRun.requested(context, Manifest.permission.RECORD_AUDIO)) }
    var micRefused by rememberSaveable { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micGranted = ok
        micRefused = !ok && FirstRun.refusedForGood(activity, mic)
        FirstRun.markRequested(context, mic)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { micRefused = false }
    LaunchedEffect(ui.mode) {
        // Not over the lock screen: the system's dialog would wait for the phone to be unlocked
        if (ui.mode == Mode.VIDEO && !micGranted && !micAsked && !entry.locked) {
            micAsked = true
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val micBusy = stringResource(R.string.msg_mic_busy)
    val hdrFps = stringResource(R.string.msg_hdr_fps)
    val hdrCodec = stringResource(R.string.msg_hdr_codec)
    val locationDenied = stringResource(R.string.msg_location_denied)
    fun explain(message: String) = scope.launch { snackbars.showSnackbar(message, duration = SnackbarDuration.Short) }
    // Location is asked for when it is first turned on (SPEC §16); refused, it stays off and a snackbar says why
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val final = FirstRun.refusedForGood(activity, result.keys.toTypedArray())
        FirstRun.markRequested(context, result.keys.toTypedArray())
        when {
            result.values.any { it } -> viewModel.setLocation(true)
            // Refused for good: the dialog no longer shows, so the system's settings
            final -> FirstRun.openAppSettings(context)
            else -> explain(locationDenied)
        }
    }
    // On only with its access: a one-time grant that ended or access taken away in the system's settings reads as off (not "searching" forever)
    val locationOn = ui.settings.location && viewModel.location.allowed()
    fun toggleLocation() {
        when {
            locationOn -> viewModel.setLocation(false)
            viewModel.location.allowed() -> viewModel.setLocation(true)
            else -> unlockThen(activity, entry.locked) { locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        }
    }
    val review by viewModel.review.collectAsStateWithLifecycle()
    val phone by viewModel.phoneState.collectAsStateWithLifecycle()
    // The LED's reason, said when its disabled controls are tapped
    val ledReason = stringResource(if (phone.hot) R.string.msg_phone_hot else R.string.msg_flash_battery)
    val ledBlocked = phone.ledBlocked && camera?.front != true
    val flashBattery = stringResource(R.string.msg_flash_battery)
    val stoppedHot = stringResource(R.string.msg_recording_stopped_hot)
    val stoppedCall = stringResource(R.string.msg_recording_stopped_call)
    val stoppedStorage = stringResource(R.string.msg_recording_stopped_storage)
    val notSaved = stringResource(R.string.msg_recording_not_saved_storage)
    val tooHot = stringResource(R.string.msg_too_hot_to_record)
    LaunchedEffect(Unit) {
        viewModel.stopped.collect { stop ->
            explain(
                when (stop.why) {
                    Stop.HOT -> stoppedHot
                    Stop.CALL -> stoppedCall
                    Stop.STORAGE -> if (stop.saved) stoppedStorage else notSaved
                },
            )
        }
    }
    LaunchedEffect(Unit) { viewModel.refused.collect { explain(tooHot) } }
    // After both restarts failed: a snackbar with the error's details (SPEC §14.1)
    val failedTitle = stringResource(R.string.title_camera_failed)
    val show = stringResource(R.string.button_show)
    var errorShown by remember { mutableStateOf<ErrorDetails?>(null) }
    val failedCode = (engine.status as? CameraStatus.Error)?.takeIf { it.failed }?.code
    // In the effect's own scope: a camera that comes back takes the snackbar away
    LaunchedEffect(failedCode) {
        failedCode?.let { code -> reportFailure(snackbars, failedTitle, code, show) { errorShown = it } }
    }
    errorShown?.let { ErrorDetailsDialog(it) { errorShown = null } }
    val capture = entry as? Entry.Capture
    // Back in Review is Retake (SPEC §15.3)
    BackHandler(enabled = review != null) { viewModel.retake() }

    val colors = MaterialTheme.colorScheme
    val problem = engine.status.takeIf { it is CameraStatus.Busy || it is CameraStatus.Blocked || it is CameraStatus.Error }
    // For another app only the mode it asked for (SPEC §15.3)
    val modes = if (capture != null) listOf(ui.mode) else listOfNotNull(Mode.PHOTO, Mode.VIDEO, Mode.NIGHT.takeIf { camera?.night != null })
    val night = ui.mode == Mode.NIGHT
    val postview by viewModel.postviewImage.collectAsStateWithLifecycle()
    val stepsPerThird = camera?.let { (1.0 / 3 / it.exposureStep).roundToInt().coerceAtLeast(1) } ?: 1
    val ev = (camera?.exposureStep ?: 0.0) * ui.exposure

    // The QR code found in the analysis stream, only while the session has one
    val found by viewModel.analyzer.qr.collectAsStateWithLifecycle()
    val qr = found.takeIf { engine.analysis }
    LaunchedEffect(qr != null) { if (qr != null) viewModel.clearPointAtCode() }
    var qrSheet by remember { mutableStateOf<QrContent?>(null) }
    val noApp = stringResource(R.string.msg_no_browser)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            // Every touch anywhere on the viewfinder counts as use, seen before the controls take it
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        used()
                    }
                }
            },
    ) {
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val modeBottom = nav + 2.dp
        val shutterBottom = modeBottom + 36.dp + 14.dp
        val zoomBottom = shutterBottom + 80.dp + 12.dp
        // The status bar's band stays free, hidden or not: icons there sat under the screen's rounded corners and the pulled-down bar (owner, 23 Nov 2026)
        val status = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
        // The area for the picture: from the status bar's band down to the middle of the gap between the shutter and the zoom row (owner, 24 Nov 2026)
        val areaBottom = maxHeight - shutterBottom - 80.dp - 6.dp
        val geometry = remember(maxWidth, status, areaBottom) { FinderGeometry(maxWidth.value, status.value, areaBottom.value) }
        val area = geometry.area
        // The live picture, the square's bands and the photo's frame follow the session the engine was told (Video is 16:9)
        val stream = streamOf(previewSize)
        val sensor = geometry.sensor(stream)
        val streamBox = geometry.content(sensor, stream)
        val shownAspect = shown?.pictureAspect ?: Aspect.FOUR_THREE
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
        // The square's bands slide in over the 4:3 picture only when the session stays (4:3 ↔ 1:1, LOGIKA §23.2); with a new session they are set at once,
        // under the cover (sliding over a fading frame they uncovered or covered its parts – owner, 25 Nov 2026)
        val shownSquare = shownAspect == Aspect.SQUARE && stream == Aspect.FOUR_THREE
        val square = remember { Animatable(if (shownSquare) 1f else 0f) }
        var squareSession by remember { mutableStateOf(shown) }
        LaunchedEffect(shownSquare, shown) {
            val to = if (shownSquare) 1f else 0f
            val before = squareSession
            val now = shown
            squareSession = now
            if (before != null && now != null && viewModel.sameSession(before, now)) square.animateTo(to, spatial) else square.snapTo(to)
        }
        val frame = geometry.photo(sensor, shownAspect)
        val frozenMotion = remember { FrozenMotion() }
        // Its bitmap (about 11 MB) isn't kept while the app is away
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { frozenMotion.held = null }
        val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        LaunchedEffect(frozen) { frozenMotion.follow(frozen, fade) }
        // The photo's frame: where the fading frozen frame's was while it goes, then the live session's (sliding with the square's bands)
        val frameNow = {
            val held = frozenMotion.held ?: frozen
            when {
                held != null && !frozenMotion.incoming -> geometry.photo(geometry.sensor(held.stream), held.aspect)
                stream == Aspect.FOUR_THREE && shownAspect != Aspect.SIXTEEN_NINE -> geometry.photo(sensor, Aspect.FOUR_THREE).lerp(geometry.photo(sensor, Aspect.SQUARE), square.value.coerceIn(0f, 1f))
                else -> frame
            }
        }

        // The picture: the preview's surface, the cover while a session starts, the blink of a shot, the marks and the gestures in the stream's box (which
        // taps and marks refer to); the square's bands, the frozen frame over a session being rebuilt, and over them what belongs to the photo's frame
        Box(Modifier.offset(0.dp, status).size(maxWidth, (area.height).dp).clipToBounds()) {
            Box(Modifier.place(area) { streamBox }) {
                if (previewSize != null && problem == null) {
                    key(previewSize) {
                        AndroidExternalSurface(Modifier.fillMaxSize(), surfaceSize = IntSize(previewSize.width, previewSize.height)) {
                            onSurface { surface, _, _ ->
                                viewModel.setPreviewSurface(surface, previewSize)
                                surface.onDestroyed { viewModel.clearPreviewSurface(surface) }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxSize().drawBehind { drawRect(colors.surface.copy(alpha = cover.value)) })
                // The square's bands are solid (owner, 25 Nov 2026: translucent ones looked poor, being thin), under the marks: a tap there focuses and shows it
                if (problem == null && stream == Aspect.FOUR_THREE) {
                    Canvas(Modifier.fillMaxSize()) {
                        val band = (size.height - size.width).coerceAtLeast(0f) / 2 * square.value.coerceIn(0f, 1f)
                        if (band > 0f) {
                            drawRect(colors.surface, size = Size(size.width, band))
                            drawRect(colors.surface, topLeft = Offset(0f, size.height - band), size = Size(size.width, band))
                        }
                    }
                }
                if (problem == null) {
                    FinderMarks(
                        focus = ui.focus,
                        focusing = engine.focusing,
                        locked = ui.locked,
                        exposure = if (ui.exposure != 0 || exposureOpen) numbers.exposure(ev) else null,
                        face = engine.face,
                    )
                    qr?.let { if (it.corners.isNotEmpty()) QrMarks(it.corners) }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        viewModel.focusAt(Point((offset.x / size.width).toDouble(), (offset.y / size.height).toDouble()))
                                        // Night's extension has no exposure compensation
                                        if (ui.mode != Mode.NIGHT) exposureOpen = true
                                        exposureTouch = System.currentTimeMillis()
                                    },
                                    onLongPress = { offset ->
                                        haptics.lock()
                                        viewModel.lockAt(Point((offset.x / size.width).toDouble(), (offset.y / size.height).toDouble()))
                                        exposureOpen = false
                                    },
                                )
                            }
                            .pointerInput(ui.mode, modes) {
                                // A horizontal swipe on the picture changes the mode by one (the modes of this camera: Night only where it has it)
                                var travel = 0f
                                detectHorizontalDragGestures(onDragStart = { travel = 0f }) { change, amount ->
                                    travel += amount
                                    val step = MODE_SWIPE_DP.dp.toPx()
                                    val index = modes.indexOf(ui.mode)
                                    if (abs(travel) >= step) {
                                        val next = if (travel < 0) index + 1 else index - 1
                                        travel = 0f
                                        modes.getOrNull(next)?.let {
                                            haptics.mode()
                                            viewModel.selectMode(it)
                                        }
                                    }
                                    change.consume()
                                }
                            },
                    )
                }
            }
            FrozenLayer(frozenMotion, frozen, geometry, colors.surface)
            // The blink of a shot over everything of the picture (a shot that waited for a new session blinks as it comes in)
            Box(Modifier.place(area, frameNow).drawBehind { drawRect(colors.surface.copy(alpha = blink.value)) })
            // What belongs to the photo's frame fades with the picture over a new session (its new place isn't seen jumping)
            Box(Modifier.place(area, frameNow).graphicsLayer { alpha = minOf(frozenMotion.shown(frozen), 1f - cover.value) }, contentAlignment = Alignment.Center) {
                if (problem == null) {
                    // Night's early postview over the photo's frame until its file is out (SPEC §9.2)
                    postview?.takeIf { night }?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    // The tilt read in here only: it changes many times a second and must not recompose the whole viewfinder
                    Level(viewModel, ui.settings.grid, ui.settings.level, ui.rotation)
                    // Hold still: the crosshairs while a Night photo gathers its frames
                    if (ui.nightGathering && camera != null) NightCrosshairs(camera.lensView, ui.zoom, if (ui.settings.aspect == Aspect.SIXTEEN_NINE) 9.0 / 16 else 0.75, Modifier.fillMaxSize())
                }
            }
            // Not of the picture: the countdown and the full storage stay seen whatever the picture does
            Box(Modifier.place(area, frameNow), contentAlignment = Alignment.Center) {
                if (problem == null) {
                    ui.countdown?.let { Countdown(it, turn, Modifier.align(Alignment.Center)) }
                    // Storage full: the picture stays, the shutter waits (SPEC §14.1)
                    if (phone.storageFull && recording == null) StorageFull(activity, entry.locked, Modifier.align(Alignment.Center).padding(16.dp))
                }
            }
            if (problem != null) CameraProblem(problem, onRetry = { viewModel.retry() }, modifier = Modifier.align(Alignment.Center).padding(16.dp))
        }
        val pictureTop = (frame.top - area.top).dp

        // The top row (SPEC §4.3, §6.3): state icons in two groups beside the camera's cutout; while recording only the flashlight
        if (problem == null && camera != null) {
            val video = ui.mode == Mode.VIDEO
            val settings = ui.settings
            Row(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = topRowTop(status, start = 12.dp, end = 140.dp)), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (video && camera.flash) {
                    TopButton(if (ui.torch) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off, stringResource(R.string.tooltip_flashlight), null, filled = ui.torch, turn = turn, enabled = !ledBlocked, onDisabled = { explain(ledReason) }) { viewModel.toggleTorch() }
                }
                // Night's extension takes no flash
                if (!video && !night && (camera.flash || camera.front)) {
                    MenuButton(listOf(Flash.entries.map { f -> MenuChoice(stringResource(flashName(f)), f == ui.flash) { viewModel.setFlash(f) } })) { open ->
                        // Automatic: the button's background shows when the flash will fire (the exposure asks for it, or the scene is dark)
                        val willFire = ui.flash == Flash.AUTO && if (camera.front) engine.dark else engine.flashNeeded
                        // Heat or a low battery: the flash shows off and says why (SPEC §14.1)
                        if (ledBlocked) {
                            TopButton(R.drawable.ic_flash_off, stringResource(R.string.tooltip_flash), stringResource(flashState(Flash.OFF)), filled = false, turn = turn, enabled = false, onDisabled = { explain(ledReason) }) {}
                        } else {
                            TopButton(flashIcon(ui.flash), stringResource(R.string.tooltip_flash), stringResource(flashState(ui.flash)), filled = ui.flash != Flash.OFF, turn = turn, accent = willFire, onClick = open)
                        }
                    }
                }
                if (recording == null && ui.countdown == null) {
                    MenuButton(listOf(listOf(0, 3, 10).map { t -> MenuChoice(if (t == 0) stringResource(R.string.menu_timer_off) else stringResource(R.string.value_seconds, t), t == ui.timer) { viewModel.setTimer(t) } })) { open ->
                        TopButton(timerIcon(ui.timer), stringResource(R.string.tooltip_timer), null, filled = ui.timer != 0, turn = turn, onClick = open)
                    }
                    val searching = locationOn && (fix == null || viewModel.location.current() == null)
                    TopButton(
                        if (!locationOn) R.drawable.ic_location_off else if (searching) R.drawable.ic_location_searching else R.drawable.ic_location_on,
                        stringResource(R.string.tooltip_location),
                        stringResource(if (!locationOn) R.string.desc_state_location_off else if (searching) R.string.desc_state_location_searching else R.string.desc_state_location_on),
                        filled = locationOn,
                        turn = turn,
                    ) { toggleLocation() }
                }
            }
            // Nothing that rebuilds the session while a countdown runs or a recording goes on
            if (recording == null && ui.countdown == null && !ui.nightGathering && !bursting) {
                Row(Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = topRowTop(status, start = maxWidth - 148.dp, end = maxWidth - 12.dp)), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Another app's low-quality video is SDR whatever the setting: no HDR to switch
                    val hdrAvailable = when {
                        video -> camera.hlg && capture?.lowQuality != true
                        night -> camera.night?.jpegR?.isNotEmpty() == true
                        else -> camera.jpegR.isNotEmpty()
                    }
                    if (hdrAvailable) {
                        val possible = !video || viewModel.videoHdrPossible
                        val on = (if (video) settings.hdrVideo else settings.ultraHdr) && possible
                        TopButton(
                            if (on) R.drawable.ic_hdr_on else R.drawable.ic_hdr_off,
                            stringResource(R.string.tooltip_hdr),
                            stringResource(if (on) R.string.desc_state_hdr_on else R.string.desc_state_hdr_off),
                            // HDR on is the default; off is the value that stands out
                            filled = !on,
                            turn = turn,
                            enabled = possible,
                            onDisabled = { explain(if (settings.videoHevc) hdrFps else hdrCodec) },
                        ) { viewModel.toggleHdr() }
                    }
                    // Not for another app's photo: it never gets a motion photo (SPEC §15.3)
                    if (!video && !night && capture == null) {
                        MenuButton(listOf(MotionPhoto.entries.map { m -> MenuChoice(stringResource(motionName(m)), m == settings.motionPhoto) { viewModel.setMotionPhoto(m) } })) { open ->
                            TopButton(motionIcon(settings.motionPhoto), stringResource(R.string.tooltip_motion_photo), stringResource(motionState(settings.motionPhoto)), filled = settings.motionPhoto != MotionPhoto.AUTO, turn = turn, onClick = open)
                        }
                    }
                    if (video && capture?.lowQuality != true) {
                        val uhdAvailable = SessionLadder.UHD in camera.video
                        // A hot phone has no 4K at 60 fps (SPEC §14.1): not offered then
                        val rates = videoRates(camera.fixedFps).filter { !(phone.hot && settings.videoUhd && it > 30) }
                        val groups = buildList {
                            if (uhdAvailable) add(listOf(MenuChoice(stringResource(R.string.menu_resolution_4k), settings.videoUhd) { viewModel.setVideoSize(true) }, MenuChoice(stringResource(R.string.menu_resolution_1080p), !settings.videoUhd) { viewModel.setVideoSize(false) }))
                            add(rates.map { fps -> MenuChoice(stringResource(R.string.value_fps, fps), fps == viewModel.videoFps, if (fps > SessionLadder.HLG_MAX_FPS && camera.hlg) stringResource(R.string.desc_fps_no_hdr) else null) { viewModel.setVideoFps(fps) } })
                        }
                        // What the session records, once it runs (the ladder may have fallen back from the choice)
                        val recorded = engine.video
                        val uhd = recorded?.let { it.size.width >= SessionLadder.UHD.width } ?: (settings.videoUhd && uhdAvailable)
                        val label = stringResource(R.string.value_video_quality, stringResource(if (uhd) R.string.menu_resolution_4k else R.string.menu_resolution_1080p), recorded?.fps ?: viewModel.videoFps)
                        // Held sideways the pill is too narrow for "4K 30" turned: "4K" over "30"
                        val sideways = ui.rotation == 90 || ui.rotation == 270
                        val stacked = if (sideways) listOf(stringResource(if (uhd) R.string.menu_resolution_4k else R.string.menu_resolution_1080p), "${recorded?.fps ?: viewModel.videoFps}") else null
                        MenuButton(groups) { open -> TopTextButton(label, stringResource(R.string.tooltip_video_quality), filled = false, turn = turn, stacked = stacked, onClick = open) }
                    } else {
                        MenuButton(listOf(Aspect.entries.map { a -> MenuChoice(aspectText(a), a == settings.aspect) { viewModel.setAspect(a) } })) { open ->
                            TopTextButton(aspectText(settings.aspect), stringResource(R.string.tooltip_aspect_ratio), filled = settings.aspect != Aspect.FOUR_THREE, turn = turn, onClick = open)
                        }
                    }
                }
            }
        }
        // The recording's time, in line with the top row, in its free middle
        if (recording != null) RecordingTime(recording, Modifier.align(Alignment.TopCenter).padding(top = status + 10.dp))
        // Indicators at the picture's top-start, one under another (SPEC §4.4): over the lock screen, for another app, a motion photo's video still
        // recording (SPEC §7.6), no sound in Video
        val noSound = ui.mode == Mode.VIDEO && problem == null && (!micGranted || recording?.silenced == true)
        Column(
            Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = status + maxOf(52.dp, pictureTop) + 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entry.locked) IndicatorChip(R.drawable.ic_lock, null, description = stringResource(R.string.content_description_locked)) {}
            capture?.caller?.let { IndicatorChip(R.drawable.ic_apps, stringResource(R.string.label_for_app, it)) {} }
            if (ui.motionWaiting > 0) IndicatorChip(R.drawable.ic_motion_photos_on, stringResource(R.string.label_motion), solid = true) {}
            // The phone's states (SPEC §14.1): heat, a low battery, little room left
            if (phone.hot) IndicatorChip(R.drawable.ic_device_thermostat, stringResource(R.string.label_phone_hot)) { explain(ledReason) }
            if (phone.lowBattery) IndicatorChip(R.drawable.ic_battery_alert, stringResource(R.string.label_battery_level, phone.battery ?: 0)) { explain(flashBattery) }
            roomLeft(phone, ui.mode == Mode.VIDEO, engine.video?.bitrate, ui.settings.motionPhoto != MotionPhoto.OFF)?.let { left ->
                IndicatorChip(R.drawable.ic_storage, if (left.minutes) stringResource(R.string.label_minutes_left, left.count) else pluralStringResource(R.plurals.label_photos_left, left.count, left.count)) {}
            }
            if (noSound) {
                IndicatorChip(R.drawable.ic_mic_off, stringResource(R.string.label_no_sound)) {
                    if (!micGranted) {
                        unlockThen(activity, entry.locked) {
                            if (micRefused) FirstRun.openAppSettings(context) else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        scope.launch { snackbars.showSnackbar(micBusy, duration = SnackbarDuration.Short) }
                    }
                }
            }
        }
        // Snackbars 8 dp above the suggestion slot (SPEC §14.2)
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).padding(bottom = zoomBottom + 44.dp + 36.dp + 32.dp + 8.dp)) { data ->
            Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
        }

        // The suggestion slot: the zoom value over the ruler's needle, the AE/AF lock
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = zoomBottom + 44.dp + 36.dp).height(32.dp)) {
            when {
                zoomRulerOpen -> SolidChip(stringResource(R.string.value_zoom, numbers.zoom(ui.zoom)), primary = true)
                // Macro (SPEC §4.5, above the lock): the camera took the ultrawide by itself; it can't be told not to, so the chip only says so (D8)
                engine.macro && !exposureOpen -> SuggestionChip(R.drawable.ic_macro_auto, stringResource(R.string.label_macro), onDismiss = null)
                ui.locked && !exposureOpen -> SuggestionChip(R.drawable.ic_lock, stringResource(R.string.label_ae_af_locked), onDismiss = { viewModel.unlock() })
                // A QR code: its domain, network or first words; a tap opens what it holds (SPEC §7.10)
                qr != null && !exposureOpen -> SuggestionChip(qrIcon(qr.content), qrChipText(qr.content), onDismiss = null, onClick = { qrSheet = qr.content })
                // From the QR code shortcut, until a code is found (SPEC §15.5)
                ui.pointAtCode && ui.mode == Mode.PHOTO && !exposureOpen -> SuggestionChip(R.drawable.ic_qr_code_scanner, stringResource(R.string.label_point_at_code), onDismiss = { viewModel.clearPointAtCode() })
                // A Night photo gathering its frames (SPEC §9.2)
                ui.nightGathering -> SuggestionChip(R.drawable.ic_back_hand, stringResource(R.string.label_hold_still), onDismiss = null)
                // Dark for a while in Photo: Night offered, never switched to by itself (SPEC §9.1)
                ui.nightSuggested && ui.mode == Mode.PHOTO && camera?.night != null && !exposureOpen -> SuggestionChip(R.drawable.ic_bedtime, stringResource(R.string.label_suggest_night), onDismiss = { viewModel.closeNightSuggestion() }, onClick = { viewModel.selectMode(Mode.NIGHT) })
            }
        }
        // The exposure ruler above the zoom row, in the suggestion slot's place
        if (exposureOpen && camera != null) {
            ExposureRuler(
                steps = ui.exposure,
                stepEv = camera.exposureStep,
                stepsPerThird = stepsPerThird,
                limits = camera.exposureSteps,
                onSteps = {
                    exposureTouch = System.currentTimeMillis()
                    viewModel.setExposure(it)
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = zoomBottom + 44.dp + 12.dp),
            )
        }

        ZoomRow(
            scale = camera?.zoom,
            zoom = ui.zoom,
            rulerOpen = zoomRulerOpen,
            onRulerOpen = { zoomRulerOpen = it },
            onZoom = { viewModel.zoomTo(it) },
            onQuickSettings = { quickSettings = true },
            quickSettingsOpen = quickSettings,
            // Not during a countdown either: the panel's values rebuild the session the timed shot waits for
            showQuickSettings = recording == null && ui.countdown == null && !ui.nightGathering && !bursting,
            turn = turn,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = zoomBottom - 4.dp),
        )

        // The shutter row: the last shot, the shutter, the other camera
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = shutterBottom).fillMaxWidth().height(80.dp)) {
            if (recording != null) {
                // While recording: pause or resume in the thumbnail's place, a photo in the camera switch's
                val paused = recording.pausedAt != null
                RowButton(if (paused) R.drawable.ic_play_arrow else R.drawable.ic_pause, stringResource(if (paused) R.string.tooltip_resume else R.string.tooltip_pause), turn, Modifier.align(Alignment.CenterStart).padding(start = 34.dp)) {
                    if (paused) viewModel.resumeRecording() else viewModel.pauseRecording()
                }
                // Not for another app: such a photo would land in the gallery
                if (engine.video != null && engine.range != null && capture == null) {
                    RowButton(R.drawable.ic_photo_camera, stringResource(R.string.tooltip_photo_while_recording), turn, Modifier.align(Alignment.CenterEnd).padding(end = 34.dp)) { viewModel.shoot() }
                }
            } else {
                // For another app Cancel takes the thumbnail's place (SPEC §15.3); over the lock screen the thumbnail shows this session's shots
                if (capture != null) {
                    RowButton(R.drawable.ic_close, stringResource(R.string.button_cancel), turn, Modifier.align(Alignment.CenterStart).padding(start = 34.dp)) { onCancel?.invoke() }
                } else {
                    Thumbnail(last, turn, { ui.processing + burstsSaving.value }, ui.nightProgress, Modifier.align(Alignment.CenterStart).padding(start = 34.dp)) { shot ->
                        if (entry.locked) reviewSecure(context, activity, viewModel.sessionShots(), shot) else openInGallery(context, shot)
                    }
                }
                if (viewModel.cameras.size > 1 && !ui.nightGathering && !bursting) SwitchCameraButton(turn, onClick = { viewModel.switchCamera() }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 34.dp))
            }
            val video = ui.mode == Mode.VIDEO
            val counting = ui.countdown != null
            ShutterButton(
                look = when {
                    bursting -> ShutterLook.BURST
                    counting -> ShutterLook.TIMER
                    ui.nightGathering -> ShutterLook.NIGHT
                    recording != null -> ShutterLook.RECORDING
                    video -> ShutterLook.VIDEO
                    else -> ShutterLook.PHOTO
                },
                label = stringResource(
                    when {
                        counting -> R.string.content_description_shutter_cancel_timer
                        ui.nightGathering -> R.string.label_hold_still
                        recording != null -> R.string.content_description_shutter_stop
                        video -> R.string.content_description_shutter_record
                        else -> R.string.content_description_shutter_photo
                    },
                ),
                enabled = engine.status == CameraStatus.Ready && (!video || (engine.video != null && !engine.finishing)) && (!phone.storageFull || recording != null) && !ui.nightGathering && engine.request?.let { it.mode == ui.mode && it.camera.id == camera?.id } == true,
                progress = if (ui.nightGathering) ui.gatherProgress else ui.countdown?.let { 1f - it.toFloat() / ui.timer.coerceAtLeast(1) },
                turn = turn,
                count = { burstCount.value },
                onClick = {
                    if (video && !counting && ui.timer == 0) {
                        if (recording != null) haptics.photo() else haptics.lock()
                    }
                    viewModel.shutterPressed()
                },
                // Held in Photo from the app, without the timer: a burst (LOGIKA §18)
                onHold = if (ui.mode == Mode.PHOTO && capture == null && ui.timer == 0 && !counting) ({ viewModel.shutterHeld() }) else null,
                onRelease = viewModel::shutterReleased,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (recording == null && ui.countdown == null && !ui.nightGathering && !bursting) ModeRow(
            modes = modes,
            selected = ui.mode,
            label = {
                stringResource(
                    when (it) {
                        Mode.VIDEO -> R.string.label_mode_video
                        Mode.NIGHT -> R.string.label_mode_night
                        Mode.PHOTO -> R.string.label_mode_photo
                    },
                )
            },
            onSelect = {
                if (it != ui.mode) haptics.mode()
                viewModel.selectMode(it)
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = modeBottom),
        )
    }

    val wifiRefused = stringResource(R.string.msg_wifi_refused)
    val noContactsApp = stringResource(R.string.msg_no_contacts_app)
    qrSheet?.let { content ->
        QrSheet(
            content,
            locked = entry.locked,
            onDismiss = { qrSheet = null },
            onNoApp = { explain(noApp) },
            // The snackbar would stand behind the sheet
            onWifiRefused = {
                qrSheet = null
                explain(wifiRefused)
            },
            onNoContactsApp = {
                qrSheet = null
                explain(noContactsApp)
            },
        )
    }

    if (ui.screenFlash) ScreenFlash(viewModel::onScreenLit)

    review?.let { shot ->
        val notHanded = stringResource(R.string.msg_capture_not_saved)
        // Use once: a second tap while the file is written would hand it over twice
        var delivering by remember(shot) { mutableStateOf(false) }
        ReviewScreen(
            shot,
            onRetake = { if (!delivering) viewModel.retake() },
            onUse = {
                if (!delivering) {
                    delivering = true
                    scope.launch {
                        val result = viewModel.deliver()
                        if (result != null) onResult?.invoke(result) else explain(notHanded)
                        delivering = false
                    }
                }
            },
            snackbars = snackbars,
        )
    }

    if (quickSettings && camera != null) {
        val settings = ui.settings
        ModalBottomSheet(onDismissRequest = { quickSettings = false }, containerColor = colors.surfaceContainerLow) {
            QuickSettings(
                PanelState(
                    video = ui.mode == Mode.VIDEO,
                    flashAvailable = !night && ((camera.flash && !ledBlocked) || camera.front),
                    flash = ui.flash,
                    flashLevels = if (camera.front) 1 else camera.flashLevels,
                    flashLevel = ui.flashLevel ?: camera.flashDefaultLevel,
                    timer = ui.timer,
                    aspect = settings.aspect,
                    grid = settings.grid,
                    level = settings.level,
                    hdr = settings.hdrVideo,
                    hdrPossible = viewModel.videoHdrPossible && camera.hlg,
                    uhdAvailable = SessionLadder.UHD in camera.video,
                    uhd = settings.videoUhd,
                    fpsChoices = videoRates(camera.fixedFps).filter { !(phone.hot && settings.videoUhd && it > 30) },
                    fps = viewModel.videoFps,
                    torch = ui.torch,
                    torchAvailable = camera.flash && !ledBlocked,
                    ultraHdrAvailable = camera.jpegR.isNotEmpty(),
                    motionPhoto = settings.motionPhoto,
                    motionAvailable = capture == null && !night,
                    hdrAvailable = camera.hlg && capture?.lowQuality != true,
                    qualityAvailable = capture?.lowQuality != true,
                    ultraHdr = settings.ultraHdr,
                    location = locationOn,
                ),
                PanelActions(
                    onFlash = viewModel::setFlash,
                    onFlashLevel = viewModel::setFlashLevel,
                    onTimer = viewModel::setTimer,
                    onAspect = { viewModel.setAspect(it) },
                    onGrid = { viewModel.setGrid(it) },
                    onLevel = { viewModel.setLevel(it) },
                    onHdr = {
                        if (viewModel.videoHdrPossible) {
                            viewModel.toggleHdr()
                        } else {
                            // The snackbar would stand behind the sheet
                            quickSettings = false
                            explain(if (settings.videoHevc) hdrFps else hdrCodec)
                        }
                    },
                    onUltraHdr = { viewModel.toggleHdr() },
                    onMotionPhoto = { viewModel.setMotionPhoto(it) },
                    onLocation = {
                        if (!ui.settings.location && !viewModel.location.allowed()) quickSettings = false
                        toggleLocation()
                    },
                    onUhd = { viewModel.setVideoSize(it) },
                    onFps = { viewModel.setVideoFps(it) },
                    onTorch = viewModel::toggleTorch,
                    onSettings = onOpenSettings?.let { open ->
                        {
                            quickSettings = false
                            open()
                        }
                    },
                ),
            )
        }
    }

}

/** The exposure ruler: ±2 EV in thirds, a detent at zero; dragged directly. */
@Composable
private fun ExposureRuler(steps: Int, stepEv: Double, stepsPerThird: Int, limits: IntRange, onSteps: (Int) -> Unit, modifier: Modifier) {
    val haptics = rememberHaptics()
    val numbers = rememberNumbers()
    val scale = remember { LinearScale(-EV_RANGE, EV_RANGE, 60f) }
    val ticks = remember(numbers) {
        (-6..6).map { third ->
            val value = third / 3.0
            val whole = third % 3 == 0
            Tick(value, major = whole, label = if (whole) numbers.exposure(value) else null, detent = third == 0)
        }
    }
    var position by remember { mutableFloatStateOf(scale.position(steps * stepEv)) }
    var current by remember { mutableIntStateOf(steps) }
    ValueRuler(
        scale,
        current * stepEv,
        ticks,
        modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(onDragStart = { position = scale.position(current * stepEv) }) { change, amount ->
                position = (position - amount / density).coerceIn(scale.position(scale.min), scale.position(scale.max))
                val thirds = (scale.value(position) * 3).roundToInt()
                val next = (thirds * stepsPerThird).coerceIn(limits)
                if (next != current) {
                    if (next == 0) haptics.detent() else haptics.step()
                    current = next
                    onSteps(next)
                }
                change.consume()
            }
        },
    )
}

/** The thumbnail of the newest own shot (SPEC §8.1); a tap hands it to the gallery with the rest to swipe through. */
@Composable
private fun Thumbnail(last: LastShot?, turn: Float, processing: () -> Int, progress: Float?, modifier: Modifier, onOpen: (LastShot) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.tooltip_last_shot)
    // The alignment goes on a box of our own: TooltipBox doesn't pass it to the row
    Box(modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceContainerHigh)
                    .clickable(enabled = last != null, role = Role.Button) { last?.let(onOpen) }
                    .semantics { contentDescription = label },
            ) {
                val bitmap = last?.thumbnail
                if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().rotate(turn))
                else Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null, tint = IconButtonDefaults.iconButtonColors().disabledContentColor, modifier = Modifier.rotate(turn))
            }
        }
        // The cover of a burst: how many frames it has, at the bottom-end corner (LOGIKA §18)
        val frames = last?.burst ?: 0
        if (frames > 1) {
            Row(
                Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 6.dp).background(colors.secondaryContainer, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp).rotate(turn),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(painterResource(R.drawable.ic_burst_mode), contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(12.dp))
                Text("$frames", color = colors.onSecondaryContainer, style = MaterialTheme.typography.labelSmall)
            }
        }
        // Photos still being put together (SPEC §8.2): the newest one's progress at the top-end corner, how many at the top-start
        val waiting = processing()
        if (waiting > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).size(26.dp).background(colors.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val value = progress
                if (value != null) CircularWavyProgressIndicator(progress = { value }, modifier = Modifier.size(20.dp))
                else CircularWavyProgressIndicator(modifier = Modifier.size(20.dp))
            }
            Badge(Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-6).dp), containerColor = colors.primary, contentColor = colors.onPrimary) { Text("$waiting") }
        }
    }
}

/**
 * Over the lock screen (SPEC §15.2, LOGIKA §7): `ACTION_REVIEW_SECURE` with this session's shots only, the newest first; a gallery that can't show them over
 * the lock screen gets them after the phone is unlocked.
 */
private fun reviewSecure(context: Context, activity: Activity?, shots: List<MediaFiles.Saved>, newest: LastShot) {
    // The newest few hundred: a session of several bursts would not fit in one intent
    val uris = shots.map { it.uri }.take(REVIEW_SECURE_MAX).ifEmpty { listOf(newest.uri) }
    val intent = Intent(MediaStore.ACTION_REVIEW_SECURE).setDataAndType(newest.uri, newest.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
        clipData = ClipData.newRawUri("", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        unlockThen(activity, true) { openInGallery(context, newest) }
    }
}

/** `ACTION_REVIEW` (any gallery that supports it shows the shot with the others to swipe), else a plain view. */
private fun openInGallery(context: Context, shot: LastShot) {
    for (action in listOf(MediaStore.ACTION_REVIEW, Intent.ACTION_VIEW)) {
        try {
            context.startActivity(Intent(action).setDataAndType(shot.uri, shot.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}

/** The other camera: a 52 dp tonal circle whose icon turns half a turn on each tap. */
@Composable
private fun SwitchCameraButton(turn: Float, onClick: () -> Unit, modifier: Modifier) {
    var turns by remember { mutableIntStateOf(0) }
    val angle by animateFloatAsState(turns * 180f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "switch")
    val label = stringResource(R.string.tooltip_switch_camera)
    // The alignment goes on a box of our own: TooltipBox doesn't pass it to the row
    Box(modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
        ) {
            FilledTonalIconButton(
                onClick = {
                    turns++
                    onClick()
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(painterResource(R.drawable.ic_cameraswitch), contentDescription = label, modifier = Modifier.size(24.dp).rotate(angle + turn))
            }
        }
    }
}

/** A suggestion chip (SPEC §4.5): 32 dp, small corners, `secondaryContainer`, a filled icon and an optional close. */
@Composable
private fun SuggestionChip(icon: Int, text: String, onDismiss: (() -> Unit)?, onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(8.dp), color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer, modifier = Modifier.height(32.dp).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, end = if (onDismiss == null) 12.dp else 0.dp)) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.content_description_dismiss), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** A value chip without an action (the zoom's value above the ruler's needle). */
@Composable
private fun SolidChip(text: String, primary: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = if (primary) colors.primaryContainer else colors.secondaryContainer, modifier = Modifier.height(32.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** What keeps the camera from showing a picture, in the picture's place (SPEC §14). */
@Composable
private fun CameraProblem(status: CameraStatus, onRetry: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val (title, text) = when (status) {
        CameraStatus.Busy -> stringResource(R.string.title_camera_busy) to stringResource(R.string.desc_camera_busy)
        CameraStatus.Blocked -> null to stringResource(R.string.desc_camera_blocked_admin)
        is CameraStatus.Error -> if (status.failed) stringResource(R.string.title_camera_failed) to stringResource(R.string.desc_camera_failed) else stringResource(R.string.title_camera_restarting) to stringResource(R.string.desc_camera_restarting)
        else -> null to ""
    }
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status is CameraStatus.Error && !status.failed) LoadingIndicator(Modifier.size(40.dp))
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            // Busy comes back by itself when the camera is free; the button is for when the system doesn't say so
            if ((status is CameraStatus.Error && status.failed) || status == CameraStatus.Busy) {
                Button(onClick = onRetry, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.button_restart_camera)) }
            }
        }
    }
}

/** How many more shots fit: photos under 100 or video minutes under 15 (SPEC §14.1); null with more room. */
private class RoomLeft(val count: Int, val minutes: Boolean)

private fun roomLeft(phone: PhoneState, video: Boolean, bitrate: Int?, motion: Boolean): RoomLeft? {
    val free = phone.freeBytes ?: return null
    if (phone.storageFull) return null
    return if (video) {
        // The video's own bit rate where the session runs, else 4K's; sound on top
        val perSecond = ((bitrate ?: UHD_BITS) + AUDIO_BITS) / 8L
        (free / perSecond / 60).toInt().takeIf { it < LOW_MINUTES }?.let { RoomLeft(it, minutes = true) }
    } else {
        // An Ultra HDR photo of the default size, with a motion photo's video when they are on
        val perPhoto = if (motion) PHOTO_BYTES + MOTION_BYTES else PHOTO_BYTES
        (free / perPhoto).toInt().takeIf { it < LOW_PHOTOS }?.let { RoomLeft(it, minutes = false) }
    }
}

private const val UHD_BITS = 50_000_000
private const val AUDIO_BITS = 256_000
private const val PHOTO_BYTES = 5L * 1024 * 1024
private const val MOTION_BYTES = 3L * 1024 * 1024
private const val LOW_PHOTOS = 100
private const val LOW_MINUTES = 15

/** Storage full (SPEC §14.1): the family's problem card over the picture, with the way to the system's storage settings. */
@Composable
private fun StorageFull(activity: Activity?, locked: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.title_storage_full), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.desc_storage_full), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            // Over the lock screen the settings wait for the phone to be unlocked
            Button(onClick = {
                unlockThen(activity, locked) { runCatching { context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) } }
            }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.button_open_storage_settings)) }
        }
    }
}

/** Without camera access the viewfinder shows only this (SPEC §16): the app's mark, why, the way to allow it, and Settings. */
@Composable
private fun NoCameraAccess(permanentlyDenied: Boolean, onAllow: () -> Unit, onOpenSettings: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.navigationBars)) {
        EmptyState(
            R.drawable.ic_launcher_monochrome,
            stringResource(R.string.desc_no_camera_access),
            modifier = Modifier.weight(1f),
            // The app's mark is drawn on the launcher icon's canvas, hence the larger size for a 64 dp mark
            iconSize = 96.dp,
        ) {
            Button(onClick = onAllow) {
                Text(stringResource(if (permanentlyDenied) R.string.button_open_app_settings else R.string.button_allow_access), textAlign = TextAlign.Center)
            }
        }
        if (onOpenSettings != null) TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.button_settings))
        }
    }
}

/** A 52 dp tonal circle of the shutter row (pause, resume, photo while recording). */
@Composable
private fun RowButton(icon: Int, label: String, turn: Float, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
        ) {
            FilledTonalIconButton(
                onClick = onClick,
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp).rotate(turn))
            }
        }
    }
}

/** The recording's time (SPEC §4.4): a solid chip with the red dot (owner, 23 Nov 2026) and tabular figures; paused, the time blinks once a second. */
@Composable
private fun RecordingTime(recording: Recording, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(recording) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(TIME_TICK_MS)
        }
    }
    val end = recording.pausedAt ?: now
    val seconds = ((end - recording.startedAt - recording.pausedMs) / 1000).coerceAtLeast(0)
    val shown = recording.pausedAt == null || (now / 500) % 2 == 0L
    val recordingLabel = stringResource(R.string.content_description_recording)
    Surface(shape = CircleShape, color = colors.surfaceContainerHigh, modifier = modifier.height(32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(colors.error).semantics { contentDescription = recordingLabel })
            Spacer(Modifier.width(8.dp))
            Text(
                duration(seconds),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.alpha(if (shown) 1f else 0f),
            )
        }
    }
}

/** A length as "0:42", "12:05" or "1:02:03". */
internal fun duration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds / 60 % 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

/** An indicator chip (SPEC §4.4): 28 dp, solid, with an icon and a short label (or the icon alone, [description] for the screen reader); a tap explains it. */
@Composable
private fun IndicatorChip(icon: Int, label: String?, modifier: Modifier = Modifier, description: String? = null, solid: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .height(28.dp)
            .controlSurface(CircleShape, if (solid) colors.secondaryContainer else controlColor())
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { description?.let { contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = if (label == null) 6.dp else 10.dp)) {
            // The content color of the chip's own container: the screen's default (black) would vanish on it
            val content = if (solid) colors.onSecondaryContainer else colors.onSurface
            Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            if (label != null) {
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
            }
        }
    }
}

private const val TIME_TICK_MS = 250L

/**
 * Where a group of the top row sits (SPEC §4.3): 6 dp under the status bar's band, as long as it keeps 16 dp from the camera cutout's bounds; a group that would
 * collide with a cutout reaching lower moves below it.
 */
@Composable
private fun topRowTop(status: Dp, start: Dp, end: Dp): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val cutouts = view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
    return with(density) {
        val left = (start - 16.dp).toPx()
        val right = (end + 16.dp).toPx()
        val top = status.toPx()
        val below = cutouts.filter { it.bottom > top && it.top < top + 52.dp.toPx() && it.right > left && it.left < right }.maxOfOrNull { it.bottom }
        if (below == null) status + 6.dp else below.toDp() + 6.dp
    }
}

/** The frame rates the camera holds for video, of 24, 30 and 60 (30 always: the ladder falls back to it). */
fun videoRates(fixed: Set<Int>): List<Int> = listOf(24, 30, 60).filter { it == 30 || it in fixed }

@DrawableRes
fun motionIcon(mode: MotionPhoto) = when (mode) {
    MotionPhoto.OFF -> R.drawable.ic_motion_photos_off
    MotionPhoto.AUTO -> R.drawable.ic_motion_photos_auto
    MotionPhoto.ON -> R.drawable.ic_motion_photos_on
}

fun motionName(mode: MotionPhoto) = when (mode) {
    MotionPhoto.OFF -> R.string.menu_motion_off
    MotionPhoto.AUTO -> R.string.menu_motion_auto
    MotionPhoto.ON -> R.string.menu_motion_on
}

fun motionState(mode: MotionPhoto) = when (mode) {
    MotionPhoto.OFF -> R.string.desc_state_motion_off
    MotionPhoto.AUTO -> R.string.desc_state_motion_auto
    MotionPhoto.ON -> R.string.desc_state_motion_on
}

fun flashState(flash: Flash) = when (flash) {
    Flash.OFF -> R.string.desc_state_flash_off
    Flash.AUTO -> R.string.desc_state_flash_auto
    Flash.ON -> R.string.desc_state_flash_on
}

/**
 * The timer's countdown (SPEC §7.3): the digit in the middle of the picture on a 9-sided cookie, 128 sp (owner, 23 Nov 2026: readable from a step away), turned
 * with the phone.
 */
@Composable
private fun Countdown(seconds: Int, turn: Float, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(216.dp).rotate(turn).clip(MaterialShapes.Cookie9Sided.toShape()).background(colors.surfaceContainerHigh),
    ) {
        Text(seconds.toString(), fontSize = 128.sp, lineHeight = 128.sp, color = colors.onSurface, style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"))
    }
}

/**
 * The front camera's flash (SPEC §7.2): the whole window in the light scheme's brightest surface (white in the dark theme too) at full brightness while the
 * photo is taken.
 */
@Composable
private fun ScreenFlash(onLit: (Boolean) -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        val before = window?.attributes?.screenBrightness
        window?.attributes = window.attributes.apply { screenBrightness = 1f }
        onLit(true)
        onDispose {
            onLit(false)
            window?.attributes = window.attributes.apply { screenBrightness = before ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }
    MaterialTheme(colorScheme = lightColorScheme()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest))
    }
}

/** The grid and the level, the phone's tilt read only here (it changes many times a second). */
@Composable
private fun Level(viewModel: ViewfinderViewModel, grid: Grid, level: Boolean, rotation: Int) {
    val tilt by viewModel.tilt.collectAsStateWithLifecycle()
    GridAndLevel(grid, if (level) tilt else null, rotation, Modifier.fillMaxSize())
}

/** The stream a preview's buffer is: 16:9, or 4:3 for everything else (the square is shown over 4:3). */
private fun streamOf(size: PixelSize?): Aspect = if (size != null && Sizes.near(size, Aspect.SIXTEEN_NINE)) Aspect.SIXTEEN_NINE else Aspect.FOUR_THREE

/**
 * Lays its content out at [rect] (dp, from the viewfinder's top-left) inside a parent at [area], at its full size even past the parent's edges (the 16:9
 * stream's ends): the parent clips. The rectangle is read while laying out, so an animated one moves without recomposing.
 */
private fun Modifier.place(area: FinderRect, rect: () -> FinderRect) = layout { measurable, constraints ->
    val r = rect()
    val placeable = measurable.measure(Constraints.fixed(r.width.dp.roundToPx().coerceAtLeast(0), r.height.dp.roundToPx().coerceAtLeast(0)))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place((r.left - area.left).dp.roundToPx(), (r.top - area.top).dp.roundToPx()) }
}

/**
 * A session rebuilt under the picture (LOGIKA §23.3, owner, 25 Nov 2026): the frozen last frame fades out to the background where it lay, the new geometry is
 * set under the background, and the live picture fades in from it once the new session shows its first frame. Moving the frozen frame into the new geometry
 * was tried first: a 16:9 frame has nothing for the sides 4:3 needs, and every fill of them showed.
 */
@Stable
private class FrozenMotion {
    var held by mutableStateOf<FrozenFrame?>(null)
    /** The live picture is fading in (the frozen frame is gone, the background going). */
    var incoming by mutableStateOf(false)
    /** How much of a picture shows: the frozen frame going out (1 → 0), then the live one coming in (0 → 1). */
    val visible = Animatable(1f)

    /** What belongs to the picture (the grid, the level) shows as much as the picture does. */
    fun shown(published: FrozenFrame?): Float = if (held == null && published == null) 1f else visible.value

    suspend fun follow(frozen: FrozenFrame?, fade: FiniteAnimationSpec<Float>) {
        if (frozen != null) {
            // A new frame during a fade starts as far as the picture had come (it is a copy of the live one, which showed that much)
            if (held == null) visible.snapTo(1f)
            held = frozen
            incoming = false
            visible.animateTo(0f, fade)
        } else if (held != null) {
            // The fade out ends first when the new session was quicker
            if (!incoming) visible.animateTo(0f, fade)
            incoming = true
            visible.animateTo(1f, fade)
            held = null
            incoming = false
        }
    }
}

/** The frozen frame where it lay, over the background, both going as [FrozenMotion] says. */
@Composable
private fun FrozenLayer(motion: FrozenMotion, published: FrozenFrame?, geometry: FinderGeometry, background: Color) {
    // From the frame it is published, before the effect that follows it runs: the new surface under it has no picture yet
    val frame = motion.held ?: published ?: return
    val place = remember(frame, geometry) { geometry.content(geometry.sensor(frame.stream), frame.stream) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (motion.incoming) 1f - motion.visible.value else 1f }.background(background)) {
        if (!motion.incoming) {
            FrozenPicture(frame, Modifier.place(geometry.area) { place }.graphicsLayer { alpha = motion.visible.value })
            // The bands it was seen with stay on it while it fades
            if (frame.square) {
                Canvas(Modifier.place(geometry.area) { place }) {
                    val band = (size.height - size.width).coerceAtLeast(0f) / 2
                    drawRect(background, size = Size(size.width, band))
                    drawRect(background, topLeft = Offset(0f, size.height - band), size = Size(size.width, band))
                }
            }
        }
    }
}

private const val REVIEW_SECURE_MAX = 300
