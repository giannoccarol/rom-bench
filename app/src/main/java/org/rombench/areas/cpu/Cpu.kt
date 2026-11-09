package org.rombench.areas.cpu

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import org.rombench.core.Sys
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val CPU = "/sys/devices/system/cpu"

data class Core(
    val cpu: Int,
    val online: Boolean,
    val cur: Long?,
    val scalingMin: Long?,
    val scalingMax: Long?,
    val hwMin: Long?,
    val hwMax: Long?,
    val governor: String?,
)

data class Policy(
    val name: String,
    val cpus: List<Int>,
    val hwMin: Long?, val hwMax: Long?,
    val scalingMin: Long?, val scalingMax: Long?,
    val governor: String?,
    val governors: String?,
    val frequencies: String?,
)

object CpuInfo {
    /** Parses "0-3,6" into [0,1,2,3,6]. */
    fun range(text: String?): List<Int> = text?.trim()?.split(',')?.flatMap { part ->
        val p = part.trim()
        if (p.isEmpty()) emptyList()
        else if ('-' in p) p.split('-').let { r ->
            val a = r.getOrNull(0)?.trim()?.toIntOrNull(); val b = r.getOrNull(1)?.trim()?.toIntOrNull()
            if (a != null && b != null && b >= a) (a..b).toList() else emptyList()
        }
        else listOfNotNull(p.toIntOrNull())
    }?.distinct()?.sorted() ?: emptyList()

    fun possible() = range(Sys.line("$CPU/possible")).ifEmpty { (0 until Runtime.getRuntime().availableProcessors()).toList() }
    fun present() = range(Sys.line("$CPU/present"))
    fun online() = range(Sys.line("$CPU/online"))

    fun cores(): List<Core> {
        val online = online().toSet()
        return possible().map { c ->
            val f = "$CPU/cpu$c/cpufreq"
            val on = if (online.isNotEmpty()) c in online else (Sys.line("$CPU/cpu$c/online") ?: "1") == "1"
            Core(
                c, on,
                if (on) Sys.long("$f/scaling_cur_freq") ?: Sys.long("$f/cpuinfo_cur_freq") else null,
                Sys.long("$f/scaling_min_freq"), Sys.long("$f/scaling_max_freq"),
                Sys.long("$f/cpuinfo_min_freq"), Sys.long("$f/cpuinfo_max_freq"),
                Sys.line("$f/scaling_governor"),
            )
        }
    }

    fun policies(): List<Policy> {
        val dir = "$CPU/cpufreq"
        val names = Sys.list(dir).filter { it.startsWith("policy") }
        if (names.isEmpty()) {
            // Old kernels without policy directories: group cores by related_cpus of each online core.
            return possible().mapNotNull { c ->
                val f = "$CPU/cpu$c/cpufreq"
                if (!Sys.exists(f)) return@mapNotNull null
                val related = range(Sys.line("$f/related_cpus") ?: Sys.line("$f/affected_cpus") ?: "$c")
                if (related.firstOrNull() != c && related.isNotEmpty()) return@mapNotNull null
                policyAt("cpu$c", f, related)
            }
        }
        return names.map { n -> policyAt(n, "$dir/$n", range(Sys.line("$dir/$n/related_cpus") ?: Sys.line("$dir/$n/affected_cpus"))) }
    }

    private fun policyAt(name: String, f: String, cpus: List<Int>) = Policy(
        name, cpus,
        Sys.long("$f/cpuinfo_min_freq"), Sys.long("$f/cpuinfo_max_freq"),
        Sys.long("$f/scaling_min_freq"), Sys.long("$f/scaling_max_freq"),
        Sys.line("$f/scaling_governor"), Sys.line("$f/scaling_available_governors"),
        Sys.line("$f/scaling_available_frequencies"),
    )

    /** "Hardware" and per-core "CPU part" from /proc/cpuinfo. */
    fun cpuinfo(): Pair<Map<String, String>, Map<Int, String>> {
        val text = Sys.read("/proc/cpuinfo") ?: return emptyMap<String, String>() to emptyMap()
        val global = mutableMapOf<String, String>()
        val parts = mutableMapOf<Int, String>()
        var cur = -1
        text.lineSequence().forEach { l ->
            val i = l.indexOf(':'); if (i < 0) return@forEach
            val k = l.substring(0, i).trim(); val v = l.substring(i + 1).trim()
            when (k) {
                "processor" -> cur = v.toIntOrNull() ?: cur
                "CPU part" -> if (cur >= 0) parts[cur] = v else global[k] = v
                "Hardware", "model name", "Processor", "CPU implementer", "Features" -> global.putIfAbsent(k, v)
            }
        }
        return global to parts
    }

