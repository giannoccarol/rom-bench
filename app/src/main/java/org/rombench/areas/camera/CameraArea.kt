package org.rombench.areas.camera

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.Perms
import org.rombench.core.Status
import org.rombench.core.TestResult
import org.rombench.core.Txt
import org.rombench.core.d
import org.rombench.core.fmt1
import org.rombench.core.result
import org.rombench.core.skippedForPermission
import org.rombench.core.txt

object CameraArea : Area {
    override val id = "camera"
    override val title = R.string.camera_title
    override val subtitle = R.string.camera_subtitle
    override val icon: ImageVector = Icons.Outlined.PhotoCamera
    override val group = AreaGroup.MEDIA
    override val permissions = listOf(Perms.CAMERA)
    override val autoSeconds = 24

    override suspend fun autoCheck(ctx: Context): List<TestResult> = withContext(Dispatchers.Default) {
        val listTitle = txt(R.string.camera_t_list)
        val missing = Perms.missing(ctx, permissions)
        if (missing.isNotEmpty()) return@withContext listOf(skippedForPermission("list", listTitle, missing))
        val pm = ctx.packageManager
        val declared = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val cm = ctx.getSystemService(CameraManager::class.java)
            ?: return@withContext listOf(result("list", listTitle, Status.FAIL, txt(R.string.camera_s_no_service), txt(R.string.camera_r_no_service)))
        val ids = try {
            cm.cameraIdList.toList()
        } catch (e: Exception) {
            return@withContext listOf(
                result("list", listTitle, Status.FAIL, txt(R.string.camera_s_list_failed), accessReason(e), listOf(d("Error", e.toString()))),
            )
        }
        val out = mutableListOf<TestResult>()
        val infos = mutableListOf<CamInfo>()
        for (id in ids) {
            try {
                infos += CamInfo(id, cm.getCameraCharacteristics(id))
            } catch (e: Exception) {
                out += result("$id.info", txt(R.string.camera_t_info, id), Status.FAIL, txt(R.string.camera_s_chars_failed), accessReason(e), listOf(d("Error", e.toString())))
            }
        }
        val listDetails = buildList {
            add(d("FEATURE_CAMERA_ANY", declared))
            add(d("FEATURE_CAMERA_FLASH", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)))
            add(d("FEATURE_CAMERA_FRONT", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)))
            infos.forEach { add(d("Camera ${it.id}", "${CamNames.facing(it.facing)} · ${CamNames.level(it.level)} · ${CamNames.size(it.jpegMax)}")) }
            if (cm.concurrentCameraIds.isNotEmpty()) add(d("Concurrent sets", cm.concurrentCameraIds.joinToString(" ") { it.sorted().joinToString(",", "[", "]") }))
        }
        out.add(
            0,
            when {
                ids.isEmpty() && declared -> result("list", listTitle, Status.FAIL, txt(R.string.camera_s_none), txt(R.string.camera_r_none_declared), listDetails)
                ids.isEmpty() -> result("list", listTitle, Status.INFO, txt(R.string.camera_s_none), txt(R.string.camera_r_none), listDetails)
                infos.none { it.facing == android.hardware.camera2.CameraMetadata.LENS_FACING_BACK } && pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ->
                    result("list", listTitle, Status.WARN, txt(R.string.camera_s_list, ids.size, ids.joinToString(", ")), txt(R.string.camera_r_no_back), listDetails)
                else -> result("list", listTitle, Status.PASS, txt(R.string.camera_s_list, ids.size, ids.joinToString(", ")), details = listDetails)
            },
        )
        if (infos.isEmpty()) return@withContext out

        val thread = HandlerThread("rb-camera-auto").apply { start() }
        val handler = Handler(thread.looper)
        try {
            // One camera at a time: some HALs (Lumia 950 XL) cannot hold two sessions.
            for (info in infos) {
                out += infoResult(info)
                out += framesResult(info, probeFrames(cm, info.id, info.probeSize(), handler))
            }
            for (info in infos.filter { it.hasFlash }) {
                out += torchResult(info, probeTorch(cm, info.id, handler))
            }
        } finally {
            thread.quitSafely()
        }
        out
    }

    @Composable
    override fun Content() = CameraContent()

    // ---------------------------------------------------------------------------------------

    internal fun join(items: List<Txt>): Txt? = items.reduceOrNull { a, b -> txt(R.string.camera_join2, a, b) }

    private fun infoResult(info: CamInfo): TestResult {
        val warn = info.warnings()
        val summary = txt(
            R.string.camera_s_info, CamNames.facingTxt(info.facing), CamNames.level(info.level), info.orientation,
            CamNames.size(info.jpegMax) ?: "—",
        )
        val legacy = info.level == android.hardware.camera2.CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        return result(
            "${info.id}.info", txt(R.string.camera_t_info, info.id), if (warn.isEmpty()) Status.INFO else Status.WARN, summary,
            join(warn) ?: if (legacy) txt(R.string.camera_r_legacy) else null, info.details(),
        )
    }

    private fun framesResult(info: CamInfo, p: FrameProbe): TestResult {
        val name = "${info.id}.frames"
        val title = txt(R.string.camera_t_frames, info.label())
        val details = buildList {
            add(d("Stream", "YUV_420_888 ${CamNames.size(p.size)}"))
            add(d("Open", p.openMs?.let { "$it ms" }))
            add(d("Session", p.sessionMs?.let { "$it ms" }))
            add(d("First frame", p.firstFrameMs?.let { "$it ms" }))
            add(d("Frames", p.frames))
            add(d("Captures started / completed / failed", "${p.started} / ${p.completed} / ${p.failed}"))
            p.failReasons?.let { add(d("Failure reasons", it)) }
            if (p.bufferLost > 0) add(d("Buffers lost", p.bufferLost))
            add(d("FPS (sensor timestamps)", p.fps?.let { fmt1(it) }))
            add(d("FPS (wall clock)", p.wallFps?.let { fmt1(it) }))
            if (p.nonMonotonic > 0) add(d("Non-monotonic timestamps", p.nonMonotonic))
            p.stats?.let { s ->
                add(d("Mean Y / U / V", "${fmt1(s.meanY)} / ${fmt1(s.meanU)} / ${fmt1(s.meanV)}"))
                add(d("Y std dev", fmt1(s.sdY)))
            }
            p.errorCode?.let { add(d("Error code", openErrorName(it))) }
            p.errorLater?.let { add(d("Error during stream", openErrorName(it))) }
            if (p.disconnectedLater) add(d("Disconnected during stream", true))
            p.error?.let { add(d("Error", it)) }
        }
        return when (p.stage) {
            ProbeStage.OPEN_TIMEOUT -> result(name, title, Status.FAIL, txt(R.string.camera_s_open_timeout), txt(R.string.camera_r_open_timeout), details)
            ProbeStage.OPEN_ERROR -> result(
                name, title, Status.FAIL, txt(R.string.camera_s_open_failed),
                p.errorCode?.let { if (p.error != null) accessCodeReason(it) else openErrorReason(it) } ?: txt(R.string.camera_r_device_error), details,
            )
            ProbeStage.DISCONNECTED -> result(name, title, Status.FAIL, txt(R.string.camera_s_disconnected), txt(R.string.camera_r_disconnected), details)
            ProbeStage.SESSION_FAILED -> result(name, title, Status.FAIL, txt(R.string.camera_s_session_failed), txt(R.string.camera_r_session_failed), details)
            ProbeStage.REQUEST_FAILED -> result(name, title, Status.FAIL, txt(R.string.camera_s_request_failed), txt(R.string.camera_r_request_failed), details)
            ProbeStage.STREAMED -> streamed(name, title, p, details)
        }
    }

    private fun accessCodeReason(code: Int): Txt = accessReason(android.hardware.camera2.CameraAccessException(code))

    private fun streamed(name: String, title: Txt, p: FrameProbe, details: List<org.rombench.core.Detail>): TestResult {
        if (p.frames == 0) {
            val reason = when {
                p.disconnectedLater -> txt(R.string.camera_r_disconnected)
                p.errorLater != null -> openErrorReason(p.errorLater)
                p.started > 0 || p.failed > 0 -> txt(R.string.camera_r_no_buffers)
                else -> txt(R.string.camera_r_no_sof)
            }
            return result(name, title, Status.FAIL, txt(R.string.camera_s_no_frames, p.started, p.failed), reason, details)
        }
        val warn = mutableListOf<Txt>()
        if (p.disconnectedLater) warn += txt(R.string.camera_r_disconnected)
        p.errorLater?.let { warn += openErrorReason(it) }
        if (p.frames < 30) warn += txt(R.string.camera_r_few_frames, p.frames)
        val fps = p.fps ?: p.wallFps
        if (fps != null && fps < 10) warn += txt(R.string.camera_r_low_fps, fmt1(fps))
        if ((p.firstFrameMs ?: 0) > 3000) warn += txt(R.string.camera_r_slow_first, p.firstFrameMs)
        if (p.failed > 0) warn += txt(R.string.camera_r_capture_failed, p.failed)
        if (p.nonMonotonic > 0) warn += txt(R.string.camera_r_timestamps, p.nonMonotonic)
        p.stats?.let { s ->
            when {
                s.black -> warn += txt(R.string.camera_r_black, fmt1(s.meanY))
                s.flat -> warn += txt(R.string.camera_r_flat, fmt1(s.sdY))
            }
            if (s.green) warn += txt(R.string.camera_r_green, fmt1(s.meanU), fmt1(s.meanV))
            if (s.magenta) warn += txt(R.string.camera_r_magenta, fmt1(s.meanU), fmt1(s.meanV))
        }
        if (p.stats == null) warn += txt(R.string.camera_r_not_analysed)
        val summary = txt(R.string.camera_s_frames, p.frames, fps?.let { fmt1(it) } ?: "—", p.firstFrameMs ?: 0)
        return result(name, title, if (warn.isEmpty()) Status.PASS else Status.WARN, summary, join(warn), details)
    }

    private fun torchResult(info: CamInfo, t: TorchProbe): TestResult {
        val name = "${info.id}.torch"
        val title = txt(R.string.camera_t_torch, info.label())
        val details = listOf(
            d("setTorchMode error", t.error?.toString()),
            d("Callback ON", t.onSeen),
            d("Callback OFF", t.offSeen),
            d("ON latency", t.onMs?.let { "$it ms" }),
            d("Unavailable reported", t.unavailable),
        )
        return when {
            t.error != null -> result(name, title, Status.FAIL, txt(R.string.camera_s_torch_error), accessReason(t.error), details)
            t.onSeen && t.offSeen -> result(name, title, Status.PASS, txt(R.string.camera_s_torch_ok, t.onMs ?: 0), details = details)
            else -> result(name, title, Status.WARN, txt(R.string.camera_s_torch_no_cb), txt(R.string.camera_r_torch_no_cb), details)
        }
    }
}
