package org.rombench.areas.gnss

import android.annotation.SuppressLint
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.roundToInt

/** One satellite of a [GnssStatus] update. */
internal data class Sat(
    val constellation: Int,
    val svid: Int,
    val cn0: Float,
    val elevation: Float,
    val azimuth: Float,
    val used: Boolean,
    val almanac: Boolean,
    val ephemeris: Boolean,
    val carrierHz: Float?,
    val basebandCn0: Float?,
) {
    val band get() = bandName(carrierHz)
}

/** A satellite seen at least once during the session, with its best values. */
internal data class SeenSat(
    val constellation: Int,
    val svid: Int,
    val band: String,
    val maxCn0: Float,
    val everUsed: Boolean,
    val almanac: Boolean,
    val ephemeris: Boolean,
)

internal data class GnssSnapshot(
    val elapsedMs: Long,
    val engineStarted: Boolean,
    val engineStopped: Boolean,
    val hwTtffMs: Int?,
    val firstFixMs: Long?,
    val sats: List<Sat>,
    val seen: List<SeenSat>,
    val statusUpdates: Int,
    val maxInView: Int,
    val maxUsed: Int,
    val location: Location?,
    val wallAtFix: Long,
    val fixes: Int,
    val nmeaCount: Int,
    val nmeaBad: Int,
    val nmeaTypes: Map<String, Int>,
    val nmeaTail: List<String>,
    val gsvBlocks: Int,
    val gsvWithSnr: Int,
    val gsvMaxSnr: Float,
    val measEvents: Int,
    val measCount: Int,
    val measMaxCn0: Double,
    val regStatus: Boolean,
    val regLocation: Boolean,
    val regNmea: Boolean,
    val regMeas: Boolean,
    val error: String?,
)

/**
 * Listens to the GNSS engine (status, GPS fixes, NMEA, raw measurements) and keeps the numbers the
 * checks need. Callbacks run on [executor]; [snapshot] can be read from any thread.
 */
@SuppressLint("MissingPermission")
internal class GnssSession(private val lm: LocationManager, private val executor: Executor) {
    private val lock = Any()

    /** Incremented on every callback; read it in composition to refresh. */
    var version by mutableIntStateOf(0)
        private set

    private var startedAt = 0L
    private var running = false
    private var engineStarted = false
    private var engineStopped = false
    private var hwTtffMs: Int? = null
    private var firstFixAt: Long? = null
    private var sats: List<Sat> = emptyList()
    private val seen = LinkedHashMap<String, SeenSat>()
    private var statusUpdates = 0
    private var maxInView = 0
    private var maxUsed = 0
    private var location: Location? = null
    private var wallAtFix = 0L
    private var fixes = 0
    private var nmeaCount = 0
    private var nmeaBad = 0
    private val nmeaTypes = LinkedHashMap<String, Int>()
    private val nmeaTail = ArrayDeque<String>()
    private var gsvBlocks = 0
    private var gsvWithSnr = 0
    private var gsvMaxSnr = 0f
    private var measEvents = 0
    private var measCount = 0
    private var measMaxCn0 = 0.0
    private var regStatus = false
    private var regLocation = false
    private var regNmea = false
    private var regMeas = false
    private var error: String? = null

    private fun bump() {
        version++
    }

