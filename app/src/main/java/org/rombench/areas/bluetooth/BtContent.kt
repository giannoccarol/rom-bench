package org.rombench.areas.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.rombench.R
import org.rombench.core.Perms
import org.rombench.core.Status
import org.rombench.core.txt
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.GuidedCheck
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.LevelBar
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.StatusChip
import org.rombench.ui.components.rememberPermissions
import org.rombench.ui.theme.LocalStatusColors
import org.rombench.ui.theme.Mono

private const val UI_SCAN_MS = 12_000L

@Composable
internal fun BluetoothContent(area: BluetoothArea) {
    val ctx = LocalContext.current
    val adapter = remember { BtUtil.adapter(ctx) }
    if (adapter == null) {
        SectionCard(stringResource(R.string.bluetooth_ui_adapter), icon = Icons.Outlined.PowerSettingsNew) {
            Hint(stringResource(R.string.bluetooth_ui_no_adapter))
        }
        return
    }
    var state by remember { mutableIntStateOf(adapter.state) }
    DisposableEffect(adapter) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                state = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, adapter.state)
            }
        }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    AdapterSection(adapter, state)
    if (state == BluetoothAdapter.STATE_ON) {
        ScanSection(area, adapter)
        PairedSection(adapter)
    }
    SectionCard(stringResource(R.string.bluetooth_guided_title), icon = Icons.Outlined.Headphones) {
        Hint(stringResource(R.string.bluetooth_g_hint))
        GuidedCheck(area, "audio", txt(R.string.bluetooth_g_audio), stringResource(R.string.bluetooth_g_audio_q))
        GuidedCheck(area, "call_audio", txt(R.string.bluetooth_g_call), stringResource(R.string.bluetooth_g_call_q))
    }
}

private fun openBtSettings(ctx: Context) {
    runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun AdapterSection(adapter: BluetoothAdapter, state: Int) {
    val ctx = LocalContext.current
    val connectPerm = rememberPermissions(listOf(Perms.BT_CONNECT))
    var enableError by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val status = when (state) {
        BluetoothAdapter.STATE_ON -> Status.PASS
        BluetoothAdapter.STATE_OFF -> Status.WARN
        else -> Status.INFO
    }
    SectionCard(
        stringResource(R.string.bluetooth_ui_adapter),
        icon = Icons.Outlined.PowerSettingsNew,
        actions = { StatusChip(status) },
    ) {
        KeyValue("State", BtUtil.stateName(state))
        if (connectPerm.allGranted) {
            KeyValue("Name", runCatching { adapter.name }.getOrNull() ?: "—")
            KeyValue("Scan mode", runCatching { scanModeName(adapter.scanMode) }.getOrNull() ?: "—")
        }
        when (state) {
            BluetoothAdapter.STATE_OFF -> Hint(stringResource(R.string.bluetooth_ui_off))
            BluetoothAdapter.STATE_TURNING_ON -> Hint(stringResource(R.string.bluetooth_ui_turning_on))
        }
        enableError?.let { Hint(it) }
        ButtonRow {
            if (state == BluetoothAdapter.STATE_OFF) FilledTonalButton(onClick = {
                enableError = null
                if (!connectPerm.allGranted) {
                    connectPerm.request()
                } else {
                    runCatching { launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                        .onFailure { enableError = ctx.getString(R.string.bluetooth_ui_enable_failed, it.javaClass.simpleName) }
                }
            }) {
                Icon(Icons.Outlined.PowerSettingsNew, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.bluetooth_ui_turn_on))
            }
            OutlinedButton(onClick = { openBtSettings(ctx) }) {
                Icon(Icons.Outlined.Settings, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.bluetooth_ui_settings))
            }
        }
    }
}

private fun scanModeName(m: Int) = when (m) {
    BluetoothAdapter.SCAN_MODE_NONE -> "NONE"
    BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "CONNECTABLE"
    BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "DISCOVERABLE"
    else -> "$m"
}

