package org.rombench.areas.usb

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.storage.StorageManager
import android.view.InputDevice
import androidx.core.content.ContextCompat
import org.rombench.core.Sys

internal const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"

/** Extras of the sticky USB_STATE broadcast that are states, not gadget functions. */
private val STATE_KEYS = setOf("connected", "configured", "host_connected", "unlocked", "data_unlocked", "config_changed", "none")

internal data class UsbPort(val extras: Map<String, Boolean>) {
    val connected get() = extras["connected"] == true
    val configured get() = extras["configured"] == true
    val hostConnected get() = extras["host_connected"] == true
    val dataUnlocked get() = extras["data_unlocked"] ?: extras["unlocked"]
    val functions: List<String> get() = extras.filter { it.value && it.key !in STATE_KEYS }.keys.sorted()
}

internal data class PowerSupply(val name: String, val attrs: Map<String, String>) {
    val type: String? get() = attrs["REAL_TYPE"] ?: attrs["USB_TYPE"]?.let { t -> Regex("\\[(\\w+)]").find(t)?.groupValues?.get(1) } ?: attrs["TYPE"]
    val online: Boolean get() = attrs["ONLINE"] == "1"
    val present: Boolean? get() = attrs["PRESENT"]?.let { it == "1" }
    val currentMaxMa: Long? get() = attrs["CURRENT_MAX"]?.toLongOrNull()?.let { it / 1000 }
    val inputCurrentMaxMa: Long? get() = (attrs["INPUT_CURRENT_MAX"] ?: attrs["INPUT_CURRENT_LIMIT"])?.toLongOrNull()?.let { it / 1000 }
    val voltageV: Double? get() = attrs["VOLTAGE_NOW"]?.toLongOrNull()?.takeIf { it > 0 }?.let { it / 1e6 }

    /** A PC or hub port (data capable), as opposed to a wall charger. */
    val isDataPort: Boolean get() = type in setOf("USB", "SDP", "USB_CDP", "CDP", "USB_ACA")
}

internal data class KernelUsbDev(
    val node: String,
    val vid: String,
    val pid: String,
    val product: String?,
    val manufacturer: String?,
    val speed: String?,
    val deviceClass: String?,
    val interfaceClasses: List<Int>,
) {
    val rootHub get() = vid == "1d6b"
}

internal data class VolumeInfo(
    val description: String,
    val state: String,
    val uuid: String?,
    val path: String?,
    val removable: Boolean,
    val primary: Boolean,
    val emulated: Boolean,
)

internal data class InputInfo(val name: String, val kinds: List<String>, val vid: Int, val pid: Int)

internal data class UsbSnapshot(
    val port: UsbPort?,
    val props: Map<String, String>,
    val gadgetState: String?,
    val udc: List<String>,
    val supplies: List<PowerSupply>,
    val plugged: Int?,
    val typec: List<Pair<String, Map<String, String>>>,
    val dualRole: List<Pair<String, Map<String, String>>>,
    val apiDevices: List<UsbDevice>,
    val kernelDevices: List<KernelUsbDev>,
    val accessories: List<String>,
    val volumes: List<VolumeInfo>,
    val inputs: List<InputInfo>,
) {
    val massStorageAttached get() = kernelDevices.any { 8 in it.interfaceClasses } ||
        apiDevices.any { d -> (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE } }
    val hidAttached get() = kernelDevices.any { 3 in it.interfaceClasses } ||
        apiDevices.any { d -> (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_HID } }
    val onlineSupplies get() = supplies.filter { it.online }
}

internal object UsbData {
    val PROPS = listOf(
        "sys.usb.config", "sys.usb.state", "persist.sys.usb.config", "sys.usb.controller", "sys.usb.configfs",
        "sys.usb.ffs.ready", "vendor.usb.controller", "ro.boot.usbcontroller", "persist.vendor.usb.config",
    )

    fun port(ctx: Context): UsbPort? {
        val i: Intent = runCatching {
            ContextCompat.registerReceiver(ctx.applicationContext, null, IntentFilter(ACTION_USB_STATE), ContextCompat.RECEIVER_EXPORTED)
        }.getOrNull() ?: return null
        return portFrom(i)
    }

