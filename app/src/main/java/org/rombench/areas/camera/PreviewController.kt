package org.rombench.areas.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.rombench.R
import org.rombench.core.ResultStore
import org.rombench.core.Status
import org.rombench.core.Txt
import org.rombench.core.d
import org.rombench.core.result
import org.rombench.core.txt
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.max

internal enum class FlashMode(@StringRes val label: Int) {
    OFF(R.string.camera_flash_off),
    AUTO(R.string.camera_flash_auto),
    ON(R.string.camera_flash_on),
    TORCH(R.string.camera_flash_torch),
}

internal class Shot(
    val bitmap: ImageBitmap?, val width: Int, val height: Int, val bytes: Int, val ms: Long,
    val exif: Int?, val jpegOrientation: Int, val flashState: Int?, val cameraId: String,
)

/**
 * One Camera2 preview session on its own HandlerThread. Every camera operation runs on that
 * thread; switching cameras waits for onClosed before opening the next one (HALs such as the
 * Lumia 950 XL's cannot hold two sessions).
 */
@SuppressLint("MissingPermission")
internal class PreviewController(ctx: Context) {
    private val cm = ctx.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("rb-camera-preview").apply { start() }
    private val handler = Handler(thread.looper)

    var cameraId by mutableStateOf<String?>(null)
        private set
    var streaming by mutableStateOf(false)
        private set
    var error by mutableStateOf<Txt?>(null)
        private set
    var previewSize by mutableStateOf<Size?>(null)
        private set
    var fps by mutableFloatStateOf(0f)
        private set
    var iso by mutableStateOf<Int?>(null)
        private set
    var exposureNs by mutableStateOf<Long?>(null)
        private set
    var afState by mutableStateOf<Int?>(null)
        private set
    var aeState by mutableStateOf<Int?>(null)
        private set
    var shot by mutableStateOf<Shot?>(null)
        private set
    var capturing by mutableStateOf(false)
        private set
    var captureAvailable by mutableStateOf(false)
        private set
    var flash by mutableStateOf(FlashMode.OFF)
        private set

    /** Elapsed time of the last completed preview capture (polled by the UI). */
    @Volatile var lastFrameAt = 0L
        private set

    private var released = false
    private var device: CameraDevice? = null
    private var closingDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var surface: Surface? = null
    private var info: CamInfo? = null
    private var gen = 0
    private var afterClose: (() -> Unit)? = null
    private var frameCount = 0
    private var fpsWindow = 0L
    private var captureStart = 0L
    private var precaptureAt = 0L
    private var sawPrecapture = false
    private var waitingPrecapture = false
    private var stillFlashState: Int? = null
    private var jpegOrientation = 0

    fun open(id: String, texture: SurfaceTexture) {
        handler.post { if (!released) closeThen { openNow(id, texture) } }
    }

    fun close() {
        handler.post { closeThen(null) }
    }

    fun release() {
        handler.post {
            released = true
            closeThen { thread.quitSafely() }
        }
    }

    fun selectFlash(mode: FlashMode) {
        flash = mode
        handler.post { if (session != null) startRepeating() }
    }

    fun capture(displayDegrees: Int) {
        handler.post { captureNow(displayDegrees) }
    }

    // ---- internals, all on the handler thread ----

    private val closeTimeout = Runnable { onDeviceClosed() }

    private fun closeThen(next: (() -> Unit)?) {
        gen++
        handler.removeCallbacks(captureTimeout)
        capturing = false
        waitingPrecapture = false
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        streaming = false
        val d = device
        device = null
        if (d == null && closingDevice == null) {
            finishClose()
            next?.invoke()
            return
        }
        afterClose = next
        if (d != null) {
            closingDevice = d
            try {
                d.close()
            } catch (_: Exception) {
            }
        }
        handler.removeCallbacks(closeTimeout)
        handler.postDelayed(closeTimeout, 3000)
    }

    private fun onDeviceClosed() {
        handler.removeCallbacks(closeTimeout)
        closingDevice = null
        finishClose()
        val n = afterClose
        afterClose = null
        n?.invoke()
    }

    private fun finishClose() {
        jpegReader?.close()
        jpegReader = null
        surface?.release()
        surface = null
        captureAvailable = false
    }

    private fun openNow(id: String, texture: SurfaceTexture) {
        if (released || cm == null) return
        val myGen = gen
        error = null
        val ci = try {
            CamInfo(id, cm.getCameraCharacteristics(id))
        } catch (e: Exception) {
            error = accessReason(e)
            return
        }
        info = ci
        cameraId = id
        val ps = choosePreview(ci)
        previewSize = ps
        texture.setDefaultBufferSize(ps.width, ps.height)
        surface = Surface(texture)
        jpegReader = ci.jpegMax?.let { js ->
            ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 2).also { r ->
                r.setOnImageAvailableListener({ onJpeg(it, ci) }, handler)
            }
        }
        fpsWindow = SystemClock.elapsedRealtime()
        frameCount = 0
        try {
            cm.openCamera(id, handler.executor(), object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (myGen != gen || released) {
                        camera.close()
                        return
                    }
                    device = camera
                    createSession(camera, myGen, withJpeg = true)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) {
                        device = null
                        streaming = false
                        error = txt(R.string.camera_r_disconnected)
                    }
                }

