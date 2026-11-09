package org.rombench.areas.usb

import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.rombench.R
import org.rombench.core.Detail
import org.rombench.core.Status
import org.rombench.core.Sys
import org.rombench.core.TestResult
import org.rombench.core.d
import org.rombench.core.result
import org.rombench.core.txt

internal suspend fun UsbArea.runChecks(ctx: Context): List<TestResult> {
    val pm = ctx.packageManager
    var s = withContext(Dispatchers.IO) { UsbData.snapshot(ctx) }
    // A port that is still enumerating, or a config being applied, settles in a second or two.
    val transient = s.port.let { it != null && it.connected && !it.configured } ||
        (s.props["sys.usb.config"] != null && s.props["sys.usb.config"] != s.props["sys.usb.state"])
    if (transient) {
        delay(2000)
        s = withContext(Dispatchers.IO) { UsbData.snapshot(ctx) }
    }
    val hostFeature = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
    val accFeature = pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY)
    return listOf(
        portCheck(s),
        powerCheck(s),
        typecCheck(s),
        hostCheck(s, hostFeature),
        devicesCheck(s, hostFeature),
        storageCheck(s),
        inputCheck(s),
        accessoryCheck(s, accFeature),
    )
}

private fun UsbArea.portCheck(s: UsbSnapshot): TestResult {
    val title = txt(R.string.usb_t_port)
    val p = s.port
    val details = mutableListOf(
        d("connected", p?.connected),
        d("configured", p?.configured),
        d("host_connected", p?.hostConnected),
        d("data unlocked", p?.dataUnlocked),
        d("Functions", p?.functions?.joinToString()?.ifEmpty { "none" }),
        d("android_usb state", s.gadgetState),
        d("UDC", s.udc.joinToString().ifEmpty { null }),
    )
    s.props.forEach { (k, v) -> details += d(k, v) }
    val config = s.props["sys.usb.config"]
    val state = s.props["sys.usb.state"]
    val dataSupply = s.onlineSupplies.firstOrNull { it.isDataPort }
    val fn = p?.functions?.joinToString()?.ifEmpty { "none" } ?: "—"
    return when {
        p == null -> result("port", title, Status.WARN, txt(R.string.usb_s_no_state), txt(R.string.usb_r_no_state), details)
        config != null && state != null && config != state ->
            result("port", title, Status.WARN, txt(R.string.usb_s_config_mismatch, config, state), txt(R.string.usb_r_config_mismatch), details)
        p.connected && !p.configured -> result("port", title, Status.WARN, txt(R.string.usb_s_not_configured), txt(R.string.usb_r_not_configured), details)
        !p.connected && dataSupply != null ->
            result("port", title, Status.WARN, txt(R.string.usb_s_no_data, dataSupply.type ?: "USB"), txt(R.string.usb_r_no_data), details)
        p.connected -> result("port", title, Status.PASS, txt(R.string.usb_s_connected, fn), null, details)
        p.hostConnected -> result("port", title, Status.PASS, txt(R.string.usb_s_host_mode), null, details)
        else -> result("port", title, Status.INFO, txt(R.string.usb_s_idle, fn), txt(R.string.usb_r_idle), details)
    }
}

private fun UsbArea.powerCheck(s: UsbSnapshot): TestResult {
    val title = txt(R.string.usb_t_power)
    val details = mutableListOf<Detail>(d("Battery EXTRA_PLUGGED", UsbData.pluggedName(s.plugged)))
    s.supplies.forEach { ps ->
        val keys = listOf("TYPE", "REAL_TYPE", "USB_TYPE", "ONLINE", "PRESENT", "CURRENT_MAX", "INPUT_CURRENT_MAX", "VOLTAGE_NOW", "VOLTAGE_MAX", "TYPEC_MODE", "PD_ACTIVE")
        val text = keys.mapNotNull { k -> ps.attrs[k]?.let { "${k.lowercase()}=$it" } }.joinToString(" ")
        details += d(ps.name, text.ifEmpty { "(no readable attributes)" })
    }
    if (s.supplies.isEmpty()) {
        return result("power", title, Status.INFO, txt(R.string.usb_s_no_supply), txt(R.string.usb_r_no_supply), details)
    }
    val online = s.onlineSupplies
    val main = online.firstOrNull { it.name.startsWith("usb", true) } ?: online.firstOrNull()
    val plugged = s.plugged
    val pluggedNow = plugged != null && plugged != 0
    val usbPlugged = plugged != null && (plugged and (BatteryManager.BATTERY_PLUGGED_USB or BatteryManager.BATTERY_PLUGGED_AC)) != 0
    return when {
        main == null && usbPlugged -> result("power", title, Status.WARN, txt(R.string.usb_s_power_mismatch), txt(R.string.usb_r_plugged_no_supply), details)
        main == null -> result("power", title, Status.INFO, txt(R.string.usb_s_unplugged), txt(R.string.usb_r_unplugged), details)
        !pluggedNow && plugged != null -> result("power", title, Status.WARN, txt(R.string.usb_s_power_mismatch), txt(R.string.usb_r_supply_not_plugged, main.name), details)
        main.currentMaxMa == 0L -> result("power", title, Status.WARN, txt(R.string.usb_s_power, UsbData.supplySummary(main)), txt(R.string.usb_r_zero_current), details)
        main.voltageV != null && (main.voltageV!! < 4.0 || main.voltageV!! > 12.5) ->
            result("power", title, Status.WARN, txt(R.string.usb_s_power, UsbData.supplySummary(main)), txt(R.string.usb_r_vbus_range, String.format(java.util.Locale.US, "%.2f", main.voltageV)), details)
        else -> result("power", title, Status.PASS, txt(R.string.usb_s_power, UsbData.supplySummary(main)), null, details)
    }
}

