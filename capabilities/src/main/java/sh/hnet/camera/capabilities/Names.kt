package sh.hnet.camera.capabilities

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat

/** Readable names for the integer constants the camera and codec APIs report; an unknown value keeps its number. Constants of newer releases are only compared, never passed to the platform. */
@SuppressLint("InlinedApi")
object Names {

    private fun name(map: Map<Int, String>, value: Int): String = map[value] ?: value.toString()

    private val capabilities = mapOf(
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE to "BACKWARD_COMPATIBLE",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR to "MANUAL_SENSOR",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING to "MANUAL_POST_PROCESSING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW to "RAW",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING to "PRIVATE_REPROCESSING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS to "READ_SENSOR_SETTINGS",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE to "BURST_CAPTURE",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING to "YUV_REPROCESSING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT to "DEPTH_OUTPUT",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO to "CONSTRAINED_HIGH_SPEED_VIDEO",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING to "MOTION_TRACKING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA to "LOGICAL_MULTI_CAMERA",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME to "MONOCHROME",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA to "SECURE_IMAGE_DATA",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA to "SYSTEM_CAMERA",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING to "OFFLINE_PROCESSING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR to "ULTRA_HIGH_RESOLUTION_SENSOR",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING to "REMOSAIC_REPROCESSING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT to "DYNAMIC_RANGE_TEN_BIT",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE to "STREAM_USE_CASE",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES to "COLOR_SPACE_PROFILES",
    )

    fun capability(value: Int) = name(capabilities, value)

    private val hardwareLevels = mapOf(
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED to "LIMITED",
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL to "FULL",
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY to "LEGACY",
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 to "LEVEL_3",
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL to "EXTERNAL",
    )

    fun hardwareLevel(value: Int) = name(hardwareLevels, value)

    private val facings = mapOf(
        CameraMetadata.LENS_FACING_BACK to "BACK",
        CameraMetadata.LENS_FACING_FRONT to "FRONT",
        CameraMetadata.LENS_FACING_EXTERNAL to "EXTERNAL",
    )

    fun facing(value: Int) = name(facings, value)

    private val formats = mapOf(
        ImageFormat.JPEG to "JPEG",
        ImageFormat.JPEG_R to "JPEG_R",
        ImageFormat.HEIC to "HEIC",
        ImageFormat.HEIC_ULTRAHDR to "HEIC_ULTRAHDR",
        ImageFormat.YUV_420_888 to "YUV_420_888",
        ImageFormat.YCBCR_P010 to "YCBCR_P010",
        ImageFormat.PRIVATE to "PRIVATE",
        ImageFormat.RAW_SENSOR to "RAW_SENSOR",
        ImageFormat.RAW_PRIVATE to "RAW_PRIVATE",
        ImageFormat.RAW10 to "RAW10",
        ImageFormat.RAW12 to "RAW12",
        ImageFormat.DEPTH16 to "DEPTH16",
        ImageFormat.DEPTH_JPEG to "DEPTH_JPEG",
        ImageFormat.DEPTH_POINT_CLOUD to "DEPTH_POINT_CLOUD",
        ImageFormat.Y8 to "Y8",
        ImageFormat.NV21 to "NV21",
        ImageFormat.YV12 to "YV12",
        PixelFormat.RGBA_8888 to "RGBA_8888",
    )

    fun format(value: Int) = name(formats, value)

    private val dynamicRanges = mapOf(
        DynamicRangeProfiles.STANDARD.toInt() to "STANDARD",
        DynamicRangeProfiles.HLG10.toInt() to "HLG10",
        DynamicRangeProfiles.HDR10.toInt() to "HDR10",
        DynamicRangeProfiles.HDR10_PLUS.toInt() to "HDR10_PLUS",
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF.toInt() to "DOLBY_VISION_10B_HDR_REF",
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO.toInt() to "DOLBY_VISION_10B_HDR_REF_PO",
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM.toInt() to "DOLBY_VISION_10B_HDR_OEM",
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM_PO.toInt() to "DOLBY_VISION_10B_HDR_OEM_PO",
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF.toInt() to "DOLBY_VISION_8B_HDR_REF",
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF_PO.toInt() to "DOLBY_VISION_8B_HDR_REF_PO",
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM.toInt() to "DOLBY_VISION_8B_HDR_OEM",
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM_PO.toInt() to "DOLBY_VISION_8B_HDR_OEM_PO",
    )

    fun dynamicRange(value: Long) = name(dynamicRanges, value.toInt())

    private val streamUseCases = mapOf(
        CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT to "DEFAULT",
        CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW to "PREVIEW",
        CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE to "STILL_CAPTURE",
        CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD to "VIDEO_RECORD",
        CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW_VIDEO_STILL to "PREVIEW_VIDEO_STILL",
        CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_CALL to "VIDEO_CALL",
        CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_CROPPED_RAW to "CROPPED_RAW",
    )

    // Vendor use cases start at 0x10000 (SCALER_AVAILABLE_STREAM_USE_CASES_VENDOR_START is hidden)
    fun streamUseCase(value: Long) = if (value >= 0x10000L) "VENDOR_$value" else name(streamUseCases, value.toInt())

