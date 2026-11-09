package org.rombench.core

import android.annotation.SuppressLint
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/** Small helpers to read /proc, /sys, system properties and run shell tools without root. */
object Sys {
    private const val TAG = "RomBench"

    /**
     * debugfs is never read: on some kernels reading a debugfs node (for example
     * /sys/kernel/debug/shrinker on msm8994) panics the device.
     */
    private fun forbidden(path: String) =
        path.startsWith("/sys/kernel/debug") || path == "/d" || path.startsWith("/d/")

    fun read(path: String, maxBytes: Int = 256 * 1024): String? {
        if (forbidden(path)) return null
        return try {
            File(path).inputStream().use { input ->
                val buf = ByteArray(maxBytes)
                var total = 0
                while (total < maxBytes) {
                    val n = input.read(buf, total, maxBytes - total)
                    if (n <= 0) break
                    total += n
                }
                String(buf, 0, total)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** First line of a file, trimmed; null when unreadable or empty. */
    fun line(path: String): String? = read(path, 4096)?.trim()?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    fun long(path: String): Long? = line(path)?.split(' ', '\t')?.firstOrNull()?.toLongOrNull()

    fun exists(path: String): Boolean = !forbidden(path) && File(path).exists()

    fun list(path: String): List<String> =
        if (forbidden(path)) emptyList() else (File(path).list()?.sorted() ?: emptyList())

    /** key=value pairs of a uevent-style file. */
    fun keyValues(path: String): Map<String, String> =
        read(path)?.lineSequence()?.mapNotNull { l ->
            val i = l.indexOf('=')
            if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
        }?.toMap() ?: emptyMap()

    @SuppressLint("PrivateApi")
    private val propGet = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
    } catch (e: Exception) {
        null
    }

    /** System property through android.os.SystemProperties, falling back to `getprop`. */
    fun prop(name: String): String {
        try {
            val v = propGet?.invoke(null, name) as? String
            if (v != null) return v
        } catch (_: Exception) {
        }
        return exec("getprop", name).out.trim()
    }

    /** Every property readable by the app (SELinux hides some contexts on enforcing ROMs). */
    fun allProps(): Map<String, String> {
        val r = exec("getprop", timeoutMs = 8000)
        val re = Regex("""^\[(.*)]: \[(.*)]$""")
        return r.out.lineSequence().mapNotNull { re.find(it)?.destructured?.let { (k, v) -> k to v } }.toMap(sortedMapOf())
    }

    data class ExecResult(val exit: Int, val out: String, val err: String) {
        val ok get() = exit == 0
    }

    /** Runs a command (no shell) with a timeout; never throws. */
    fun exec(vararg cmd: String, timeoutMs: Long = 5000): ExecResult = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(false).start()
        val outReader = StringBuilder()
        val errSb = StringBuilder()
        // Readers must never throw: destroying the process on timeout interrupts their reads.
        val tOut = Thread { runCatching { p.inputStream.bufferedReader().use { r -> r.forEachLine { outReader.appendLine(it) } } } }
        val tErr = Thread { runCatching { p.errorStream.bufferedReader().use { r -> r.forEachLine { errSb.appendLine(it) } } } }
        tOut.start(); tErr.start()
        val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) p.destroyForcibly()
        tOut.join(1000); tErr.join(1000)
        ExecResult(if (done) p.exitValue() else -1, synchronized(outReader) { outReader.toString() }, errSb.toString())
    } catch (e: Exception) {
        Log.w(TAG, "exec ${cmd.joinToString(" ")} failed: $e")
        ExecResult(-2, "", e.toString())
    }

    /** Runs through /system/bin/sh -c. */
    fun sh(command: String, timeoutMs: Long = 5000) = exec("/system/bin/sh", "-c", command, timeoutMs = timeoutMs)

    /** SELinux mode: "Enforcing", "Permissive", "Disabled" or "Unknown". */
    fun selinux(): String {
        line("/sys/fs/selinux/enforce")?.let { return if (it == "1") "Enforcing" else "Permissive" }
        // android.os.SELinux and getenforce read the same node, so they fail the same way.
        val g = exec("getenforce").out.trim()
        if (g == "Enforcing" || g == "Permissive" || g == "Disabled") return g
        // On a permissive kernel the read above would have succeeded (denials are only logged).
        return if (File("/sys/fs/selinux/enforce").exists()) "Enforcing" else "Unknown"
    }
}

/** Formats bytes with binary units. */
fun fmtBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}

fun fmt1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
fun fmt2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
