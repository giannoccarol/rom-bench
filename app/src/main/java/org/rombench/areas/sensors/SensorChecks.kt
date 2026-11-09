package org.rombench.areas.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.Detail
import org.rombench.core.Status
import org.rombench.core.TestResult
import org.rombench.core.Txt
import org.rombench.core.d
import org.rombench.core.fmt1
import org.rombench.core.fmt2
import org.rombench.core.raw
import org.rombench.core.result
import org.rombench.core.txt
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

internal const val G = 9.80665

internal class Sample(val t: Long, val recv: Long, val v: FloatArray)

/** Records the events of one sensor during the automatic check. */
internal class Collector(val entry: SensorEntry, private val cap: Int = 2500) : SensorEventListener {
    private val lock = Any()
    private val list = ArrayList<Sample>()

    @Volatile var accuracy = -1
    @Volatile var registered = false

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.elapsedRealtimeNanos()
        synchronized(lock) { if (list.size < cap) list.add(Sample(e.timestamp, now, e.values.copyOf())) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        this.accuracy = accuracy
    }

    fun samples(): List<Sample> = synchronized(lock) { ArrayList(list) }
}

/** Statistics over the collected samples; NaN/inf samples are counted and left out of the means. */
internal class Stats(samples: List<Sample>) {
    val n = samples.size
    val dims = samples.minOfOrNull { it.v.size } ?: 0
    val badValues = samples.count { s -> s.v.any { it.isNaN() || it.isInfinite() } }
    private val good = samples.filter { s -> s.v.none { it.isNaN() || it.isInfinite() } }
    val nonMonotonic: Int
    val rateHz: Double
    val offsetMs: Double
    val mean = DoubleArray(dims)
    val std = DoubleArray(dims)
    val min = DoubleArray(dims) { Double.POSITIVE_INFINITY }
    val max = DoubleArray(dims) { Double.NEGATIVE_INFINITY }
    val allSame: Boolean
    val allZero: Boolean
    val magMean: Double
    val magStd: Double
    val magMax: Double

    init {
        var nm = 0
        for (i in 1 until samples.size) if (samples[i].t <= samples[i - 1].t) nm++
        nonMonotonic = nm
        rateHz = if (samples.size >= 3) {
            val dt = (samples.last().t - samples.first().t) / 1e9
            if (dt > 0) (samples.size - 1) / dt else 0.0
        } else 0.0
        val offs = samples.map { (it.recv - it.t) / 1e6 }.sorted()
        offsetMs = if (offs.isEmpty()) 0.0 else offs[offs.size / 2]
        for (s in good) for (k in 0 until dims) {
            val v = s.v[k].toDouble()
            mean[k] += v
            if (v < min[k]) min[k] = v
            if (v > max[k]) max[k] = v
        }
        if (good.isNotEmpty()) for (k in 0 until dims) mean[k] /= good.size
        for (s in good) for (k in 0 until dims) { val dv = s.v[k] - mean[k]; std[k] += dv * dv }
        if (good.size > 1) for (k in 0 until dims) std[k] = sqrt(std[k] / (good.size - 1))
        allSame = good.size >= 2 && good.all { it.v.take(dims).toList() == good.first().v.take(dims).toList() }
        allZero = good.isNotEmpty() && good.all { s -> (0 until dims).all { s.v[it] == 0f } }
        val mags = good.map { magnitude(it.v, dims) }
        magMean = if (mags.isEmpty()) 0.0 else mags.average()
        magStd = if (mags.size < 2) 0.0 else sqrt(mags.sumOf { (it - magMean) * (it - magMean) } / (mags.size - 1))
        magMax = mags.maxOrNull() ?: 0.0
    }

    fun samplesGood() = good

    companion object {
        fun magnitude(v: FloatArray, dims: Int = v.size): Double {
            val k = minOf(3, dims)
            var sum = 0.0
            for (i in 0 until k) sum += v[i].toDouble() * v[i]
            return sqrt(sum)
        }
    }
}

private class Verdict(val status: Status, val summary: Txt? = null, val reason: Txt? = null)

private fun vec(st: Stats, unit: String) = (0 until minOf(3, st.dims)).joinToString(" ") { fmt2(st.mean[it]) } + " $unit"

