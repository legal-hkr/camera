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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.material3.rememberTooltipState
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
import androidx.compose.ui.draw.rotate
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
import sh.hnet.camera.R
import sh.hnet.camera.camera.CameraStatus
import sh.hnet.camera.camera.Mode
import sh.hnet.camera.camera.Recording
import sh.hnet.camera.capabilities.model.Aspect
import sh.hnet.camera.capabilities.model.Orientation
import sh.hnet.camera.capabilities.model.Point
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
    // How the phone is held, for the photo's orientation (the controls turn with it in stage 1c)
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
    // Back while recording ends it and saves; a second Back leaves (SPEC §3)
    BackHandler(enabled = recording != null) { viewModel.record() }

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

    val colors = MaterialTheme.colorScheme
    val problem = engine.status.takeIf { it is CameraStatus.Busy || it is CameraStatus.Blocked || it is CameraStatus.Error }
    val modes = listOf(Mode.PHOTO, Mode.VIDEO)
    val stepsPerThird = camera?.let { (1.0 / 3 / it.exposureStep).roundToInt().coerceAtLeast(1) } ?: 1
    val ev = (camera?.exposureStep ?: 0.0) * ui.exposure

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.surface)) {
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val modeBottom = nav + 2.dp
        val shutterBottom = modeBottom + 36.dp + 14.dp
        val zoomBottom = shutterBottom + 80.dp + 12.dp
        val canvas = maxHeight - shutterBottom - 80.dp - 4.dp
        val aspect = request?.pictureAspect ?: Aspect.FOUR_THREE
        val portrait = aspect.width.toFloat() / aspect.height
        var pictureWidth = maxWidth
        var pictureHeight: Dp = maxWidth * portrait
        if (pictureHeight > canvas) {
            pictureHeight = canvas
            pictureWidth = canvas / portrait
        }
        val pictureTop = (canvas - pictureHeight) / 2
        val pictureLeft = (maxWidth - pictureWidth) / 2

        // The picture: the preview's surface, the cover while a session starts, the blink of a shot, the marks and the gestures
        Box(Modifier.offset(pictureLeft, pictureTop).size(pictureWidth, pictureHeight)) {
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
            } else {
                CameraProblem(problem, onRetry = { viewModel.retry() }, modifier = Modifier.align(Alignment.Center).padding(16.dp))
            }
        }

        // The top row: the flashlight in Video (the other state icons come with stage 1c), level with the camera's cutout, beside it
        if (ui.mode == Mode.VIDEO && camera?.flash == true && problem == null) {
            Box(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = topRowTop(start = 12.dp, end = 52.dp))) {
                GlassIconButton(
                    if (ui.torch) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off,
                    stringResource(R.string.tooltip_flashlight),
                    selected = ui.torch,
                    size = 40,
                    iconSize = 20,
                    onClick = { viewModel.toggleTorch() },
                )
            }
        }
        // The recording's time, centred under the cutout
        if (recording != null) RecordingTime(recording, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
        // Indicators at the picture's top-start: no sound in Video
        val noSound = ui.mode == Mode.VIDEO && problem == null && (!micGranted || recording?.silenced == true)
        if (noSound) {
            IndicatorChip(
                R.drawable.ic_mic_off,
                stringResource(R.string.label_no_sound),
                Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = maxOf(52.dp, pictureTop) + 8.dp),
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
                ui.locked && !exposureOpen -> SuggestionChip(R.drawable.ic_lock, stringResource(R.string.label_ae_af_locked), onDismiss = { viewModel.unlock() })
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
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = zoomBottom - 4.dp),
        )

        // The shutter row: the last shot, the shutter, the other camera
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = shutterBottom).fillMaxWidth().height(80.dp)) {
            if (recording != null) {
                // While recording: pause or resume in the thumbnail's place, a photo in the camera switch's
                val paused = recording.pausedAt != null
                RowButton(if (paused) R.drawable.ic_play_arrow else R.drawable.ic_pause, stringResource(if (paused) R.string.tooltip_resume else R.string.tooltip_pause), Modifier.align(Alignment.CenterStart).padding(start = 34.dp)) {
                    if (paused) viewModel.resumeRecording() else viewModel.pauseRecording()
                }
                if (engine.video != null && engine.range != null) {
                    RowButton(R.drawable.ic_photo_camera, stringResource(R.string.tooltip_photo_while_recording), Modifier.align(Alignment.CenterEnd).padding(end = 34.dp)) { viewModel.shoot() }
                }
            } else {
                Thumbnail(last, Modifier.align(Alignment.CenterStart).padding(start = 34.dp))
                if (viewModel.cameras.size > 1) SwitchCameraButton(onClick = { viewModel.switchCamera() }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 34.dp))
            }
            val video = ui.mode == Mode.VIDEO
            ShutterButton(
                look = if (recording != null) ShutterLook.RECORDING else if (video) ShutterLook.VIDEO else ShutterLook.PHOTO,
                label = stringResource(if (recording != null) R.string.content_description_shutter_stop else if (video) R.string.content_description_shutter_record else R.string.content_description_shutter_photo),
                enabled = engine.status == CameraStatus.Ready && (!video || (engine.video != null && !engine.finishing)),
                onClick = {
                    if (video) {
                        if (recording != null) haptics.photo() else haptics.lock()
                        viewModel.record()
                    } else {
                        viewModel.shoot()
                    }
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (recording == null) ModeRow(
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

    if (quickSettings) {
        ModalBottomSheet(onDismissRequest = { quickSettings = false }, containerColor = colors.surfaceContainerLow) {
            // Stage 1a: only the way to Settings; the quick settings themselves come with stage 1c
            FilledTonalButton(
                onClick = {
                    quickSettings = false
                    onOpenSettings()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).height(48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.button_panel_settings))
            }
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
private fun Thumbnail(last: LastShot?, modifier: Modifier) {
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
                if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null, tint = IconButtonDefaults.iconButtonColors().disabledContentColor)
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
private fun SwitchCameraButton(onClick: () -> Unit, modifier: Modifier) {
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
                Icon(painterResource(R.drawable.ic_cameraswitch), contentDescription = label, modifier = Modifier.size(24.dp).rotate(angle))
            }
        }
    }
}

/** A suggestion chip (SPEC §4.5): 32 dp, small corners, `secondaryContainer`, a filled icon and an optional close. */
@Composable
private fun SuggestionChip(icon: Int, text: String, onDismiss: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(8.dp), color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer, modifier = Modifier.height(32.dp)) {
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
private fun RowButton(icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
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
                Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp))
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
private fun IndicatorChip(icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(28.dp)
            .finderGlass(CircleShape, glassColor())
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private const val TIME_TICK_MS = 250L

/**
 * Where a group of the top row sits (SPEC §4.3): 6 dp from the top edge, level with the camera's cutout, as long as it keeps 16 dp from the cutout's bounds; a
 * group that would collide moves below the cutout instead.
 */
@Composable
private fun topRowTop(start: Dp, end: Dp): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val cutouts = view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
    return with(density) {
        val left = (start - 16.dp).toPx()
        val right = (end + 16.dp).toPx()
        val below = cutouts.filter { it.top < 52.dp.toPx() && it.right > left && it.left < right }.maxOfOrNull { it.bottom }
        if (below == null) 6.dp else below.toDp() + 6.dp
    }
}
