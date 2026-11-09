package org.rombench.areas.sensors

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.annotation.StringRes
import org.rombench.R
import org.rombench.core.Detail
import org.rombench.core.d
import java.util.Locale

/** Reporting mode of a sensor, as declared by the HAL. */
internal enum class Mode { CONTINUOUS, ON_CHANGE, ONE_SHOT, SPECIAL }

/**
 * Static description of a sensor type: stable key used in result ids, localised label, unit and
 * the names of the values in [android.hardware.SensorEvent.values].
 */
internal class TypeInfo(val key: String, @StringRes val label: Int, val unit: String, val axes: List<String>)

private val XYZ = listOf("x", "y", "z")
private val XYZ_BIAS = listOf("x", "y", "z", "bx", "by", "bz")
private val QUAT = listOf("x", "y", "z", "w")
private val ONE = listOf("")

/** Deprecated platform types, by value to avoid deprecation warnings. */
internal const val TYPE_ORIENTATION = 3
internal const val TYPE_TEMPERATURE = 7

/** Sensor types defined by the platform up to Android 16, by numeric value. */
internal fun typeInfo(s: Sensor): TypeInfo = when (s.type) {
    Sensor.TYPE_ACCELEROMETER -> TypeInfo("accelerometer", R.string.sensors_type_accelerometer, "m/s²", XYZ)
    Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> TypeInfo("accelerometer_uncalibrated", R.string.sensors_type_accelerometer_uncal, "m/s²", XYZ_BIAS)
    Sensor.TYPE_MAGNETIC_FIELD -> TypeInfo("magnetometer", R.string.sensors_type_magnetometer, "µT", XYZ)
    Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> TypeInfo("magnetometer_uncalibrated", R.string.sensors_type_magnetometer_uncal, "µT", XYZ_BIAS)
    Sensor.TYPE_GYROSCOPE -> TypeInfo("gyroscope", R.string.sensors_type_gyroscope, "rad/s", XYZ)
    Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> TypeInfo("gyroscope_uncalibrated", R.string.sensors_type_gyroscope_uncal, "rad/s", XYZ_BIAS)
    Sensor.TYPE_GRAVITY -> TypeInfo("gravity", R.string.sensors_type_gravity, "m/s²", XYZ)
    Sensor.TYPE_LINEAR_ACCELERATION -> TypeInfo("linear_acceleration", R.string.sensors_type_linear_acceleration, "m/s²", XYZ)
    Sensor.TYPE_ROTATION_VECTOR -> TypeInfo("rotation_vector", R.string.sensors_type_rotation_vector, "", QUAT)
    Sensor.TYPE_GAME_ROTATION_VECTOR -> TypeInfo("game_rotation_vector", R.string.sensors_type_game_rotation_vector, "", QUAT)
    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> TypeInfo("geomagnetic_rotation_vector", R.string.sensors_type_geomagnetic_rotation_vector, "", QUAT)
    TYPE_ORIENTATION -> TypeInfo("orientation", R.string.sensors_type_orientation, "°", listOf("azimuth", "pitch", "roll"))
    Sensor.TYPE_PRESSURE -> TypeInfo("pressure", R.string.sensors_type_pressure, "hPa", ONE)
    Sensor.TYPE_LIGHT -> TypeInfo("light", R.string.sensors_type_light, "lx", ONE)
    Sensor.TYPE_PROXIMITY -> TypeInfo("proximity", R.string.sensors_type_proximity, "cm", ONE)
    Sensor.TYPE_AMBIENT_TEMPERATURE -> TypeInfo("ambient_temperature", R.string.sensors_type_ambient_temperature, "°C", ONE)
    TYPE_TEMPERATURE -> TypeInfo("temperature", R.string.sensors_type_temperature, "°C", ONE)
    Sensor.TYPE_RELATIVE_HUMIDITY -> TypeInfo("humidity", R.string.sensors_type_humidity, "%", ONE)
    Sensor.TYPE_STEP_COUNTER -> TypeInfo("step_counter", R.string.sensors_type_step_counter, "", ONE)
    Sensor.TYPE_STEP_DETECTOR -> TypeInfo("step_detector", R.string.sensors_type_step_detector, "", ONE)
    Sensor.TYPE_SIGNIFICANT_MOTION -> TypeInfo("significant_motion", R.string.sensors_type_significant_motion, "", ONE)
    Sensor.TYPE_HEART_RATE -> TypeInfo("heart_rate", R.string.sensors_type_heart_rate, "bpm", ONE)
    Sensor.TYPE_HEART_BEAT -> TypeInfo("heart_beat", R.string.sensors_type_heart_beat, "", ONE)
    Sensor.TYPE_STATIONARY_DETECT -> TypeInfo("stationary_detect", R.string.sensors_type_stationary, "", ONE)
    Sensor.TYPE_MOTION_DETECT -> TypeInfo("motion_detect", R.string.sensors_type_motion, "", ONE)
    Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> TypeInfo("offbody_detect", R.string.sensors_type_offbody, "", ONE)
    Sensor.TYPE_POSE_6DOF -> TypeInfo("pose_6dof", R.string.sensors_type_pose, "", emptyList())
    Sensor.TYPE_HINGE_ANGLE -> TypeInfo("hinge_angle", R.string.sensors_type_hinge, "°", ONE)
    Sensor.TYPE_HEAD_TRACKER -> TypeInfo("head_tracker", R.string.sensors_type_head_tracker, "", emptyList())
    Sensor.TYPE_ACCELEROMETER_LIMITED_AXES -> TypeInfo("accelerometer_limited", R.string.sensors_type_accelerometer, "m/s²", XYZ)
    Sensor.TYPE_GYROSCOPE_LIMITED_AXES -> TypeInfo("gyroscope_limited", R.string.sensors_type_gyroscope, "rad/s", XYZ)
    Sensor.TYPE_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED -> TypeInfo("accelerometer_limited_uncalibrated", R.string.sensors_type_accelerometer_uncal, "m/s²", XYZ_BIAS)
    Sensor.TYPE_GYROSCOPE_LIMITED_AXES_UNCALIBRATED -> TypeInfo("gyroscope_limited_uncalibrated", R.string.sensors_type_gyroscope_uncal, "rad/s", XYZ_BIAS)
    Sensor.TYPE_HEADING -> TypeInfo("heading", R.string.sensors_type_heading, "°", listOf("heading", "accuracy"))
    else -> TypeInfo(vendorKey(s), 0, "", emptyList())
}

