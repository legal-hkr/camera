@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.camera.ui.viewfinder

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.AndroidExternalSurface
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.camera.MotionPhoto
import sh.hnet.camera.R
import sh.hnet.camera.camera.CameraStatus
import sh.hnet.camera.camera.Flash
import sh.hnet.camera.camera.Mode
import sh.hnet.camera.camera.QrContent
import sh.hnet.camera.camera.Recording
import sh.hnet.camera.capabilities.model.Aspect
import sh.hnet.camera.capabilities.model.Orientation
import sh.hnet.camera.capabilities.model.Point
import sh.hnet.camera.capabilities.model.SessionLadder
import sh.hnet.camera.media.LastShot
import sh.hnet.camera.ui.components.EmptyState

/** The exposure ruler closes this long after the last touch (SPEC §5.3). */
private const val EXPOSURE_RULER_MS = 3_000L

/** Exposure compensation shown and set in thirds of a stop, ±2 EV (SPEC §7.1). */
private const val EV_RANGE = 2.0

/**
 * The viewfinder (SPEC §4): built from the bottom up – the mode row above the navigation bar, the shutter row, the zoom row lying over the bottom of the preview
 * canvas – with the picture centred in the canvas in the shot's aspect ratio and `surface` around it. Stage 1a: Photo and Video's preview, zoom, focus and
 * exposure, photos with the thumbnail.
 */
@Composable
fun ViewfinderScreen(onOpenSettings: () -> Unit, viewModel: ViewfinderViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
        permanentlyDenied = !ok && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }
    // Access may have been given in the system's settings meanwhile
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    ViewfinderWindow()
    if (!granted) {
        NoCameraAccess(
            permanentlyDenied = permanentlyDenied,
            onAllow = {
                if (permanentlyDenied) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                else launcher.launch(Manifest.permission.CAMERA)
            },
            onOpenSettings = onOpenSettings,
        )
        LaunchedEffect(Unit) { if (!asked) launcher.launch(Manifest.permission.CAMERA) }
        return
    }
    Viewfinder(viewModel, onOpenSettings)
}

/** While the viewfinder is shown: no status bar (a swipe brings it for a moment), the display's HDR headroom for the preview, the screen kept on. */
@Composable
private fun ViewfinderWindow() {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    DisposableEffect(activity) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
        view.keepScreenOn = true
        onDispose {
            controller.show(WindowInsetsCompat.Type.statusBars())
            window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            view.keepScreenOn = false
        }
    }
}

