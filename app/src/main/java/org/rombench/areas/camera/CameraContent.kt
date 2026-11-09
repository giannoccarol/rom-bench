package org.rombench.areas.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.rombench.R
import org.rombench.core.AutoRunner
import org.rombench.core.Perms
import org.rombench.core.Status
import org.rombench.core.Txt
import org.rombench.core.d
import org.rombench.core.text
import org.rombench.core.txt
import org.rombench.ui.SubHeader
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.GuidedCheck
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.Metric
import org.rombench.ui.components.PermissionGate
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.rememberPolled
import org.rombench.ui.theme.LocalStatusColors

@Composable
internal fun CameraContent() {
    PermissionGate(listOf(Perms.CAMERA)) { CameraScreen() }
}

@Composable
private fun CameraScreen() {
    val ctx = LocalContext.current
    val cm = remember { ctx.getSystemService(CameraManager::class.java) } ?: return
    val infos = remember {
        runCatching { cm.cameraIdList.mapNotNull { id -> runCatching { CamInfo(id, cm.getCameraCharacteristics(id)) }.getOrNull() } }
            .getOrDefault(emptyList())
    }
    if (infos.isEmpty()) {
        SectionCard(stringResource(R.string.camera_preview_title), icon = Icons.Outlined.Videocam) {
            Hint(stringResource(R.string.camera_none_hint))
        }
        return
    }
    val ctl = remember { PreviewController(ctx.applicationContext) }
    DisposableEffect(Unit) { onDispose { ctl.release() } }
    var selected by remember { mutableStateOf(infos.first().id) }
    var previewOn by remember { mutableStateOf(false) }
    val run by AutoRunner.state.collectAsState()
    val autoBusy = run.running && run.current == CameraArea.id
    LaunchedEffect(autoBusy) { if (autoBusy) previewOn = false }
    val info = infos.firstOrNull { it.id == selected } ?: infos.first()
    val view = LocalView.current
    val config = LocalConfiguration.current
    val rotation = remember(config) { view.display?.rotation ?: Surface.ROTATION_0 }
    val sc = LocalStatusColors.current

    SectionCard(stringResource(R.string.camera_preview_title), subtitle = stringResource(R.string.camera_preview_sub), icon = Icons.Outlined.Videocam) {
        SubHeader(stringResource(R.string.camera_select))
        ButtonRow {
            infos.forEach { ci ->
                FilterChip(selected = selected == ci.id, onClick = { selected = ci.id }, label = { Text(ci.label().text()) })
            }
        }
        ButtonRow {
            Button(onClick = { previewOn = !previewOn }, enabled = !autoBusy) {
                Icon(if (previewOn) Icons.Outlined.Stop else Icons.Outlined.Videocam, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (previewOn) R.string.camera_preview_stop else R.string.camera_preview_start))
            }
            if (infos.size > 1) OutlinedButton(onClick = {
                val i = infos.indexOfFirst { it.id == selected }
                selected = infos[(i + 1) % infos.size].id
            }) {
                Icon(Icons.Outlined.Cameraswitch, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.camera_switch))
            }
        }
        if (autoBusy) Hint(stringResource(R.string.camera_auto_busy))
        if (previewOn) {
            PreviewView(ctl, info, rotation)
            LiveStats(ctl)
            if (info.hasFlash) {
                SubHeader(stringResource(R.string.camera_flash))
                ButtonRow {
                    FlashMode.entries.forEach { m ->
                        FilterChip(selected = ctl.flash == m, onClick = { ctl.selectFlash(m) }, label = { Text(stringResource(m.label)) })
                    }
                }
            }
            FilledTonalButton(onClick = { ctl.capture(rotation * 90) }, enabled = ctl.streaming && ctl.captureAvailable && !ctl.capturing) {
                if (ctl.capturing) CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Camera, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.camera_capture))
            }
        } else {
            Hint(stringResource(R.string.camera_preview_hint))
        }
        ctl.error?.let { Text(it.text(), color = sc.of(Status.WARN), style = MaterialTheme.typography.bodySmall) }
        ctl.shot?.let { ShotView(it) }
    }

    TorchSection(cm, infos)
    GuidedSection(info)
}

@Composable
private fun PreviewView(ctl: PreviewController, info: CamInfo, rotation: Int) {
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var tv by remember { mutableStateOf<TextureView?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(texture, info.id) { texture?.let { ctl.open(info.id, it) } }
    DisposableEffect(Unit) { onDispose { ctl.close() } }
    val ps = ctl.previewSize?.takeIf { ctl.cameraId == info.id }
    val swapped = ((info.orientation - rotation * 90 + 360) % 180) == 90
    val ratio = ps?.let { if (swapped) it.height.toFloat() / it.width else it.width.toFloat() / it.height } ?: 0.75f
    LaunchedEffect(tv, ps, rotation, viewSize) {
        val t = tv
        if (t != null && ps != null && viewSize.width > 0) t.setTransform(previewTransform(viewSize.width.toFloat(), viewSize.height.toFloat(), ps, rotation))
    }
    AndroidView(
        factory = { c ->
            TextureView(c).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        texture = st
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        texture = null
                        ctl.close()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
                tv = this
            }
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(16.dp)).onSizeChanged { viewSize = it },
    )
}