/** Stable key for a vendor or unknown type: the sanitised string type ("com.qti.sensor.amd" → "com_qti_sensor_amd"). */
private fun vendorKey(s: Sensor): String {
    val st = s.stringType?.takeIf { it.isNotBlank() } ?: return "type_${s.type}"
    return st.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(48)
}

/** One sensor of the device with the key of its result. */
internal class SensorEntry(val sensor: Sensor, val key: String, val info: TypeInfo) {
    val type get() = sensor.type

    val mode: Mode
        get() = when (sensor.reportingMode) {
            Sensor.REPORTING_MODE_CONTINUOUS -> Mode.CONTINUOUS
            Sensor.REPORTING_MODE_ON_CHANGE -> Mode.ON_CHANGE
            Sensor.REPORTING_MODE_ONE_SHOT -> Mode.ONE_SHOT
            else -> Mode.SPECIAL
        }

    /** Continuous and on-change sensors are sampled by the automatic check; trigger sensors are not. */
    val sampled get() = mode == Mode.CONTINUOUS || mode == Mode.ON_CHANGE

    /** Period requested by the automatic check: as fast as the sensor claims, capped at 200 Hz. */
    val autoPeriodUs: Int get() = if (mode == Mode.CONTINUOUS) maxOf(sensor.minDelay, 5000) else 100_000

    /** Number of values drawn in the live chart (the bias half of uncalibrated sensors is left out). */
    val chartChannels: Int
        get() = when {
            info.axes.isEmpty() -> 3
            info.axes.size >= 6 -> 3
            else -> info.axes.size.coerceAtMost(4)
        }

    fun axisLabel(i: Int): String = info.axes.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: if (info.axes.size == 1) "v" else "v$i"
}

/** All sensors in a stable order with unique keys ("accelerometer", "accelerometer_wakeup", "light_2"…). */
internal fun sensorEntries(sm: SensorManager): List<SensorEntry> {
    val list = sm.getSensorList(Sensor.TYPE_ALL).sortedWith(
        compareBy<Sensor>({ it.type }, { it.isWakeUpSensor }, { it.name ?: "" }, { it.vendor ?: "" }),
    )
    val used = HashMap<String, Int>()
    return list.map { s ->
        val info = typeInfo(s)
        val base = info.key + if (s.isWakeUpSensor) "_wakeup" else ""
        val n = (used[base] ?: 0) + 1
        used[base] = n
        SensorEntry(s, if (n == 1) base else "${base}_$n", info)
    }
}

internal fun Mode.label(): String = when (this) {
    Mode.CONTINUOUS -> "continuous"
    Mode.ON_CHANGE -> "on-change"
    Mode.ONE_SHOT -> "one-shot"
    Mode.SPECIAL -> "special"
}

internal fun hz(periodUs: Int): String = if (periodUs <= 0) "—" else String.format(Locale.US, "%.1f Hz", 1e6 / periodUs)

/** Datasheet-like facts of a sensor, labels in technical English. */
internal fun sensorFacts(e: SensorEntry): List<Detail> {
    val s = e.sensor
    val unit = e.info.unit.let { if (it.isEmpty()) "" else " $it" }
    return listOf(
        d("Name", s.name),
        d("Vendor", s.vendor),
        d("Type", "${s.stringType} (${s.type})"),
        d("Version", s.version),
        d("Range", "${fmtF(s.maximumRange)}$unit"),
        d("Resolution", "${fmtF(s.resolution)}$unit"),
        d("Power", "${fmtF(s.power)} mA"),
        d("Min delay", if (s.minDelay > 0) "${s.minDelay} µs (${hz(s.minDelay)})" else s.minDelay.toString()),
        d("Max delay", if (s.maxDelay > 0) "${s.maxDelay} µs" else s.maxDelay.toString()),
        d("FIFO", "${s.fifoReservedEventCount} reserved / ${s.fifoMaxEventCount} max"),
        d("Reporting", e.mode.label()),
        d("Wake-up", s.isWakeUpSensor),
        d("Dynamic", s.isDynamicSensor),
    )
}

internal fun fmtF(v: Float): String = when {
    v.isNaN() -> "NaN"
    v.isInfinite() -> if (v > 0) "inf" else "-inf"
    v == 0f -> "0"
    kotlin.math.abs(v) >= 1000 -> String.format(Locale.US, "%.0f", v)
    kotlin.math.abs(v) >= 1 -> String.format(Locale.US, "%.2f", v)
    kotlin.math.abs(v) >= 0.001 -> String.format(Locale.US, "%.4f", v)
    else -> String.format(Locale.US, "%.2e", v)
}

internal fun fmtD(v: Double) = fmtF(v.toFloat())
