package org.rombench.report

import android.content.Context
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import org.rombench.BuildConfig
import org.rombench.core.Areas
import org.rombench.core.Perms
import org.rombench.core.Settings
import org.rombench.core.Status
import org.rombench.core.Sys

/**
 * Report for AI agents and scripts (`rombench-agent-report/1`): the results plus the context an
 * agent needs to reason about them — problems sorted by severity with probable subsystems and
 * next commands, init service states, what the app could not read, and Android logs when the
 * app holds READ_LOGS. Identifiers are redacted in logs too when the redaction setting is on.
 */
object AgentReport {
    const val SCHEMA = "rombench-agent-report/1"
    private const val MAX_LINE = 400

    /** Log tags and words that belong to each area, used to attach relevant log lines. */
    private val AREA_LOG = mapOf(
        "wifi" to "wlan|wifi|wpa_supplicant|wificond|cnss|wcnss|ath10k|qca6174|hostapd|dhcp|netd",
        "bluetooth" to "bluetooth|bt_|btif|bta_|hci|a2dp|bthal",
        "telephony" to "ril|qcril|qmi|ims|telephony|phone|netmgr|rmnet|modem|sim|uim|nas|dsi_|data_call|mpss|subsys",
        "nfc" to "nfc|nxp|pn5|nq-nci|nci",
        "gnss" to "gnss|gps|loc_|location|xtra|izat|lowi",
        "audio" to "audio|alsa|tinyalsa|q6asm|adsp|msm-pcm|tas2|wcd|slimbus|mixer|acdb|voice",
        "mic" to "audio|alsa|audiorecord|q6asm|adsp|wcd|slimbus|mixer|acdb|input_source",
        "camera" to "camera|cam_|mm-camera|qcamera|csiphy|csid|vfe|ispif|sensor_init|cci|imx|ov[0-9]",
        "display" to "surfaceflinger|hwcomposer|hwc|mdss|mdp|gralloc|composer|vsync|panel|backlight",
        "buttons" to "input|keylayout|gpio-keys|vibrat|leds|lights",
        "sensors" to "sensor|ssc|sns|accel|gyro|magnet|proximity|light",
        "battery" to "health|battery|charger|fg_|qpnp|smbcharger|power_supply|aicl",
        "usb" to "usb|dwc3|typec|otg|mtp|adbd|gadget",
        "suspend" to "suspend|wakelock|wakeup|pm_|resume|deviceidle|alarm",
        "cpu" to "thermal|cpufreq|cpu[0-9]|throttl|msm_thermal|core_ctl|hotplug|kgsl|adreno",
        "memory" to "lmkd|lowmemory|oom|zram|kswapd|mmc|ufs|f2fs|ext4|vold",
        "device" to "selinux|avc|init|vintf|hidl|hwservicemanager|servicemanager",
    )

