package org.rombench.areas.nfc

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import org.rombench.R
import org.rombench.core.Detail
import org.rombench.core.Status
import org.rombench.core.Sys
import org.rombench.core.TestResult
import org.rombench.core.d
import org.rombench.core.result
import org.rombench.core.txt
import java.io.File

/** What the kernel, init and properties say about an NFC controller, independent of the framework. */
internal data class NfcHal(
    val props: Map<String, String>,
    val services: Map<String, String>,
    val devNodes: List<String>,
    val i2cDrivers: Map<String, List<String>>,
) {
    /** Some evidence of NFC hardware or a HAL on this ROM. */
    val present: Boolean
        get() = devNodes.isNotEmpty() || i2cDrivers.values.any { it.isNotEmpty() } || services.isNotEmpty() ||
            props["ro.hardware.nfc_nci"].orEmpty().isNotEmpty()

    val restarting: List<String> get() = services.filterValues { it == "restarting" }.keys.map { it.removePrefix("init.svc.") }
}

private val DEV_NODES = listOf(
    "/dev/nq-nci", "/dev/pn544", "/dev/pn54x", "/dev/pn547", "/dev/pn548", "/dev/pn551", "/dev/pn553", "/dev/nxpnfc",
    "/dev/st21nfc", "/dev/st54j_se", "/dev/sec-nfc", "/dev/sec-nfc0", "/dev/bcm2079x", "/dev/bcm2079x-i2c", "/dev/nfc0",
)
private val DRIVER_RE = Regex("nfc|nq-|^pn5|^pn6|nxp|st21|st54|bcm2079|sec_nfc|sec-nfc", RegexOption.IGNORE_CASE)

internal fun readNfcHal(): NfcHal {
    val all = Sys.allProps()
    val services = all.filterKeys { it.startsWith("init.svc.") && it.contains("nfc", true) }
    val props = all.filterKeys { !it.startsWith("init.svc.") && !it.startsWith("ro.boottime.") && it.contains("nfc", true) }
        .entries.take(25).associate { it.key to it.value }
    val nodes = DEV_NODES.filter { File(it).exists() }
    val drivers = Sys.list("/sys/bus/i2c/drivers").filter { DRIVER_RE.containsMatchIn(it) }.associateWith { drv ->
        // Bound devices appear as "<bus>-<addr>" links inside the driver directory.
        Sys.list("/sys/bus/i2c/drivers/$drv").filter { Regex("^\\d+-[0-9a-f]{4}$").matches(it) }
    }
    return NfcHal(props, services, nodes, drivers)
}

internal fun nfcAdapter(ctx: Context): NfcAdapter? = runCatching { NfcAdapter.getDefaultAdapter(ctx.applicationContext) }.getOrNull()

private fun yes(b: Boolean?) = when (b) {
    true -> "yes"; false -> "no"; null -> "—"
}