                override fun onError(camera: CameraDevice, code: Int) {
                    camera.close()
                    if (device === camera || (device == null && myGen == gen)) {
                        device = null
                        streaming = false
                        error = openErrorReason(code)
                    }
                }

                override fun onClosed(camera: CameraDevice) {
                    if (camera === closingDevice) onDeviceClosed()
                }
            })
        } catch (e: Exception) {
            error = accessReason(e)
        }
    }

    private fun choosePreview(ci: CamInfo): Size {
        val target = ci.jpegMax?.let { it.width.toDouble() / it.height } ?: (4.0 / 3)
        val fit = ci.previewSizes.filter { it.width <= 1920 && it.height <= 1080 }
        val same = fit.filter { abs(it.width.toDouble() / it.height - target) < 0.03 }
        return (same.ifEmpty { fit }).maxByOrNull { it.width * it.height } ?: ci.previewSizes.firstOrNull() ?: Size(640, 480)
    }

    private fun createSession(camera: CameraDevice, myGen: Int, withJpeg: Boolean) {
        val s = surface ?: return
        val outs = mutableListOf(OutputConfiguration(s))
        val jr = if (withJpeg) jpegReader else null
        jr?.let { outs += OutputConfiguration(it.surface) }
        try {
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, outs, handler.executor(),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(ses: CameraCaptureSession) {
                            if (myGen != gen) {
                                ses.close()
                                return
                            }
                            session = ses
                            captureAvailable = jr != null
                            startRepeating()
                        }

                        override fun onConfigureFailed(ses: CameraCaptureSession) {
                            if (myGen != gen) return
                            if (withJpeg && jr != null) {
                                // Some HALs refuse preview + full-size JPEG: keep the preview at least.
                                error = txt(R.string.camera_r_jpeg_session)
                                createSession(camera, myGen, withJpeg = false)
                            } else {
                                error = txt(R.string.camera_r_session_failed)
                            }
                        }
                    },
                ),
            )
        } catch (e: Exception) {
            error = accessReason(e)
        }
    }

    private fun applyCommon(b: CaptureRequest.Builder) {
        val ci = info ?: return
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in ci.afModes) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (!ci.hasFlash) return
        when (flash) {
            FlashMode.OFF -> {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            FlashMode.AUTO -> b.set(
                CaptureRequest.CONTROL_AE_MODE,
                if (CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH in ci.aeModes) CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH else CaptureRequest.CONTROL_AE_MODE_ON,
            )
            FlashMode.ON -> b.set(
                CaptureRequest.CONTROL_AE_MODE,
                if (CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH in ci.aeModes) CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH else CaptureRequest.CONTROL_AE_MODE_ON,
            )
            FlashMode.TORCH -> {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
        }
    }

    private fun previewRequest(): CaptureRequest.Builder? {
        val c = device ?: return null
        val s = surface ?: return null
        return try {
            c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(s)
                applyCommon(this)
            }
        } catch (e: Exception) {
            error = accessReason(e)
            null
        }
    }

    private fun startRepeating() {
        val ses = session ?: return
        val req = previewRequest()?.build() ?: return
        try {
            ses.setRepeatingRequest(req, repeatCb, handler)
            streaming = true
            lastFrameAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            error = accessReason(e)
        }
    }

    private val repeatCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            lastFrameAt = now
            frameCount++
            if (now - fpsWindow >= 1000) {
                fps = frameCount * 1000f / (now - fpsWindow)
                frameCount = 0
                fpsWindow = now
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                afState = result.get(CaptureResult.CONTROL_AF_STATE)
                aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            }
            if (waitingPrecapture) {
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                if (ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) sawPrecapture = true
                val el = now - precaptureAt
                if ((sawPrecapture && ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) || el > 1500 || (!sawPrecapture && el > 600)) {
                    waitingPrecapture = false
                    stillCapture()
                }
            }
        }
    }

    private val captureTimeout = Runnable {
        if (!capturing) return@Runnable
        capturing = false
        waitingPrecapture = false
        val ci = info ?: return@Runnable
        ResultStore.put(
            CameraArea.result(
                "${ci.id}.jpeg", txt(R.string.camera_t_jpeg, ci.label()), Status.FAIL, txt(R.string.camera_s_jpeg_timeout),
                txt(R.string.camera_r_jpeg_timeout), listOf(d("Flash mode", flash.name)),
            ),
        )
    }

    private fun captureNow(displayDegrees: Int) {
        val ses = session
        val ci = info
        if (ses == null || ci == null || jpegReader == null || !captureAvailable) {
            error = txt(R.string.camera_r_no_capture)
            return
        }
        if (capturing) return
        capturing = true
        captureStart = SystemClock.elapsedRealtime()
        stillFlashState = null
        val front = ci.facing == CameraMetadata.LENS_FACING_FRONT
        jpegOrientation = if (front) (ci.orientation + displayDegrees) % 360 else (ci.orientation - displayDegrees + 360) % 360
        handler.postDelayed(captureTimeout, 6000)
        if (ci.hasFlash && (flash == FlashMode.AUTO || flash == FlashMode.ON)) {
            val b = previewRequest() ?: return
            b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            try {
                ses.capture(b.build(), null, handler)
                precaptureAt = SystemClock.elapsedRealtime()
                sawPrecapture = false
                waitingPrecapture = true
            } catch (e: Exception) {
                stillCapture()
            }
        } else {
            stillCapture()
        }
    }

    private fun stillCapture() {
        val c = device
        val ses = session
        val jr = jpegReader
        val s = surface
        if (c == null || ses == null || jr == null || s == null) {
            capturing = false
            return
        }
        try {
            val b = c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jr.surface)
                addTarget(s)
                applyCommon(this)
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                set(CaptureRequest.JPEG_QUALITY, 92.toByte())
            }
            ses.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    stillFlashState = result.get(CaptureResult.FLASH_STATE)
                }

                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    handler.removeCallbacks(captureTimeout)
                    capturing = false
                    val ci = info ?: return
                    ResultStore.put(
                        CameraArea.result(
                            "${ci.id}.jpeg", txt(R.string.camera_t_jpeg, ci.label()), Status.FAIL, txt(R.string.camera_s_jpeg_failed),
                            txt(R.string.camera_r_jpeg_failed),
                            listOf(d("Failure reason", if (failure.reason == CaptureFailure.REASON_FLUSHED) "FLUSHED" else "ERROR"), d("Flash mode", flash.name)),
                        ),
                    )
                }
            }, handler)
        } catch (e: Exception) {
            handler.removeCallbacks(captureTimeout)
            capturing = false
            error = accessReason(e)
        }
    }

    private fun onJpeg(reader: ImageReader, ci: CamInfo) {
        val img = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return
        val bytes = try {
            val b = img.planes[0].buffer
            ByteArray(b.remaining()).also { b.get(it) }
        } finally {
            img.close()
        }
        val ms = SystemClock.elapsedRealtime() - captureStart
        handler.removeCallbacks(captureTimeout)
        capturing = false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        var sample = 1
        while (w > 0 && max(w, h) / sample > 1024) sample *= 2
        val bmp = if (w > 0) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) else null
        val exif = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrNull()
        val rot = when (exif) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val shown: Bitmap? = bmp?.let { b ->
            if (rot == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot) }, true)
        }
        shot = Shot(shown?.asImageBitmap(), w, h, bytes.size, ms, exif, jpegOrientation, stillFlashState, ci.id)
        val requested = ci.jpegMax
        val details = listOf(
            d("Size", "${w}×$h"),
            d("Requested", CamNames.size(requested)),
            d("Bytes", bytes.size),
            d("Capture time", "$ms ms"),
            d("JPEG_ORIENTATION", jpegOrientation),
            d("EXIF orientation", exif),
            d("Flash mode", flash.name),
            d("FLASH_STATE", when (stillFlashState) {
                null -> null
                CaptureResult.FLASH_STATE_FIRED -> "FIRED"
                CaptureResult.FLASH_STATE_READY -> "READY"
                CaptureResult.FLASH_STATE_CHARGING -> "CHARGING"
                CaptureResult.FLASH_STATE_PARTIAL -> "PARTIAL"
                else -> "UNAVAILABLE"
            }),
        )
        val title = txt(R.string.camera_t_jpeg, ci.label())
        val r = when {
            bmp == null -> CameraArea.result(
                "${ci.id}.jpeg", title, Status.FAIL, txt(R.string.camera_s_jpeg_undecodable, bytes.size), txt(R.string.camera_r_jpeg_undecodable), details,
            )
            requested != null && !((w == requested.width && h == requested.height) || (w == requested.height && h == requested.width)) ->
                CameraArea.result(
                    "${ci.id}.jpeg", title, Status.WARN, txt(R.string.camera_s_jpeg_ok, w, h, bytes.size / 1024, ms),
                    txt(R.string.camera_r_jpeg_size, CamNames.size(requested) ?: "—"), details,
                )
            else -> CameraArea.result("${ci.id}.jpeg", title, Status.PASS, txt(R.string.camera_s_jpeg_ok, w, h, bytes.size / 1024, ms), details = details)
        }
        ResultStore.put(r)
    }
}

/** TextureView transform for landscape display rotations (portrait-natural devices). */
internal fun previewTransform(viewW: Float, viewH: Float, ps: Size, rotation: Int): Matrix {
    val m = Matrix()
    val viewRect = android.graphics.RectF(0f, 0f, viewW, viewH)
    val bufferRect = android.graphics.RectF(0f, 0f, ps.height.toFloat(), ps.width.toFloat())
    val cx = viewRect.centerX()
    val cy = viewRect.centerY()
    if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
        bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
        m.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = max(viewH / ps.height, viewW / ps.width)
        m.postScale(scale, scale, cx, cy)
        m.postRotate(90f * (rotation - 2), cx, cy)
    } else if (rotation == Surface.ROTATION_180) {
        m.postRotate(180f, cx, cy)
    }
    return m
}