@Composable
private fun Viewfinder(viewModel: ViewfinderViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val numbers = rememberNumbers()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    val last by viewModel.lastShot.collectAsStateWithLifecycle()
    val camera = viewModel.camera
    val request = viewModel.request()
    val previewSize = viewModel.previewSize()

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

    val blink = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        viewModel.shutter.collect {
            haptics.photo()
            blink.snapTo(1f)
            blink.animateTo(0f, tween(100))
        }
    }
    val cover by animateFloatAsState(if (engine.frameShown) 0f else 1f, if (engine.frameShown) tween(300) else snap(), label = "cover")

    val recording = engine.recording
    // Back while recording ends it and saves, during a countdown cancels it; a second Back leaves (SPEC §3)
    BackHandler(enabled = recording != null || ui.countdown != null) { if (!viewModel.cancelTimer()) viewModel.record() }
    // The volume keys go to the viewfinder while it is shown (SPEC §7.9)
    DisposableEffect(viewModel) {
        HardwareKeys.handler = { viewModel.onVolumeKey(it) }
        onDispose { HardwareKeys.handler = null }
    }
    LaunchedEffect(Unit) { viewModel.countdownTick.collect { haptics.mode() } }
    val turn = controlTurn(ui.rotation)
    val fix by viewModel.location.fix.collectAsStateWithLifecycle()
    val tilt by viewModel.tilt.collectAsStateWithLifecycle()

    // The microphone is asked for on the first entry into Video (SPEC §16); without it videos have no sound
    val activity = LocalActivity.current
    var micGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var micAsked by rememberSaveable { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(ui.mode) {
        if (ui.mode == Mode.VIDEO && !micGranted && !micAsked) {
            micAsked = true
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val micBusy = stringResource(R.string.msg_mic_busy)
    val hdrFps = stringResource(R.string.msg_hdr_fps)
    val locationDenied = stringResource(R.string.msg_location_denied)
    fun explain(message: String) = scope.launch { snackbars.showSnackbar(message, duration = SnackbarDuration.Short) }
    // Location is asked for when it is first turned on (SPEC §16); refused, it stays off and a snackbar says why
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) viewModel.setLocation(true) else explain(locationDenied)
    }
    fun toggleLocation() {
        when {
            ui.settings.location -> viewModel.setLocation(false)
            viewModel.location.allowed() -> viewModel.setLocation(true)
            else -> locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    val colors = MaterialTheme.colorScheme
    val problem = engine.status.takeIf { it is CameraStatus.Busy || it is CameraStatus.Blocked || it is CameraStatus.Error }
    val modes = listOf(Mode.PHOTO, Mode.VIDEO)
    val stepsPerThird = camera?.let { (1.0 / 3 / it.exposureStep).roundToInt().coerceAtLeast(1) } ?: 1
    val ev = (camera?.exposureStep ?: 0.0) * ui.exposure

    // The QR code found in the analysis stream, only while the session has one
    val found by viewModel.analyzer.qr.collectAsStateWithLifecycle()
    val qr = found.takeIf { engine.analysis }
    var qrSheet by remember { mutableStateOf<QrContent?>(null) }
    val noApp = stringResource(R.string.msg_no_browser)

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.surface)) {
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val modeBottom = nav + 2.dp
        val shutterBottom = modeBottom + 36.dp + 14.dp
        val zoomBottom = shutterBottom + 80.dp + 12.dp
        // The status bar's band stays free, hidden or not: icons there sat under the screen's rounded corners and the pulled-down bar (owner, 23 Nov 2026)
        val status = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
        val canvas = maxHeight - status - shutterBottom - 80.dp - 4.dp
        val aspect = request?.pictureAspect ?: Aspect.FOUR_THREE
        val portrait = aspect.width.toFloat() / aspect.height
        var pictureWidth = maxWidth
        var pictureHeight: Dp = maxWidth * portrait
        // 16:9 fills the canvas's width, a few pixels cut off its ends in the preview only (owner, 23 Nov 2026); the other ratios fit inside the canvas
        val fill = aspect == Aspect.SIXTEEN_NINE
        if (pictureHeight > canvas) {
            pictureHeight = canvas
            if (!fill) pictureWidth = canvas / portrait
        }
        val pictureTop = (canvas - pictureHeight) / 2
        val pictureLeft = (maxWidth - pictureWidth) / 2

        // The picture: the preview's surface, the cover while a session starts, the blink of a shot, the marks and the gestures. A square shot is previewed
        // in 4:3 with the picture's box cutting it; the surface's box is the one that taps and marks refer to.
        val surfaceHeight = when {
            aspect == Aspect.SQUARE -> pictureWidth * 4f / 3f
            fill -> pictureWidth * portrait
            else -> pictureHeight
        }
        Box(Modifier.offset(pictureLeft, status + pictureTop).size(pictureWidth, pictureHeight).clipToBounds(), contentAlignment = Alignment.Center) {
          Box(Modifier.requiredSize(pictureWidth, surfaceHeight)) {
            if (previewSize != null && problem == null) {
                key(previewSize) {
                    AndroidExternalSurface(Modifier.fillMaxSize(), surfaceSize = IntSize(previewSize.width, previewSize.height)) {
                        onSurface { surface, _, _ ->
                            viewModel.setPreviewSurface(surface)
                            surface.onDestroyed { viewModel.clearPreviewSurface(surface) }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxSize().background(colors.surface.copy(alpha = maxOf(cover, blink.value))))
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
                                    exposureOpen = true
                                    exposureTouch = System.currentTimeMillis()
                                },
                                onLongPress = { offset ->
                                    haptics.lock()
                                    viewModel.lockAt(Point((offset.x / size.width).toDouble(), (offset.y / size.height).toDouble()))
                                    exposureOpen = false
                                },
                            )
                        }
                        .pointerInput(ui.mode) {
                            // A horizontal swipe on the picture changes the mode by one
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
          if (problem == null) {
              GridAndLevel(ui.settings.grid, if (ui.settings.level) tilt else null, ui.rotation, Modifier.fillMaxSize())
              ui.countdown?.let { Countdown(it, turn, Modifier.align(Alignment.Center)) }
          } else {
              CameraProblem(problem, onRetry = { viewModel.retry() }, modifier = Modifier.align(Alignment.Center).padding(16.dp))
          }
        }

        // The top row (SPEC §4.3, §6.3): state icons in two groups beside the camera's cutout; while recording only the flashlight
        if (problem == null && camera != null) {
            val video = ui.mode == Mode.VIDEO
            val settings = ui.settings
            Row(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = topRowTop(status, start = 12.dp, end = 140.dp)), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (video && camera.flash) {
                    TopButton(if (ui.torch) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off, stringResource(R.string.tooltip_flashlight), null, filled = ui.torch, turn = turn) { viewModel.toggleTorch() }
                }
                if (!video && (camera.flash || camera.front)) {
                    MenuButton(listOf(Flash.entries.map { f -> MenuChoice(stringResource(flashName(f)), f == ui.flash) { viewModel.setFlash(f) } })) { open ->
                        // Automatic: the button's background shows when the flash will fire (the exposure asks for it, or the scene is dark)
                        val willFire = ui.flash == Flash.AUTO && if (camera.front) engine.dark else engine.flashNeeded
                        TopButton(flashIcon(ui.flash), stringResource(R.string.tooltip_flash), stringResource(flashState(ui.flash)), filled = ui.flash != Flash.OFF, turn = turn, accent = willFire, onClick = open)
                    }
                }
                if (recording == null && ui.countdown == null) {
                    MenuButton(listOf(listOf(0, 3, 10).map { t -> MenuChoice(if (t == 0) stringResource(R.string.menu_timer_off) else stringResource(R.string.value_seconds, t), t == ui.timer) { viewModel.setTimer(t) } })) { open ->
                        TopButton(timerIcon(ui.timer), stringResource(R.string.tooltip_timer), null, filled = ui.timer != 0, turn = turn, onClick = open)
                    }
                    val searching = settings.location && (fix == null || viewModel.location.current() == null)
                    TopButton(
                        if (!settings.location) R.drawable.ic_location_off else if (searching) R.drawable.ic_location_searching else R.drawable.ic_location_on,
                        stringResource(R.string.tooltip_location),
                        stringResource(if (!settings.location) R.string.desc_state_location_off else if (searching) R.string.desc_state_location_searching else R.string.desc_state_location_on),
                        filled = settings.location,
                        turn = turn,
                    ) { toggleLocation() }
                }
            }
            // Nothing that rebuilds the session while a countdown runs or a recording goes on
            if (recording == null && ui.countdown == null) {
                Row(Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = topRowTop(status, start = maxWidth - 148.dp, end = maxWidth - 12.dp)), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val hdrAvailable = if (video) camera.hlg else camera.jpegR.isNotEmpty()
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
                            onDisabled = { explain(hdrFps) },
                        ) { viewModel.toggleHdr() }
                    }
                    if (!video) {
                        MenuButton(listOf(MotionPhoto.entries.map { m -> MenuChoice(stringResource(motionName(m)), m == settings.motionPhoto) { viewModel.setMotionPhoto(m) } })) { open ->
                            TopButton(motionIcon(settings.motionPhoto), stringResource(R.string.tooltip_motion_photo), stringResource(motionState(settings.motionPhoto)), filled = settings.motionPhoto != MotionPhoto.AUTO, turn = turn, onClick = open)
                        }
                    }
                    if (video) {
                        val uhdAvailable = SessionLadder.UHD in camera.video
                        val rates = videoRates(camera.fixedFps)
                        val groups = buildList {
                            if (uhdAvailable) add(listOf(MenuChoice(stringResource(R.string.menu_resolution_4k), settings.videoUhd) { viewModel.setVideoSize(true) }, MenuChoice(stringResource(R.string.menu_resolution_1080p), !settings.videoUhd) { viewModel.setVideoSize(false) }))
                            add(rates.map { fps -> MenuChoice(stringResource(R.string.value_fps, fps), fps == settings.videoFps, if (fps > SessionLadder.HLG_MAX_FPS && camera.hlg) stringResource(R.string.desc_fps_no_hdr) else null) { viewModel.setVideoFps(fps) } })
                        }
                        // What the session records, once it runs (the ladder may have fallen back from the choice)
                        val recorded = engine.video
                        val uhd = recorded?.let { it.size.width >= SessionLadder.UHD.width } ?: (settings.videoUhd && uhdAvailable)
                        val label = stringResource(R.string.value_video_quality, stringResource(if (uhd) R.string.menu_resolution_4k else R.string.menu_resolution_1080p), recorded?.fps ?: settings.videoFps)
                        // Held sideways the pill is too narrow for "4K 30" turned: "4K" over "30"
                        val sideways = ui.rotation == 90 || ui.rotation == 270
                        val stacked = if (sideways) listOf(stringResource(if (uhd) R.string.menu_resolution_4k else R.string.menu_resolution_1080p), "${recorded?.fps ?: settings.videoFps}") else null
                        MenuButton(groups) { open -> TopTextButton(label, stringResource(R.string.tooltip_video_quality), filled = false, turn = turn, stacked = stacked, onClick = open) }
                    } else {
                        MenuButton(listOf(Aspect.entries.map { a -> MenuChoice(aspectText(a), a == settings.aspect) { viewModel.setAspect(a) } })) { open ->
                            TopTextButton(aspectText(settings.aspect), stringResource(R.string.tooltip_aspect_ratio), filled = settings.aspect != Aspect.FOUR_THREE, turn = turn, onClick = open)
                        }
                    }
                }
            }
        }
        // The recording's time, centred under the cutout
        // In line with the top row, in its free middle
        if (recording != null) RecordingTime(recording, Modifier.align(Alignment.TopCenter).padding(top = status + 10.dp))
        // Indicators at the picture's top-start: a motion photo's video still recording (SPEC §7.6)
        if (ui.motionWaiting > 0) {
            IndicatorChip(
                R.drawable.ic_motion_photos_on,
                stringResource(R.string.label_motion),
                Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = status + maxOf(52.dp, pictureTop) + 8.dp),
                solid = true,
            ) {}
        }
        // No sound in Video
        val noSound = ui.mode == Mode.VIDEO && problem == null && (!micGranted || recording?.silenced == true)
        if (noSound) {
            IndicatorChip(
                R.drawable.ic_mic_off,
                stringResource(R.string.label_no_sound),
                Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = status + maxOf(52.dp, pictureTop) + 8.dp),
            ) {
                if (!micGranted) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                else scope.launch { snackbars.showSnackbar(micBusy, duration = SnackbarDuration.Short) }
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
            showQuickSettings = recording == null,
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
                if (engine.video != null && engine.range != null) {
                    RowButton(R.drawable.ic_photo_camera, stringResource(R.string.tooltip_photo_while_recording), turn, Modifier.align(Alignment.CenterEnd).padding(end = 34.dp)) { viewModel.shoot() }
                }
            } else {
                Thumbnail(last, turn, Modifier.align(Alignment.CenterStart).padding(start = 34.dp))
                if (viewModel.cameras.size > 1) SwitchCameraButton(turn, onClick = { viewModel.switchCamera() }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 34.dp))
            }
            val video = ui.mode == Mode.VIDEO
            val counting = ui.countdown != null
            ShutterButton(
                look = when {
                    counting -> ShutterLook.TIMER
                    recording != null -> ShutterLook.RECORDING
                    video -> ShutterLook.VIDEO
                    else -> ShutterLook.PHOTO
                },
                label = stringResource(
                    when {
                        counting -> R.string.content_description_shutter_cancel_timer
                        recording != null -> R.string.content_description_shutter_stop
                        video -> R.string.content_description_shutter_record
                        else -> R.string.content_description_shutter_photo
                    },
                ),
                enabled = engine.status == CameraStatus.Ready && (!video || (engine.video != null && !engine.finishing)),
                progress = ui.countdown?.let { 1f - it.toFloat() / ui.timer.coerceAtLeast(1) },
                turn = turn,
                onClick = {
                    if (video && !counting && ui.timer == 0) {
                        if (recording != null) haptics.photo() else haptics.lock()
                    }
                    viewModel.shutterPressed()
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (recording == null && ui.countdown == null) ModeRow(
            modes = modes,
            selected = ui.mode,
            label = { stringResource(if (it == Mode.VIDEO) R.string.label_mode_video else R.string.label_mode_photo) },
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

    if (quickSettings && camera != null) {
        val settings = ui.settings
        ModalBottomSheet(onDismissRequest = { quickSettings = false }, containerColor = colors.surfaceContainerLow) {
            QuickSettings(
                PanelState(
                    video = ui.mode == Mode.VIDEO,
                    flashAvailable = camera.flash || camera.front,
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
                    fpsChoices = videoRates(camera.fixedFps),
                    fps = settings.videoFps,
                    torch = ui.torch,
                    ultraHdrAvailable = camera.jpegR.isNotEmpty(),
                    motionPhoto = settings.motionPhoto,
                    ultraHdr = settings.ultraHdr,
                    location = settings.location,
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
                            explain(hdrFps)
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
                    onSettings = {
                        quickSettings = false
                        onOpenSettings()
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
private fun Thumbnail(last: LastShot?, turn: Float, modifier: Modifier) {
    val context = LocalContext.current
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
                    .clickable(enabled = last != null, role = Role.Button) { last?.let { openInGallery(context, it) } }
                    .semantics { contentDescription = label },
            ) {
                val bitmap = last?.thumbnail
                if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().rotate(turn))
                else Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null, tint = IconButtonDefaults.iconButtonColors().disabledContentColor, modifier = Modifier.rotate(turn))
            }
        }
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

/** What keeps the camera from showing a picture, in the picture's place (SPEC §14; the full set of states comes with stage 1d). */
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

/** Without camera access the viewfinder shows only this (SPEC §16): the app's mark, why, the way to allow it, and Settings. */
@Composable
private fun NoCameraAccess(permanentlyDenied: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
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
        TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp)) {
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
private fun duration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds / 60 % 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

/** An indicator chip (SPEC §4.4): 28 dp of finder glass with an icon and a short label; a tap explains it. */
@Composable
private fun IndicatorChip(icon: Int, label: String, modifier: Modifier, solid: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .height(28.dp)
            .controlSurface(CircleShape, if (solid) colors.secondaryContainer else controlColor())
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = if (solid) colors.onSecondaryContainer else LocalContentColor.current, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (solid) colors.onSecondaryContainer else Color.Unspecified)
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