/** Name of the axis carrying gravity, e.g. "+Z". */
internal fun dominantAxis(x: Double, y: Double, z: Double): String {
    val ax = listOf(abs(x), abs(y), abs(z))
    val i = ax.indexOf(ax.max())
    val sign = if (listOf(x, y, z)[i] >= 0) "+" else "-"
    return sign + "XYZ"[i]
}

private fun plausibility(e: SensorEntry, st: Stats): Verdict {
    val unit = e.info.unit
    return when (e.type) {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> {
            val m = st.magMean
            val summary = txt(R.string.sensors_s_accel, fmt2(m), dominantAxis(st.mean[0], st.mean[1], st.mean[2]))
            when {
                st.allZero -> Verdict(Status.FAIL, summary, txt(R.string.sensors_r_all_zero))
                m in 0.7..1.3 -> Verdict(Status.FAIL, summary, txt(R.string.sensors_r_accel_g))
                st.magStd > 1.0 -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_moving))
                abs(m - G) <= 1.5 -> Verdict(Status.PASS, summary)
                abs(m - 2 * G) <= 2.0 || abs(m - G / 2) <= 1.0 -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_accel_factor2))
                else -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_accel_scale))
            }
        }
        Sensor.TYPE_GRAVITY -> {
            val summary = txt(R.string.sensors_s_gravity, fmt2(st.magMean), dominantAxis(st.mean[0], st.mean[1], st.mean[2]))
            when {
                st.allZero -> Verdict(Status.FAIL, summary, txt(R.string.sensors_r_fusion_zero))
                abs(st.magMean - G) <= 1.0 -> Verdict(Status.PASS, summary)
                else -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_gravity))
            }
        }
        Sensor.TYPE_LINEAR_ACCELERATION -> {
            val summary = txt(R.string.sensors_s_vector, "|a|", fmt2(st.magMean), unit)
            when {
                abs(st.magMean - G) < 1.5 -> Verdict(Status.FAIL, summary, txt(R.string.sensors_r_linear_gravity))
                st.magMean < 1.0 -> Verdict(Status.PASS, summary)
                st.magStd > 1.0 -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_moving))
                else -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_linear))
            }
        }
        Sensor.TYPE_GYROSCOPE -> {
            val summary = txt(R.string.sensors_s_vector, "|ω|", fmt2(st.magMean), unit)
            when {
                st.allZero -> Verdict(Status.INFO, summary, txt(R.string.sensors_r_gyro_zero))
                st.magMean < 0.1 -> Verdict(Status.PASS, summary)
                st.magStd > 0.3 -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_moving))
                else -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_gyro_bias))
            }
        }
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
            val summary = txt(R.string.sensors_s_vector, "|ω|", fmt2(st.magMean), unit)
            when {
                st.allZero -> Verdict(Status.INFO, summary, txt(R.string.sensors_r_gyro_zero))
                st.magMean < 0.3 -> Verdict(Status.PASS, summary)
                st.magStd > 0.3 -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_moving))
                else -> Verdict(Status.INFO, summary, txt(R.string.sensors_r_gyro_uncal))
            }
        }
        Sensor.TYPE_MAGNETIC_FIELD -> {
            val m = st.magMean
            val summary = txt(R.string.sensors_s_vector, "|B|", fmt1(m), unit)
            when {
                st.allZero -> Verdict(Status.FAIL, summary, txt(R.string.sensors_r_all_zero))
                m in 20.0..70.0 -> Verdict(Status.PASS, summary)
                m in 200.0..800.0 -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_mag_mg))
                else -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_mag_range))
            }
        }
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
            val cal = st.samplesGood().map { s ->
                if (s.v.size >= 6) {
                    val x = s.v[0] - s.v[3]; val y = s.v[1] - s.v[4]; val z = s.v[2] - s.v[5]
                    sqrt((x * x + y * y + z * z).toDouble())
                } else Stats.magnitude(s.v)
            }
            val m = if (cal.isEmpty()) 0.0 else cal.average()
            val summary = txt(R.string.sensors_s_vector, "|B−bias|", fmt1(m), unit)
            when {
                st.allZero -> Verdict(Status.FAIL, summary, txt(R.string.sensors_r_all_zero))
                m in 20.0..70.0 -> Verdict(Status.PASS, summary)
                else -> Verdict(Status.INFO, summary, txt(R.string.sensors_r_mag_uncal))
            }
        }
        Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
            val good = st.samplesGood()
            val xyzMax = good.maxOfOrNull { s -> (0 until minOf(3, s.v.size)).sumOf { s.v[it].toDouble() * s.v[it] } } ?: 0.0
            val norm4 = good.filter { it.v.size >= 4 }.map { s -> sqrt((0 until 4).sumOf { s.v[it].toDouble() * s.v[it] }) }
            val worst = norm4.maxOfOrNull { abs(it - 1) } ?: 0.0
            val summary = txt(R.string.sensors_s_quat, fmt2(if (norm4.isEmpty()) sqrt(xyzMax) else norm4.average()))
            when {
                st.allZero -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_fusion_zero))
                xyzMax > 1.01 -> Verdict(Status.FAIL, summary, txt(R.string.sensors_r_quat_over))
                worst > 0.02 -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_quat_norm))
                else -> Verdict(Status.PASS, summary)
            }
        }
        TYPE_ORIENTATION -> {
            val summary = txt(R.string.sensors_s_values, vec(st, unit))
            val ok = st.min[0] >= 0 && st.max[0] <= 360 && st.min[1] >= -180 && st.max[1] <= 180 && st.min[2] >= -90 && st.max[2] <= 90
            if (ok) Verdict(Status.PASS, summary) else Verdict(Status.WARN, summary, txt(R.string.sensors_r_orientation))
        }
        Sensor.TYPE_PRESSURE -> {
            val p = st.mean.getOrElse(0) { 0.0 }
            val summary = txt(R.string.sensors_s_scalar, fmt1(p), unit)
            when {
                st.allZero -> Verdict(Status.FAIL, summary, txt(R.string.sensors_r_all_zero))
                p in 300.0..1100.0 -> Verdict(Status.PASS, summary)
                else -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_pressure))
            }
        }
        Sensor.TYPE_LIGHT -> {
            val summary = txt(R.string.sensors_s_scalar, fmt1(st.mean.getOrElse(0) { 0.0 }), unit)
            when {
                st.min.getOrElse(0) { 0.0 } < 0 -> Verdict(Status.FAIL, summary, txt(R.string.sensors_r_light_negative))
                st.max.getOrElse(0) { 0.0 } > 200_000 -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_light_high))
                else -> Verdict(Status.PASS, summary)
            }
        }
        Sensor.TYPE_PROXIMITY -> {
            val range = e.sensor.maximumRange.toDouble()
            val v = st.mean.getOrElse(0) { 0.0 }
            val near = v < proximityThreshold(e.sensor)
            val summary = txt(if (near) R.string.sensors_s_prox_near else R.string.sensors_s_prox_far, fmt1(v), fmt1(range))
            val distinct = st.samplesGood().map { it.v[0] }.distinct().size
            when {
                st.min[0] < 0 || st.max[0] > range * 1.001 + 0.001 -> Verdict(Status.WARN, summary, txt(R.string.sensors_r_prox_range))
                distinct > 2 -> Verdict(Status.INFO, summary, txt(R.string.sensors_r_prox_distance))
                else -> Verdict(Status.PASS, summary)
            }
        }
        Sensor.TYPE_AMBIENT_TEMPERATURE, TYPE_TEMPERATURE -> {
            val t = st.mean.getOrElse(0) { 0.0 }
            val summary = txt(R.string.sensors_s_scalar, fmt1(t), unit)
            if (t in -30.0..70.0) Verdict(Status.PASS, summary) else Verdict(Status.WARN, summary, txt(R.string.sensors_r_temperature))
        }
        Sensor.TYPE_RELATIVE_HUMIDITY -> {
            val h = st.mean.getOrElse(0) { 0.0 }
            val summary = txt(R.string.sensors_s_scalar, fmt1(h), unit)
            if (h in 0.0..100.0) Verdict(Status.PASS, summary) else Verdict(Status.WARN, summary, txt(R.string.sensors_r_humidity))
        }
        Sensor.TYPE_STEP_COUNTER -> {
            val steps = st.max.getOrElse(0) { 0.0 }
            val summary = txt(R.string.sensors_s_steps, steps.toLong())
            if (st.min[0] < 0) Verdict(Status.WARN, summary, txt(R.string.sensors_r_negative)) else Verdict(Status.PASS, summary)
        }
        Sensor.TYPE_HINGE_ANGLE -> {
            val a = st.mean.getOrElse(0) { 0.0 }
            val summary = txt(R.string.sensors_s_scalar, fmt1(a), unit)
            if (a in 0.0..360.0) Verdict(Status.PASS, summary) else Verdict(Status.WARN, summary, txt(R.string.sensors_r_orientation))
        }
        else -> Verdict(Status.PASS)
    }
}

