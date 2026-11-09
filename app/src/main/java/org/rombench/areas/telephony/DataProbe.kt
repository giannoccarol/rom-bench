package org.rombench.areas.telephony

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Brings up a cellular network even when Wi-Fi is the default one (ConnectivityManager
 * .requestNetwork with TRANSPORT_CELLULAR), then sends one HTTP request through it.
 */
internal object DataProbe {
    data class Outcome(
        /** Time until onAvailable, null when the network never came up. */
        val setupMs: Long?,
        val iface: String? = null,
        val addresses: List<String> = emptyList(),
        val dns: List<String> = emptyList(),
        val mtu: Int? = null,
        val downKbps: Int? = null,
        val upKbps: Int? = null,
        val dnsMs: Long? = null,
        val httpCode: Int? = null,
        val httpMs: Long? = null,
        val error: String? = null,
        val requestError: String? = null,
    ) {
        val up get() = setupMs != null
        val httpOk get() = httpCode != null && httpCode in 200..399
    }

    suspend fun run(ctx: Context, url: String, setupTimeoutMs: Long = 15_000): Outcome {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
            ?: return Outcome(null, requestError = "no ConnectivityManager")
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val got = CompletableDeferred<Network?>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { got.complete(network) }
            override fun onUnavailable() { got.complete(null) }
        }
        val t0 = SystemClock.elapsedRealtime()
        try {
            cm.requestNetwork(req, cb, setupTimeoutMs.toInt())
        } catch (e: Exception) {
            return Outcome(null, requestError = e.toString())
        }
        try {
            val net = withTimeoutOrNull(setupTimeoutMs + 2000) { got.await() } ?: return Outcome(null)
            val setup = SystemClock.elapsedRealtime() - t0
            val lp: LinkProperties? = cm.getLinkProperties(net)
            val caps = cm.getNetworkCapabilities(net)
            var out = Outcome(
                setupMs = setup,
                iface = lp?.interfaceName,
                addresses = lp?.linkAddresses?.map { it.toString() } ?: emptyList(),
                dns = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList(),
                mtu = lp?.mtu?.takeIf { it > 0 },
                downKbps = caps?.linkDownstreamBandwidthKbps,
                upKbps = caps?.linkUpstreamBandwidthKbps,
            )
            out = withContext(Dispatchers.IO) { http(net, url, out) }
            return out
        } finally {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
            }
        }
    }

    private fun http(net: Network, url: String, base: Outcome): Outcome {
        val u = try {
            URL(url)
        } catch (e: Exception) {
            return base.copy(error = "bad URL: $url")
        }
        var dnsMs: Long? = null
        try {
            val t = SystemClock.elapsedRealtime()
            net.getAllByName(u.host)
            dnsMs = SystemClock.elapsedRealtime() - t
        } catch (e: Exception) {
            return base.copy(error = "DNS: $e")
        }
        return try {
            val t = SystemClock.elapsedRealtime()
            val c = net.openConnection(u) as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.useCaches = false
            c.instanceFollowRedirects = false
            try {
                val code = c.responseCode
                try {
                    (if (code >= 400) c.errorStream else c.inputStream)?.use { it.read(ByteArray(1024)) }
                } catch (e: Exception) {
                }
                base.copy(dnsMs = dnsMs, httpCode = code, httpMs = SystemClock.elapsedRealtime() - t)
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            base.copy(dnsMs = dnsMs, error = e.toString())
        }
    }
}
