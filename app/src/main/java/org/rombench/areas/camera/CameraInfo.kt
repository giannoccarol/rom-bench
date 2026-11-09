package org.rombench.areas.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import org.rombench.R
import org.rombench.core.Detail
import org.rombench.core.Txt
import org.rombench.core.d
import org.rombench.core.fmt1
import org.rombench.core.fmt2
import org.rombench.core.txt
import kotlin.math.abs
import kotlin.math.sqrt

internal object CamNames {
    fun facing(v: Int?): String = when (v) {
        CameraMetadata.LENS_FACING_BACK -> "BACK"
        CameraMetadata.LENS_FACING_FRONT -> "FRONT"
        CameraMetadata.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    fun facingTxt(v: Int?): Txt = txt(
        when (v) {
            CameraMetadata.LENS_FACING_BACK -> R.string.camera_facing_back
            CameraMetadata.LENS_FACING_FRONT -> R.string.camera_facing_front
            CameraMetadata.LENS_FACING_EXTERNAL -> R.string.camera_facing_external
            else -> R.string.camera_facing_unknown
        },
    )

    fun level(v: Int?): String = when (v) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($v)"
    }

    fun capability(v: Int): String = when (v) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "REMOSAIC_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "10BIT_OUTPUT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "STREAM_USE_CASE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES -> "COLOR_SPACE_PROFILES"
        else -> "CAP_$v"
    }

    fun afMode(v: Int): String = when (v) {
        CameraMetadata.CONTROL_AF_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AF_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AF_MODE_MACRO -> "MACRO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        CameraMetadata.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "AF_$v"
    }

    fun afState(v: Int?): String = when (v) {
        null -> "—"
        CameraMetadata.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
        CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
        CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
        CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
        CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
        CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED"
        CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
        else -> "AF_STATE_$v"
    }

    fun aeState(v: Int?): String = when (v) {
        null -> "—"
        CameraMetadata.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
        CameraMetadata.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
        CameraMetadata.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
        CameraMetadata.CONTROL_AE_STATE_LOCKED -> "LOCKED"
        CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
        CameraMetadata.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
        else -> "AE_STATE_$v"
    }

    fun size(s: Size?) = s?.let { "${it.width}×${it.height}" }
}

/** Why opening a camera failed, for a ROM developer. */
internal fun openErrorReason(code: Int): Txt = when (code) {
    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> txt(R.string.camera_r_in_use)
    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> txt(R.string.camera_r_max_in_use)
    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> txt(R.string.camera_r_disabled)
    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> txt(R.string.camera_r_device_error)
    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> txt(R.string.camera_r_service_error)
    else -> txt(R.string.camera_r_unknown_error, code)
}

internal fun openErrorName(code: Int): String = when (code) {
    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED"
    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "ERROR_CAMERA_DEVICE"
    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "ERROR_CAMERA_SERVICE"
    else -> "ERROR_$code"
}

internal fun accessReason(e: Throwable): Txt = when (e) {
    is CameraAccessException -> when (e.reason) {
        CameraAccessException.CAMERA_IN_USE -> txt(R.string.camera_r_in_use)
        CameraAccessException.MAX_CAMERAS_IN_USE -> txt(R.string.camera_r_max_in_use)
        CameraAccessException.CAMERA_DISABLED -> txt(R.string.camera_r_disabled)
        CameraAccessException.CAMERA_DISCONNECTED -> txt(R.string.camera_r_disconnected)
        else -> txt(R.string.camera_r_device_error)
    }
    is SecurityException -> txt(R.string.camera_r_security)
    is IllegalArgumentException -> txt(R.string.camera_r_bad_id)
    else -> txt(R.string.camera_r_device_error)
}

/** Summary of the static characteristics of one camera, with plausibility warnings. */
internal class CamInfo(val id: String, val chars: CameraCharacteristics) {
    val facing: Int? = chars.get(CameraCharacteristics.LENS_FACING)
    val level: Int? = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
    val orientation: Int = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    val hasFlash: Boolean = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    private val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val yuvSizes: List<Size> = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
    /** Standard JPEG sizes, usable in a regular session together with the preview. */
    val jpegSizes: List<Size> = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
    /** High-resolution JPEG sizes (BURST_CAPTURE only, may not reach full frame rate). */
    val jpegHighRes: List<Size> = map?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
    val previewSizes: List<Size> = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
    val rawSizes: List<Size> = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() ?: emptyList()
    val capabilities: List<Int> = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
    val afModes: List<Int> = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
    val aeModes: List<Int> = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toList() ?: emptyList()
    val physicalIds: Set<String> = runCatching { chars.physicalCameraIds }.getOrDefault(emptySet())

