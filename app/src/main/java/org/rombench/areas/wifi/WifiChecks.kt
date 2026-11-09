package org.rombench.areas.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.rombench.R
import org.rombench.core.Detail
import org.rombench.core.Perms
import org.rombench.core.Status
import org.rombench.core.Sys
import org.rombench.core.TestResult
import org.rombench.core.d
import org.rombench.core.fmt1
import org.rombench.core.result
import org.rombench.core.skippedForPermission
import org.rombench.core.txt
import java.net.HttpURLConnection
import java.net.URL

/** Snapshot of the current association, shared by the checks and the live screen. */
internal data class WifiLink(
    val state: Int,
    val connected: Boolean,
    val ssid: String?,
    val bssid: String?,
    val rssi: Int,
    val freq: Int,
    val linkSpeed: Int,
    val tx: Int,
    val rx: Int,
    val maxTx: Int,
    val maxRx: Int,
    val standard: Int,
    val security: Int?,
    val supplicant: SupplicantState?,
    val networkId: Int,
    val macInfo: String?,
)

@Suppress("DEPRECATION")
internal fun readLink(wm: WifiManager): WifiLink {
    val info: WifiInfo? = runCatching { wm.connectionInfo }.getOrNull()
    val sup = info?.supplicantState
    val connected = wm.isWifiEnabled && info != null && sup == SupplicantState.COMPLETED && (info.networkId != -1 || info.bssid != null)
    return WifiLink(
        state = wm.wifiState,
        connected = connected,
        ssid = WifiUtil.cleanSsid(info?.ssid),
        bssid = info?.bssid?.takeIf { it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" },
        rssi = info?.rssi ?: WifiUtil.INVALID_RSSI,
        freq = info?.frequency ?: -1,
        linkSpeed = info?.linkSpeed ?: -1,
        tx = info?.txLinkSpeedMbps ?: -1,
        rx = info?.rxLinkSpeedMbps ?: -1,
        maxTx = info?.maxSupportedTxLinkSpeedMbps ?: -1,
        maxRx = info?.maxSupportedRxLinkSpeedMbps ?: -1,
        standard = info?.wifiStandard ?: ScanResult.WIFI_STANDARD_UNKNOWN,
        security = if (WifiUtil.sdk(31)) info?.currentSecurityType else null,
        supplicant = sup,
        networkId = info?.networkId ?: -1,
        macInfo = info?.macAddress,
    )
}

internal data class ScanOutcome(
    val started: Boolean,
    val fresh: Boolean,
    val results: List<ScanResult>,
    val ageSec: Long?,
    val error: String?,
)

/** Requests a scan and waits for it; falls back to the cached results when throttled. */
@Suppress("DEPRECATION")
internal suspend fun scanWifi(ctx: Context, wm: WifiManager, timeoutMs: Long = 12_000): ScanOutcome {
    val done = CompletableDeferred<Boolean>()
    val r = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            done.complete(i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
        }
    }
    var started = false
    var updated = false
    var error: String? = null
    ContextCompat.registerReceiver(ctx, r, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED)
    try {
        started = try {
            wm.startScan()
        } catch (e: SecurityException) {
            error = e.message; false
        }
        if (started) updated = withTimeoutOrNull(timeoutMs) { done.await() } ?: false
    } finally {
        runCatching { ctx.unregisterReceiver(r) }
    }
    val list = try {
        wm.scanResults ?: emptyList()
    } catch (e: SecurityException) {
        error = error ?: e.message; emptyList()
    }
    val nowUs = SystemClock.elapsedRealtime() * 1000
    val newest = list.maxOfOrNull { it.timestamp }
    val age = newest?.let { ((nowUs - it) / 1_000_000).coerceAtLeast(0) }
    return ScanOutcome(started, started && updated, list, age, error)
}

private fun WifiArea.offResult(name: String, title: Int) =
    result(name, txt(title), Status.SKIP, txt(R.string.wifi_s_off), txt(R.string.wifi_r_off_skip))

private fun WifiArea.notConnected(name: String, title: Int) =
    result(name, txt(title), Status.SKIP, txt(R.string.wifi_s_not_connected), txt(R.string.wifi_r_connect_first))