/** Below this value the proximity sensor is considered "near". */
internal fun proximityThreshold(s: Sensor): Float = minOf(3f, s.maximumRange / 2f).coerceAtLeast(0.01f)

/** Types whose value always changes with noise: identical samples mean a stuck driver. */
private val NOISY = setOf(
    Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.TYPE_MAGNETIC_FIELD,
    Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION,
    Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
)

/** Body sensors need BODY_SENSORS, which this app does not request. */
private val BODY = setOf(Sensor.TYPE_HEART_RATE, Sensor.TYPE_HEART_BEAT, Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)

internal fun accuracyName(a: Int): String = when (a) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "high"
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "medium"
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "low"
    SensorManager.SENSOR_STATUS_UNRELIABLE -> "unreliable"
    SensorManager.SENSOR_STATUS_NO_CONTACT -> "no contact"
    else -> "—"
}

/** Two texts shown one after the other. */
internal fun join(a: Txt, b: Txt): Txt = txt(R.string.sensors_join, a, b)

internal fun sensorTitle(e: SensorEntry): Txt =
    if (e.info.label != 0) txt(R.string.sensors_t_sensor, txt(e.info.label), e.sensor.name ?: "?")
    else raw(e.sensor.name ?: e.sensor.stringType ?: "?")

/** Result of one sampled sensor. */
internal fun evaluate(area: Area, c: Collector): TestResult {
    val e = c.entry
    val s = e.sensor
    val title = sensorTitle(e)
    val st = Stats(c.samples())
    val details = mutableListOf<Detail>(
        d("Sensor", "${s.name} (${s.vendor})"),
        d("Type", s.stringType),
        d("Reporting", e.mode.label()),
        d("Events", st.n),
    )
    if (e.mode == Mode.CONTINUOUS) {
        details += d("Rate", "${fmt1(st.rateHz)} Hz")
        details += d("Requested", hz(e.autoPeriodUs))
        details += d("Max claimed", hz(s.minDelay))
    }
    if (st.n > 0) {
        details += d("Mean", (0 until st.dims).joinToString(" ") { "${e.axisLabel(it)}=${fmtD(st.mean[it])}" })
        details += d("Std", (0 until st.dims).joinToString(" ") { fmtD(st.std[it]) })
        details += d("Min/Max", (0 until minOf(3, st.dims)).joinToString(" ") { "${fmtD(st.min[it])}…${fmtD(st.max[it])}" })
        details += d("Timestamp lag", "${fmt1(st.offsetMs)} ms")
        if (st.nonMonotonic > 0) details += d("Non-monotonic", st.nonMonotonic)
        if (st.badValues > 0) details += d("NaN/inf", st.badValues)
        details += d("Accuracy", accuracyName(c.accuracy))
    }

    if (!c.registered) {
        return if (s.type in BODY) area.result(e.key, title, Status.INFO, txt(R.string.sensors_s_not_sampled), txt(R.string.sensors_r_body), details)
        else area.result(e.key, title, Status.FAIL, txt(R.string.sensors_s_register_failed), txt(R.string.sensors_r_register_failed), details)
    }
    if (st.n == 0) {
        return if (e.mode == Mode.CONTINUOUS) area.result(e.key, title, Status.FAIL, txt(R.string.sensors_s_no_events), txt(R.string.sensors_r_no_events), details)
        else area.result(e.key, title, Status.INFO, txt(R.string.sensors_s_silent), txt(R.string.sensors_r_silent), details)
    }

    val issues = mutableListOf<Pair<Status, Txt>>()
    if (st.badValues > 0) issues += Status.FAIL to txt(R.string.sensors_r_nan, st.badValues)
    if (st.nonMonotonic > 0) issues += Status.WARN to txt(R.string.sensors_r_timestamps, st.nonMonotonic)
    if (st.offsetMs > 1000 || st.offsetMs < -100) issues += Status.WARN to txt(R.string.sensors_r_clock, fmt1(st.offsetMs))
    if (e.mode == Mode.CONTINUOUS && st.n >= 3) {
        val expected = 1e6 / e.autoPeriodUs
        if (st.rateHz < expected * 0.5) issues += Status.WARN to txt(R.string.sensors_r_rate_low, fmt1(st.rateHz), fmt1(expected))
    }
    if (e.type in NOISY && st.n >= 10 && st.allSame && !st.allZero) issues += Status.WARN to txt(R.string.sensors_r_stuck, st.n)

    val v = if (e.info.axes.isNotEmpty() && st.dims < minOf(3, e.info.axes.size)) Verdict(Status.FAIL, null, txt(R.string.sensors_r_dims, st.dims))
    else plausibility(e, st)
    val status = Status.worst(listOf(v.status) + issues.map { it.first }) ?: Status.PASS
    val reasons = (listOfNotNull(v.reason?.let { v.status to it }) + issues)
        .filter { it.first != Status.PASS }.sortedByDescending { it.first.rank }.map { it.second }
    val reason = reasons.take(3).reduceOrNull { a, b -> join(a, b) }
    val summary = v.summary?.let {
        if (e.mode == Mode.CONTINUOUS) join(it, txt(R.string.sensors_s_rate, fmt1(st.rateHz))) else it
    } ?: if (e.mode == Mode.CONTINUOUS) txt(R.string.sensors_s_events_rate, st.n, fmt1(st.rateHz)) else txt(R.string.sensors_s_events, st.n)
    return area.result(e.key, title, status, summary, reason, details)
}

