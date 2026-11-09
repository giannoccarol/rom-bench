@file:Suppress("MissingPermission", "HardwareIds")

package org.rombench.areas.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.rombench.R
import org.rombench.core.Detail
import org.rombench.core.Perms
import org.rombench.core.Status
import org.rombench.core.Sys
import org.rombench.core.TestResult
import org.rombench.core.d
import org.rombench.core.result
import org.rombench.core.skippedForPermission
import org.rombench.core.txt
import java.util.concurrent.ConcurrentHashMap

/** One device seen by a scan; best RSSI kept. */
internal data class BtSeen(
    val address: String,
    val name: String?,
    val rssi: Int?,
    val le: Boolean,
    val classic: Boolean,
    val type: Int,
    val bonded: Boolean,
)

private const val BLE_SCAN_MS = 8_000L
private const val DISCOVERY_MS = 12_000L

private fun BluetoothArea.offSkip(name: String, title: Int) =
    result(name, txt(title), Status.SKIP, txt(R.string.bluetooth_s_off), txt(R.string.bluetooth_r_needs_on))

internal suspend fun BluetoothArea.runChecks(ctx: Context): List<TestResult> {
    val out = mutableListOf<TestResult>()
    val pm = ctx.packageManager
    val hasBt = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
    val hasLe = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    val a = BtUtil.adapter(ctx)
    val title = txt(R.string.bluetooth_t_adapter)
    if (a == null) {
        out += if (hasBt) result("adapter", title, Status.FAIL, txt(R.string.bluetooth_s_no_adapter), txt(R.string.bluetooth_r_no_adapter))
        else result("adapter", title, Status.SKIP, txt(R.string.bluetooth_s_no_bt), txt(R.string.bluetooth_r_no_bt))
        return out
    }
    val canConnect = Perms.granted(ctx, Perms.BT_CONNECT)

    var state = a.state
    val t0 = SystemClock.elapsedRealtime()
    while (state == BluetoothAdapter.STATE_TURNING_ON && SystemClock.elapsedRealtime() - t0 < 8000) {
        delay(300); state = a.state
    }
    val on = state == BluetoothAdapter.STATE_ON
    val name = if (canConnect) runCatching { a.name }.getOrNull() else null
    val rf = BtUtil.rfkills().filter { it.type == "bluetooth" }
    val details = mutableListOf(
        d("State", BtUtil.stateName(state)),
        d("Name", name ?: if (canConnect) null else "(needs BLUETOOTH_CONNECT)", sensitive = true),
        d("FEATURE_BLUETOOTH", hasBt),
        d("FEATURE_BLUETOOTH_LE", hasLe),
    )
    rf.forEach { details += d("rfkill ${it.name}", "state=${it.state} soft=${it.soft} hard=${it.hard}") }
    val hardBlocked = rf.any { it.hard == "1" }
    out += when {
        !hasBt -> result("adapter", title, Status.WARN, txt(R.string.bluetooth_s_state, BtUtil.stateName(state)), txt(R.string.bluetooth_r_no_feature), details)
        on && hardBlocked -> result("adapter", title, Status.WARN, txt(R.string.bluetooth_s_on), txt(R.string.bluetooth_r_hard_block), details)
        on -> result("adapter", title, Status.PASS, txt(R.string.bluetooth_s_on), null, details)
        state == BluetoothAdapter.STATE_TURNING_ON -> result("adapter", title, Status.FAIL, txt(R.string.bluetooth_s_stuck), txt(R.string.bluetooth_r_stuck), details)
        else -> result("adapter", title, Status.WARN, txt(R.string.bluetooth_s_off), txt(R.string.bluetooth_r_off), details)
    }

    out += halCheck()
    out += addressCheck(ctx, a, canConnect)
    out += if (on) leCheck(a, hasLe) else offSkip("le", R.string.bluetooth_t_le)
    out += when {
        !canConnect -> skippedForPermission("bonded", txt(R.string.bluetooth_t_bonded), listOf(Perms.BT_CONNECT))
        else -> bondedCheck(a, on)
    }

    if (!on) {
        out += offSkip("ble_scan", R.string.bluetooth_t_ble_scan)
        out += offSkip("discovery", R.string.bluetooth_t_discovery)
        return out
    }
    val missingScan = Perms.missing(ctx, listOf(Perms.BT_SCAN, Perms.LOCATION))
    if (missingScan.isNotEmpty()) {
        out += skippedForPermission("ble_scan", txt(R.string.bluetooth_t_ble_scan), missingScan)
        out += skippedForPermission("discovery", txt(R.string.bluetooth_t_discovery), missingScan)
        return out
    }
    val locOn = BtUtil.locationEnabled(ctx)
    out += bleScanCheck(a, locOn)
    out += discoveryCheck(ctx, a, locOn, canConnect)
    return out
}