internal suspend fun WifiArea.runChecks(ctx: Context): List<TestResult> {
    val out = mutableListOf<TestResult>()
    val app = ctx.applicationContext
    val pm = ctx.packageManager
    val wm = app.getSystemService(WifiManager::class.java)
    val cm = app.getSystemService(ConnectivityManager::class.java)
    val hasFeature = pm.hasSystemFeature(PackageManager.FEATURE_WIFI)

    // ---- presence and state ----
    if (wm == null || !hasFeature) {
        val ifacePresent = Sys.exists("/sys/class/net/wlan0")
        out += when {
            wm == null && !hasFeature && !ifacePresent -> result(
                "enabled", txt(R.string.wifi_t_enabled), Status.SKIP, txt(R.string.wifi_s_no_wifi), txt(R.string.wifi_r_no_wifi),
            )
            !hasFeature -> result(
                "enabled", txt(R.string.wifi_t_enabled), Status.WARN, txt(R.string.wifi_s_no_feature), txt(R.string.wifi_r_no_feature),
                listOf(d("FEATURE_WIFI", false), d("WifiManager", wm != null), d("wlan0", ifacePresent)),
            )
            else -> result(
                "enabled", txt(R.string.wifi_t_enabled), Status.FAIL, txt(R.string.wifi_s_no_service), txt(R.string.wifi_r_no_service),
            )
        }
        if (wm == null) return out
    }

    var state = wm.wifiState
    val t0 = SystemClock.elapsedRealtime()
    while (state == WifiManager.WIFI_STATE_ENABLING && SystemClock.elapsedRealtime() - t0 < 6000) {
        delay(300); state = wm.wifiState
    }
    val enabled = state == WifiManager.WIFI_STATE_ENABLED
    val probeLp = WifiUtil.wifiNetwork(cm)?.let { runCatching { cm?.getLinkProperties(it) }.getOrNull() }
    val iface = WifiUtil.ifaceName(probeLp)
    val ifaceExists = Sys.exists("/sys/class/net/$iface")
    val stateDetails = listOf(
        d("wifiState", stateName(state)),
        d("Interface", iface),
        d("Interface present", ifaceExists),
        d("operstate", Sys.line("/sys/class/net/$iface/operstate")),
    )
    out += when (state) {
        WifiManager.WIFI_STATE_ENABLED -> if (ifaceExists || !Sys.exists("/sys/class/net")) result(
            "enabled", txt(R.string.wifi_t_enabled), Status.PASS, txt(R.string.wifi_s_on), null, stateDetails,
        ) else result(
            "enabled", txt(R.string.wifi_t_enabled), Status.WARN, txt(R.string.wifi_s_on_no_iface, iface), txt(R.string.wifi_r_on_no_iface), stateDetails,
        )
        WifiManager.WIFI_STATE_DISABLED, WifiManager.WIFI_STATE_DISABLING -> result(
            "enabled", txt(R.string.wifi_t_enabled), Status.WARN, txt(R.string.wifi_s_off), txt(R.string.wifi_r_off), stateDetails,
        )
        WifiManager.WIFI_STATE_ENABLING -> result(
            "enabled", txt(R.string.wifi_t_enabled), Status.FAIL, txt(R.string.wifi_s_stuck_enabling), txt(R.string.wifi_r_stuck_enabling), stateDetails,
        )
        else -> result(
            "enabled", txt(R.string.wifi_t_enabled), Status.FAIL, txt(R.string.wifi_s_unknown_state), txt(R.string.wifi_r_unknown_state), stateDetails,
        )
    }

    out += capabilities(ctx, wm, enabled)

    if (!enabled) {
        out += offResult("connection", R.string.wifi_t_connection)
        out += offResult("ip", R.string.wifi_t_ip)
        out += macCheck(iface, null, false)
        out += driverCheck(iface, false)
        out += offResult("scan", R.string.wifi_t_scan)
        out += offResult("gateway", R.string.wifi_t_gateway)
        out += offResult("ping", R.string.wifi_t_ping)
        out += offResult("dns", R.string.wifi_t_dns)
        out += offResult("internet", R.string.wifi_t_internet)
        return out
    }

    // ---- association ----
    val link = readLink(wm)
    val network = WifiUtil.wifiNetwork(cm)
    val lp = network?.let { runCatching { cm?.getLinkProperties(it) }.getOrNull() }
    val caps = network?.let { runCatching { cm?.getNetworkCapabilities(it) }.getOrNull() }
    out += connectionCheck(ctx, wm, link, iface)
    out += if (link.connected) ipCheck(lp, caps, cm, network) else notConnected("ip", R.string.wifi_t_ip)
    out += macCheck(iface, link, true)
    out += driverCheck(iface, true)

    // ---- scan ----
    val missing = Perms.missing(ctx, permissions)
    out += if (missing.isNotEmpty()) skippedForPermission("scan", txt(R.string.wifi_t_scan), missing)
    else scanCheck(ctx, wm, link)

    // ---- reachability ----
    if (!link.connected || network == null) {
        out += notConnected("gateway", R.string.wifi_t_gateway)
        out += notConnected("ping", R.string.wifi_t_ping)
        out += notConnected("dns", R.string.wifi_t_dns)
        out += notConnected("internet", R.string.wifi_t_internet)
        return out
    }
    out += gatewayCheck(lp)
    out += pingCheck(ctx, cm, network)
    out += dnsCheck(ctx, network)
    out += internetCheck(ctx, network, caps)
    return out
}

