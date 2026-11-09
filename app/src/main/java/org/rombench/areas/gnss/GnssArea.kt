package org.rombench.areas.gnss

import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssCapabilities
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.delay
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.Detail
import org.rombench.core.Perms
import org.rombench.core.Status
import org.rombench.core.Sys
import org.rombench.core.TestResult
import org.rombench.core.Txt
import org.rombench.core.d
import org.rombench.core.fmt1
import org.rombench.core.result
import org.rombench.core.skippedForPermission
import org.rombench.core.txt
import kotlin.math.abs

object GnssArea : Area {
    override val id = "gnss"
    override val title = R.string.gnss_title
    override val subtitle = R.string.gnss_subtitle
    override val icon: ImageVector = Icons.Outlined.SatelliteAlt
    override val group = AreaGroup.CONNECTIVITY
    override val permissions = listOf(Perms.LOCATION)
    override val autoSeconds = 35

    /** Listening window of the automatic check; stops earlier a few seconds after a fix. */
    private const val LISTEN_MS = 30_000L

    /** Results produced by listening, with their titles (used for SKIP results too). */
    private val listenChecks = listOf(
        "satellites" to R.string.gnss_t_satellites,
        "signal" to R.string.gnss_t_signal,
        "fix" to R.string.gnss_t_fix,
        "nmea" to R.string.gnss_t_nmea,
        "measurements" to R.string.gnss_t_measurements,
    )

    override suspend fun autoCheck(ctx: Context): List<TestResult> {
        val lm = ctx.getSystemService(LocationManager::class.java)
            ?: return listOf(result("provider", txt(R.string.gnss_t_provider), Status.FAIL, txt(R.string.gnss_s_no_service), txt(R.string.gnss_r_no_service)))
        val out = mutableListOf<TestResult>()
        val providers = runCatching { lm.allProviders }.getOrDefault(emptyList())
        val hasGps = LocationManager.GPS_PROVIDER in providers
        val locOn = runCatching { lm.isLocationEnabled }.getOrDefault(false)
        val gpsOn = hasGps && runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

        out += providerResult(ctx, providers, hasGps, locOn, gpsOn)
        out += hardwareResult(lm)
        val caps = capabilities(lm)
        out += capabilitiesResult(caps)
        out += networkResult(ctx, lm, providers)

        val missing = Perms.missing(ctx, permissions)
        when {
            !hasGps -> out += listenChecks.map { (n, t) -> result(n, txt(t), Status.SKIP, txt(R.string.gnss_s_skip_no_gps), txt(R.string.gnss_r_skip_no_gps)) }
            missing.isNotEmpty() -> out += listenChecks.map { (n, t) -> skippedForPermission(n, txt(t), missing) }
            !gpsOn -> out += listenChecks.map { (n, t) -> result(n, txt(t), Status.SKIP, txt(R.string.gnss_s_skip_off), txt(R.string.gnss_r_location_off)) }
            else -> out += listen(lm, caps)
        }
        return out
    }

    private suspend fun listen(lm: LocationManager, caps: Caps): List<TestResult> {
        val thread = HandlerThread("rombench-gnss").apply { start() }
        val handler = Handler(thread.looper)
        val session = GnssSession(lm) { handler.post(it) }
        val t0 = SystemClock.elapsedRealtime()
        try {
            session.start()
            while (true) {
                delay(500)
                val el = SystemClock.elapsedRealtime() - t0
                if (el >= LISTEN_MS) break
                val fix = session.firstFixMs()
                if (fix != null && el - fix >= 5000 && el >= 10_000) break
            }
        } finally {
            session.stop()
            thread.quitSafely()
        }
        val s = session.snapshot()
        return listOf(satellitesResult(s), signalResult(s), fixResult(s), nmeaResult(s), measurementsResult(s, caps))
    }

    // ---- Static checks -------------------------------------------------------------------------

