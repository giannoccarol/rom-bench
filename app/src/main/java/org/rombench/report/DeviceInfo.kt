package org.rombench.report

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import org.rombench.core.Detail
import org.rombench.core.Sys
import org.rombench.core.d
import java.io.File

/** Build, kernel and ROM facts that head every report. */
object DeviceInfo {

    fun romVersion(): String = listOf(
        "ro.lineage.display.version", "ro.lineage.version", "ro.modversion",
        "ro.cm.display.version", "ro.build.display.id",
    ).firstNotNullOfOrNull { Sys.prop(it).takeIf { v -> v.isNotBlank() } } ?: Build.DISPLAY

    fun kernel(): String = Sys.line("/proc/version") ?: (System.getProperty("os.version") ?: "")

    /** Short kernel identification: release plus build number, e.g. "3.10.108 #44". */
    fun kernelShort(): String {
        val v = kernel()
        val rel = System.getProperty("os.version") ?: ""
        val num = Regex("""#(\d+)""").find(v)?.groupValues?.get(1)
        return if (num != null) "$rel #$num" else rel
    }

    fun installed(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun hasSu(): Boolean = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/system_ext/bin/su")
        .any { File(it).exists() }

    fun uptime(): String {
        val s = SystemClock.elapsedRealtime() / 1000
        val awake = SystemClock.uptimeMillis() / 1000
        fun f(x: Long) = "%dh %02dm".format(x / 3600, (x % 3600) / 60)
        return "${f(s)} (awake ${f(awake)})"
    }

    fun collect(ctx: Context): List<Detail> {
        val p = Sys::prop
        val out = mutableListOf(
            d("Manufacturer", Build.MANUFACTURER),
            d("Model", Build.MODEL),
            d("Device", Build.DEVICE),
            d("Product", Build.PRODUCT),
            d("Board / platform", "${Build.BOARD} / ${p("ro.board.platform")}"),
            d("Hardware", Build.HARDWARE),
        )
        if (Build.VERSION.SDK_INT >= 31) out += d("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        out += listOf(
            d("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
            d("ROM", romVersion()),
            d("Build", "${Build.ID} ${Build.TYPE} ${Build.TAGS}"),
            d("Build date", p("ro.build.date")),
            d("Fingerprint", Build.FINGERPRINT),
            d("Security patch", Build.VERSION.SECURITY_PATCH),
            d("Vendor security patch", p("ro.vendor.build.security_patch")),
            d("Kernel", kernel()),
            d("SELinux", Sys.selinux()),
            d("Baseband", Build.getRadioVersion()),
            d("Bootloader", Build.BOOTLOADER),
            d("First API level", p("ro.product.first_api_level")),
            d("Treble / VNDK", "${p("ro.treble.enabled").ifBlank { "false" }} / ${p("ro.vndk.version")}"),
            d("Verified boot", "${p("ro.boot.verifiedbootstate")} (flash.locked=${p("ro.boot.flash.locked")})"),
            d("Encryption", "${p("ro.crypto.state")} ${p("ro.crypto.type")}".trim()),
            d("ABIs", Build.SUPPORTED_ABIS.joinToString()),
            d("Google Play services", if (installed(ctx, "com.google.android.gms")) "installed" else "absent"),
            d("su binary", if (hasSu()) "present" else "absent"),
            d("Uptime", uptime()),
            d("Serial", p("ro.serialno").ifBlank { p("ro.boot.serialno") }, sensitive = true),
        )
        return out
    }
}