private val BT_RE = Regex("bluetooth|bt_|btlinux|bdroid|hci|btaudio|vendor\\.qcom\\.bt|bt\\.")

private fun BluetoothArea.halCheck(): TestResult {
    val props = Sys.allProps()
    val services = props.filterKeys { it.startsWith("init.svc.") && BT_RE.containsMatchIn(it) }
    val other = props.filterKeys {
        !it.startsWith("init.svc.") && !it.startsWith("ro.boottime.") && BT_RE.containsMatchIn(it) && !it.contains("macaddr") && !it.contains("bdaddr")
    }.entries.take(25)
    val details = mutableListOf<Detail>()
    services.forEach { (k, v) -> details += d(k, v) }
    other.forEach { (k, v) -> details += d(k, v) }
    val restarting = services.filterValues { it == "restarting" }.keys.map { it.removePrefix("init.svc.") }
    val title = txt(R.string.bluetooth_t_hal)
    return when {
        restarting.isNotEmpty() -> result("hal", title, Status.WARN, txt(R.string.bluetooth_s_hal_crash, restarting.joinToString()), txt(R.string.bluetooth_r_hal_crash), details)
        details.isEmpty() -> result("hal", title, Status.INFO, txt(R.string.bluetooth_s_hal_none), txt(R.string.bluetooth_r_hal_none))
        else -> result("hal", title, Status.INFO, txt(R.string.bluetooth_s_hal, services.size), null, details)
    }
}

private fun BluetoothArea.addressCheck(ctx: Context, a: BluetoothAdapter, canConnect: Boolean): TestResult {
    val title = txt(R.string.bluetooth_t_address)
    val api = if (canConnect) runCatching { a.address }.getOrNull() else null
    val secure = runCatching { Settings.Secure.getString(ctx.contentResolver, "bluetooth_address") }.getOrNull()
    val boot = Sys.prop("ro.boot.btmacaddr").ifBlank { null }
    val persist = Sys.prop("persist.vendor.service.bdroid.bdaddr").ifBlank { null } ?: Sys.prop("persist.service.bdroid.bdaddr").ifBlank { null }
    val pathProp = Sys.prop("ro.vendor.bt.bdaddr_path").ifBlank { null } ?: Sys.prop("ro.bt.bdaddr_path").ifBlank { null }
    val fromPath = pathProp?.let { Sys.line(it) }
    val details = listOf(
        d("BluetoothAdapter.getAddress()", api ?: if (canConnect) null else "(needs BLUETOOTH_CONNECT)", sensitive = true),
        d("Settings.Secure bluetooth_address", secure ?: "(not readable)", sensitive = true),
        d("ro.boot.btmacaddr", boot, sensitive = true),
        d("persist bdroid.bdaddr", persist, sensitive = true),
        d("bdaddr_path", pathProp),
        d("bdaddr file", fromPath ?: pathProp?.let { "(not readable)" }, sensitive = true),
    )
    val real = listOfNotNull(api, secure, boot, persist, fromPath).map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.equals(BtUtil.DUMMY_ADDRESS, true) }
    return when {
        real == null -> result("address", title, Status.INFO, txt(R.string.bluetooth_s_addr_hidden), txt(R.string.bluetooth_r_addr_hidden), details)
        BtUtil.placeholder(real) -> result("address", title, Status.WARN, txt(R.string.bluetooth_s_addr_placeholder), txt(R.string.bluetooth_r_addr_placeholder), details)
        else -> result("address", title, Status.PASS, txt(R.string.bluetooth_s_addr_ok), null, details)
    }
}

