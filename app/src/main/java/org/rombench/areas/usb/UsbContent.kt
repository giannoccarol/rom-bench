package org.rombench.areas.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.rombench.R
import org.rombench.core.Status
import org.rombench.core.d
import org.rombench.core.txt
import org.rombench.ui.SubHeader
import org.rombench.ui.components.GuidedCheck
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.StatusChip
import org.rombench.ui.components.rememberPolled

@Composable
internal fun UsbContent(area: UsbArea) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var events by remember { mutableStateOf<List<String>>(emptyList()) }

    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val what = when (i.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> "device attached"
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> "device detached"
                    UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> "accessory attached"
                    UsbManager.ACTION_USB_ACCESSORY_DETACHED -> "accessory detached"
                    ACTION_USB_STATE -> UsbData.portFrom(i).let { p ->
                        "port: " + (if (p.connected) "connected" else "disconnected") + (if (p.configured) ", configured" else "") +
                            p.functions.takeIf { it.isNotEmpty() }?.let { " (${it.joinToString()})" }.orEmpty()
                    }
                    Intent.ACTION_POWER_CONNECTED -> "power connected"
                    Intent.ACTION_POWER_DISCONNECTED -> "power disconnected"
                    else -> i.action?.substringAfterLast('.')?.lowercase()
                } ?: return
                val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                // USB_STATE is sticky: skip the replay delivered at registration.
                if (isInitialStickyBroadcast) return
                events = (listOf("$stamp  $what") + events).take(8)
                tick++
            }
        }
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(ACTION_USB_STATE)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val media = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                events = (listOf("$stamp  storage " + (i.action?.substringAfterLast('_')?.lowercase() ?: "?")) + events).take(8)
                tick++
            }
        }
        val mf = IntentFilter().apply {
            listOf(
                Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_REMOVED,
                Intent.ACTION_MEDIA_BAD_REMOVAL, Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_CHECKING, Intent.ACTION_MEDIA_UNMOUNTABLE,
            ).forEach { addAction(it) }
            addDataScheme("file")
        }
        val im = ctx.getSystemService(InputManager::class.java)
        val il = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { tick++ }
            override fun onInputDeviceRemoved(deviceId: Int) { tick++ }
            override fun onInputDeviceChanged(deviceId: Int) { tick++ }
        }
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(ctx, media, mf, ContextCompat.RECEIVER_EXPORTED)
        im?.registerInputDeviceListener(il, main)
        onDispose {
            runCatching { ctx.unregisterReceiver(r) }
            runCatching { ctx.unregisterReceiver(media) }
            runCatching { im?.unregisterInputDeviceListener(il) }
        }
    }

    val snap by rememberPolled(2000, tick) { UsbData.snapshot(ctx) }
    val s = snap

    SectionCard(
        stringResource(R.string.usb_ui_port), subtitle = stringResource(R.string.usb_ui_port_sub), icon = Icons.Outlined.Cable,
        actions = {
            s?.port?.let { p -> StatusChip(if (p.connected && p.configured) Status.PASS else if (p.connected) Status.WARN else Status.INFO) }
        },
    ) {
        if (s == null) {
            Hint(stringResource(R.string.loading)); return@SectionCard
        }
        val p = s.port
        KeyValue("Data", when {
            p == null -> "no USB_STATE"
            p.connected && p.configured -> "connected, configured"
            p.connected -> "connected, not configured"
            p.hostConnected -> "host mode"
            else -> "disconnected"
        })
        KeyValue("Functions", p?.functions?.joinToString()?.ifEmpty { "none" } ?: "—")
        s.props["sys.usb.config"]?.let { KeyValue("sys.usb.config", it) }
        s.props["sys.usb.state"]?.let { KeyValue("sys.usb.state", it) }
        s.gadgetState?.let { KeyValue("android_usb", it) }
        KeyValue("Battery plugged", UsbData.pluggedName(s.plugged))
        s.supplies.forEach { ps ->
            KeyValue(ps.name, (if (ps.online) "online · " else "offline · ") + UsbData.supplySummary(ps).ifEmpty { "—" })
        }
        s.typec.forEach { (n, a) ->
            val txt = listOfNotNull(a["data_role"], a["power_role"], a["orientation"]).joinToString(" · ")
            KeyValue("typec/$n", txt.ifEmpty { "present" })
        }
        s.dualRole.forEach { (n, a) -> KeyValue("dual_role/$n", listOfNotNull(a["mode"], a["data_role"], a["power_role"]).joinToString(" · ")) }
        if (events.isNotEmpty()) {
            SubHeader(stringResource(R.string.usb_ui_events))
            events.forEach { Text(it, style = org.rombench.ui.theme.Mono, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    SectionCard(stringResource(R.string.usb_ui_devices), subtitle = stringResource(R.string.usb_ui_devices_sub), icon = Icons.Outlined.Usb) {
        if (s == null) return@SectionCard
        val kernel = s.kernelDevices.filter { !it.rootHub }
        if (s.apiDevices.isEmpty() && kernel.isEmpty()) Hint(stringResource(R.string.usb_ui_no_devices))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.apiDevices.forEach { dv ->
                Column {
                    Text(
                        listOfNotNull(dv.manufacturerName, dv.productName).joinToString(" ").ifEmpty { dv.deviceName },
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(UsbData.describe(dv), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val apiIds = s.apiDevices.map { "%04x:%04x".format(it.vendorId, it.productId) }.toSet()
            val kernelOnly = kernel.filter { "${it.vid}:${it.pid}" !in apiIds }
            if (kernelOnly.isNotEmpty()) {
                SubHeader(stringResource(R.string.usb_ui_kernel_only))
                kernelOnly.forEach { k -> Text(UsbData.describe(k), style = MaterialTheme.typography.bodySmall) }
            }
            if (s.accessories.isNotEmpty()) KeyValue("Accessory", s.accessories.joinToString())
        }
    }

    SectionCard(stringResource(R.string.usb_ui_storage), icon = Icons.Outlined.SdStorage) {
        if (s == null) return@SectionCard
        val removable = s.volumes.filter { it.removable }
        if (removable.isEmpty()) Hint(stringResource(R.string.usb_ui_no_storage))
        removable.forEach { v -> KeyValue(v.description, listOfNotNull(v.state, v.path).joinToString(" · ")) }
        if (s.massStorageAttached && removable.none { it.state == "mounted" || it.state == "mounted_ro" }) {
            Hint(stringResource(R.string.usb_ui_storage_waiting))
        }
        GuidedCheck(area, "otg_storage", txt(R.string.usb_g_storage), stringResource(R.string.usb_g_storage_q)) {
            s.volumes.filter { it.removable }.map { d(it.description, "${it.state} · ${it.path}", sensitive = it.uuid != null) } +
                s.kernelDevices.filter { 8 in it.interfaceClasses }.map { d("Kernel ${it.node}", UsbData.describe(it)) }
        }
    }

    SectionCard(stringResource(R.string.usb_ui_input), icon = Icons.Outlined.Keyboard) {
        if (s == null) return@SectionCard
        if (s.inputs.isEmpty()) Hint(stringResource(R.string.usb_ui_no_input))
        s.inputs.forEach { i -> KeyValue(i.name, "%s · %04x:%04x".format(i.kinds.joinToString("+"), i.vid, i.pid)) }
        var typed by remember { mutableStateOf("") }
        OutlinedTextField(
            typed, { typed = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.usb_ui_type_here)) },
        )
        GuidedCheck(area, "otg_input", txt(R.string.usb_g_input), stringResource(R.string.usb_g_input_q)) {
            s.inputs.map { d(it.name, "%s · %04x:%04x".format(it.kinds.joinToString("+"), it.vid, it.pid)) }
        }
    }

    SectionCard(stringResource(R.string.usb_ui_charging), icon = Icons.Outlined.Info) {
        Hint(stringResource(R.string.usb_ui_charging_hint))
        GuidedCheck(area, "charging", txt(R.string.usb_g_charging), stringResource(R.string.usb_g_charging_q)) {
            listOfNotNull(s?.let { d("Battery plugged", UsbData.pluggedName(it.plugged)) }) +
                (s?.supplies.orEmpty().map { d(it.name, (if (it.online) "online · " else "offline · ") + UsbData.supplySummary(it)) })
        }
        SubHeader(stringResource(R.string.usb_ui_host_title))
        Hint(stringResource(R.string.usb_ui_host_explain))
    }
}