/**
 * Accelerometer and gravity must agree on the direction of gravity. A large angle means the
 * fusion library uses another orientation matrix than the accelerometer driver.
 */
internal fun fusionResult(area: Area, collectors: List<Collector>): TestResult? {
    val acc = collectors.firstOrNull { it.entry.type == Sensor.TYPE_ACCELEROMETER && !it.entry.sensor.isWakeUpSensor }
        ?: collectors.firstOrNull { it.entry.type == Sensor.TYPE_ACCELEROMETER } ?: return null
    val grav = collectors.firstOrNull { it.entry.type == Sensor.TYPE_GRAVITY } ?: return null
    val a = Stats(acc.samples())
    val g = Stats(grav.samples())
    if (a.n < 3 || g.n < 3 || a.dims < 3 || g.dims < 3 || a.magMean < 3 || g.magMean < 3) return null
    if (a.magStd > 1.0) return null
    val dot = (0 until 3).sumOf { a.mean[it] * g.mean[it] }
    val la = sqrt((0 until 3).sumOf { a.mean[it] * a.mean[it] })
    val lg = sqrt((0 until 3).sumOf { g.mean[it] * g.mean[it] })
    val angle = Math.toDegrees(acos((dot / (la * lg)).coerceIn(-1.0, 1.0)))
    val details = listOf(
        d("Accelerometer", (0 until 3).joinToString(" ") { fmt2(a.mean[it]) }),
        d("Gravity", (0 until 3).joinToString(" ") { fmt2(g.mean[it]) }),
        d("Angle", "${fmt1(angle)}°"),
    )
    val title = txt(R.string.sensors_t_fusion)
    return if (angle <= 20) area.result("fusion_frame", title, Status.PASS, txt(R.string.sensors_s_fusion, fmt1(angle)), null, details)
    else area.result("fusion_frame", title, Status.WARN, txt(R.string.sensors_s_fusion, fmt1(angle)), txt(R.string.sensors_r_fusion), details)
}