private fun yes(b: Boolean?) = when (b) {
    true -> "yes"; false -> "no"; null -> "—"
}

private fun BluetoothArea.leCheck(a: BluetoothAdapter, hasLe: Boolean): TestResult {
    val title = txt(R.string.bluetooth_t_le)
    fun safe(f: () -> Boolean): Boolean? = runCatching { f() }.getOrNull()
    fun code(f: () -> Int): Boolean? = runCatching { f() }.getOrNull()?.let {
        when (it) {
            BluetoothStatusCodes.FEATURE_SUPPORTED -> true
            BluetoothStatusCodes.FEATURE_NOT_SUPPORTED -> false
            else -> null
        }
    }
    val phy2m = safe { a.isLe2MPhySupported }
    val coded = safe { a.isLeCodedPhySupported }
    val ext = safe { a.isLeExtendedAdvertisingSupported }
    val periodic = safe { a.isLePeriodicAdvertisingSupported }
    val multi = safe { a.isMultipleAdvertisementSupported }
    val filt = safe { a.isOffloadedFilteringSupported }
    val batch = safe { a.isOffloadedScanBatchingSupported }
    val maxAdv = runCatching { a.leMaximumAdvertisingDataLength }.getOrNull()
    val details = mutableListOf(
        d("LE 2M PHY", yes(phy2m)),
        d("LE Coded PHY", yes(coded)),
        d("Extended advertising", yes(ext)),
        d("Periodic advertising", yes(periodic)),
        d("Multiple advertisement", yes(multi)),
        d("Offloaded filtering", yes(filt)),
        d("Offloaded scan batching", yes(batch)),
        d("Max advertising data", maxAdv?.let { "$it bytes" }),
    )
    var leAudio: Boolean? = null
    if (Build.VERSION.SDK_INT >= 33) {
        leAudio = code { a.isLeAudioSupported }
        details += d("LE Audio", yes(leAudio))
        details += d("LE Audio broadcast source", yes(code { a.isLeAudioBroadcastSourceSupported }))
        details += d("LE Audio broadcast assistant", yes(code { a.isLeAudioBroadcastAssistantSupported }))
    }
    if (Build.VERSION.SDK_INT >= 33) details += d("Max connected audio devices", runCatching { a.maxConnectedAudioDevices }.getOrNull())
    val bt5 = phy2m == true || coded == true || ext == true
    val names = listOfNotNull(
        "2M".takeIf { phy2m == true }, "Coded".takeIf { coded == true }, "Ext. adv".takeIf { ext == true },
        "Multi-adv".takeIf { multi == true }, "LE Audio".takeIf { leAudio == true },
    )
    val summary = txt(
        if (bt5) R.string.bluetooth_s_le5 else R.string.bluetooth_s_le4,
        names.joinToString().ifEmpty { "—" },
    )
    return when {
        !hasLe -> result("le", title, Status.WARN, summary, txt(R.string.bluetooth_r_no_le_feature), details)
        multi == false && filt == false && batch == false && !bt5 ->
            result("le", title, Status.INFO, summary, txt(R.string.bluetooth_r_le_minimal), details)
        else -> result("le", title, Status.INFO, summary, null, details)
    }
}

private fun BluetoothArea.bondedCheck(a: BluetoothAdapter, on: Boolean): TestResult {
    val title = txt(R.string.bluetooth_t_bonded)
    val list = BtUtil.bondedDevices(a)
        ?: return result("bonded", title, Status.INFO, txt(R.string.bluetooth_s_bonded_unknown), txt(if (on) R.string.bluetooth_r_bonded_unknown else R.string.bluetooth_r_needs_on))
    val details = list.take(20).mapIndexed { i, dev ->
        val parts = listOfNotNull(BtUtil.name(dev) ?: "?", BtUtil.typeName(BtUtil.type(dev)), BtUtil.major(dev)?.let { BtUtil.majorName(it) })
        d("#${i + 1}", parts.joinToString(" · ") + " · " + dev.address, sensitive = true)
    }
    return result("bonded", title, Status.INFO, txt(R.string.bluetooth_s_bonded, list.size), null, details)
}