    private fun providerResult(ctx: Context, providers: List<String>, hasGps: Boolean, locOn: Boolean, gpsOn: Boolean): TestResult {
        val feature = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
        val details = listOf(
            d("Providers", providers.joinToString()),
            d("Feature location.gps", feature),
            d("Location enabled", locOn),
            d("GPS provider enabled", gpsOn),
        )
        val title = txt(R.string.gnss_t_provider)
        return when {
            !hasGps -> result("provider", title, Status.FAIL, txt(R.string.gnss_s_no_gps), txt(R.string.gnss_r_no_gps), details)
            !locOn -> result("provider", title, Status.WARN, txt(R.string.gnss_s_location_off), txt(R.string.gnss_r_location_off), details)
            !gpsOn -> result("provider", title, Status.WARN, txt(R.string.gnss_s_gps_off), txt(R.string.gnss_r_location_off), details)
            !feature -> result("provider", title, Status.WARN, txt(R.string.gnss_s_gps_ok), txt(R.string.gnss_r_no_feature), details)
            else -> result("provider", title, Status.PASS, txt(R.string.gnss_s_gps_ok), null, details)
        }
    }

    private fun hardwareResult(lm: LocationManager): TestResult {
        val model = runCatching { lm.gnssHardwareModelName }.getOrNull()
        val year = runCatching { lm.gnssYearOfHardware }.getOrDefault(0)
        val details = mutableListOf(
            d("Model", model ?: "unknown"),
            d("Year of hardware", if (year == 0) "0 (not reported: old GNSS HAL)" else year),
        )
        details += gpsConf()
        return result(
            "hardware", txt(R.string.gnss_t_hardware), Status.INFO,
            txt(R.string.gnss_s_hardware, model ?: "?", if (year == 0) "—" else year.toString()),
            null, details,
        )
    }