    private val aeModes = mapOf(
        CameraMetadata.CONTROL_AE_MODE_OFF to "OFF",
        CameraMetadata.CONTROL_AE_MODE_ON to "ON",
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH to "ON_AUTO_FLASH",
        CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH to "ON_ALWAYS_FLASH",
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE to "ON_AUTO_FLASH_REDEYE",
        CameraMetadata.CONTROL_AE_MODE_ON_EXTERNAL_FLASH to "ON_EXTERNAL_FLASH",
        CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY to "ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY",
    )

    fun aeMode(value: Int) = name(aeModes, value)

    private val afModes = mapOf(
        CameraMetadata.CONTROL_AF_MODE_OFF to "OFF",
        CameraMetadata.CONTROL_AF_MODE_AUTO to "AUTO",
        CameraMetadata.CONTROL_AF_MODE_MACRO to "MACRO",
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO to "CONTINUOUS_VIDEO",
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE to "CONTINUOUS_PICTURE",
        CameraMetadata.CONTROL_AF_MODE_EDOF to "EDOF",
    )

    fun afMode(value: Int) = name(afModes, value)

    private val stabilizationModes = mapOf(
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF to "OFF",
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON to "ON",
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION to "PREVIEW_STABILIZATION",
    )

    fun stabilizationMode(value: Int) = name(stabilizationModes, value)

    private val extendedSceneModes = mapOf(
        CameraMetadata.CONTROL_EXTENDED_SCENE_MODE_DISABLED to "DISABLED",
        CameraMetadata.CONTROL_EXTENDED_SCENE_MODE_BOKEH_STILL_CAPTURE to "BOKEH_STILL_CAPTURE",
        CameraMetadata.CONTROL_EXTENDED_SCENE_MODE_BOKEH_CONTINUOUS to "BOKEH_CONTINUOUS",
    )

    fun extendedSceneMode(value: Int) = name(extendedSceneModes, value)

    private val extensions = mapOf(
        CameraExtensionCharacteristics.EXTENSION_AUTOMATIC to "AUTOMATIC",
        CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH to "FACE_RETOUCH",
        CameraExtensionCharacteristics.EXTENSION_BOKEH to "BOKEH",
        CameraExtensionCharacteristics.EXTENSION_HDR to "HDR",
        CameraExtensionCharacteristics.EXTENSION_NIGHT to "NIGHT",
    )

    fun extension(value: Int) = name(extensions, value)

    private val hevcProfiles = mapOf(
        CodecProfileLevel.HEVCProfileMain to "Main",
        CodecProfileLevel.HEVCProfileMain10 to "Main10",
        CodecProfileLevel.HEVCProfileMainStill to "MainStill",
        CodecProfileLevel.HEVCProfileMain10HDR10 to "Main10HDR10",
        CodecProfileLevel.HEVCProfileMain10HDR10Plus to "Main10HDR10Plus",
    )
    private val avcProfiles = mapOf(
        CodecProfileLevel.AVCProfileBaseline to "Baseline",
        CodecProfileLevel.AVCProfileConstrainedBaseline to "ConstrainedBaseline",
        CodecProfileLevel.AVCProfileMain to "Main",
        CodecProfileLevel.AVCProfileExtended to "Extended",
        CodecProfileLevel.AVCProfileHigh to "High",
        CodecProfileLevel.AVCProfileConstrainedHigh to "ConstrainedHigh",
        CodecProfileLevel.AVCProfileHigh10 to "High10",
        CodecProfileLevel.AVCProfileHigh422 to "High422",
        CodecProfileLevel.AVCProfileHigh444 to "High444",
    )
    private val av1Profiles = mapOf(
        CodecProfileLevel.AV1ProfileMain8 to "Main8",
        CodecProfileLevel.AV1ProfileMain10 to "Main10",
        CodecProfileLevel.AV1ProfileMain10HDR10 to "Main10HDR10",
        CodecProfileLevel.AV1ProfileMain10HDR10Plus to "Main10HDR10Plus",
    )
    private val vp9Profiles = mapOf(
        CodecProfileLevel.VP9Profile0 to "Profile0",
        CodecProfileLevel.VP9Profile1 to "Profile1",
        CodecProfileLevel.VP9Profile2 to "Profile2",
        CodecProfileLevel.VP9Profile3 to "Profile3",
        CodecProfileLevel.VP9Profile2HDR to "Profile2HDR",
        CodecProfileLevel.VP9Profile3HDR to "Profile3HDR",
        CodecProfileLevel.VP9Profile2HDR10Plus to "Profile2HDR10Plus",
        CodecProfileLevel.VP9Profile3HDR10Plus to "Profile3HDR10Plus",
    )
    private val apvProfiles = mapOf(
        CodecProfileLevel.APVProfile422_10 to "422_10",
        CodecProfileLevel.APVProfile422_10HDR10 to "422_10HDR10",
        CodecProfileLevel.APVProfile422_10HDR10Plus to "422_10HDR10Plus",
    )

    fun codecProfile(mime: String, value: Int): String = when (mime) {
        MediaFormat.MIMETYPE_VIDEO_HEVC -> name(hevcProfiles, value)
        MediaFormat.MIMETYPE_VIDEO_AVC -> name(avcProfiles, value)
        MediaFormat.MIMETYPE_VIDEO_AV1 -> name(av1Profiles, value)
        MediaFormat.MIMETYPE_VIDEO_VP9 -> name(vp9Profiles, value)
        MediaFormat.MIMETYPE_VIDEO_APV -> name(apvProfiles, value)
        else -> value.toString()
    }
}
