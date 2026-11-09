package org.rombench.areas.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

internal fun Handler.executor() = Executor { post(it) }

/** Mean and spread of a YUV_420_888 frame (sampled on a grid). */
internal data class FrameStats(val meanY: Double, val sdY: Double, val meanU: Double, val meanV: Double, val w: Int, val h: Int) {
    val black get() = meanY < 10 && sdY < 4
    val flat get() = !black && sdY < 2.5
    val green get() = meanU < 108 && meanV < 108
    val magenta get() = meanU > 148 && meanV > 148
}

internal fun analyseYuv(img: Image): FrameStats? {
    if (img.format != ImageFormat.YUV_420_888 || img.planes.size < 3) return null
    val w = img.width
    val h = img.height
    val yp = img.planes[0]
    val up = img.planes[1]
    val vp = img.planes[2]
    val yb = yp.buffer
    var n = 0
    var sum = 0.0
    var sq = 0.0
    var row = 0
    while (row < h) {
        var col = 0
        while (col < w) {
            val i = row * yp.rowStride + col * yp.pixelStride
            if (i < yb.limit()) {
                val v = (yb.get(i).toInt() and 0xff).toDouble()
                sum += v; sq += v * v; n++
            }
            col += 4
        }
        row += 4
    }
    var un = 0
    var us = 0.0
    var vs = 0.0
    val ub = up.buffer
    val vb = vp.buffer
    row = 0
    while (row < h / 2) {
        var col = 0
        while (col < w / 2) {
            val iu = row * up.rowStride + col * up.pixelStride
            val iv = row * vp.rowStride + col * vp.pixelStride
            if (iu < ub.limit() && iv < vb.limit()) {
                us += ub.get(iu).toInt() and 0xff
                vs += vb.get(iv).toInt() and 0xff
                un++
            }
            col += 2
        }
        row += 2
    }
    if (n == 0 || un == 0) return null
    val mean = sum / n
    return FrameStats(mean, sqrt((sq / n - mean * mean).coerceAtLeast(0.0)), us / un, vs / un, w, h)
}

internal enum class ProbeStage { OPEN_TIMEOUT, OPEN_ERROR, DISCONNECTED, SESSION_FAILED, REQUEST_FAILED, STREAMED }

internal data class FrameProbe(
    val stage: ProbeStage,
    val size: Size?,
    val errorCode: Int? = null,
    val error: String? = null,
    val openMs: Long? = null,
    val sessionMs: Long? = null,
    val firstFrameMs: Long? = null,
    val frames: Int = 0,
    val started: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val failReasons: String? = null,
    val bufferLost: Int = 0,
    val fps: Double? = null,
    val wallFps: Double? = null,
    val nonMonotonic: Int = 0,
    val stats: FrameStats? = null,
    val disconnectedLater: Boolean = false,
    val errorLater: Int? = null,
)

/** State callback that turns Camera2 callbacks into deferreds. */
internal class DeviceWaiter : CameraDevice.StateCallback() {
    val opened = CompletableDeferred<CameraDevice>()
    val closed = CompletableDeferred<Unit>()
    val trouble = CompletableDeferred<Unit>()
    @Volatile var abandoned = false
    @Volatile var errorCode: Int? = null
    @Volatile var disconnected = false

    override fun onOpened(camera: CameraDevice) {
        if (abandoned) camera.close() else opened.complete(camera)
    }

    override fun onDisconnected(camera: CameraDevice) {
        disconnected = true
        opened.completeExceptionally(IllegalStateException("onDisconnected"))
        trouble.complete(Unit)
        camera.close()
    }

    override fun onError(camera: CameraDevice, error: Int) {
        errorCode = error
        opened.completeExceptionally(IllegalStateException("onError $error"))
        trouble.complete(Unit)
        camera.close()
    }

    override fun onClosed(camera: CameraDevice) {
        closed.complete(Unit)
    }
}