    fun portFrom(i: Intent): UsbPort {
        val ex = i.extras ?: return UsbPort(emptyMap())
        val m = ex.keySet().mapNotNull { k -> runCatching { k to ex.getBoolean(k, false) }.getOrNull() }.toMap()
        return UsbPort(m)
    }

    fun plugged(ctx: Context): Int? = runCatching {
        ContextCompat.registerReceiver(ctx.applicationContext, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }
    }.getOrNull()

    private val SUPPLY_NAME = Regex("^(usb|pc_port|ac|dc|typec|usb[-_]pd|usbpd|mains)", RegexOption.IGNORE_CASE)

    fun supplies(): List<PowerSupply> = Sys.list("/sys/class/power_supply").mapNotNull { n ->
        val base = "/sys/class/power_supply/$n"
        val ue = Sys.keyValues("$base/uevent").mapKeys { it.key.removePrefix("POWER_SUPPLY_") }.toMutableMap()
        if (ue.isEmpty()) {
            listOf("type", "real_type", "online", "present", "current_max", "voltage_now", "input_current_max", "typec_mode", "pd_active", "usb_type")
                .forEach { a -> Sys.line("$base/$a")?.let { ue[a.uppercase()] = it } }
        }
        val type = ue["TYPE"].orEmpty()
        if (!SUPPLY_NAME.containsMatchIn(n) && !type.startsWith("USB") && type != "Mains") null
        else PowerSupply(n, ue)
    }

    private fun attrs(base: String, names: List<String>): Map<String, String> =
        names.mapNotNull { a -> Sys.line("$base/$a")?.let { a to it } }.toMap()

    private val TYPEC_ATTRS = listOf(
        "data_role", "power_role", "port_type", "power_operation_mode", "preferred_role", "orientation",
        "usb_power_delivery_revision", "usb_typec_revision", "vconn_source", "accessory_mode", "supports_usb_power_delivery", "type",
    )

    fun typec(): List<Pair<String, Map<String, String>>> =
        Sys.list("/sys/class/typec").filter { !it.contains("cable") && !it.contains("plug") }.map { n -> n to attrs("/sys/class/typec/$n", TYPEC_ATTRS) }

    fun dualRole(): List<Pair<String, Map<String, String>>> =
        Sys.list("/sys/class/dual_role_usb").map { n ->
            n to attrs("/sys/class/dual_role_usb/$n", listOf("mode", "power_role", "data_role", "supported_modes", "powers_vconn"))
        }

    fun kernelDevices(): List<KernelUsbDev> {
        val base = "/sys/bus/usb/devices"
        val entries = Sys.list(base)
        return entries.filter { !it.contains(':') }.mapNotNull { n ->
            val vid = Sys.line("$base/$n/idVendor") ?: return@mapNotNull null
            val ifaces = entries.filter { it.startsWith("$n:") }.mapNotNull { Sys.line("$base/$it/bInterfaceClass")?.toIntOrNull(16) }
            KernelUsbDev(
                n, vid, Sys.line("$base/$n/idProduct") ?: "?", Sys.line("$base/$n/product"), Sys.line("$base/$n/manufacturer"),
                Sys.line("$base/$n/speed"), Sys.line("$base/$n/bDeviceClass"), ifaces,
            )
        }
    }

    fun volumes(ctx: Context): List<VolumeInfo> = runCatching {
        ctx.getSystemService(StorageManager::class.java)?.storageVolumes?.map { v ->
            VolumeInfo(
                v.getDescription(ctx) ?: "?", v.state ?: "?", v.uuid, v.directory?.absolutePath,
                v.isRemovable, v.isPrimary, v.isEmulated,
            )
        }
    }.getOrNull().orEmpty()