@Composable
private fun LiveStats(ctl: PreviewController) {
    val sc = LocalStatusColors.current
    val stalled by rememberPolled(500) { ctl.streaming && SystemClock.elapsedRealtime() - ctl.lastFrameAt > 3000 }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Metric(if (ctl.streaming) "%.1f".format(java.util.Locale.US, ctl.fps) else "—", "fps", Modifier.weight(1f))
        Metric(ctl.iso?.toString() ?: "—", "ISO", Modifier.weight(1f))
        Metric(ctl.exposureNs?.let { if (it > 0) "1/${(1e9 / it).toInt()}" else "—" } ?: "—", stringResource(R.string.camera_exposure), Modifier.weight(1f))
    }
    KeyValue(stringResource(R.string.camera_preview_size), CamNames.size(ctl.previewSize) ?: "—")
    KeyValue("AF / AE", "${CamNames.afState(ctl.afState)} / ${CamNames.aeState(ctl.aeState)}")
    if (stalled == true) Text(stringResource(R.string.camera_stalled), color = sc.of(Status.FAIL), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ShotView(shot: Shot) {
    SubHeader(stringResource(R.string.camera_last_shot, shot.cameraId))
    shot.bitmap?.let {
        Image(
            it, stringResource(R.string.camera_last_shot, shot.cameraId),
            Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit,
        )
    } ?: Text(stringResource(R.string.camera_r_jpeg_undecodable), color = LocalStatusColors.current.of(Status.FAIL), style = MaterialTheme.typography.bodySmall)
    KeyValue(stringResource(R.string.camera_shot_size), "${shot.width}×${shot.height} · ${shot.bytes / 1024} KiB")
    KeyValue(stringResource(R.string.camera_shot_time), "${shot.ms} ms")
    KeyValue("JPEG / EXIF orientation", "${shot.jpegOrientation}° / ${shot.exif ?: "—"}")
    shot.flashState?.let { KeyValue("FLASH_STATE", if (it == android.hardware.camera2.CaptureResult.FLASH_STATE_FIRED) "FIRED" else it.toString()) }
}

@Composable
private fun TorchSection(cm: CameraManager, infos: List<CamInfo>) {
    val withFlash = infos.filter { it.hasFlash }
    if (withFlash.isEmpty()) return
    val states = remember { mutableStateMapOf<String, Boolean>() }
    val unavailable = remember { mutableStateMapOf<String, Boolean>() }
    var error by remember { mutableStateOf<Txt?>(null) }
    val turnedOn = remember { mutableSetOf<String>() }
    DisposableEffect(cm) {
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                states[cameraId] = enabled
                unavailable[cameraId] = false
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                unavailable[cameraId] = true
            }
        }
        cm.registerTorchCallback(cb, Handler(Looper.getMainLooper()))
        onDispose {
            turnedOn.forEach { runCatching { cm.setTorchMode(it, false) } }
            cm.unregisterTorchCallback(cb)
        }
    }
    SectionCard(stringResource(R.string.camera_torch_title), subtitle = stringResource(R.string.camera_torch_sub), icon = Icons.Outlined.FlashlightOn) {
        withFlash.forEach { ci ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(ci.label().text(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (unavailable[ci.id] == true) Text(
                    stringResource(R.string.camera_torch_busy), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp),
                )
                Switch(checked = states[ci.id] == true, onCheckedChange = { on ->
                    error = try {
                        cm.setTorchMode(ci.id, on)
                        if (on) turnedOn += ci.id else turnedOn -= ci.id
                        null
                    } catch (e: Exception) {
                        accessReason(e)
                    }
                })
            }
        }
        error?.let { Text(it.text(), color = LocalStatusColors.current.of(Status.WARN), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun GuidedSection(info: CamInfo) {
    SectionCard(stringResource(R.string.camera_guided_title), subtitle = info.label().text(), icon = Icons.Outlined.TouchApp) {
        GuidedCheck(
            CameraArea, "${info.id}.guided_preview", txt(R.string.camera_g_preview_t, info.label()), stringResource(R.string.camera_g_preview),
            details = { listOf(d("Facing", CamNames.facing(info.facing)), d("Sensor orientation", info.orientation)) },
        )
        if (info.hasFlash) {
            GuidedCheck(CameraArea, "${info.id}.guided_flash", txt(R.string.camera_g_flash_t, info.label()), stringResource(R.string.camera_g_flash))
        }
        GuidedCheck(CameraArea, "${info.id}.guided_photo", txt(R.string.camera_g_photo_t, info.label()), stringResource(R.string.camera_g_photo))
    }
}