/**
 * Opens camera [id], streams a small YUV_420_888 stream until 30 frames or [frameTimeoutMs],
 * analyses a late frame and closes the device completely (waiting for onClosed) so that the
 * next camera can be opened on HALs that cannot hold two sessions.
 */
@SuppressLint("MissingPermission")
internal suspend fun probeFrames(cm: CameraManager, id: String, size: Size?, handler: Handler, frameTimeoutMs: Long = 6000): FrameProbe {
    val sz = size ?: Size(640, 480)
    val waiter = DeviceWaiter()
    val t0 = SystemClock.elapsedRealtime()
    try {
        cm.openCamera(id, handler.executor(), waiter)
    } catch (e: Exception) {
        return FrameProbe(ProbeStage.OPEN_ERROR, sz, error = e.toString(), errorCode = (e as? android.hardware.camera2.CameraAccessException)?.reason)
    }
    val device = try {
        withTimeoutOrNull(5000) { waiter.opened.await() }
    } catch (e: Exception) {
        null
    }
    if (device == null) {
        waiter.abandoned = true
        return when {
            waiter.disconnected -> FrameProbe(ProbeStage.DISCONNECTED, sz)
            waiter.errorCode != null -> FrameProbe(ProbeStage.OPEN_ERROR, sz, errorCode = waiter.errorCode)
            else -> FrameProbe(ProbeStage.OPEN_TIMEOUT, sz)
        }
    }
    val openMs = SystemClock.elapsedRealtime() - t0

    val reader = ImageReader.newInstance(sz.width, sz.height, ImageFormat.YUV_420_888, 4)
    val frames = AtomicInteger(0)
    val done = CompletableDeferred<Unit>()
    val readerOpen = java.util.concurrent.atomic.AtomicBoolean(true)
    var firstAt = 0L
    var lastAt = 0L
    var firstTs = 0L
    var lastTs = 0L
    var nonMono = 0
    var stats: FrameStats? = null
    var started = 0
    var completed = 0
    var failed = 0
    var lost = 0
    val failReasons = mutableSetOf<String>()
    var reqAt = 0L
    reader.setOnImageAvailableListener({ r ->
        if (!readerOpen.get()) return@setOnImageAvailableListener
        val img = try {
            r.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return@setOnImageAvailableListener
        try {
            val now = SystemClock.elapsedRealtime()
            val n = frames.incrementAndGet()
            if (n == 1) { firstAt = now; firstTs = img.timestamp } else if (img.timestamp <= lastTs) nonMono++
            lastAt = now
            lastTs = img.timestamp
            // Analyse a late frame (AE has converged) and keep the last one.
            if (n >= 8 && (n % 5 == 0 || stats == null)) analyseYuv(img)?.let { stats = it }
            if (n >= 30) done.complete(Unit)
        } finally {
            img.close()
        }
    }, handler)

    var session: CameraCaptureSession? = null
    try {
        val sessionDef = CompletableDeferred<CameraCaptureSession>()
        val s0 = SystemClock.elapsedRealtime()
        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(reader.surface)), handler.executor(),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    sessionDef.complete(s)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    sessionDef.completeExceptionally(IllegalStateException("onConfigureFailed"))
                }
            },
        )
        try {
            device.createCaptureSession(cfg)
        } catch (e: Exception) {
            return FrameProbe(ProbeStage.SESSION_FAILED, sz, error = e.toString(), openMs = openMs)
        }
        val ses = try {
            withTimeoutOrNull(4000) { sessionDef.await() }
        } catch (e: Exception) {
            null
        }
        session = ses
        if (ses == null) {
            return if (waiter.disconnected || waiter.errorCode != null) {
                FrameProbe(ProbeStage.DISCONNECTED, sz, errorCode = waiter.errorCode, openMs = openMs, disconnectedLater = waiter.disconnected, errorLater = waiter.errorCode)
            } else {
                FrameProbe(ProbeStage.SESSION_FAILED, sz, error = if (sessionDef.isCompleted) "onConfigureFailed" else "no callback in 4 s", openMs = openMs)
            }
        }
        val sessionMs = SystemClock.elapsedRealtime() - s0
        val cb = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long) {
                started++
            }

            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                completed++
            }

            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                failed++
                failReasons += if (failure.reason == CaptureFailure.REASON_FLUSHED) "FLUSHED" else "ERROR"
            }

            override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frameNumber: Long) {
                lost++
            }
        }
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }.build()
            reqAt = SystemClock.elapsedRealtime()
            ses.setRepeatingRequest(req, cb, handler)
        } catch (e: Exception) {
            return FrameProbe(ProbeStage.REQUEST_FAILED, sz, error = e.toString(), openMs = openMs, sessionMs = sessionMs)
        }
        withTimeoutOrNull(frameTimeoutMs) {
            // Stop early on device errors.
            kotlinx.coroutines.selects.select<Unit> {
                done.onAwait { }
                waiter.trouble.onAwait { }
            }
        }
        val n = frames.get()
        val fps = if (n >= 3 && lastTs > firstTs) (n - 1) * 1e9 / (lastTs - firstTs) else null
        val wall = if (n >= 3 && lastAt > firstAt) (n - 1) * 1000.0 / (lastAt - firstAt) else null
        return FrameProbe(
            ProbeStage.STREAMED, sz, openMs = openMs, sessionMs = sessionMs,
            firstFrameMs = if (n > 0) firstAt - reqAt else null, frames = n,
            started = started, completed = completed, failed = failed, failReasons = failReasons.joinToString(",").ifEmpty { null },
            bufferLost = lost, fps = fps, wallFps = wall, nonMonotonic = nonMono, stats = stats,
            disconnectedLater = waiter.disconnected, errorLater = waiter.errorCode,
        )
    } finally {
        withContext(NonCancellable) {
            try {
                session?.stopRepeating()
            } catch (_: Exception) {
            }
            try {
                session?.close()
            } catch (_: Exception) {
            }
            try {
                device.close()
            } catch (_: Exception) {
            }
            withTimeoutOrNull(3000) { waiter.closed.await() }
            readerOpen.set(false)
            reader.close()
        }
    }
}