    fun inputs(): List<InputInfo> = runCatching {
        InputDevice.getDeviceIds().toList().mapNotNull { id ->
            val dev = InputDevice.getDevice(id) ?: return@mapNotNull null
            if (dev.isVirtual || !dev.isExternal) return@mapNotNull null
            val kinds = buildList {
                if (dev.supportsSource(InputDevice.SOURCE_KEYBOARD) && dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) add("keyboard")
                if (dev.supportsSource(InputDevice.SOURCE_MOUSE)) add("mouse")
                if (dev.supportsSource(InputDevice.SOURCE_TOUCHPAD)) add("touchpad")
                if (dev.supportsSource(InputDevice.SOURCE_GAMEPAD) || dev.supportsSource(InputDevice.SOURCE_JOYSTICK)) add("gamepad")
                if (dev.supportsSource(InputDevice.SOURCE_STYLUS)) add("stylus")
                if (dev.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) add("touchscreen")
                if (isEmpty()) add("keys")
            }
            InputInfo(dev.name ?: "?", kinds, dev.vendorId, dev.productId)
        }
    }.getOrNull().orEmpty()

    fun snapshot(ctx: Context): UsbSnapshot {
        val um = ctx.applicationContext.getSystemService(UsbManager::class.java)
        return UsbSnapshot(
            port = port(ctx),
            props = PROPS.associateWith { Sys.prop(it) }.filterValues { it.isNotEmpty() },
            gadgetState = Sys.line("/sys/class/android_usb/android0/state"),
            udc = Sys.list("/sys/class/udc"),
            supplies = supplies(),
            plugged = plugged(ctx),
            typec = typec(),
            dualRole = dualRole(),
            apiDevices = runCatching { um?.deviceList?.values?.toList() }.getOrNull().orEmpty(),
            kernelDevices = kernelDevices(),
            accessories = runCatching { um?.accessoryList?.map { "${it.manufacturer} ${it.model}" } }.getOrNull().orEmpty(),
            volumes = volumes(ctx),
            inputs = inputs(),
        )
    }

    fun className(c: Int): String = when (c) {
        UsbConstants.USB_CLASS_PER_INTERFACE -> "per-interface"
        UsbConstants.USB_CLASS_AUDIO -> "audio"
        UsbConstants.USB_CLASS_COMM -> "CDC"
        UsbConstants.USB_CLASS_HID -> "HID"
        UsbConstants.USB_CLASS_PHYSICA -> "physical"
        UsbConstants.USB_CLASS_STILL_IMAGE -> "image"
        UsbConstants.USB_CLASS_PRINTER -> "printer"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "mass storage"
        UsbConstants.USB_CLASS_HUB -> "hub"
        UsbConstants.USB_CLASS_CDC_DATA -> "CDC data"
        UsbConstants.USB_CLASS_CSCID -> "smart card"
        UsbConstants.USB_CLASS_CONTENT_SEC -> "content security"
        UsbConstants.USB_CLASS_VIDEO -> "video"
        UsbConstants.USB_CLASS_WIRELESS_CONTROLLER -> "wireless"
        UsbConstants.USB_CLASS_MISC -> "misc"
        UsbConstants.USB_CLASS_APP_SPEC -> "app-specific"
        UsbConstants.USB_CLASS_VENDOR_SPEC -> "vendor"
        else -> "0x%02x".format(c)
    }

    fun ifaceClasses(d: UsbDevice): List<Int> = (0 until d.interfaceCount).map { d.getInterface(it).interfaceClass }.distinct()

    fun describe(d: UsbDevice): String {
        val name = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifEmpty { d.deviceName }
        val classes = if (d.deviceClass == 0) ifaceClasses(d).joinToString("+") { className(it) } else className(d.deviceClass)
        return "%04x:%04x · %s · %s".format(d.vendorId, d.productId, name, classes)
    }

    fun describe(k: KernelUsbDev): String {
        val name = listOfNotNull(k.manufacturer, k.product).joinToString(" ").ifEmpty { "?" }
        val classes = k.interfaceClasses.distinct().joinToString("+") { className(it) }.ifEmpty { k.deviceClass ?: "?" }
        return "${k.node} · ${k.vid}:${k.pid} · $name · $classes" + (k.speed?.let { " · $it Mbit/s" } ?: "")
    }

    fun pluggedName(p: Int?): String = when (p) {
        null -> "—"
        0 -> "unplugged"
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
        else -> "0x%x".format(p)
    }

    fun supplySummary(s: PowerSupply): String = listOfNotNull(
        s.type,
        s.currentMaxMa?.takeIf { it > 0 }?.let { "$it mA max" },
        s.voltageV?.let { String.format(java.util.Locale.US, "%.2f V", it) },
    ).joinToString(" · ")
}