    private val statusCb = object : GnssStatus.Callback() {
        override fun onStarted() {
            synchronized(lock) { engineStarted = true }
            bump()
        }

        override fun onStopped() {
            synchronized(lock) { engineStopped = true }
            bump()
        }

        override fun onFirstFix(ttffMillis: Int) {
            synchronized(lock) { hwTtffMs = ttffMillis }
            bump()
        }

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val list = (0 until status.satelliteCount).map { i ->
                Sat(
                    constellation = status.getConstellationType(i),
                    svid = status.getSvid(i),
                    cn0 = status.getCn0DbHz(i),
                    elevation = status.getElevationDegrees(i),
                    azimuth = status.getAzimuthDegrees(i),
                    used = status.usedInFix(i),
                    almanac = status.hasAlmanacData(i),
                    ephemeris = status.hasEphemerisData(i),
                    carrierHz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else null,
                    basebandCn0 = if (status.hasBasebandCn0DbHz(i)) status.getBasebandCn0DbHz(i) else null,
                )
            }
            synchronized(lock) {
                sats = list
                statusUpdates++
                maxInView = maxOf(maxInView, list.size)
                maxUsed = maxOf(maxUsed, list.count { it.used })
                list.forEach { s ->
                    val key = "${s.constellation}:${s.svid}:${s.band}"
                    val old = seen[key]
                    seen[key] = SeenSat(
                        s.constellation, s.svid, s.band,
                        maxOf(old?.maxCn0 ?: 0f, s.cn0),
                        (old?.everUsed ?: false) || s.used,
                        (old?.almanac ?: false) || s.almanac,
                        (old?.ephemeris ?: false) || s.ephemeris,
                    )
                }
            }
            bump()
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            synchronized(lock) {
                this@GnssSession.location = location
                wallAtFix = System.currentTimeMillis()
                fixes++
                if (firstFixAt == null) firstFixAt = SystemClock.elapsedRealtime()
            }
            bump()
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val nmeaListener = OnNmeaMessageListener { message, _ ->
        onNmea(message)
        bump()
    }

    private val measCb = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
            val ms = eventArgs.measurements
            synchronized(lock) {
                measEvents++
                measCount += ms.size
                ms.forEach { if (it.cn0DbHz > measMaxCn0) measMaxCn0 = it.cn0DbHz }
            }
        }
    }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            startedAt = SystemClock.elapsedRealtime()
            firstFixAt = null
            hwTtffMs = null
            engineStopped = false
        }
        regStatus = try {
            lm.registerGnssStatusCallback(executor, statusCb)
        } catch (e: Exception) {
            error = e.toString(); false
        }
        regLocation = try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, executor, locationListener); true
        } catch (e: Exception) {
            error = e.toString(); false
        }
        regNmea = try {
            lm.addNmeaListener(executor, nmeaListener)
        } catch (e: Exception) {
            false
        }
        regMeas = try {
            lm.registerGnssMeasurementsCallback(executor, measCb)
        } catch (e: Exception) {
            false
        }
        bump()
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
        }
        runCatching { lm.unregisterGnssStatusCallback(statusCb) }
        runCatching { lm.removeUpdates(locationListener) }
        runCatching { lm.removeNmeaListener(nmeaListener) }
        runCatching { lm.unregisterGnssMeasurementsCallback(measCb) }
    }

    /** Elapsed time from start to the first GPS location, or null. */
    fun firstFixMs(): Long? = synchronized(lock) { firstFixAt?.let { it - startedAt } }

    fun snapshot(): GnssSnapshot = synchronized(lock) {
        GnssSnapshot(
            elapsedMs = if (startedAt == 0L) 0 else SystemClock.elapsedRealtime() - startedAt,
            engineStarted = engineStarted,
            engineStopped = engineStopped,
            hwTtffMs = hwTtffMs,
            firstFixMs = firstFixAt?.let { it - startedAt },
            sats = sats,
            seen = seen.values.toList(),
            statusUpdates = statusUpdates,
            maxInView = maxInView,
            maxUsed = maxUsed,
            location = location,
            wallAtFix = wallAtFix,
            fixes = fixes,
            nmeaCount = nmeaCount,
            nmeaBad = nmeaBad,
            nmeaTypes = LinkedHashMap(nmeaTypes),
            nmeaTail = nmeaTail.toList(),
            gsvBlocks = gsvBlocks,
            gsvWithSnr = gsvWithSnr,
            gsvMaxSnr = gsvMaxSnr,
            measEvents = measEvents,
            measCount = measCount,
            measMaxCn0 = measMaxCn0,
            regStatus = regStatus,
            regLocation = regLocation,
            regNmea = regNmea,
            regMeas = regMeas,
            error = error,
        )
    }

    private fun onNmea(message: String) {
        val line = message.trim()
        if (line.isEmpty()) return
        synchronized(lock) {
            nmeaCount++
            nmeaTail.addLast(line)
            while (nmeaTail.size > 20) nmeaTail.removeFirst()
            if (!checksumOk(line)) nmeaBad++
            val fields = line.substringAfter('$').substringBefore('*').split(',')
            val tag = fields.firstOrNull().orEmpty()
            val type = if (tag.startsWith("P") || tag.length < 5) tag else tag.substring(2)
            nmeaTypes[type] = (nmeaTypes[type] ?: 0) + 1
            if (type == "GSV") {
                // $xxGSV,total,num,inView,(svid,elev,az,snr)*{1..4}[,signalId]
                var i = 4
                while (i + 3 < fields.size) {
                    if (fields[i].isNotEmpty()) {
                        gsvBlocks++
                        val snr = fields[i + 3].toFloatOrNull()
                        if (snr != null && snr > 0) {
                            gsvWithSnr++
                            if (snr > gsvMaxSnr) gsvMaxSnr = snr
                        }
                    }
                    i += 4
                }
            }
        }
    }

    companion object {
        /** NMEA checksum: XOR of the characters between '$' and '*', as two hex digits. */
        fun checksumOk(line: String): Boolean {
            val star = line.lastIndexOf('*')
            if (!line.startsWith("$") || star < 0 || star + 3 > line.length) return false
            var x = 0
            for (i in 1 until star) x = x xor line[i].code
            return line.substring(star + 1, star + 3).equals(String.format(Locale.US, "%02X", x), ignoreCase = true)
        }
    }
}

/** Name of the GNSS band from the carrier frequency. */
internal fun bandName(hz: Float?): String {
    if (hz == null || hz <= 0f) return "—"
    val mhz = hz / 1e6
    return when {
        abs(mhz - 1575.42) < 3 -> "L1"
        abs(mhz - 1561.098) < 3 -> "B1"
        mhz in 1597.0..1607.0 -> "G1"
        abs(mhz - 1176.45) < 3 -> "L5"
        abs(mhz - 1227.60) < 3 -> "L2"
        mhz in 1240.0..1250.0 -> "G2"
        abs(mhz - 1207.14) < 3 -> "E5b"
        abs(mhz - 1268.52) < 3 -> "B3"
        abs(mhz - 1278.75) < 3 -> "E6"
        mhz in 2490.0..2496.0 -> "S"
        else -> "${mhz.roundToInt()} MHz"
    }
}

internal fun constellationName(c: Int): String = when (c) {
    GnssStatus.CONSTELLATION_GPS -> "GPS"
    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
    GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
    GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
    GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
    else -> "Unknown"
}