/** BLE scan; returns the devices seen and the scan error code, if any. */
internal suspend fun bleScan(a: BluetoothAdapter, ms: Long): Pair<Map<String, BtSeen>, Int?> {
    val scanner = a.bluetoothLeScanner ?: return emptyMap<String, BtSeen>() to -1
    val found = ConcurrentHashMap<String, BtSeen>()
    val failed = CompletableDeferred<Int>()
    val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) = add(r)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { add(it) }
        override fun onScanFailed(errorCode: Int) {
            failed.complete(errorCode)
        }

        fun add(r: ScanResult) {
            val addr = r.device?.address ?: return
            val prev = found[addr]
            val name = r.scanRecord?.deviceName ?: prev?.name
            val rssi = if (prev?.rssi == null || r.rssi > prev.rssi) r.rssi else prev.rssi
            found[addr] = BtSeen(addr, name, rssi, le = true, classic = false, type = BluetoothDevice.DEVICE_TYPE_LE, bonded = false)
        }
    }
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    var code: Int? = null
    try {
        withContext(Dispatchers.Main) { scanner.startScan(null, settings, cb) }
        code = withTimeoutOrNull(ms) { failed.await() }
    } catch (e: SecurityException) {
        code = -2
    } finally {
        withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) { runCatching { scanner.stopScan(cb) } }
    }
    return HashMap(found) to code
}

private suspend fun BluetoothArea.bleScanCheck(a: BluetoothAdapter, locOn: Boolean): TestResult {
    val title = txt(R.string.bluetooth_t_ble_scan)
    val (found, code) = bleScan(a, BLE_SCAN_MS)
    val rssis = found.values.mapNotNull { it.rssi }
    val best = found.values.filter { it.rssi != null }.sortedByDescending { it.rssi }.take(5)
    val details = mutableListOf(
        d("Duration", "${BLE_SCAN_MS / 1000} s"),
        d("Devices", found.size),
        d("Named", found.values.count { it.name != null }),
        d("Strongest RSSI", rssis.maxOrNull()?.let { "$it dBm" }),
        d("RSSI range", if (rssis.isEmpty()) null else "${rssis.min()} … ${rssis.max()} dBm"),
        d("Location services", locOn),
    )
    if (code != null) details += d("Error", if (code == -1) "no BluetoothLeScanner" else if (code == -2) "SecurityException" else BtUtil.scanErrorName(code))
    best.forEachIndexed { i, s -> details += d("#${i + 1}", "${s.name ?: "?"} · ${s.rssi} dBm · ${s.address}", sensitive = true) }
    val stuck = rssis.size >= 3 && rssis.distinct().size == 1
    val odd = rssis.any { it >= 0 || it < -120 }
    return when {
        code == -1 -> result("ble_scan", title, Status.FAIL, txt(R.string.bluetooth_s_no_scanner), txt(R.string.bluetooth_r_no_scanner), details)
        code != null && found.isEmpty() -> result("ble_scan", title, Status.FAIL, txt(R.string.bluetooth_s_scan_failed, if (code == -2) "SecurityException" else BtUtil.scanErrorName(code)), txt(R.string.bluetooth_r_scan_failed), details)
        found.isEmpty() && !locOn -> result("ble_scan", title, Status.WARN, txt(R.string.bluetooth_s_ble_none), txt(R.string.bluetooth_r_location_off), details)
        found.isEmpty() -> result("ble_scan", title, Status.WARN, txt(R.string.bluetooth_s_ble_none), txt(R.string.bluetooth_r_ble_none), details)
        stuck -> result("ble_scan", title, Status.WARN, txt(R.string.bluetooth_s_ble, found.size, rssis.max()), txt(R.string.bluetooth_r_rssi_stuck, rssis.first()), details)
        odd -> result("ble_scan", title, Status.WARN, txt(R.string.bluetooth_s_ble, found.size, rssis.max()), txt(R.string.bluetooth_r_rssi_odd), details)
        else -> result("ble_scan", title, Status.PASS, txt(R.string.bluetooth_s_ble, found.size, rssis.maxOrNull() ?: 0), null, details)
    }
}