private fun stateName(s: Int) = when (s) {
    WifiManager.WIFI_STATE_DISABLED -> "DISABLED"
    WifiManager.WIFI_STATE_DISABLING -> "DISABLING"
    WifiManager.WIFI_STATE_ENABLED -> "ENABLED"
    WifiManager.WIFI_STATE_ENABLING -> "ENABLING"
    else -> "UNKNOWN"
}

private fun yes(b: Boolean?) = when (b) {
    true -> "yes"; false -> "no"; null -> "—"
}

internal fun supportedStandards(wm: WifiManager): List<Int> = listOf(
    ScanResult.WIFI_STANDARD_LEGACY, ScanResult.WIFI_STANDARD_11N, ScanResult.WIFI_STANDARD_11AC,
    ScanResult.WIFI_STANDARD_11AX, ScanResult.WIFI_STANDARD_11AD, ScanResult.WIFI_STANDARD_11BE,
).filter { runCatching { wm.isWifiStandardSupported(it) }.getOrDefault(false) }

private fun WifiArea.capabilities(ctx: Context, wm: WifiManager, enabled: Boolean): TestResult {
    val pm = ctx.packageManager
    fun safe(f: () -> Boolean): Boolean? = runCatching { f() }.getOrNull()
    val b24 = safe { wm.is24GHzBandSupported }
    val b5 = safe { wm.is5GHzBandSupported }
    val b6 = safe { wm.is6GHzBandSupported }
    val b60 = safe { wm.is60GHzBandSupported }
    val stds = supportedStandards(wm)
    val details = mutableListOf(
        d("2.4 GHz", yes(b24)), d("5 GHz", yes(b5)), d("6 GHz", yes(b6)), d("60 GHz", yes(b60)),
        d("Standards", stds.joinToString { WifiUtil.standardShort(it) }.ifEmpty { "none reported" }),
        d("STA + AP concurrency", yes(safe { wm.isStaApConcurrencySupported })),
    )
    if (WifiUtil.sdk(31)) {
        details += d("Bridged AP", yes(safe { wm.isBridgedApConcurrencySupported }))
        details += d("STA + bridged AP", yes(safe { wm.isStaBridgedApConcurrencySupported }))
        details += d("Dual-band simultaneous", yes(safe { wm.isDualBandSimultaneousSupported }))
        details += d("STA local-only concurrency", yes(safe { wm.isStaConcurrencyForLocalOnlyConnectionsSupported }))
    }
    if (WifiUtil.sdk(33)) details += d("STA multi-internet", yes(safe { wm.isStaConcurrencyForMultiInternetSupported }))
    details += d("Wi-Fi Direct (P2P)", yes(safe { wm.isP2pSupported }))
    details += d("TDLS", yes(safe { wm.isTdlsSupported }))
    details += d("PNO offload", yes(safe { wm.isPreferredNetworkOffloadSupported }))
    details += d("WPA3-SAE", yes(safe { wm.isWpa3SaeSupported }))
    details += d("WPA3 Suite-B", yes(safe { wm.isWpa3SuiteBSupported }))
    details += d("Enhanced Open (OWE)", yes(safe { wm.isEnhancedOpenSupported }))
    details += d("Easy Connect (DPP)", yes(safe { wm.isEasyConnectSupported }))
    details += d("FEATURE_WIFI_AWARE", pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE))
    details += d("FEATURE_WIFI_RTT", pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT))
    details += d("FEATURE_WIFI_PASSPOINT", pm.hasSystemFeature(PackageManager.FEATURE_WIFI_PASSPOINT))
    details += d("FEATURE_WIFI_DIRECT", pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT))

    val bands = listOfNotNull("2.4".takeIf { b24 == true }, "5".takeIf { b5 == true }, "6".takeIf { b6 == true }, "60".takeIf { b60 == true })
    val best = stds.filter { it != ScanResult.WIFI_STANDARD_11AD }.maxByOrNull { stdRank(it) }
    val summary = txt(
        R.string.wifi_s_caps,
        if (bands.isEmpty()) "—" else bands.joinToString(" + ") + " GHz",
        best?.let { WifiUtil.standard(it) } ?: "—",
    )
    return when {
        bands.isEmpty() && enabled -> result("capabilities", txt(R.string.wifi_t_caps), Status.WARN, summary, txt(R.string.wifi_r_caps_no_bands), details)
        bands.isEmpty() -> result("capabilities", txt(R.string.wifi_t_caps), Status.INFO, summary, txt(R.string.wifi_r_caps_off), details)
        stds.isEmpty() -> result("capabilities", txt(R.string.wifi_t_caps), Status.INFO, summary, txt(R.string.wifi_r_caps_no_std), details)
        else -> result("capabilities", txt(R.string.wifi_t_caps), Status.INFO, summary, null, details)
    }
}

