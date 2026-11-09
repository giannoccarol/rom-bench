package org.rombench.areas.device

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.Status
import org.rombench.core.Sys
import org.rombench.core.TestResult
import org.rombench.core.d
import org.rombench.core.raw
import org.rombench.core.result
import org.rombench.core.txt
import org.rombench.report.DeviceInfo
import org.rombench.ui.components.DetailsTable
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.SectionCard
import org.rombench.ui.theme.Mono
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object DeviceArea : Area {
    override val id = "device"
    override val title = R.string.device_title
    override val subtitle = R.string.device_subtitle
    override val icon: ImageVector = Icons.Outlined.PhoneAndroid
    override val group = AreaGroup.SYSTEM
    override val autoSeconds = 2

    /** Kernel release as (major, minor), e.g. "3.10.108-g…" → (3, 10). */
    internal fun kernelVersion(release: String): Pair<Int, Int>? =
        Regex("""^(\d+)\.(\d+)""").find(release)?.destructured?.let { (a, b) -> a.toInt() to b.toInt() }

    /** Minimum kernel each Android release supports on new launches (approximate, from the Android docs). */
    internal fun minKernelFor(sdk: Int): Pair<Int, Int> = when {
        sdk >= 36 -> 5 to 15
        sdk >= 35 -> 5 to 10
        sdk >= 33 -> 5 to 4
        sdk >= 31 -> 4 to 19
        else -> 4 to 14
    }

    override suspend fun autoCheck(ctx: Context): List<TestResult> = withContext(Dispatchers.IO) {
        val out = mutableListOf<TestResult>()
        out += result("build", txt(R.string.device_r_build), Status.INFO, raw("${DeviceInfo.romVersion()} · ${Build.TYPE}"), details = DeviceInfo.collect(ctx))

        // SELinux
        val se = Sys.selinux()
        out += when (se) {
            "Enforcing" -> result("selinux", txt(R.string.device_r_selinux), Status.PASS, txt(R.string.device_selinux_enforcing))
            "Permissive" -> result("selinux", txt(R.string.device_r_selinux), Status.WARN, txt(R.string.device_selinux_permissive), txt(R.string.device_selinux_permissive_why))
            else -> result("selinux", txt(R.string.device_r_selinux), Status.INFO, raw(se), txt(R.string.device_selinux_unknown_why))
        }

        // Security patch age
        val patch = Build.VERSION.SECURITY_PATCH
        val age = runCatching { ChronoUnit.MONTHS.between(LocalDate.parse(patch), LocalDate.now()) }.getOrNull()
        out += when {
            age == null -> result("patch", txt(R.string.device_r_patch), Status.WARN, raw(patch.ifBlank { "—" }), txt(R.string.device_patch_unparsable))
            age > 12 -> result("patch", txt(R.string.device_r_patch), Status.WARN, txt(R.string.device_patch_age, patch, age), txt(R.string.device_patch_old_why))
            else -> result("patch", txt(R.string.device_r_patch), Status.PASS, txt(R.string.device_patch_age, patch, age))
        }

        // Kernel
        val rel = System.getProperty("os.version") ?: ""
        val kv = kernelVersion(rel)
        val min = minKernelFor(Build.VERSION.SDK_INT)
        val kDetails = listOf(d("/proc/version", DeviceInfo.kernel()), d("Release", rel), d("Minimum for this Android", "${min.first}.${min.second}"))
        out += if (kv != null && (kv.first < min.first || (kv.first == min.first && kv.second < min.second))) {
            result("kernel", txt(R.string.device_r_kernel), Status.INFO, txt(R.string.device_kernel_legacy, rel, "${min.first}.${min.second}"), txt(R.string.device_kernel_legacy_why), kDetails)
        } else {
            result("kernel", txt(R.string.device_r_kernel), Status.PASS, raw(rel), details = kDetails)
        }

        // Fingerprints: a different vendor fingerprint usually means a mixed or prebuilt vendor.
        val fp = Sys.prop("ro.build.fingerprint").ifBlank { Build.FINGERPRINT }
        val parts = listOf("ro.vendor.build.fingerprint", "ro.system.build.fingerprint", "ro.product.build.fingerprint", "ro.odm.build.fingerprint", "ro.bootimage.build.fingerprint")
            .associateWith { Sys.prop(it) }.filterValues { it.isNotBlank() }
        val differing = parts.filterValues { it != fp }
        out += if (differing.isEmpty()) {
            result("fingerprint", txt(R.string.device_r_fingerprint), Status.PASS, txt(R.string.device_fp_same, parts.size + 1), details = listOf(d("ro.build.fingerprint", fp)))
        } else {
            result(
                "fingerprint", txt(R.string.device_r_fingerprint), Status.INFO, txt(R.string.device_fp_diff, differing.size),
                txt(R.string.device_fp_diff_why), listOf(d("ro.build.fingerprint", fp)) + differing.map { (k, v) -> d(k, v) },
            )
        }

        // Verified boot / build type / Play services
        val vb = Sys.prop("ro.boot.verifiedbootstate")
        out += result(
            "boot", txt(R.string.device_r_boot), Status.INFO,
            txt(R.string.device_boot_summary, Build.TYPE, vb.ifBlank { "—" }),
            if (vb == "orange") txt(R.string.device_boot_orange) else null,
            listOf(d("ro.build.type", Build.TYPE), d("ro.build.tags", Build.TAGS), d("ro.boot.verifiedbootstate", vb), d("ro.boot.flash.locked", Sys.prop("ro.boot.flash.locked")), d("ro.debuggable", Sys.prop("ro.debuggable")), d("ro.adb.secure", Sys.prop("ro.adb.secure"))),
        )
        val gms = DeviceInfo.installed(ctx, "com.google.android.gms")
        val microg = DeviceInfo.installed(ctx, "org.microg.gms.droidguard")
        out += result(
            "gms", txt(R.string.device_r_gms), Status.INFO,
            txt(if (gms) R.string.device_gms_present else R.string.device_gms_absent),
            if (!gms) txt(R.string.device_gms_absent_why) else null,
            listOf(d("com.google.android.gms", if (gms) "installed" else "absent"), d("com.android.vending", if (DeviceInfo.installed(ctx, "com.android.vending")) "installed" else "absent"), d("microG", if (microg) "installed" else "absent")),
        )
        out
    }

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val info by produceState<List<org.rombench.core.Detail>?>(null) { value = withContext(Dispatchers.IO) { DeviceInfo.collect(ctx) } }
        SectionCard(stringResource(R.string.device_sec_build), icon = Icons.Outlined.PhoneAndroid) {
            info?.let { DetailsTable(it) } ?: Hint(stringResource(R.string.loading))
        }
        val kernel by produceState<List<Pair<String, String>>?>(null) {
            value = withContext(Dispatchers.IO) {
                listOfNotNull(
                    "/proc/version" to DeviceInfo.kernel(),
                    Sys.line("/proc/cmdline")?.let { "/proc/cmdline" to it },
                    Sys.read("/proc/config.gz")?.let { "/proc/config.gz" to "present" },
                    "Treble" to Sys.prop("ro.treble.enabled").ifBlank { "false" },
                    "VNDK" to Sys.prop("ro.vndk.version"),
                    "Boot reason" to Sys.prop("ro.boot.bootreason").ifBlank { Sys.prop("sys.boot.reason") },
                    "Hardware (boot)" to Sys.prop("ro.boot.hardware"),
                )
            }
        }
        SectionCard(stringResource(R.string.device_sec_kernel), icon = Icons.Outlined.Memory) {
            kernel?.forEach { (k, v) -> KeyValue(k, v.ifBlank { "—" }) } ?: Hint(stringResource(R.string.loading))
            Hint(stringResource(R.string.device_cmdline_hint))
        }
        SectionCard(stringResource(R.string.device_sec_security), icon = Icons.Outlined.Security) {
            KeyValue("SELinux", Sys.selinux())
            KeyValue("Security patch", Build.VERSION.SECURITY_PATCH)
            KeyValue("Vendor patch", Sys.prop("ro.vendor.build.security_patch").ifBlank { "—" })
            KeyValue("Verified boot", Sys.prop("ro.boot.verifiedbootstate").ifBlank { "—" })
        }
        PropertyBrowser()
    }

    @Composable
    private fun PropertyBrowser() {
        var filter by rememberSaveable { mutableStateOf("") }
        val props by produceState<Map<String, String>?>(null) { value = withContext(Dispatchers.IO) { Sys.allProps() } }
        SectionCard(
            stringResource(R.string.device_sec_props), icon = Icons.Outlined.Search,
            subtitle = props?.let { stringResource(R.string.device_props_count, it.size) },
        ) {
            OutlinedTextField(
                filter, { filter = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.device_props_filter)) }, textStyle = Mono,
            )
            val shown = props?.filter { (k, v) -> filter.isBlank() || k.contains(filter, true) || v.contains(filter, true) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                shown?.entries?.take(MAX_PROPS)?.forEach { (k, v) -> KeyValue(k, v.ifEmpty { "\"\"" }) }
            }
            if (shown != null && shown.size > MAX_PROPS) Hint(stringResource(R.string.device_props_more, shown.size - MAX_PROPS))
        }
    }

    private const val MAX_PROPS = 150
}