    /** What to look at next, per area: commands an agent (or developer) can run with adb. */
    private val AREA_NEXT = mapOf(
        "wifi" to listOf("adb logcat -b all | grep -iE 'wlan|cnss|wpa_supplicant|wificond'", "adb shell dmesg | grep -iE 'wlan|cnss|ath10k|pcie'", "adb shell ls -l /vendor/firmware /vendor/firmware/wlan /vendor/etc/wifi", "adb shell getprop | grep -iE 'wifi|wlan'"),
        "bluetooth" to listOf("adb logcat -b all | grep -iE 'bt_|bluetooth|hci'", "adb shell dumpsys bluetooth_manager", "adb shell ls -l /vendor/firmware | grep -iE 'nvm|rampatch|bt'"),
        "telephony" to listOf("adb logcat -b radio -v threadtime", "adb shell dumpsys telephony.registry", "adb shell dumpsys carrier_config", "adb shell getprop | grep -iE 'ril|radio|ims|baseband'", "adb shell dmesg | grep -iE 'modem|mba|pil|subsys|smd|glink'"),
        "nfc" to listOf("adb logcat | grep -iE 'nfc|nci'", "adb shell dmesg | grep -iE 'nfc|nq|pn5'", "adb shell getprop | grep -i nfc"),
        "gnss" to listOf("adb logcat | grep -iE 'gnss|loc_|gps|xtra'", "adb shell dumpsys location", "adb shell cat /vendor/etc/gps.conf"),
        "audio" to listOf("adb logcat | grep -iE 'audio_hw|audioflinger|q6asm|msm-pcm|tinyalsa'", "adb shell dumpsys media.audio_flinger", "adb shell dumpsys media.audio_policy", "adb shell dmesg | grep -iE 'adsp|q6|wcd|slim|asoc'", "adb shell ls /vendor/etc | grep -iE 'mixer_paths|audio_policy'"),
        "mic" to listOf("adb logcat | grep -iE 'audiorecord|audio_hw|input'", "adb shell dumpsys media.audio_flinger", "adb shell dmesg | grep -iE 'wcd|adc|dmic|slim'"),
        "camera" to listOf("adb logcat | grep -iE 'camera|mm-camera|qcamera|cameraprovider'", "adb shell dumpsys media.camera", "adb shell dmesg | grep -iE 'cam|csiphy|csid|vfe|cci|msm_sensor'"),
        "display" to listOf("adb shell dumpsys SurfaceFlinger", "adb shell dumpsys display", "adb logcat | grep -iE 'hwcomposer|surfaceflinger|gralloc'"),
        "buttons" to listOf("adb shell getevent -lp", "adb shell dumpsys input", "adb shell ls /sys/class/leds"),
        "sensors" to listOf("adb shell dumpsys sensorservice", "adb logcat | grep -iE 'sensor|sns'"),
        "battery" to listOf("adb shell dumpsys battery", "adb shell 'for f in /sys/class/power_supply/*/uevent; do echo \$f; cat \$f; done'", "adb shell dmesg | grep -iE 'charger|fg|aicl|smb'"),
        "usb" to listOf("adb shell dumpsys usb", "adb shell getprop | grep -i usb", "adb shell dmesg | grep -iE 'usb|dwc3|typec'"),
        "suspend" to listOf("adb shell dumpsys power", "adb shell dumpsys deviceidle", "adb shell cat /sys/power/wakeup_count /sys/power/state", "adb shell dmesg | grep -iE 'suspend|resume|wakeup'"),
        "cpu" to listOf("adb shell cat /sys/devices/system/cpu/online", "adb shell 'cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq'", "adb shell 'for z in /sys/class/thermal/thermal_zone*; do echo \$(cat \$z/type) \$(cat \$z/temp); done'", "adb shell dumpsys thermalservice"),
        "memory" to listOf("adb shell cat /proc/meminfo", "adb shell getprop | grep ro.lmk", "adb logcat | grep -i lmkd", "adb shell cat /proc/swaps"),
        "device" to listOf("adb shell getprop", "adb shell 'dmesg | grep avc'", "adb logcat -b all | grep 'avc: denied'"),
    )

    // ---- Redaction -------------------------------------------------------------------------

    private val MAC = Regex("""\b([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b""")
    private val LONG_NUMBER = Regex("""(?<![\w.])\d{14,20}(?![\w.])""")
    private val PHONE = Regex("""\+\d{8,15}\b""")
    private val SSID = Regex("""(?i)(\bssid\b["']?\s*[=:]\s*["']?)([^"',\s]+)""")
    private val COORD = Regex("""(?i)\b(lat|lon|lng|latitude|longitude)(\s*[=:]\s*)-?\d{1,3}\.\d+""")
    private val ICCID = Regex("""(?i)(iccid\s*[=:]\s*)\w+""")