private fun stdRank(s: Int) = when (s) {
    ScanResult.WIFI_STANDARD_LEGACY -> 1
    ScanResult.WIFI_STANDARD_11N -> 2
    ScanResult.WIFI_STANDARD_11AC -> 3
    ScanResult.WIFI_STANDARD_11AX -> 4
    ScanResult.WIFI_STANDARD_11BE -> 5
    else -> 0
}

private fun WifiArea.connectionCheck(ctx: Context, wm: WifiManager, l: WifiLink, iface: String): TestResult {
    val title = txt(R.string.wifi_t_connection)
    if (!l.connected) {
        val hasLoc = Perms.granted(ctx, Perms.LOCATION)
        return result(
            "connection", title, Status.INFO, txt(R.string.wifi_s_not_connected),
            txt(R.string.wifi_r_not_connected),
            listOf(d("Supplicant", l.supplicant?.name), d("Location permission", hasLoc)),
        )
    }
    val proc = WifiUtil.procWirelessLevel(iface)
    val details = mutableListOf(
        d("SSID", l.ssid ?: "(hidden / needs location)", sensitive = true),
        d("BSSID", l.bssid, sensitive = true),
        d("RSSI", "${l.rssi} dBm"),
        d("/proc/net/wireless level", proc?.let { "$it dBm" }),
        d("Frequency", "${l.freq} MHz"),
        d("Band / channel", WifiUtil.bandChannel(l.freq)),
        d("Link speed", "${l.linkSpeed} Mbps"),
        d("TX / RX link", "${l.tx} / ${l.rx} Mbps"),
        d("Max TX / RX", "${l.maxTx} / ${l.maxRx} Mbps"),
        d("Standard", WifiUtil.standard(l.standard)),
        d("Security", l.security?.let { WifiUtil.security(it) }),
        d("Supplicant", l.supplicant?.name),
    )
    val summary = txt(R.string.wifi_s_connection, l.rssi, WifiUtil.bandChannel(l.freq), l.linkSpeed.coerceAtLeast(0), WifiUtil.standardShort(l.standard))
    val b5 = runCatching { wm.is5GHzBandSupported }.getOrDefault(true)
    val b6 = runCatching { wm.is6GHzBandSupported }.getOrDefault(true)
    val band = WifiUtil.bandIndex(l.freq)
    val reason = when {
        l.rssi == WifiUtil.INVALID_RSSI || l.rssi >= 0 || l.rssi < -100 -> txt(R.string.wifi_r_bad_rssi, l.rssi)
        l.freq <= 0 -> txt(R.string.wifi_r_bad_freq)
        l.linkSpeed <= 0 -> txt(R.string.wifi_r_bad_speed)
        band == 1 && !b5 -> txt(R.string.wifi_r_band_mismatch, "5 GHz")
        band == 2 && !b6 -> txt(R.string.wifi_r_band_mismatch, "6 GHz")
        proc != null && proc < 0 && kotlin.math.abs(proc - l.rssi) > 20 -> txt(R.string.wifi_r_rssi_mismatch, proc, l.rssi)
        l.rssi < -80 -> txt(R.string.wifi_r_weak, l.rssi)
        else -> null
    }
    val status = if (reason == null) Status.PASS else Status.WARN
    return result("connection", title, status, summary, reason, details)
}

