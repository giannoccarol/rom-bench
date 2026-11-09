package org.rombench.areas.wifi

import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.rombench.core.Sys
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address

/** Frequency, band, standard and MAC helpers shared by the checks and the screen. */
internal object WifiUtil {
    const val INVALID_RSSI = -127

    fun band(freq: Int): String = when (freq) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5899 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        in 57000..72000 -> "60 GHz"
        else -> "?"
    }

    /** Band index for counting: 0 = 2.4, 1 = 5, 2 = 6, 3 = 60 GHz, -1 unknown. */
    fun bandIndex(freq: Int): Int = when (freq) {
        in 2400..2500 -> 0
        in 4900..5899 -> 1
        in 5925..7125 -> 2
        in 57000..72000 -> 3
        else -> -1
    }

    fun channel(freq: Int): Int? = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 4910..4980 -> (freq - 4000) / 5
        freq in 5000..5899 -> (freq - 5000) / 5
        freq == 5935 -> 2
        freq in 5950..7115 -> (freq - 5950) / 5
        freq in 58320..70200 -> (freq - 56160) / 2160
        else -> null
    }

    fun bandChannel(freq: Int): String = channel(freq)?.let { "${band(freq)} ch $it" } ?: band(freq)

    fun standard(std: Int): String = when (std) {
        ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
        ScanResult.WIFI_STANDARD_11N -> "802.11n (Wi-Fi 4)"
        ScanResult.WIFI_STANDARD_11AC -> "802.11ac (Wi-Fi 5)"
        ScanResult.WIFI_STANDARD_11AX -> "802.11ax (Wi-Fi 6)"
        ScanResult.WIFI_STANDARD_11AD -> "802.11ad (WiGig)"
        ScanResult.WIFI_STANDARD_11BE -> "802.11be (Wi-Fi 7)"
        else -> "unknown"
    }

    fun standardShort(std: Int): String = when (std) {
        ScanResult.WIFI_STANDARD_LEGACY -> "a/b/g"
        ScanResult.WIFI_STANDARD_11N -> "n"
        ScanResult.WIFI_STANDARD_11AC -> "ac"
        ScanResult.WIFI_STANDARD_11AX -> "ax"
        ScanResult.WIFI_STANDARD_11AD -> "ad"
        ScanResult.WIFI_STANDARD_11BE -> "be"
        else -> "?"
    }

    fun width(w: Int): String = when (w) {
        ScanResult.CHANNEL_WIDTH_20MHZ -> "20 MHz"
        ScanResult.CHANNEL_WIDTH_40MHZ -> "40 MHz"
        ScanResult.CHANNEL_WIDTH_80MHZ -> "80 MHz"
        ScanResult.CHANNEL_WIDTH_160MHZ -> "160 MHz"
        ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80 MHz"
        ScanResult.CHANNEL_WIDTH_320MHZ -> "320 MHz"
        else -> "?"
    }

    fun security(t: Int): String = when (t) {
        WifiInfo.SECURITY_TYPE_OPEN -> "Open"
        WifiInfo.SECURITY_TYPE_WEP -> "WEP"
        WifiInfo.SECURITY_TYPE_PSK -> "WPA2-PSK"
        WifiInfo.SECURITY_TYPE_EAP -> "EAP"
        WifiInfo.SECURITY_TYPE_SAE -> "WPA3-SAE"
        WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> "WPA3-Enterprise 192"
        WifiInfo.SECURITY_TYPE_OWE -> "OWE"
        WifiInfo.SECURITY_TYPE_WAPI_PSK -> "WAPI-PSK"
        WifiInfo.SECURITY_TYPE_WAPI_CERT -> "WAPI-CERT"
        WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> "WPA3-Enterprise"
        WifiInfo.SECURITY_TYPE_OSEN -> "OSEN"
        WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2 -> "Passpoint R1/R2"
        WifiInfo.SECURITY_TYPE_PASSPOINT_R3 -> "Passpoint R3"
        WifiInfo.SECURITY_TYPE_DPP -> "DPP"
        else -> "unknown"
    }

    /** Short security label from the capabilities string of a scan result. */
    fun scanSecurity(caps: String?): String {
        val c = caps ?: return "?"
        return when {
            "SAE" in c && "PSK" in c -> "WPA2/3"
            "SAE" in c -> "WPA3"
            "EAP" in c -> "EAP"
            "PSK" in c -> "WPA2"
            "WEP" in c -> "WEP"
            "OWE" in c -> "OWE"
            else -> "Open"
        }
    }

    /** SSID without quotes; null when hidden or redacted by the framework. */
    fun cleanSsid(s: String?): String? {
        if (s == null) return null
        val t = s.removeSurrounding("\"")
        return t.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID && it != "<unknown ssid>" }
    }

    @Suppress("DEPRECATION")
    fun scanSsid(r: ScanResult): String? = cleanSsid(r.SSID)

    fun locationEnabled(ctx: Context): Boolean =
        ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true

    /** The Wi-Fi network as seen by ConnectivityService, if any. */
    @Suppress("DEPRECATION")
    fun wifiNetwork(cm: ConnectivityManager?): Network? = cm?.let { m ->
        runCatching {
            m.allNetworks.firstOrNull { n ->
                m.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrNull()
    }

    fun isDefault(cm: ConnectivityManager?, n: Network?): Boolean =
        n != null && runCatching { cm?.activeNetwork == n }.getOrDefault(false)

    fun ipv4(lp: LinkProperties?): List<String> =
        lp?.linkAddresses?.filter { it.address is Inet4Address }?.map { "${it.address.hostAddress}/${it.prefixLength}" } ?: emptyList()

    fun ipv6(lp: LinkProperties?): List<String> =
        lp?.linkAddresses?.filter { it.address is Inet6Address }?.map { "${it.address.hostAddress}/${it.prefixLength}" } ?: emptyList()

    fun hasGlobalV6(lp: LinkProperties?): Boolean =
        lp?.linkAddresses?.any { val a = it.address; a is Inet6Address && !a.isLinkLocalAddress && !a.isSiteLocalAddress } == true

    /** IPv4 default gateway, else any gateway. */
    fun gateway(lp: LinkProperties?): String? {
        val routes = lp?.routes ?: return null
        val gws = routes.filter { it.isDefaultRoute && it.gateway != null && !it.gateway!!.isAnyLocalAddress }.mapNotNull { it.gateway }
        return (gws.firstOrNull { it is Inet4Address } ?: gws.firstOrNull())?.hostAddress?.substringBefore('%')
    }

    fun dns(lp: LinkProperties?): List<String> = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()

    // ---- Interface, MAC and driver from sysfs ----

    fun ifaceName(lp: LinkProperties?): String =
        lp?.interfaceName ?: Sys.prop("wifi.interface").ifBlank { "wlan0" }

    fun macOf(iface: String): String? = Sys.line("/sys/class/net/$iface/address")

    fun isZeroMac(m: String) = m.replace(":", "").all { it == '0' }

    /** Locally administered bit: set for randomised and synthetic addresses. */
    fun isLocalMac(m: String): Boolean =
        m.split(':').firstOrNull()?.toIntOrNull(16)?.let { it and 0x02 != 0 } ?: false

    /** Qualcomm Atheros default address that the driver uses when no MAC is provisioned. */
    fun isQcaDefault(m: String) = m.lowercase().startsWith("00:03:7f")

    fun canonical(path: String): String? = runCatching {
        val f = File(path)
        if (f.exists()) f.canonicalPath else null
    }.getOrNull()

    /** level column (dBm) of /proc/net/wireless for [iface]. */
    fun procWirelessLevel(iface: String): Int? {
        val line = Sys.read("/proc/net/wireless")?.lineSequence()?.firstOrNull { it.trim().startsWith("$iface:") } ?: return null
        val cols = line.substringAfter(':').trim().split(Regex("\\s+"))
        return cols.getOrNull(2)?.trimEnd('.')?.toFloatOrNull()?.toInt()
    }

    // ---- ping ----

    data class Ping(
        val sent: Int, val received: Int, val lossPct: Int,
        val min: Double?, val avg: Double?, val max: Double?,
        val exit: Int, val error: String?,
    ) {
        /** The ROM does not let apps use ICMP echo sockets (ping_group_range or SELinux). */
        val notAllowed get() = error?.contains("Permission denied", true) == true || error?.contains("Operation not permitted", true) == true
        val unknownHost get() = error?.contains("unknown host", true) == true
    }

    fun ping(host: String, count: Int = 5): Ping {
        val r = Sys.exec("/system/bin/ping", "-c", "$count", "-i", "0.2", "-W", "2", host, timeoutMs = (count * 1200L + 4000L))
        val out = r.out
        val m = Regex("""(\d+) packets transmitted, (\d+) (?:packets )?received""").find(out)
        val sent = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val recv = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val loss = Regex("""(\d+(?:\.\d+)?)% packet loss""").find(out)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt()
            ?: if (sent > 0) (100 * (sent - recv) / sent) else 100
        val rtt = Regex("""= ([\d.]+)/([\d.]+)/([\d.]+)""").find(out)?.groupValues
        val err = (r.err.trim().ifEmpty { null } ?: if (r.exit == -1) "timeout" else null)?.lineSequence()?.firstOrNull()
        return Ping(sent, recv, loss, rtt?.get(1)?.toDoubleOrNull(), rtt?.get(2)?.toDoubleOrNull(), rtt?.get(3)?.toDoubleOrNull(), r.exit, err)
    }

    /** Signal quality bucket, used for colours: 2 good, 1 fair, 0 poor. */
    fun quality(rssi: Int): Int = when {
        rssi >= -67 -> 2
        rssi >= -80 -> 1
        else -> 0
    }

    fun sdk(v: Int) = Build.VERSION.SDK_INT >= v
}

/**
 * Runs a blocking call (DNS, HTTP) on its own daemon thread and waits at most [timeoutMs].
 * Null on timeout; the thread is abandoned instead of blocking cancellation.
 */
internal suspend fun <T> blockingWithTimeout(timeoutMs: Long, block: () -> T): Result<T>? {
    val done = CompletableDeferred<Result<T>>()
    Thread({ done.complete(runCatching(block)) }, "rombench-net").apply { isDaemon = true }.start()
    return withTimeoutOrNull(timeoutMs) { done.await() }
}