    fun redact(s: String): String = s
        .replace(MAC) { m -> if (m.value.replace(Regex("[:-]"), "").all { it == '0' }) m.value else "<mac>" }
        .replace(LONG_NUMBER, "<number>")
        .replace(PHONE, "<phone>")
        .replace(SSID) { m -> "${m.groupValues[1]}<ssid>" }
        .replace(COORD) { m -> "${m.groupValues[1]}${m.groupValues[2]}<coord>" }
        .replace(ICCID) { m -> "${m.groupValues[1]}<iccid>" }

    // ---- Logs ------------------------------------------------------------------------------

    data class Logs(val available: Boolean, val note: String, val all: List<String>, val crash: List<String>, val kernel: List<String>, val radio: List<String>)

    private fun logcat(vararg args: String, timeoutMs: Long = 15_000): List<String> =
        Sys.exec("logcat", "-d", "-v", "threadtime", *args, timeoutMs = timeoutMs).out.lines().filter { it.isNotBlank() && !it.startsWith("--------- beginning of") }

    /** Pid column of a threadtime line ("MM-DD HH:MM:SS.mmm  PID  TID L TAG: msg"). */
    private fun pidOf(line: String): Int? = line.split(Regex("\\s+")).getOrNull(2)?.toIntOrNull()

    fun collectLogs(ctx: Context): Logs {
        if (!Perms.hasReadLogs(ctx)) {
            return Logs(false, "READ_LOGS not granted: run `adb shell pm grant ${ctx.packageName} android.permission.READ_LOGS` (tools/autotest.sh does it) and run again.", emptyList(), emptyList(), emptyList(), emptyList())
        }
        // On Android 13+ the first call waits for the "allow access to all device logs" answer.
        val all = logcat("-b", "all", "-t", "20000", timeoutMs = if (Build.VERSION.SDK_INT >= 33) 45_000 else 15_000)
        val mine = Process.myPid()
        val others = all.count { pidOf(it).let { p -> p != null && p != mine } }
        if (others == 0) {
            return Logs(
                false,
                "READ_LOGS is granted but only this app's own lines were returned: on Android 13 and later the system asks the user to allow access to all device logs; tap Allow on the phone and run again.",
                emptyList(), emptyList(), emptyList(), emptyList(),
            )
        }
        return Logs(
            true, "Logs read with READ_LOGS (logcat -b all, last 20000 lines).", all,
            logcat("-b", "crash", "-t", "400"),
            logcat("-b", "kernel", "-t", "800"),
            logcat("-b", "radio", "-t", "3000"),
        )
    }