private fun WifiArea.ipCheck(lp: LinkProperties?, caps: NetworkCapabilities?, cm: ConnectivityManager?, n: Network?): TestResult {
    val title = txt(R.string.wifi_t_ip)
    if (lp == null) return result("ip", title, Status.WARN, txt(R.string.wifi_s_no_lp), txt(R.string.wifi_r_no_lp))
    val v4 = WifiUtil.ipv4(lp)
    val v6 = WifiUtil.ipv6(lp)
    val gw = WifiUtil.gateway(lp)
    val dns = WifiUtil.dns(lp)
    val details = mutableListOf(
        d("Interface", lp.interfaceName),
        d("IPv4", v4.joinToString().ifEmpty { null }),
        d("IPv6", v6.joinToString().ifEmpty { null }),
        d("Gateway", gw),
        d("DNS", dns.joinToString().ifEmpty { null }),
        d("Private DNS", if (lp.isPrivateDnsActive) (lp.privateDnsServerName ?: "opportunistic") else "off"),
        d("Domains", lp.domains),
        d("MTU", lp.mtu.takeIf { it > 0 }),
        d("Default network", WifiUtil.isDefault(cm, n)),
    )
    caps?.let { c ->
        details += d("VALIDATED", c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        details += d("CAPTIVE_PORTAL", c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
        details += d("NOT_METERED", c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        details += d("Bandwidth est. down/up", "${c.linkDownstreamBandwidthKbps / 1000} / ${c.linkUpstreamBandwidthKbps / 1000} Mbps")
    }
    val summary = txt(R.string.wifi_s_ip, v4.firstOrNull() ?: v6.firstOrNull() ?: "—", gw ?: "—")
    return when {
        v4.isEmpty() && !WifiUtil.hasGlobalV6(lp) -> result("ip", title, Status.FAIL, txt(R.string.wifi_s_no_ip), txt(R.string.wifi_r_no_ip), details)
        gw == null -> result("ip", title, Status.WARN, summary, txt(R.string.wifi_r_no_gw), details)
        dns.isEmpty() -> result("ip", title, Status.WARN, summary, txt(R.string.wifi_r_no_dns), details)
        else -> result("ip", title, Status.PASS, summary, null, details)
    }
}

private fun WifiArea.macCheck(iface: String, l: WifiLink?, enabled: Boolean): TestResult {
    val title = txt(R.string.wifi_t_mac)
    val mac = WifiUtil.macOf(iface)
    val assign = Sys.line("/sys/class/net/$iface/addr_assign_type")
    val bootMac = Sys.prop("ro.boot.wifimacaddr").ifBlank { null }
    val details = listOf(
        d("Interface MAC ($iface)", mac, sensitive = true),
        d("addr_assign_type", assign?.let { "$it (${assignName(it)})" }),
        d("WifiInfo MAC (app view)", l?.macInfo, sensitive = true),
        d("ro.boot.wifimacaddr", bootMac, sensitive = true),
        d("Locally administered", mac?.let { WifiUtil.isLocalMac(it) }),
    )
    return when {
        mac == null && !enabled -> result("mac", title, Status.SKIP, txt(R.string.wifi_s_off), txt(R.string.wifi_r_off_skip), details)
        mac == null -> result("mac", title, Status.INFO, txt(R.string.wifi_s_mac_unreadable), txt(R.string.wifi_r_mac_unreadable), details)
        WifiUtil.isZeroMac(mac) -> result("mac", title, Status.FAIL, txt(R.string.wifi_s_mac_zero), txt(R.string.wifi_r_mac_zero), details)
        WifiUtil.isQcaDefault(mac) -> result("mac", title, Status.WARN, txt(R.string.wifi_s_mac_default), txt(R.string.wifi_r_mac_default), details)
        WifiUtil.isLocalMac(mac) -> result(
            "mac", title, Status.INFO, txt(R.string.wifi_s_mac_random),
            txt(if (l?.connected == true) R.string.wifi_r_mac_random_connected else R.string.wifi_r_mac_random_idle), details,
        )
        else -> result("mac", title, Status.PASS, txt(R.string.wifi_s_mac_global), txt(R.string.wifi_r_mac_global), details)
    }
}

private fun assignName(v: String) = when (v) {
    "0" -> "permanent"; "1" -> "random"; "2" -> "stolen"; "3" -> "set by userspace"; else -> "?"
}

private val WIFI_SERVICE_RE = Regex("wifi|wpa|wlan|cnss|wcnss|hostapd|wificond|qca|ath")

private fun WifiArea.driverCheck(iface: String, enabled: Boolean): TestResult {
    val title = txt(R.string.wifi_t_driver)
    val base = "/sys/class/net/$iface"
    val devPath = WifiUtil.canonical("$base/device")
    val driver = WifiUtil.canonical("$base/device/driver")?.substringAfterLast('/')
    val uevent = Sys.keyValues("$base/device/uevent")
    val modules = Sys.read("/proc/modules")?.lineSequence()
        ?.map { it.substringBefore(' ') }
        ?.filter { Regex("wlan|ath|cnss|wcn|qca|bcmdhd|dhd|rtl|mt76|wil6210").containsMatchIn(it) }
        ?.toList().orEmpty()
    val services = Sys.allProps().filterKeys { it.startsWith("init.svc.") && WIFI_SERVICE_RE.containsMatchIn(it) }
    val details = mutableListOf(
        d("Interface", iface),
        d("Driver", driver ?: uevent["DRIVER"]),
        d("Device", devPath),
        d("PCI_ID", uevent["PCI_ID"]),
        d("MODALIAS", uevent["MODALIAS"]),
        d("OF_COMPATIBLE", uevent["OF_COMPATIBLE_0"]),
        d("phy", Sys.line("$base/phy80211/name")),
        d("carrier", Sys.line("$base/carrier")),
        d("Modules", modules.joinToString().ifEmpty { null }),
        d("wlan module", if (Sys.exists("/sys/module/wlan")) "loaded / built-in" else null),
    )
    services.forEach { (k, v) -> details += d(k.removePrefix("init.svc."), v) }
    val restarting = services.filterValues { it == "restarting" }.keys.map { it.removePrefix("init.svc.") }
    val name = driver ?: uevent["DRIVER"] ?: if (Sys.exists("/sys/module/wlan")) "wlan" else null
    return when {
        restarting.isNotEmpty() -> result("driver", title, Status.WARN, txt(R.string.wifi_s_driver, name ?: "—"), txt(R.string.wifi_r_restarting, restarting.joinToString()), details)
        enabled && !Sys.exists(base) -> result("driver", title, Status.FAIL, txt(R.string.wifi_s_no_iface, iface), txt(R.string.wifi_r_on_no_iface), details)
        name == null -> result("driver", title, Status.INFO, txt(R.string.wifi_s_driver_unknown), txt(R.string.wifi_r_driver_unknown), details)
        else -> result("driver", title, Status.INFO, txt(R.string.wifi_s_driver, name), null, details)
    }
}

private suspend fun WifiArea.scanCheck(ctx: Context, wm: WifiManager, l: WifiLink): TestResult {
    val title = txt(R.string.wifi_t_scan)
    if (!WifiUtil.locationEnabled(ctx)) {
        return result("scan", title, Status.WARN, txt(R.string.wifi_s_location_off), txt(R.string.wifi_r_location_off))
    }
    val s = scanWifi(ctx, wm)
    val list = s.results
    val byBand = IntArray(4)
    list.forEach { r -> WifiUtil.bandIndex(r.frequency).takeIf { it >= 0 }?.let { byBand[it]++ } }
    val levels = list.map { it.level }
    val strongest = list.sortedByDescending { it.level }.take(5)
    val details = mutableListOf(
        d("Scan started", s.started),
        d("Fresh results", s.fresh),
        d("Newest result age", s.ageSec?.let { "$it s" }),
        d("Networks", list.size),
        d("2.4 GHz", byBand[0]), d("5 GHz", byBand[1]), d("6 GHz", byBand[2]),
        d("Unique SSIDs", list.mapNotNull { WifiUtil.scanSsid(it) }.distinct().size),
        d("Channels", list.mapNotNull { WifiUtil.channel(it.frequency) }.distinct().sorted().joinToString().ifEmpty { null }),
        d("RSSI range", if (levels.isEmpty()) null else "${levels.min()} … ${levels.max()} dBm"),
    )
    if (s.error != null) details += d("Error", s.error)
    strongest.forEachIndexed { i, r ->
        details += d(
            "#${i + 1}",
            "${WifiUtil.scanSsid(r) ?: "(hidden)"} · ${r.level} dBm · ${WifiUtil.bandChannel(r.frequency)} · ${WifiUtil.standardShort(r.wifiStandard)}",
            sensitive = true,
        )
    }
    val summary = txt(R.string.wifi_s_scan, list.size, byBand[0], byBand[1], byBand[2])
    val b5 = runCatching { wm.is5GHzBandSupported }.getOrDefault(false)
    val oddLevel = levels.any { it >= 0 || it < -110 }
    val stuck = levels.size >= 3 && levels.distinct().size == 1
    return when {
        list.isEmpty() && (s.fresh || l.connected) -> result("scan", title, Status.FAIL, txt(R.string.wifi_s_scan_empty), txt(R.string.wifi_r_scan_empty), details)
        list.isEmpty() -> result("scan", title, Status.WARN, txt(R.string.wifi_s_scan_empty), txt(R.string.wifi_r_scan_empty_cached), details)
        stuck -> result("scan", title, Status.WARN, summary, txt(R.string.wifi_r_scan_stuck, levels.first()), details)
        oddLevel -> result("scan", title, Status.WARN, summary, txt(R.string.wifi_r_scan_odd), details)
        !s.fresh && (s.ageSec ?: 0) > 600 -> result("scan", title, Status.WARN, summary, txt(R.string.wifi_r_scan_old, s.ageSec ?: 0), details)
        !s.fresh -> result("scan", title, Status.INFO, summary, txt(R.string.wifi_r_scan_throttled, s.ageSec ?: 0), details)
        b5 && byBand[1] == 0 && byBand[0] >= 5 -> result("scan", title, Status.INFO, summary, txt(R.string.wifi_r_scan_no5), details)
        else -> result("scan", title, Status.PASS, summary, null, details)
    }
}

private fun pingDetails(p: WifiUtil.Ping, host: String): List<Detail> = listOf(
    d("Host", host),
    d("Sent / received", "${p.sent} / ${p.received}"),
    d("Loss", "${p.lossPct} %"),
    d("RTT min/avg/max", if (p.avg != null) "${p.min} / ${p.avg} / ${p.max} ms" else null),
    d("Exit code", p.exit),
    d("Error", p.error),
)

private fun WifiArea.gatewayCheck(lp: LinkProperties?): TestResult {
    val title = txt(R.string.wifi_t_gateway)
    val gw = WifiUtil.gateway(lp)
        ?: return result("gateway", title, Status.WARN, txt(R.string.wifi_s_no_gw), txt(R.string.wifi_r_no_gw))
    val p = WifiUtil.ping(gw)
    val details = pingDetails(p, gw)
    return when {
        p.notAllowed -> result("gateway", title, Status.INFO, txt(R.string.wifi_s_ping_denied), txt(R.string.wifi_r_ping_denied), details)
        p.received == 0 -> result("gateway", title, Status.WARN, txt(R.string.wifi_s_gw_silent, gw), txt(R.string.wifi_r_gw_silent), details)
        p.lossPct >= 20 -> result("gateway", title, Status.WARN, txt(R.string.wifi_s_ping, p.lossPct, fmt1(p.avg ?: 0.0)), txt(R.string.wifi_r_gw_loss), details)
        (p.avg ?: 0.0) > 100 -> result("gateway", title, Status.WARN, txt(R.string.wifi_s_ping, p.lossPct, fmt1(p.avg ?: 0.0)), txt(R.string.wifi_r_gw_slow), details)
        else -> result("gateway", title, Status.PASS, txt(R.string.wifi_s_ping, p.lossPct, fmt1(p.avg ?: 0.0)), null, details)
    }
}

private fun WifiArea.pingCheck(ctx: Context, cm: ConnectivityManager?, n: Network): TestResult {
    val title = txt(R.string.wifi_t_ping)
    val host = org.rombench.core.Settings.pingHost(ctx)
    val p = WifiUtil.ping(host)
    val details = pingDetails(p, host) + d("Wi-Fi is default network", WifiUtil.isDefault(cm, n))
    return when {
        p.notAllowed -> result("ping", title, Status.INFO, txt(R.string.wifi_s_ping_denied), txt(R.string.wifi_r_ping_denied), details)
        p.unknownHost -> result("ping", title, Status.WARN, txt(R.string.wifi_s_ping_unknown, host), txt(R.string.wifi_r_ping_unknown), details)
        p.received == 0 -> result("ping", title, Status.WARN, txt(R.string.wifi_s_ping_none, host), txt(R.string.wifi_r_ping_none), details)
        p.lossPct >= 20 -> result("ping", title, Status.WARN, txt(R.string.wifi_s_ping, p.lossPct, fmt1(p.avg ?: 0.0)), txt(R.string.wifi_r_ping_loss), details)
        else -> result("ping", title, Status.PASS, txt(R.string.wifi_s_ping, p.lossPct, fmt1(p.avg ?: 0.0)), null, details)
    }
}

private fun probeHost(ctx: Context): String =
    runCatching { URL(org.rombench.core.Settings.probeUrl(ctx)).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "connectivitycheck.gstatic.com"

private suspend fun WifiArea.dnsCheck(ctx: Context, n: Network): TestResult {
    val title = txt(R.string.wifi_t_dns)
    val host = probeHost(ctx)
    val t0 = SystemClock.elapsedRealtime()
    val r = blockingWithTimeout(8000) { n.getAllByName(host).mapNotNull { it.hostAddress } }
    val ms = SystemClock.elapsedRealtime() - t0
    return when {
        r == null -> result("dns", title, Status.WARN, txt(R.string.wifi_s_dns_timeout, host), txt(R.string.wifi_r_dns_fail), listOf(d("Host", host)))
        r.isFailure -> result(
            "dns", title, Status.WARN, txt(R.string.wifi_s_dns_fail, host), txt(R.string.wifi_r_dns_fail),
            listOf(d("Host", host), d("Error", r.exceptionOrNull()?.toString())),
        )
        else -> {
            val addrs = r.getOrNull().orEmpty()
            result(
                "dns", title, Status.PASS, txt(R.string.wifi_s_dns, host, ms), null,
                listOf(d("Host", host), d("Addresses", addrs.joinToString()), d("Time", "$ms ms")),
            )
        }
    }
}

private suspend fun WifiArea.internetCheck(ctx: Context, n: Network, caps: NetworkCapabilities?): TestResult {
    val title = txt(R.string.wifi_t_internet)
    val url = org.rombench.core.Settings.probeUrl(ctx)
    val t0 = SystemClock.elapsedRealtime()
    val r = blockingWithTimeout(12_000) {
        val c = n.openConnection(URL(url)) as HttpURLConnection
        try {
            c.connectTimeout = 5000; c.readTimeout = 5000
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.responseCode to (c.getHeaderField("Location"))
        } finally {
            c.disconnect()
        }
    }
    val ms = SystemClock.elapsedRealtime() - t0
    val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    val base = listOf(d("URL", url), d("Time", "$ms ms"), d("VALIDATED", validated))
    if (r == null || r.isFailure) {
        return result(
            "internet", title, Status.WARN, txt(R.string.wifi_s_internet_fail), txt(R.string.wifi_r_internet_fail),
            base + d("Error", r?.exceptionOrNull()?.toString() ?: "timeout"),
        )
    }
    val (code, location) = r.getOrThrow()
    val details = base + d("HTTP status", code) + d("Location", location)
    val expects204 = url.endsWith("generate_204")
    return when {
        expects204 && code == 204 -> result("internet", title, Status.PASS, txt(R.string.wifi_s_internet_ok, code, ms), null, details)
        !expects204 && code in 200..299 -> result("internet", title, Status.PASS, txt(R.string.wifi_s_internet_ok, code, ms), null, details)
        code in 300..399 || (expects204 && code == 200) -> result("internet", title, Status.WARN, txt(R.string.wifi_s_internet_portal, code), txt(R.string.wifi_r_internet_portal), details)
        else -> result("internet", title, Status.WARN, txt(R.string.wifi_s_internet_code, code), txt(R.string.wifi_r_internet_code), details)
    }
}

/** Kept for the screen: same rule as the auto check. */
internal fun signalStatus(rssi: Int): Status = when (WifiUtil.quality(rssi)) {
    2 -> Status.PASS
    1 -> Status.WARN
    else -> Status.FAIL
}