private fun UsbArea.typecCheck(s: UsbSnapshot): TestResult {
    val title = txt(R.string.usb_t_typec)
    val details = mutableListOf<Detail>()
    s.typec.forEach { (n, a) -> details += d("typec/$n", a.entries.joinToString(" ") { "${it.key}=${it.value}" }.ifEmpty { "present" }) }
    s.dualRole.forEach { (n, a) -> details += d("dual_role_usb/$n", a.entries.joinToString(" ") { "${it.key}=${it.value}" }) }
    s.supplies.forEach { ps ->
        listOf("TYPEC_MODE", "TYPEC_CC_ORIENTATION", "TYPEC_POWER_ROLE", "TYPEC_SRC_RP", "PD_ACTIVE").forEach { k ->
            ps.attrs[k]?.let { details += d("${ps.name}/${k.lowercase()}", it) }
        }
    }
    val port = s.typec.firstOrNull { !it.first.contains("partner") }
    val partner = s.typec.any { it.first.contains("partner") }
    val dr = s.dualRole.firstOrNull()
    return when {
        port != null -> {
            val a = port.second
            result(
                "typec", title, Status.INFO,
                txt(R.string.usb_s_typec, bracket(a["data_role"]), bracket(a["power_role"]), if (partner) "yes" else "no"), null, details,
            )
        }
        dr != null -> result(
            "typec", title, Status.INFO,
            txt(R.string.usb_s_dual_role, dr.second["mode"] ?: "?", dr.second["data_role"] ?: "?", dr.second["power_role"] ?: "?"), null, details,
        )
        details.isNotEmpty() -> result("typec", title, Status.INFO, txt(R.string.usb_s_typec_charger), txt(R.string.usb_r_typec_private), details)
        else -> result("typec", title, Status.INFO, txt(R.string.usb_s_typec_none), txt(R.string.usb_r_typec_private), details)
    }
}

/** Selected value of a "[host] device" style attribute. */
private fun bracket(v: String?): String =
    v?.let { Regex("\\[(\\w+)]").find(it)?.groupValues?.get(1) ?: it } ?: "?"

private fun UsbArea.hostCheck(s: UsbSnapshot, feature: Boolean): TestResult {
    val title = txt(R.string.usb_t_host)
    val hubs = s.kernelDevices.filter { it.rootHub }
    val hcd = Sys.list("/sys/bus/usb/devices").filter { it.startsWith("usb") }
    val details = listOf(
        d("FEATURE_USB_HOST", feature),
        d("Root hubs", (hcd.ifEmpty { hubs.map { it.node } }).joinToString().ifEmpty { "none" }),
        d("Root hub speeds", hubs.mapNotNull { it.speed }.joinToString().ifEmpty { null }),
        d("Data role", s.typec.firstOrNull()?.second?.get("data_role") ?: s.dualRole.firstOrNull()?.second?.get("data_role")),
    )
    val active = hcd.isNotEmpty() || hubs.isNotEmpty()
    return when {
        !feature && active -> result("host", title, Status.WARN, txt(R.string.usb_s_host_no_feature), txt(R.string.usb_r_host_no_feature), details)
        !feature -> result("host", title, Status.INFO, txt(R.string.usb_s_host_undeclared), txt(R.string.usb_r_host_undeclared), details)
        active -> result("host", title, Status.PASS, txt(R.string.usb_s_host_active, hcd.size.coerceAtLeast(hubs.size)), null, details)
        else -> result("host", title, Status.INFO, txt(R.string.usb_s_host_idle), txt(R.string.usb_r_host_idle), details)
    }
}