    private val AVC_PERM = Regex("""denied\s+\{([^}]*)\}""")
    private val AVC_COMM = Regex("""comm="([^"]*)"""")
    private val AVC_NAME = Regex("""name="([^"]*)"""")
    private val AVC_S = Regex("""scontext=(\S+)""")
    private val AVC_T = Regex("""tcontext=(\S+)""")
    private val AVC_C = Regex("""tclass=(\S+)""")

    /** SELinux denials grouped by (process, permission, target, class), most frequent first. */
    private fun selinuxDenials(lines: List<String>, clean: (String) -> String): JSONArray {
        data class K(val comm: String, val perm: String, val name: String, val s: String, val t: String, val c: String)
        val counts = linkedMapOf<K, Int>()
        val permissive = mutableMapOf<K, Boolean>()
        fun Regex.g(l: String) = find(l)?.groupValues?.get(1) ?: ""
        lines.filter { it.contains("avc:") && it.contains("denied") }.forEach { l ->
            val perm = AVC_PERM.g(l).trim(); val s = AVC_S.g(l); val t = AVC_T.g(l)
            if (perm.isEmpty() || s.isEmpty() || t.isEmpty()) return@forEach
            val k = K(AVC_COMM.g(l), perm, AVC_NAME.g(l), s.replace(Regex(":s0.*$"), ""), t.replace(Regex(":s0.*$"), ""), AVC_C.g(l))
            counts[k] = (counts[k] ?: 0) + 1
            if (l.contains("permissive=1")) permissive[k] = true
        }
        return JSONArray().also { arr ->
            counts.entries.sortedByDescending { it.value }.take(120).forEach { (k, n) ->
                arr.put(JSONObject().apply {
                    put("count", n); put("comm", k.comm); put("perm", k.perm); put("name", clean(k.name))
                    put("scontext", k.s); put("tcontext", k.t); put("tclass", k.c)
                    put("permissive", permissive[k] == true)
                    put("allowRule", "allow ${k.s.split(':').getOrNull(2) ?: k.s} ${k.t.split(':').getOrNull(2) ?: k.t}:${k.c} { ${k.perm} };")
                })
            }
        }
    }

    // ---- Build ----------------------------------------------------------------------------

    private val PROP_PREFIXES = listOf(
        "init.svc.", "ro.build.", "ro.product.", "ro.vendor.build.", "ro.hardware", "ro.board.", "ro.boot.hardware", "ro.boot.verifiedbootstate",
        "ro.treble.", "ro.vndk.", "ro.lineage.", "ro.lmk.", "ro.config.", "ro.sf.", "debug.sf.", "ro.telephony.", "persist.dbg.", "gsm.version.",
        "ro.baseband", "sys.usb.", "persist.sys.usb.", "wifi.", "ro.bluetooth.", "ro.zygote", "dalvik.vm.heap", "ro.kernel.", "sys.boot", "ro.crypto.",
    )

    fun build(ctx: Context, report: Report): JSONObject {
        val redact = Settings.redact(ctx)
        val clean: (String) -> String = { s -> (if (redact) redact(s) else s).let { if (it.length > MAX_LINE) it.take(MAX_LINE) + "…" else it } }
        val logs = collectLogs(ctx)
        val items = report.areas.flatMap { a -> a.results.map { a to it } }
        val counts = report.counts
        val worst = Status.worst(items.map { it.second.status })
        val props = Sys.allProps()
        val services = props.filterKeys { it.startsWith("init.svc.") }.mapKeys { it.key.removePrefix("init.svc.") }

        fun resultJson(areaId: String, r: Report.Item) = JSONObject().apply {
            put("key", r.key); put("area", areaId); put("status", r.status.name); put("kind", r.kind)
            put("title", r.title); put("summary", r.summary)
            r.reason?.let { put("reason", it) }
            put("details", JSONObject().also { o -> r.details.forEach { o.put(it.label, it.value) } })
        }

        return JSONObject().apply {
            put("schema", SCHEMA)
            put("generated", Report.isoDate(report.generated))
            put("app", JSONObject().put("name", "ROM Bench").put("version", BuildConfig.VERSION_NAME).put("package", ctx.packageName))
            put(
                "readme",
                "Hardware test results of a custom Android ROM, collected on the phone by ROM Bench. Status meanings: FAIL = broken, " +
                    "WARN = suspicious or degraded, PASS = works, INFO = fact only, SKIP = not run (see reason). 'kind': auto = measured by the app, " +
                    "guided = answered by the tester. 'problems' lists FAIL and WARN first with related log lines and adb commands to investigate. " +
                    "Values marked <redacted>, <mac>, <number>, <ssid> were hidden for privacy. Paths under debugfs are never read by the app.",
            )
            put("device", JSONObject().also { o -> report.device.forEach { o.put(it.label, it.value) } })
            put("verdict", JSONObject().apply {
                put("worst", worst?.name ?: JSONObject.NULL)
                put("counts", JSONObject().also { o -> Status.entries.forEach { s -> o.put(s.name, counts[s] ?: 0) } })
                put("areasWithProblems", JSONArray(report.areas.filter { it.status == Status.FAIL || it.status == Status.WARN }.map { it.id }))
                put("areasNotTested", JSONArray(Areas.all.map { it.id }.filter { id -> report.areas.none { it.id == id } }))
            })
            put("environment", JSONObject().apply {
                put("sdk", Build.VERSION.SDK_INT)
                put("selinux", Sys.selinux())
                put("readLogs", Perms.hasReadLogs(ctx)); put("dump", Perms.hasDump(ctx))
                put("missingRuntimePermissions", JSONArray(Perms.missing(ctx, Areas.all.flatMap { it.permissions }).map { it.name }))
                put("unreadablePaths", JSONArray(listOf("/proc/version", "/sys/class/net", "/sys/class/thermal", "/sys/class/power_supply", "/sys/class/leds", "/sys/class/backlight", "/sys/fs/selinux/enforce", "/proc/cmdline")
                    .filter { p -> Sys.exists(p) && Sys.read(p, 64) == null && Sys.list(p).isEmpty() }))
                put("note", "Unreadable paths and SKIP results usually mean SELinux hides them from apps, not that the hardware is missing.")
            })
            put("problems", JSONArray().also { arr ->
                items.filter { it.second.status == Status.FAIL || it.second.status == Status.WARN }
                    .sortedBy { if (it.second.status == Status.FAIL) 0 else 1 }
                    .forEach { (a, r) ->
                        val j = resultJson(a.id, r)
                        j.put("areaTitle", a.title)
                        AREA_NEXT[a.id]?.let { j.put("nextCommands", JSONArray(it)) }
                        if (logs.available) {
                            val re = AREA_LOG[a.id]?.let { Regex(it, RegexOption.IGNORE_CASE) }
                            if (re != null) {
                                val related = logs.all.filter { l -> re.containsMatchIn(l) && Regex("""\s[EWF]\s""").containsMatchIn(l) }.takeLast(40)
                                j.put("relatedLogs", JSONArray(related.map(clean)))
                            }
                        }
                        arr.put(j)
                    }
            })
            put("services", JSONObject().apply {
                put("restarting", JSONArray(services.filterValues { it == "restarting" }.keys))
                put("stopped", JSONArray(services.filterValues { it == "stopped" }.keys))
                put("all", JSONObject().also { o -> services.forEach { (k, v) -> o.put(k, v) } })
            })
            put("areas", JSONArray().also { arr ->
                report.areas.forEach { a ->
                    arr.put(JSONObject().apply {
                        put("id", a.id); put("title", a.title); put("status", a.status?.name ?: JSONObject.NULL)
                        put("results", JSONArray().also { ra -> a.results.forEach { ra.put(resultJson(a.id, it)) } })
                    })
                }
            })
            put("logs", JSONObject().apply {
                put("available", logs.available)
                put("note", logs.note)
                if (logs.available) {
                    put("crash", JSONArray(logs.crash.takeLast(300).map(clean)))
                    put("errors", JSONArray(logs.all.filter { Regex("""\s[EF]\s""").containsMatchIn(it) }.takeLast(400).map(clean)))
                    put("radio", JSONArray(logs.radio.filter { Regex("""\s[EWF]\s""").containsMatchIn(it) }.takeLast(200).map(clean)))
                    put("kernel", JSONArray(logs.kernel.takeLast(400).map(clean)))
                    put("anrAndCrashEvents", JSONArray(logs.all.filter { Regex("am_crash|am_anr|am_proc_died|FATAL EXCEPTION|Fatal signal|tombstone").containsMatchIn(it) }.takeLast(80).map(clean)))
                    // Denials caused by ROM Bench probing sysfs are expected and would mislead an agent.
                    val mine = Process.myPid().toString()
                    val (own, rom) = (logs.all + logs.kernel).filter { it.contains("avc:") }
                        .partition { it.contains("app=${ctx.packageName}") || it.split(Regex("\\s+")).getOrNull(2) == mine }
                    put("selinuxDenials", selinuxDenials(rom, clean))
                    put("selinuxDenialsCausedByRomBench", own.size)
                }
            })
            put("properties", JSONObject().also { o ->
                props.filterKeys { k -> PROP_PREFIXES.any { k.startsWith(it) } && Report.SENSITIVE_PROPS.none { k.contains(it) } }
                    .forEach { (k, v) -> o.put(k, clean(v)) }
            })
        }
    }
}