internal data class TorchProbe(val error: Throwable? = null, val onSeen: Boolean = false, val offSeen: Boolean = false, val unavailable: Boolean = false, val onMs: Long? = null)

/** Switches the torch of [id] on and off and checks that TorchCallback reports both changes. */
internal suspend fun probeTorch(cm: CameraManager, id: String, handler: Handler): TorchProbe {
    val states = kotlinx.coroutines.channels.Channel<Boolean>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    val unavail = java.util.concurrent.atomic.AtomicBoolean(false)
    val cb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == id) states.trySend(enabled)
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == id) unavail.set(true)
        }
    }
    cm.registerTorchCallback(cb, handler)
    try {
        delay(200)
        while (states.tryReceive().isSuccess) continue
        val t0 = SystemClock.elapsedRealtime()
        try {
            cm.setTorchMode(id, true)
        } catch (e: Exception) {
            return TorchProbe(error = e, unavailable = unavail.get())
        }
        val on = withTimeoutOrNull(2000) { while (!states.receive()) continue; true } ?: false
        val onMs = if (on) SystemClock.elapsedRealtime() - t0 else null
        delay(400)
        try {
            cm.setTorchMode(id, false)
        } catch (e: Exception) {
            return TorchProbe(error = e, onSeen = on, unavailable = unavail.get(), onMs = onMs)
        }
        val off = withTimeoutOrNull(2000) { while (states.receive()) continue; true } ?: false
        return TorchProbe(onSeen = on, offSeen = off, unavailable = unavail.get(), onMs = onMs)
    } finally {
        withContext(NonCancellable) {
            try {
                cm.setTorchMode(id, false)
            } catch (_: Exception) {
            }
            cm.unregisterTorchCallback(cb)
        }
    }
}