    val jpegMax: Size? get() = jpegSizes.maxByOrNull { it.width.toLong() * it.height }

    /** Short line for chips and titles: "0 · Back". */
    fun label(): Txt = txt(R.string.camera_label, id, CamNames.facingTxt(facing))

    /** YUV size for the frame probe: closest to 640×480, preferring the same aspect. */
    fun probeSize(): Size? = yuvSizes.minByOrNull { abs(it.width * it.height - 640 * 480) + abs(it.width * 3 - it.height * 4) * 10 }

    fun details(): List<Detail> {
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val pixel = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val phys = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        val apert = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: emptyList()
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
        val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val diag = phys?.let { sqrt((it.width * it.width + it.height * it.height).toDouble()) }
        val eq = if (diag != null && diag > 0 && focal.isNotEmpty()) focal.joinToString(", ") { "${(it * 43.27 / diag).toInt()} mm" } else null
        return buildList {
            add(d("Facing", CamNames.facing(facing)))
            add(d("Hardware level", CamNames.level(level)))
            add(d("Sensor orientation", "$orientation°"))
            add(d("Active array", active?.let { "${it.width()}×${it.height()} @ ${it.left},${it.top}" }))
            add(d("Pixel array", CamNames.size(pixel)))
            add(d("Physical size", phys?.let { "${fmt2(it.width.toDouble())}×${fmt2(it.height.toDouble())} mm" }))
            add(d("Focal lengths", focal.joinToString(", ") { "${fmt2(it.toDouble())} mm" }))
            add(d("35 mm equivalent", eq))
            add(d("Apertures", apert.joinToString(", ") { "f/${fmt1(it.toDouble())}" }))
            add(d("Min focus distance", minFocus?.let { if (it == 0f) "fixed focus" else "${fmt2(it.toDouble())} dpt" }))
            add(d("Capabilities", capabilities.joinToString(", ") { CamNames.capability(it) }))
            if (physicalIds.isNotEmpty()) add(d("Physical cameras", physicalIds.joinToString(", ")))
            add(d("Flash", hasFlash))
            if (hasFlash && Build.VERSION.SDK_INT >= 33) {
                add(d("Torch strength max", chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL)))
            }
            add(d("AF modes", afModes.joinToString(", ") { CamNames.afMode(it) }))
            add(d("Max JPEG", CamNames.size(jpegMax)))
            if (jpegHighRes.isNotEmpty()) add(d("High-res JPEG", CamNames.size(jpegHighRes.maxByOrNull { it.width.toLong() * it.height })))
            add(d("Max YUV", CamNames.size(yuvSizes.maxByOrNull { it.width.toLong() * it.height })))
            add(d("Max RAW_SENSOR", CamNames.size(rawSizes.maxByOrNull { it.width.toLong() * it.height })))
            add(d("Preview sizes", previewSizes.size))
            add(d("FPS ranges", fps.joinToString(" ") { "[${it.lower},${it.upper}]" }))
            add(d("ISO range", iso?.let { "${it.lower}..${it.upper}" }))
            add(d("Exposure range", exp?.let { "${it.lower / 1000} µs..${it.upper / 1_000_000} ms" }))
        }
    }

    fun warnings(): List<Txt> = buildList {
        if (orientation !in listOf(0, 90, 180, 270)) add(txt(R.string.camera_r_bad_orientation, orientation))
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (active == null || active.width() <= 0 || active.height() <= 0) add(txt(R.string.camera_r_no_active_array))
        if (yuvSizes.isEmpty()) add(txt(R.string.camera_r_no_yuv))
        if (jpegSizes.isEmpty() && CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE in capabilities) add(txt(R.string.camera_r_no_jpeg))
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        if (focal.isEmpty() || focal.any { it <= 0f || it > 100f }) add(txt(R.string.camera_r_bad_focal))
        val phys = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (phys == null || phys.width <= 0f || phys.height <= 0f || phys.width > 50f) add(txt(R.string.camera_r_bad_physical))
        val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
        if (fps.isEmpty() || fps.maxOf { it.upper } < 15) add(txt(R.string.camera_r_low_fps_ranges))
    }
}