private fun UsbArea.devicesCheck(s: UsbSnapshot, feature: Boolean): TestResult {
    val title = txt(R.string.usb_t_devices)
    val kernel = s.kernelDevices.filter { !it.rootHub }
    val details = mutableListOf<Detail>()
    s.apiDevices.forEachIndexed { i, dv -> details += d("UsbManager #${i + 1}", UsbData.describe(dv)) }
    kernel.forEach { k -> details += d("Kernel ${k.node}", UsbData.describe(k)) }
    return when {
        s.apiDevices.isNotEmpty() -> result(
            "devices", title, Status.PASS,
            txt(R.string.usb_s_devices, s.apiDevices.size, s.apiDevices.joinToString { it.productName ?: "%04x:%04x".format(it.vendorId, it.productId) }),
            null, details,
        )
        kernel.isNotEmpty() && feature -> result("devices", title, Status.WARN, txt(R.string.usb_s_kernel_only, kernel.size), txt(R.string.usb_r_kernel_only), details)
        kernel.isNotEmpty() -> result("devices", title, Status.INFO, txt(R.string.usb_s_kernel_only, kernel.size), txt(R.string.usb_r_kernel_only_nofeature), details)
        else -> result("devices", title, Status.INFO, txt(R.string.usb_s_no_devices), txt(R.string.usb_r_no_devices), details)
    }
}

private fun UsbArea.storageCheck(s: UsbSnapshot): TestResult {
    val title = txt(R.string.usb_t_storage)
    val removable = s.volumes.filter { it.removable }
    val mounted = removable.filter { it.state == "mounted" || it.state == "mounted_ro" }
    val details = s.volumes.map { v ->
        d(
            v.description,
            listOfNotNull(v.state, "removable".takeIf { v.removable }, "primary".takeIf { v.primary }, "emulated".takeIf { v.emulated }, v.path).joinToString(" · "),
            sensitive = v.uuid != null,
        )
    } + d("Mass storage attached", s.massStorageAttached)
    val bad = removable.firstOrNull { it.state == "unmountable" || it.state == "bad_removal" || it.state == "removed" }
    return when {
        bad != null -> result("storage", title, Status.WARN, txt(R.string.usb_s_storage_bad, bad.description, bad.state), txt(R.string.usb_r_storage_bad), details)
        s.massStorageAttached && mounted.isEmpty() -> result("storage", title, Status.WARN, txt(R.string.usb_s_storage_unmounted), txt(R.string.usb_r_storage_unmounted), details)
        mounted.isNotEmpty() -> result("storage", title, Status.PASS, txt(R.string.usb_s_storage, mounted.size, mounted.joinToString { it.description }), null, details)
        else -> result("storage", title, Status.INFO, txt(R.string.usb_s_storage_none), txt(R.string.usb_r_storage_none), details)
    }
}

private fun UsbArea.inputCheck(s: UsbSnapshot): TestResult {
    val title = txt(R.string.usb_t_input)
    val usbIds = s.apiDevices.map { it.vendorId to it.productId }.toSet() +
        s.kernelDevices.mapNotNull { k -> k.vid.toIntOrNull(16)?.let { v -> k.pid.toIntOrNull(16)?.let { p -> v to p } } }
    val details = s.inputs.map { i ->
        val bus = if ((i.vid to i.pid) in usbIds) "USB" else "other bus (Bluetooth?)"
        d(i.name, "%s · %04x:%04x · %s".format(i.kinds.joinToString("+"), i.vid, i.pid, bus))
    } + d("HID interface attached", s.hidAttached)
    return when {
        s.inputs.isNotEmpty() -> result(
            "input", title, Status.PASS,
            txt(R.string.usb_s_input, s.inputs.size, s.inputs.flatMap { it.kinds }.distinct().joinToString()), null, details,
        )
        s.hidAttached -> result("input", title, Status.WARN, txt(R.string.usb_s_input_hid_only), txt(R.string.usb_r_input_hid_only), details)
        else -> result("input", title, Status.INFO, txt(R.string.usb_s_input_none), txt(R.string.usb_r_input_none), details)
    }
}

private fun UsbArea.accessoryCheck(s: UsbSnapshot, feature: Boolean): TestResult {
    val title = txt(R.string.usb_t_accessory)
    val details = listOf(
        d("FEATURE_USB_ACCESSORY", feature),
        d("accessory function", s.port?.extras?.get("accessory")),
        d("Accessories", s.accessories.joinToString().ifEmpty { "none" }),
    )
    return when {
        s.accessories.isNotEmpty() -> result("accessory", title, Status.PASS, txt(R.string.usb_s_accessory, s.accessories.joinToString()), null, details)
        else -> result("accessory", title, Status.INFO, txt(if (feature) R.string.usb_s_accessory_none else R.string.usb_s_accessory_undeclared), null, details)
    }
}