internal fun NfcArea.runChecks(ctx: Context): List<TestResult> {
    val out = mutableListOf<TestResult>()
    val pm = ctx.packageManager
    fun f(name: String) = pm.hasSystemFeature(name)
    val hasNfc = f(PackageManager.FEATURE_NFC)
    val features = linkedMapOf(
        "FEATURE_NFC" to hasNfc,
        "HCE" to f(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION),
        "HCE-F" to f(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF),
        "Off-host UICC" to f(PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC),
        "Off-host eSE" to f(PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE),
        "OMAPI UICC" to f(PackageManager.FEATURE_SE_OMAPI_UICC),
        "OMAPI eSE" to f(PackageManager.FEATURE_SE_OMAPI_ESE),
        "OMAPI SD" to f(PackageManager.FEATURE_SE_OMAPI_SD),
    )
    val hal = readNfcHal()
    val featureDetails = features.map { (k, v) -> d(k, v) }
    val extras = features.filterKeys { it != "FEATURE_NFC" }.filterValues { it }.keys.joinToString().ifEmpty { "—" }

    // ---- feature ----
    val tFeature = txt(R.string.nfc_t_feature)
    out += when {
        hasNfc && !features.getValue("HCE") -> result("feature", tFeature, Status.WARN, txt(R.string.nfc_s_feature, extras), txt(R.string.nfc_r_no_hce), featureDetails)
        hasNfc -> result("feature", tFeature, Status.PASS, txt(R.string.nfc_s_feature, extras), null, featureDetails)
        hal.present -> result("feature", tFeature, Status.WARN, txt(R.string.nfc_s_feature_missing), txt(R.string.nfc_r_feature_missing), featureDetails)
        else -> result("feature", tFeature, Status.SKIP, txt(R.string.nfc_s_none), txt(R.string.nfc_r_none), featureDetails)
    }

    // ---- adapter ----
    val tAdapter = txt(R.string.nfc_t_adapter)
    val a = nfcAdapter(ctx)
    if (a == null) {
        out += when {
            hasNfc -> result("adapter", tAdapter, Status.FAIL, txt(R.string.nfc_s_no_service), txt(R.string.nfc_r_no_service))
            else -> result("adapter", tAdapter, Status.SKIP, txt(R.string.nfc_s_none), txt(R.string.nfc_r_none))
        }
    } else {
        val enabled = runCatching { a.isEnabled }.getOrDefault(false)
        val details = mutableListOf(
            d("Enabled", enabled),
            d("Secure NFC supported", yes(runCatching { a.isSecureNfcSupported }.getOrNull())),
            d("Secure NFC enabled", yes(runCatching { a.isSecureNfcEnabled }.getOrNull())),
        )
        if (Build.VERSION.SDK_INT >= 35) {
            details += d("Observe mode supported", yes(runCatching { a.isObserveModeSupported }.getOrNull()))
            details += d("Reader option supported", yes(runCatching { a.isReaderOptionSupported }.getOrNull()))
            details += d("Reader option enabled", yes(runCatching { a.isReaderOptionEnabled }.getOrNull()))
        }
        if (Build.VERSION.SDK_INT >= 36) {
            details += d("Tag intent app preference", yes(runCatching { a.isTagIntentAppPreferenceSupported }.getOrNull()))
        }
        out += if (enabled) result("adapter", tAdapter, Status.PASS, txt(R.string.nfc_s_on), null, details)
        else result("adapter", tAdapter, Status.WARN, txt(R.string.nfc_s_off), txt(R.string.nfc_r_off), details)
    }

    // ---- HAL / kernel ----
    val tHal = txt(R.string.nfc_t_hal)
    val halDetails = mutableListOf<Detail>()
    hal.services.forEach { (k, v) -> halDetails += d(k, v) }
    hal.props.forEach { (k, v) -> halDetails += d(k, v) }
    halDetails += d("Device nodes", hal.devNodes.joinToString().ifEmpty { "none visible" })
    hal.i2cDrivers.forEach { (drv, devs) -> halDetails += d("i2c driver $drv", devs.joinToString().ifEmpty { "no device bound" }) }
    val boundDriver = hal.i2cDrivers.entries.firstOrNull { it.value.isNotEmpty() }?.key
    val unbound = hal.i2cDrivers.isNotEmpty() && boundDriver == null
    val chip = hal.props["ro.hardware.nfc_nci"]?.takeIf { it.isNotEmpty() } ?: boundDriver ?: hal.devNodes.firstOrNull()?.substringAfterLast('/')
    out += when {
        hal.restarting.isNotEmpty() -> result("hal", tHal, Status.FAIL, txt(R.string.nfc_s_hal_crash, hal.restarting.joinToString()), txt(R.string.nfc_r_hal_crash), halDetails)
        unbound && (hasNfc || a != null) -> result("hal", tHal, Status.WARN, txt(R.string.nfc_s_hal_unbound), txt(R.string.nfc_r_hal_unbound), halDetails)
        hasNfc && a != null && runCatching { a.isEnabled }.getOrDefault(false) ->
            result("hal", tHal, Status.INFO, txt(R.string.nfc_s_hal, chip ?: "—"), null, halDetails)
        hasNfc && !hal.present -> result("hal", tHal, Status.INFO, txt(R.string.nfc_s_hal_hidden), txt(R.string.nfc_r_hal_hidden), halDetails)
        !hasNfc && !hal.present -> result("hal", tHal, Status.SKIP, txt(R.string.nfc_s_none), txt(R.string.nfc_r_none), halDetails)
        else -> result("hal", tHal, Status.INFO, txt(R.string.nfc_s_hal, chip ?: "—"), null, halDetails)
    }
    return out
}