    fun partName(part: String?): String? = when (part?.lowercase()?.removePrefix("0x")?.trimStart('0')) {
        "d03" -> "Cortex-A53"; "d04" -> "Cortex-A35"; "d05" -> "Cortex-A55"; "d07" -> "Cortex-A57"
        "d08" -> "Cortex-A72"; "d09" -> "Cortex-A73"; "d0a" -> "Cortex-A75"; "d0b" -> "Cortex-A76"
        "d0d" -> "Cortex-A77"; "d41" -> "Cortex-A78"; "d44" -> "Cortex-X1"; "d46" -> "Cortex-A510"; "d47" -> "Cortex-A710"
        "d4d" -> "Cortex-A715"; "d4e" -> "Cortex-X3"; "d80" -> "Cortex-A520"; "d81" -> "Cortex-A720"
        "6f" -> "Krait"; "211" -> "Kryo"; "205" -> "Kryo"; "800" -> "Kryo 2xx Gold"; "801" -> "Kryo 2xx Silver"
        "802" -> "Kryo 3xx Gold"; "803" -> "Kryo 3xx Silver"; "804" -> "Kryo 4xx Gold"; "805" -> "Kryo 4xx Silver"
        "c07" -> "Cortex-A7"; "c09" -> "Cortex-A9"; "c0f" -> "Cortex-A15"
        else -> null
    }
}

fun mhz(khz: Long?) = khz?.let { "${it / 1000} MHz" } ?: "—"

data class Zone(val name: String, val type: String, val tempC: Double?)
data class Cooling(val name: String, val type: String, val cur: Long?, val max: Long?)

object Thermal {
    private const val ROOT = "/sys/class/thermal"

    /** Thermal zones; the unit (m°C or °C) is guessed from the magnitude. */
    fun zones(): List<Zone> = Sys.list(ROOT).filter { it.startsWith("thermal_zone") }
        .sortedBy { it.removePrefix("thermal_zone").toIntOrNull() ?: 0 }
        .map { z ->
            val raw = Sys.long("$ROOT/$z/temp")
            Zone(z, Sys.line("$ROOT/$z/type") ?: "?", raw?.let { if (it > 1000 || it < -1000) it / 1000.0 else it.toDouble() })
        }

    fun cooling(): List<Cooling> = Sys.list(ROOT).filter { it.startsWith("cooling_device") }
        .map { c -> Cooling(c, Sys.line("$ROOT/$c/type") ?: "?", Sys.long("$ROOT/$c/cur_state"), Sys.long("$ROOT/$c/max_state")) }
}

data class GlInfo(val vendor: String, val renderer: String, val version: String, val extensions: Int)

object Gpu {
    private val KGSL = "/sys/class/kgsl/kgsl-3d0"

    /** GPU facts from kgsl (Adreno), Mali or generic devfreq nodes. */
    fun sysfs(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        if (Sys.exists(KGSL)) {
            listOf("gpu_model", "gpuclk", "max_gpuclk", "devfreq/cur_freq", "devfreq/min_freq", "devfreq/max_freq", "devfreq/governor", "gpu_busy_percentage", "gpubusy", "thermal_pwrlevel", "default_pwrlevel", "min_pwrlevel", "max_pwrlevel", "temp")
                .forEach { a -> Sys.line("$KGSL/$a")?.let { out["kgsl/$a"] = it } }
        }
        val mali = "/sys/class/misc/mali0/device"
        if (Sys.exists(mali)) listOf("gpuinfo", "clock", "dvfs_period", "utilization").forEach { a -> Sys.line("$mali/$a")?.let { out["mali/$a"] = it } }
        Sys.list("/sys/class/devfreq").forEach { dv ->
            val g = Sys.line("/sys/class/devfreq/$dv/governor") ?: return@forEach
            out["devfreq/$dv"] = "${Sys.line("/sys/class/devfreq/$dv/cur_freq") ?: "?"} ($g)"
        }
        return out
    }

    fun curFreqHz(): Long? = Sys.long("$KGSL/devfreq/cur_freq") ?: Sys.long("$KGSL/gpuclk")
    fun busyPercent(): Int? = Sys.line("$KGSL/gpu_busy_percentage")?.filter { it.isDigit() }?.toIntOrNull()
        ?: Sys.line("$KGSL/gpubusy")?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 2 && it[1] > 0 }?.let { (it[0] * 100 / it[1]).toInt() }

    /** Strings of the GLES driver from an off-screen EGL context; null if EGL fails. */
    fun gl(): GlInfo? {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (dpy == EGL14.EGL_NO_DISPLAY) return null
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return null
        try {
            val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0) || n[0] == 0) return null
            val ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) return null
            val surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            try {
                EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
                val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
                return GlInfo(
                    GLES20.glGetString(GLES20.GL_VENDOR) ?: "?", GLES20.glGetString(GLES20.GL_RENDERER) ?: "?",
                    GLES20.glGetString(GLES20.GL_VERSION) ?: "?", ext.split(' ').count { it.isNotBlank() },
                )
            } finally {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(dpy, surf)
                EGL14.eglDestroyContext(dpy, ctx)
            }
        } finally {
            EGL14.eglTerminate(dpy)
        }
    }

    fun isSoftware(renderer: String) = listOf("swiftshader", "llvmpipe", "softpipe", "software").any { renderer.lowercase().contains(it) }
}

/** Busy threads on every core until [stop]; used by the load checks and the stress test. */
class CpuLoad(threads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(CpuInfo.possible().size)) {
    private val running = AtomicBoolean(true)

    @Volatile var sink = 0.0

    private val workers = List(threads) { i ->
        thread(name = "rombench-load-$i", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            var x = 1.0 + i
            while (running.get()) {
                for (k in 0 until 50_000) x = x * 1.0000001 + 0.5 / x
            }
            sink = x
        }
    }

    fun stop() {
        running.set(false)
        workers.forEach { it.join(200) }
    }
}
