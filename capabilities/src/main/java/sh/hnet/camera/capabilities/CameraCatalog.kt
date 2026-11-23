package sh.hnet.camera.capabilities

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Size
import android.view.SurfaceHolder
import sh.hnet.camera.capabilities.model.LensOptics
import sh.hnet.camera.capabilities.model.PixelRect
import sh.hnet.camera.capabilities.model.PixelSize
import sh.hnet.camera.capabilities.model.ZoomScale
import sh.hnet.camera.capabilities.model.ZoomStops

/** What the camera app needs to know about one logical camera, read once when the app starts. */
data class CameraSpec(
    val id: String,
    val front: Boolean,
    val sensorOrientation: Int,
    val activeArray: PixelRect,
    val zoom: ZoomScale,
    val jpegR: List<PixelSize>,
    val jpeg: List<PixelSize>,
    val preview: List<PixelSize>,
    val yuv: List<PixelSize>,
    val p010: List<PixelSize>,
    val hlg: Boolean,
    val exposureSteps: IntRange,
    /** One step of exposure compensation in EV (usually 1/3 or 1/6). */
    val exposureStep: Double,
    val autofocus: Boolean,
    val afRegions: Int,
    val aeRegions: Int,
    val faceDetection: Boolean,
    val flash: Boolean,
    /** The camera can take a still from its own buffer of recent frames (`CONTROL_ENABLE_ZSL`). */
    val zsl: Boolean,
    /** Sessions can be checked before they are configured (`CameraDeviceSetup`, Android 15+). */
    val sessionQueries: Boolean,
)

object CameraCatalog {
    /** The phone's main back and front cameras (the first of each facing in the system's list), back first; external cameras are left out. */
    fun read(manager: CameraManager): List<CameraSpec> {
        val specs = manager.cameraIdList.mapNotNull { id -> runCatching { spec(manager, id) }.getOrNull() }
        return listOfNotNull(specs.firstOrNull { !it.front }, specs.firstOrNull { it.front })
    }

    private fun spec(manager: CameraManager, id: String): CameraSpec? {
        val chars = manager.getCameraCharacteristics(id)
        val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: return null
        if (facing == CameraMetadata.LENS_FACING_EXTERNAL) return null
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in capabilities) return null
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { it.lower.toDouble()..it.upper.toDouble() } ?: 1.0..1.0
        val physical = chars.physicalCameraIds.mapNotNull { optics(it, runCatching { manager.getCameraCharacteristics(it) }.getOrNull()) }
        val logical = optics(id, chars) ?: return null
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
        val compensation = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val requestKeys = chars.availableCaptureRequestKeys.toSet()
        return CameraSpec(
            id = id,
            front = facing == CameraMetadata.LENS_FACING_FRONT,
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            activeArray = PixelRect(active.left, active.top, active.right, active.bottom),
            zoom = ZoomStops.compute(logical, physical, range),
            jpegR = sizes(map, ImageFormat.JPEG_R),
            jpeg = sizes(map, ImageFormat.JPEG),
            preview = map.getOutputSizes(SurfaceHolder::class.java)?.map { PixelSize(it.width, it.height) }.orEmpty(),
            yuv = sizes(map, ImageFormat.YUV_420_888),
            p010 = sizes(map, ImageFormat.YCBCR_P010),
            hlg = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true,
            exposureSteps = compensation?.let { it.lower..it.upper } ?: 0..0,
            exposureStep = step?.toDouble() ?: 1.0,
            // The closest focus distance needs the camera permission; without it the AF modes alone decide
            autofocus = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes && (chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let { it > 0f } ?: true),
            afRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
            aeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
            faceDetection = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) == true,
            flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            zsl = CaptureRequest.CONTROL_ENABLE_ZSL in requestKeys,
            sessionQueries = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && manager.isCameraDeviceSetupSupported(id),
        )
    }

    /** A lens' focal length and the width of the sensor area it uses (the active array's share of the physical sensor). */
    private fun optics(id: String, chars: CameraCharacteristics?): LensOptics? {
        chars ?: return null
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.toDouble() ?: return null
        val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val pixels = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        return LensOptics(id, focal, physical.width.toDouble() * active.width() / pixels.width)
    }

    // A format the release doesn't know may throw instead of returning null
    private fun sizes(map: StreamConfigurationMap, format: Int): List<PixelSize> =
        runCatching { map.getOutputSizes(format)?.toList() }.getOrNull().orEmpty().map { size: Size -> PixelSize(size.width, size.height) }
}