@Composable
private fun ScanSection(area: BluetoothArea, adapter: BluetoothAdapter) {
    val ctx = LocalContext.current
    val perms = rememberPermissions(area.permissions)
    var scanning by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<Map<String, BtSeen>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    val canConnect = Perms.granted(ctx, Perms.BT_CONNECT)

    fun merge(s: BtSeen) {
        val p = devices[s.address]
        val merged = if (p == null) s else BtSeen(
            s.address, s.name ?: p.name,
            listOfNotNull(s.rssi, p.rssi).maxOrNull(),
            le = s.le || p.le, classic = s.classic || p.classic,
            type = if (s.type != BluetoothDevice.DEVICE_TYPE_UNKNOWN) s.type else p.type,
            bonded = s.bonded || p.bonded,
        )
        devices = devices + (s.address to merged)
    }

    DisposableEffect(scanning) {
        if (!scanning) return@DisposableEffect onDispose { }
        val main = Handler(Looper.getMainLooper())
        val stop = Runnable { scanning = false }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != BluetoothDevice.ACTION_FOUND) return
                val dev = foundDevice(i) ?: return
                val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt().takeIf { it != Short.MIN_VALUE.toInt() }
                merge(
                    BtSeen(
                        dev.address, i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: if (canConnect) BtUtil.name(dev) else null, rssi,
                        le = false, classic = true, type = if (canConnect) BtUtil.type(dev) else BluetoothDevice.DEVICE_TYPE_UNKNOWN,
                        bonded = canConnect && BtUtil.bonded(dev),
                    ),
                )
            }
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) = add(r)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { add(it) }
            override fun onScanFailed(errorCode: Int) {
                error = ctx.getString(R.string.bluetooth_ui_scan_error, BtUtil.scanErrorName(errorCode))
            }

            fun add(r: ScanResult) {
                val dev = r.device ?: return
                merge(
                    BtSeen(
                        dev.address, r.scanRecord?.deviceName ?: if (canConnect) BtUtil.name(dev) else null, r.rssi,
                        le = true, classic = false, type = if (canConnect) BtUtil.type(dev) else BluetoothDevice.DEVICE_TYPE_LE,
                        bonded = canConnect && BtUtil.bonded(dev),
                    ),
                )
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(BluetoothDevice.ACTION_FOUND), ContextCompat.RECEIVER_EXPORTED)
        val scanner = adapter.bluetoothLeScanner
        try {
            scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            if (!adapter.startDiscovery()) error = ctx.getString(R.string.bluetooth_ui_disc_refused)
        } catch (e: SecurityException) {
            error = ctx.getString(R.string.bluetooth_ui_scan_error, "SecurityException")
        }
        main.postDelayed(stop, UI_SCAN_MS)
        onDispose {
            main.removeCallbacks(stop)
            runCatching { scanner?.stopScan(cb) }
            runCatching { adapter.cancelDiscovery() }
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }

    SectionCard(
        stringResource(R.string.bluetooth_ui_scan_title),
        subtitle = stringResource(R.string.bluetooth_ui_scan_sub),
        icon = Icons.AutoMirrored.Outlined.BluetoothSearching,
    ) {
        if (!perms.allGranted) {
            Hint(stringResource(R.string.bluetooth_ui_scan_perm))
            return@SectionCard
        }
        if (!BtUtil.locationEnabled(ctx)) Hint(stringResource(R.string.bluetooth_ui_location_off))
        ButtonRow {
            if (!scanning) FilledTonalButton(onClick = { error = null; devices = emptyMap(); scanning = true }) {
                Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.bluetooth_ui_scan))
            } else OutlinedButton(onClick = { scanning = false }) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.stop))
            }
        }
        error?.let { Hint(it) }
        val list = devices.values.sortedByDescending { it.rssi ?: -999 }
        val le = list.count { it.le }
        val classic = list.count { it.classic }
        if (list.isNotEmpty() || scanning) Hint(stringResource(R.string.bluetooth_ui_counts, list.size, le, classic))
        else Hint(stringResource(R.string.bluetooth_ui_scan_hint))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            list.take(40).forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                DeviceRow(s)
            }
        }
        if (list.size > 40) Hint(stringResource(R.string.bluetooth_ui_more, list.size - 40))
    }
}

private fun rssiStatus(r: Int) = when {
    r >= -70 -> Status.PASS
    r >= -85 -> Status.WARN
    else -> Status.FAIL
}

@Composable
private fun DeviceRow(s: BtSeen) {
    val sc = LocalStatusColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    s.name ?: stringResource(R.string.bluetooth_ui_unnamed),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val via = listOfNotNull("LE".takeIf { s.le }, "BR/EDR".takeIf { s.classic }).joinToString(" + ")
                val extra = listOfNotNull(
                    BtUtil.typeName(s.type).takeIf { s.type != BluetoothDevice.DEVICE_TYPE_UNKNOWN },
                    stringResource(R.string.bluetooth_ui_paired).takeIf { s.bonded },
                )
                Text(
                    (listOf(s.address, via) + extra).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                s.rssi?.let { "$it dBm" } ?: "—", style = Mono,
                color = s.rssi?.let { sc.of(rssiStatus(it)) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        s.rssi?.let { LevelBar((it + 100) / 70f, color = sc.of(rssiStatus(it)), height = 6.dp) }
    }
}

@Composable
private fun PairedSection(adapter: BluetoothAdapter) {
    val perm = rememberPermissions(listOf(Perms.BT_CONNECT))
    SectionCard(stringResource(R.string.bluetooth_ui_paired_title), icon = Icons.Outlined.Link) {
        if (!perm.allGranted) {
            Hint(stringResource(R.string.bluetooth_ui_paired_perm))
            return@SectionCard
        }
        val list = remember(perm.allGranted) { BtUtil.bondedDevices(adapter).orEmpty() }
        if (list.isEmpty()) Hint(stringResource(R.string.bluetooth_ui_paired_none))
        list.take(20).forEach { dev ->
            Column {
                Text(BtUtil.name(dev) ?: dev.address, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(dev.address, BtUtil.typeName(BtUtil.type(dev)), BtUtil.major(dev)?.let { BtUtil.majorName(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