    /** Assistance servers from gps.conf, which is world-readable on most ROMs. */
    private fun gpsConf(): List<Detail> {
        val path = listOf("/vendor/etc/gps.conf", "/odm/etc/gps.conf", "/system/vendor/etc/gps.conf", "/system/etc/gps.conf")
            .firstOrNull { Sys.exists(it) } ?: return listOf(d("gps.conf", "not found"))
        val text = Sys.read(path) ?: return listOf(d("gps.conf", "$path (unreadable)"))
        val kv = text.lineSequence().map { it.substringBefore('#').trim() }.filter { '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        return listOf(d("gps.conf", path)) +
            listOf("XTRA_SERVER_1", "XTRA_SERVER_2", "NTP_SERVER", "SUPL_HOST", "SUPL_VER", "LPP_PROFILE", "GPS_LOCK")
                .mapNotNull { k -> kv[k]?.let { d(k, it) } }
    }

    /** GNSS capabilities as plain values, so the checks do not depend on the API level. */
    internal class Caps(val details: List<Detail>, val measurements: Boolean?, val navMessages: Boolean?)

    private fun capabilities(lm: LocationManager): Caps {
        val c: GnssCapabilities = runCatching { lm.gnssCapabilities }.getOrNull() ?: return Caps(emptyList(), null, null)
        val out = mutableListOf<Detail>()
        var meas: Boolean? = null
        var nav: Boolean? = null
        if (Build.VERSION.SDK_INT >= 31) {
            meas = c.hasMeasurements()
            nav = c.hasNavigationMessages()
            out += d("Measurements", meas)
            out += d("Navigation messages", nav)
            out += d("Antenna info", c.hasAntennaInfo())
        } else {
            @Suppress("DEPRECATION")
            out += d("Antenna info", c.hasGnssAntennaInfo())
        }
        if (Build.VERSION.SDK_INT >= 34) {
            out += d("Accumulated delta range", when (c.hasAccumulatedDeltaRange()) {
                GnssCapabilities.CAPABILITY_SUPPORTED -> "yes"
                GnssCapabilities.CAPABILITY_UNSUPPORTED -> "no"
                else -> "unknown"
            })
            out += d("Measurement corrections", c.hasMeasurementCorrections())
            out += d("Correlation vectors", c.hasMeasurementCorrelationVectors())
            out += d("Satellite PVT", c.hasSatellitePvt())
            out += d("Satellite blocklist", c.hasSatelliteBlocklist())
            out += d("Low power mode", c.hasLowPowerMode())
            out += d("Scheduling", c.hasScheduling())
            out += d("Single shot fix", c.hasSingleShotFix())
            out += d("MS-based / MS-assisted", "${c.hasMsb()} / ${c.hasMsa()}")
            out += d("Geofencing", c.hasGeofencing())
            out += d("On-demand time", c.hasOnDemandTime())
            out += d("Power stats", c.hasPowerTotal())
            val types = runCatching { c.gnssSignalTypes }.getOrNull().orEmpty()
            if (types.isNotEmpty()) out += d(
                "Signal types",
                types.joinToString { "${constellationName(it.constellationType)} ${bandName(it.carrierFrequencyHz.toFloat())} ${it.codeType}" },
            )
        }
        return Caps(out, meas, nav)
    }

    private fun capabilitiesResult(c: Caps): TestResult {
        val yes = R.string.gnss_yes
        val no = R.string.gnss_no
        val summary: Txt = if (c.measurements == null) txt(R.string.gnss_s_caps_old)
        else txt(R.string.gnss_s_caps, txt(if (c.measurements) yes else no), txt(if (c.navMessages == true) yes else no))
        return result("capabilities", txt(R.string.gnss_t_capabilities), Status.INFO, summary, null, c.details)
    }

    private fun networkResult(ctx: Context, lm: LocationManager, providers: List<String>): TestResult {
        val hasNet = LocationManager.NETWORK_PROVIDER in providers
        val hasFused = "fused" in providers
        val gms = runCatching { ctx.packageManager.getPackageInfo("com.google.android.gms", 0) }.isSuccess
        val details = listOf(
            d("Providers", providers.joinToString()),
            d("network", if (hasNet) "present, enabled=${runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)}" else "absent"),
            d("fused", if (hasFused) "present" else "absent"),
            d("com.google.android.gms (GMS or microG)", if (gms) "installed" else "not installed"),
        )
        val title = txt(R.string.gnss_t_network)
        return if (hasNet) result("network", title, Status.INFO, txt(R.string.gnss_s_network_yes), null, details)
        else result("network", title, Status.INFO, txt(R.string.gnss_s_network_no), txt(R.string.gnss_r_network_no), details)
    }

    // ---- Listening checks ----------------------------------------------------------------------

    private fun perConstellation(s: GnssSnapshot): List<Detail> =
        s.seen.groupBy { it.constellation }.toSortedMap().map { (c, list) ->
            val bands = list.map { it.band }.distinct().joinToString("/")
            d(
                constellationName(c),
                "${list.map { it.svid }.distinct().size} seen, ${list.count { it.everUsed }} used, " +
                    "max C/N0 ${fmt1(list.maxOf { it.maxCn0 }.toDouble())} dB-Hz, bands $bands",
            )
        }

    private fun satellitesResult(s: GnssSnapshot): TestResult {
        val title = txt(R.string.gnss_t_satellites)
        val details = perConstellation(s) + listOf(
            d("Max in view", s.maxInView),
            d("Max used in fix", s.maxUsed),
            d("With almanac", s.seen.count { it.almanac }),
            d("With ephemeris", s.seen.count { it.ephemeris }),
            d("Status updates", s.statusUpdates),
            d("Engine started", s.engineStarted),
            d("Listened", "${s.elapsedMs / 1000} s"),
        ) + listOfNotNull(s.error?.let { d("Error", it) })
        val count = s.seen.map { it.constellation to it.svid }.distinct().size
        return when {
            !s.regStatus -> result("satellites", title, Status.FAIL, txt(R.string.gnss_s_status_refused), txt(R.string.gnss_r_status_refused), details)
            s.statusUpdates == 0 -> result("satellites", title, Status.FAIL, txt(R.string.gnss_s_no_status, s.elapsedMs / 1000), txt(R.string.gnss_r_no_status), details)
            count == 0 -> result("satellites", title, Status.FAIL, txt(R.string.gnss_s_no_sats), txt(R.string.gnss_r_no_sats), details)
            else -> {
                val byC = s.seen.groupBy { it.constellation }.toSortedMap()
                    .map { (c, l) -> "${constellationName(c)} ${l.map { it.svid }.distinct().size}" }.joinToString(", ")
                result("satellites", title, Status.PASS, txt(R.string.gnss_s_sats, count, byC, s.maxUsed), null, details)
            }
        }
    }

    private fun signalResult(s: GnssSnapshot): TestResult {
        val title = txt(R.string.gnss_t_signal)
        val heard = s.seen.filter { it.maxCn0 > 0 }
        val maxCn0 = s.seen.maxOfOrNull { it.maxCn0 } ?: 0f
        val mean = if (heard.isEmpty()) 0.0 else heard.map { it.maxCn0.toDouble() }.average()
        val details = mutableListOf(
            d("Satellites", s.seen.size),
            d("With C/N0 > 0", heard.size),
            d("Max C/N0", "${fmt1(maxCn0.toDouble())} dB-Hz"),
            d("Mean C/N0 (heard)", "${fmt1(mean)} dB-Hz"),
        )
        s.seen.groupBy { it.band }.forEach { (b, l) -> details += d("Band $b", "${l.size} sats, max ${fmt1(l.maxOf { it.maxCn0 }.toDouble())} dB-Hz") }
        details += d("NMEA GSV satellites", s.gsvBlocks)
        details += d("NMEA GSV with SNR", "${s.gsvWithSnr} (max ${fmt1(s.gsvMaxSnr.toDouble())})")
        if (s.measEvents > 0) details += d("Raw measurements max C/N0", "${fmt1(s.measMaxCn0)} dB-Hz")
        return when {
            s.seen.isEmpty() -> result("signal", title, Status.FAIL, txt(R.string.gnss_s_signal_none), txt(R.string.gnss_r_signal_none), details)
            maxCn0 <= 0f && s.gsvWithSnr > 0 -> result(
                "signal", title, Status.WARN, txt(R.string.gnss_s_signal_zero, s.seen.size),
                txt(R.string.gnss_r_signal_nmea_only, fmt1(s.gsvMaxSnr.toDouble())), details,
            )
            maxCn0 <= 0f -> result(
                "signal", title, Status.FAIL, txt(R.string.gnss_s_signal_zero, s.seen.size),
                if (s.gsvBlocks > 0) txt(R.string.gnss_r_no_rf_gsv) else txt(R.string.gnss_r_no_rf), details,
            )
            maxCn0 < 20f -> result("signal", title, Status.WARN, txt(R.string.gnss_s_signal, fmt1(maxCn0.toDouble()), fmt1(mean), heard.size), txt(R.string.gnss_r_weak), details)
            else -> result("signal", title, Status.PASS, txt(R.string.gnss_s_signal, fmt1(maxCn0.toDouble()), fmt1(mean), heard.size), null, details)
        }
    }

    /** 1024 GPS weeks: the offset of a receiver hit by the week-number rollover. */
    private const val WEEK_ROLLOVER_MS = 1024L * 7 * 24 * 3600 * 1000

    private fun fixResult(s: GnssSnapshot): TestResult {
        val title = txt(R.string.gnss_t_fix)
        val loc = s.location
        val maxCn0 = s.seen.maxOfOrNull { it.maxCn0 } ?: 0f
        if (loc == null) {
            val details = listOf(d("Listened", "${s.elapsedMs / 1000} s"), d("Max used in fix", s.maxUsed), d("Location listener", s.regLocation))
            return when {
                !s.regLocation -> result("fix", title, Status.FAIL, txt(R.string.gnss_s_fix_refused), txt(R.string.gnss_r_fix_refused), details)
                s.seen.isEmpty() || maxCn0 <= 0f -> result("fix", title, Status.FAIL, txt(R.string.gnss_s_no_fix, s.elapsedMs / 1000), txt(R.string.gnss_r_no_fix_rf), details)
                else -> result("fix", title, Status.WARN, txt(R.string.gnss_s_no_fix, s.elapsedMs / 1000), txt(R.string.gnss_r_no_fix_indoor), details)
            }
        }
        val ttff = s.hwTtffMs?.toLong() ?: s.firstFixMs ?: 0L
        val timeDiff = loc.time - s.wallAtFix
        val details = listOf(
            d("Latitude", "%.6f".format(java.util.Locale.US, loc.latitude), sensitive = true),
            d("Longitude", "%.6f".format(java.util.Locale.US, loc.longitude), sensitive = true),
            d("Accuracy", if (loc.hasAccuracy()) "${fmt1(loc.accuracy.toDouble())} m" else null),
            d("Altitude", if (loc.hasAltitude()) "${fmt1(loc.altitude)} m" else null),
            d("Speed", if (loc.hasSpeed()) "${fmt1(loc.speed.toDouble())} m/s" else null),
            d("TTFF (HAL)", s.hwTtffMs?.let { "$it ms" }),
            d("TTFF (app)", s.firstFixMs?.let { "$it ms" }),
            d("Fixes", s.fixes),
            d("Max used in fix", s.maxUsed),
            d("GPS time − system time", "${timeDiff / 1000} s"),
        )
        val summary = txt(R.string.gnss_s_fix, fmt1(ttff / 1000.0), if (loc.hasAccuracy()) fmt1(loc.accuracy.toDouble()) else "?", s.maxUsed)
        return when {
            abs(abs(timeDiff) - WEEK_ROLLOVER_MS) < 30L * 24 * 3600 * 1000 -> result("fix", title, Status.WARN, summary, txt(R.string.gnss_r_rollover), details)
            abs(timeDiff) > 60_000 -> result("fix", title, Status.WARN, summary, txt(R.string.gnss_r_time, timeDiff / 1000), details)
            loc.latitude == 0.0 && loc.longitude == 0.0 -> result("fix", title, Status.WARN, summary, txt(R.string.gnss_r_null_island), details)
            loc.hasAccuracy() && loc.accuracy > 500 -> result("fix", title, Status.WARN, summary, txt(R.string.gnss_r_accuracy), details)
            else -> result("fix", title, Status.PASS, summary, null, details)
        }
    }

    private fun nmeaResult(s: GnssSnapshot): TestResult {
        val title = txt(R.string.gnss_t_nmea)
        val types = s.nmeaTypes.entries.sortedByDescending { it.value }.joinToString { "${it.key}×${it.value}" }
        val details = listOf(d("Sentences", s.nmeaCount), d("Bad checksum", s.nmeaBad), d("Types", types), d("Listener", s.regNmea)) +
            s.nmeaTail.takeLast(5).mapIndexed { i, l -> d("Last ${i + 1}", l) }
        return when {
            !s.regNmea -> result("nmea", title, Status.WARN, txt(R.string.gnss_s_nmea_refused), txt(R.string.gnss_r_nmea_none), details)
            s.nmeaCount == 0 -> result("nmea", title, Status.WARN, txt(R.string.gnss_s_nmea_none), txt(R.string.gnss_r_nmea_none), details)
            s.nmeaBad * 10 > s.nmeaCount -> result("nmea", title, Status.WARN, txt(R.string.gnss_s_nmea, s.nmeaCount, s.nmeaTypes.keys.take(8).joinToString()), txt(R.string.gnss_r_nmea_bad, s.nmeaBad), details)
            else -> result("nmea", title, Status.PASS, txt(R.string.gnss_s_nmea, s.nmeaCount, s.nmeaTypes.keys.take(8).joinToString()), null, details)
        }
    }

    private fun measurementsResult(s: GnssSnapshot, caps: Caps): TestResult {
        val title = txt(R.string.gnss_t_measurements)
        val details = listOf(
            d("Capability", caps.measurements ?: "unknown"),
            d("Callback registered", s.regMeas),
            d("Events", s.measEvents),
            d("Measurements", s.measCount),
            d("Max C/N0", "${fmt1(s.measMaxCn0)} dB-Hz"),
        )
        val tracking = (s.seen.maxOfOrNull { it.maxCn0 } ?: 0f) > 0f
        return when {
            s.measEvents > 0 -> result("measurements", title, Status.INFO, txt(R.string.gnss_s_meas, s.measEvents, s.measCount), null, details)
            caps.measurements == true && tracking -> result("measurements", title, Status.WARN, txt(R.string.gnss_s_meas_none), txt(R.string.gnss_r_meas_missing), details)
            else -> result("measurements", title, Status.INFO, txt(R.string.gnss_s_meas_none), txt(R.string.gnss_r_meas_info), details)
        }
    }

    @Composable
    override fun Content() = GnssContent()
}
