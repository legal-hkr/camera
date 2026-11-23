package sh.hnet.camera.ui.viewfinder

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaActionSound
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.acos
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sh.hnet.camera.CameraApplication
import sh.hnet.camera.camera.CameraRequest
import sh.hnet.camera.camera.EngineState
import sh.hnet.camera.camera.Mode
import sh.hnet.camera.capabilities.CameraSpec
import sh.hnet.camera.capabilities.model.Aspect
import sh.hnet.camera.capabilities.model.PixelSize
import sh.hnet.camera.capabilities.model.Point
import sh.hnet.camera.media.LastShot

/** The viewfinder's own state: what the user chose here. It starts the same way every time (back camera, Photo, 1×; SPEC §17.5). */
data class ViewfinderUi(
    val cameraIndex: Int = 0,
    val mode: Mode = Mode.PHOTO,
    val aspect: Aspect = Aspect.FOUR_THREE,
    val zoom: Double = 1.0,
    /** Exposure compensation in the camera's steps. */
    val exposure: Int = 0,
    /** The tapped point (a share of the picture), shown as the focus mark. */
    val focus: Point? = null,
    val locked: Boolean = false,
)

class ViewfinderViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app as CameraApplication
    private val engine = application.camera
    private val media = application.media

    /** Read again until the camera permission is there (some characteristics need it). */
    val cameras: List<CameraSpec> get() = engine.cameras
    val engineState: StateFlow<EngineState> = engine.state
    val lastShot: StateFlow<LastShot?> = media.last

    private val _ui = MutableStateFlow(ViewfinderUi())
    val ui: StateFlow<ViewfinderUi> = _ui.asStateFlow()

    private val _shutter = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** A still's exposure started: the picture blinks and the phone ticks. */
    val shutter: SharedFlow<Unit> = _shutter

    /** How the phone is held (0, 90, 180, 270), for the photo's orientation. */
    var rotation = 0
        set(value) {
            field = value
            engine.deviceRotation = value
        }

    private val sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    private val shots = Mutex()

    /** The shutter sound is released with the view model; a still still in flight then stays silent. */
    @Volatile
    private var cleared = false

    private val sensors = app.getSystemService(SensorManager::class.java)
    private var gravity: FloatArray? = null
    private var gravityAtFocus: FloatArray? = null
    private val gravityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = event.values.copyOf()
            gravity = now
            val then = gravityAtFocus ?: return
            // A tapped focus holds until the phone turns away from where it was aimed (a locked one stays)
            if (angle(now, then) > FOCUS_HOLD_DEGREES && !_ui.value.locked && _ui.value.focus != null) {
                gravityAtFocus = null
                _ui.update { it.copy(focus = null) }
                engine.resetFocus()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        engine.resetControls()
        configure()
        viewModelScope.launch {
            media.removeAbandoned()
            media.refreshLast()
        }
    }

    val camera: CameraSpec? get() = cameras.getOrNull(_ui.value.cameraIndex)

    fun request(): CameraRequest? = camera?.let { CameraRequest(it, _ui.value.mode, _ui.value.aspect) }

    fun previewSize(): PixelSize? = request()?.let { engine.previewSize(it) }

    fun retry() = engine.retry()

    private fun configure() {
        request()?.let { engine.configure(it) }
    }

    fun start() {
        engine.start()
        sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sensors.registerListener(gravityListener, it, SensorManager.SENSOR_DELAY_UI) }
        viewModelScope.launch { media.refreshLast() }
    }

    fun stop() {
        engine.stop()
        sensors.unregisterListener(gravityListener)
    }

    fun setPreviewSurface(surface: Surface) = engine.setPreviewSurface(surface)

    fun clearPreviewSurface(surface: Surface) = engine.clearPreviewSurface(surface)

    fun selectMode(mode: Mode) {
        if (mode == _ui.value.mode) return
        _ui.update { it.copy(mode = mode, focus = null, locked = false, exposure = 0) }
        engine.resetFocus(exposure = 0)
        configure()
    }

    fun switchCamera() {
        if (cameras.size < 2) return
        _ui.update { it.copy(cameraIndex = (it.cameraIndex + 1) % cameras.size, zoom = 1.0, focus = null, locked = false, exposure = 0) }
        engine.setZoom(1.0)
        engine.resetFocus(exposure = 0)
        configure()
    }

    /** A zoom value from the ruler or a stop; a different lens (another stop is the nearest one below) ends the tapped focus and the exposure compensation. */
    fun zoomTo(ratio: Double) {
        val scale = camera?.zoom ?: return
        val value = ratio.coerceIn(scale.min, scale.max.coerceAtMost(scale.end))
        val before = lensOf(_ui.value.zoom)
        _ui.update { it.copy(zoom = value) }
        engine.setZoom(value)
        if (lensOf(value) != before && (_ui.value.focus != null || _ui.value.exposure != 0)) {
            _ui.update { it.copy(focus = null, locked = false, exposure = 0) }
            engine.resetFocus(exposure = 0)
        }
    }

    /** The native lens a zoom value is on: the nearest stop at or below it. */
    private fun lensOf(zoom: Double): Double = camera?.zoom?.stops?.lastOrNull { it.ratio <= zoom + 1e-6 }?.ratio ?: 1.0

    fun focusAt(point: Point) {
        if (camera?.let { it.autofocus || it.aeRegions > 0 } != true) return
        gravityAtFocus = gravity
        _ui.update { it.copy(focus = point, locked = false) }
        engine.focusAt(point, lock = false)
    }

    fun lockAt(point: Point) {
        gravityAtFocus = null
        _ui.update { it.copy(focus = point, locked = true) }
        engine.focusAt(point, lock = true)
    }

    fun unlock() {
        _ui.update { it.copy(focus = null, locked = false) }
        engine.resetFocus()
    }

    fun setExposure(steps: Int) {
        _ui.update { it.copy(exposure = steps) }
        engine.setExposure(steps)
    }

    /** Takes a photo and saves it in the app's scope, so leaving the viewfinder right after doesn't lose it. Shots queue up; the shutter never waits. */
    fun shoot() {
        viewModelScope.launch {
            shots.withLock {
                val photo = engine.takePhoto(rotation) {
                    if (!cleared) sound.play(MediaActionSound.SHUTTER_CLICK)
                    _shutter.tryEmit(Unit)
                } ?: return@withLock
                application.appScope.launch {
                    media.savePhoto(photo.bytes, photo.takenAt)
                    media.refreshLast()
                }
            }
        }
    }

    override fun onCleared() {
        cleared = true
        sensors.unregisterListener(gravityListener)
        sound.release()
    }

    private fun angle(a: FloatArray, b: FloatArray): Double {
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val norm = sqrt((a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) * (b[0] * b[0] + b[1] * b[1] + b[2] * b[2]))
        if (norm == 0f) return 0.0
        return Math.toDegrees(acos((dot / norm).coerceIn(-1f, 1f).toDouble()))
    }

    private companion object {
        const val FOCUS_HOLD_DEGREES = 10.0
    }
}
