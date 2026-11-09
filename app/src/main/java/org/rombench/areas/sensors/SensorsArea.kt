package org.rombench.areas.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.Status
import org.rombench.core.TestResult
import org.rombench.core.d
import org.rombench.core.result
import org.rombench.core.txt

object SensorsArea : Area {
    override val id = "sensors"
    override val title = R.string.sensors_title
    override val subtitle = R.string.sensors_subtitle
    override val icon: ImageVector = Icons.Outlined.Sensors
    override val group = AreaGroup.SYSTEM
    override val autoSeconds = 5

    /** How long every sensor is sampled by the automatic check. */
    private const val LISTEN_MS = 2500L

    override suspend fun autoCheck(ctx: Context): List<TestResult> {
        val sm = ctx.getSystemService(SensorManager::class.java)
            ?: return listOf(
                result("list", txt(R.string.sensors_t_list), Status.FAIL, txt(R.string.sensors_s_no_service), txt(R.string.sensors_r_no_service)),
            )
        val entries = sensorEntries(sm)
        val out = mutableListOf<TestResult>()
        out += listResult(entries)
        out += expectedResult(entries)
        featuresResult(ctx, entries)?.let { out += it }

        val collectors = entries.filter { it.sampled }.map { Collector(it) }
        if (collectors.isNotEmpty()) {
            val thread = HandlerThread("rombench-sensors").apply { start() }
            try {
                val handler = Handler(thread.looper)
                withContext(Dispatchers.Default) {
                    collectors.forEach { c ->
                        c.registered = runCatching {
                            sm.registerListener(c, c.entry.sensor, c.entry.autoPeriodUs, 0, handler)
                        }.getOrDefault(false)
                    }
                    delay(LISTEN_MS)
                }
            } finally {
                collectors.forEach { runCatching { sm.unregisterListener(it) } }
                thread.quitSafely()
            }
            collectors.forEach { out += evaluate(this, it) }
            fusionResult(this, collectors)?.let { out += it }
        }
        return out
    }

    private fun listResult(entries: List<SensorEntry>): TestResult {
        val details = entries.map { e ->
            val s = e.sensor
            d(
                "[${e.key}] ${s.name}",
                "${s.vendor} · ${s.stringType} v${s.version} · range ${fmtF(s.maximumRange)} res ${fmtF(s.resolution)} · " +
                    "${fmtF(s.power)} mA · delay ${s.minDelay}…${s.maxDelay} µs · FIFO ${s.fifoReservedEventCount}/${s.fifoMaxEventCount} · " +
                    e.mode.label() + (if (s.isWakeUpSensor) " · wake-up" else "") + (if (s.isDynamicSensor) " · dynamic" else ""),
            )
        }
        val status = if (entries.isEmpty()) Status.FAIL else Status.INFO
        return result(
            "list", txt(R.string.sensors_t_list), status,
            txt(R.string.sensors_s_list, entries.size, entries.count { it.sensor.isWakeUpSensor }),
            if (entries.isEmpty()) txt(R.string.sensors_r_none) else null, details,
        )
    }

    /** Sensors every phone is expected to have. */
    private fun expectedResult(entries: List<SensorEntry>): TestResult {
        val types = entries.map { it.type }.toSet()
        val required = listOf(
            Sensor.TYPE_ACCELEROMETER to "accelerometer",
            Sensor.TYPE_PROXIMITY to "proximity",
            Sensor.TYPE_LIGHT to "light",
        )
        val optional = listOf(
            Sensor.TYPE_GYROSCOPE to "gyroscope",
            Sensor.TYPE_MAGNETIC_FIELD to "magnetometer",
            Sensor.TYPE_ROTATION_VECTOR to "rotation vector",
            Sensor.TYPE_GRAVITY to "gravity",
            Sensor.TYPE_STEP_COUNTER to "step counter",
            Sensor.TYPE_PRESSURE to "barometer",
        )
        val missing = required.filter { it.first !in types }.map { it.second }
        val missingOpt = optional.filter { it.first !in types }.map { it.second }
        val details = (required + optional).map { (t, n) -> d(n, if (t in types) "present" else "missing") }
        return if (missing.isEmpty()) result(
            "expected", txt(R.string.sensors_t_expected), Status.PASS,
            txt(R.string.sensors_s_expected_ok),
            if (missingOpt.isNotEmpty()) txt(R.string.sensors_r_expected_optional, missingOpt.joinToString()) else null,
            details,
        ) else result(
            "expected", txt(R.string.sensors_t_expected), Status.WARN,
            txt(R.string.sensors_s_expected_missing, missing.joinToString()),
            txt(R.string.sensors_r_expected_missing), details,
        )
    }

    /**
     * android.hardware.sensor.* features must match the sensors the HAL lists: the device tree
     * copies them from frameworks/native/data/etc, and apps (and the Play Store) trust them.
     */
    private fun featuresResult(ctx: Context, entries: List<SensorEntry>): TestResult? {
        val pm = ctx.packageManager ?: return null
        val types = entries.map { it.type }.toSet()
        val pairs = listOf(
            PackageManager.FEATURE_SENSOR_ACCELEROMETER to Sensor.TYPE_ACCELEROMETER,
            PackageManager.FEATURE_SENSOR_COMPASS to Sensor.TYPE_MAGNETIC_FIELD,
            PackageManager.FEATURE_SENSOR_GYROSCOPE to Sensor.TYPE_GYROSCOPE,
            PackageManager.FEATURE_SENSOR_LIGHT to Sensor.TYPE_LIGHT,
            PackageManager.FEATURE_SENSOR_PROXIMITY to Sensor.TYPE_PROXIMITY,
            PackageManager.FEATURE_SENSOR_BAROMETER to Sensor.TYPE_PRESSURE,
            PackageManager.FEATURE_SENSOR_STEP_COUNTER to Sensor.TYPE_STEP_COUNTER,
            PackageManager.FEATURE_SENSOR_STEP_DETECTOR to Sensor.TYPE_STEP_DETECTOR,
            PackageManager.FEATURE_SENSOR_AMBIENT_TEMPERATURE to Sensor.TYPE_AMBIENT_TEMPERATURE,
            PackageManager.FEATURE_SENSOR_RELATIVE_HUMIDITY to Sensor.TYPE_RELATIVE_HUMIDITY,
        )
        val declaredMissing = mutableListOf<String>()
        val undeclared = mutableListOf<String>()
        val details = pairs.map { (f, t) ->
            val has = pm.hasSystemFeature(f)
            val present = t in types
            val name = f.removePrefix("android.hardware.sensor.")
            if (has && !present) declaredMissing += name
            if (!has && present) undeclared += name
            d(f, "feature ${if (has) "yes" else "no"}, sensor ${if (present) "yes" else "no"}")
        }
        val title = txt(R.string.sensors_t_features)
        return when {
            declaredMissing.isNotEmpty() -> result(
                "features", title, Status.WARN, txt(R.string.sensors_s_features_declared, declaredMissing.joinToString()),
                txt(R.string.sensors_r_features_declared), details,
            )
            undeclared.isNotEmpty() -> result(
                "features", title, Status.WARN, txt(R.string.sensors_s_features_undeclared, undeclared.joinToString()),
                txt(R.string.sensors_r_features_undeclared), details,
            )
            else -> result("features", title, Status.PASS, txt(R.string.sensors_s_features_ok), null, details)
        }
    }

    @Composable
    override fun Content() = SensorsContent()
}