internal fun foundDevice(i: Intent): BluetoothDevice? =
    IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

private suspend fun BluetoothArea.discoveryCheck(ctx: Context, a: BluetoothAdapter, locOn: Boolean, canConnect: Boolean): TestResult {
    val title = txt(R.string.bluetooth_t_discovery)
    val found = ConcurrentHashMap<String, BtSeen>()
    val startedEv = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<Unit>()
    val r = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> startedEv.complete(Unit)
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> finished.complete(Unit)
                BluetoothDevice.ACTION_FOUND -> {
                    val dev = foundDevice(i) ?: return
                    val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt().takeIf { it != Short.MIN_VALUE.toInt() }
                    val name = i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: if (canConnect) BtUtil.name(dev) else null
                    val prev = found[dev.address]
                    found[dev.address] = BtSeen(
                        dev.address, name ?: prev?.name, maxOf(rssi ?: -999, prev?.rssi ?: -999).takeIf { it > -999 },
                        le = false, classic = true, type = BtUtil.type(dev), bonded = canConnect && BtUtil.bonded(dev),
                    )
                }
            }
        }
    }
    val filter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
    }
    ContextCompat.registerReceiver(ctx, r, filter, ContextCompat.RECEIVER_EXPORTED)
    var started = false
    var sawStart = false
    var sawFinish = false
    val t0 = SystemClock.elapsedRealtime()
    try {
        started = try {
            a.startDiscovery()
        } catch (e: SecurityException) {
            false
        }
        if (started) {
            sawStart = withTimeoutOrNull(3000) { startedEv.await() } != null || runCatching { a.isDiscovering }.getOrDefault(false)
            sawFinish = withTimeoutOrNull(DISCOVERY_MS + 2000) { finished.await() } != null
        }
    } finally {
        runCatching { a.cancelDiscovery() }
        runCatching { ctx.unregisterReceiver(r) }
    }
    val ms = SystemClock.elapsedRealtime() - t0
    val rssis = found.values.mapNotNull { it.rssi }
    val details = mutableListOf(
        d("startDiscovery()", started),
        d("DISCOVERY_STARTED seen", sawStart),
        d("DISCOVERY_FINISHED seen", sawFinish),
        d("Duration", "${ms / 1000} s"),
        d("Devices", found.size),
        d("Strongest RSSI", rssis.maxOrNull()?.let { "$it dBm" }),
        d("Location services", locOn),
    )
    found.values.sortedByDescending { it.rssi ?: -999 }.take(5).forEachIndexed { i, s ->
        details += d("#${i + 1}", "${s.name ?: "?"} · ${s.rssi ?: "?"} dBm · ${BtUtil.typeName(s.type)} · ${s.address}", sensitive = true)
    }
    return when {
        !started -> result("discovery", title, Status.FAIL, txt(R.string.bluetooth_s_disc_refused), txt(R.string.bluetooth_r_disc_refused), details)
        !sawStart && found.isEmpty() -> result("discovery", title, Status.FAIL, txt(R.string.bluetooth_s_disc_no_start), txt(R.string.bluetooth_r_disc_no_start), details)
        found.isNotEmpty() -> result("discovery", title, Status.PASS, txt(R.string.bluetooth_s_disc, found.size, rssis.maxOrNull() ?: 0), null, details)
        !locOn -> result("discovery", title, Status.WARN, txt(R.string.bluetooth_s_disc_none), txt(R.string.bluetooth_r_location_off), details)
        else -> result("discovery", title, Status.INFO, txt(R.string.bluetooth_s_disc_none), txt(R.string.bluetooth_r_disc_none), details)
    }
}
