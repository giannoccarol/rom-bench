@file:Suppress("MissingPermission")

package org.rombench.areas.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import org.rombench.core.Sys

internal object BtUtil {
    const val DUMMY_ADDRESS = "02:00:00:00:00:00"

    fun adapter(ctx: Context): BluetoothAdapter? =
        runCatching { ctx.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()

    fun stateName(s: Int): String = when (s) {
        BluetoothAdapter.STATE_OFF -> "OFF"
        BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
        BluetoothAdapter.STATE_ON -> "ON"
        BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
        else -> "STATE_$s"
    }

    fun typeName(t: Int): String = when (t) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "BR/EDR"
        BluetoothDevice.DEVICE_TYPE_LE -> "LE"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
        else -> "?"
    }

    fun majorName(major: Int): String = when (major) {
        BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio/video"
        BluetoothClass.Device.Major.PHONE -> "Phone"
        BluetoothClass.Device.Major.COMPUTER -> "Computer"
        BluetoothClass.Device.Major.PERIPHERAL -> "Peripheral"
        BluetoothClass.Device.Major.WEARABLE -> "Wearable"
        BluetoothClass.Device.Major.HEALTH -> "Health"
        BluetoothClass.Device.Major.IMAGING -> "Imaging"
        BluetoothClass.Device.Major.TOY -> "Toy"
        BluetoothClass.Device.Major.NETWORKING -> "Networking"
        BluetoothClass.Device.Major.MISC -> "Misc"
        else -> "Uncategorized"
    }

    fun scanErrorName(code: Int): String = when (code) {
        1 -> "SCAN_FAILED_ALREADY_STARTED"
        2 -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
        3 -> "SCAN_FAILED_INTERNAL_ERROR"
        4 -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
        5 -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
        6 -> "SCAN_FAILED_SCANNING_TOO_FREQUENTLY"
        else -> "error $code"
    }

    // The calls below need BLUETOOTH_CONNECT on Android 12+; they return null when it is missing.
    fun name(d: BluetoothDevice): String? = try {
        d.name
    } catch (e: SecurityException) {
        null
    }

    fun type(d: BluetoothDevice): Int = try {
        d.type
    } catch (e: SecurityException) {
        BluetoothDevice.DEVICE_TYPE_UNKNOWN
    }

    fun bonded(d: BluetoothDevice): Boolean = try {
        d.bondState == BluetoothDevice.BOND_BONDED
    } catch (e: SecurityException) {
        false
    }

    fun major(d: BluetoothDevice): Int? = try {
        d.bluetoothClass?.majorDeviceClass
    } catch (e: SecurityException) {
        null
    }

    fun bondedDevices(a: BluetoothAdapter): List<BluetoothDevice>? = try {
        a.bondedDevices?.toList()
    } catch (e: SecurityException) {
        null
    }

    fun locationEnabled(ctx: Context): Boolean =
        ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true

    /** Placeholder addresses that mean "not provisioned". */
    fun placeholder(addr: String): Boolean {
        val a = addr.uppercase()
        return a == "00:00:00:00:00:00" || a == "FF:FF:FF:FF:FF:FF" || a == "00:00:00:00:5A:AD" ||
            a == "11:22:33:44:55:66" || a == "22:22:22:22:22:22"
    }

    data class Rfkill(val name: String, val type: String, val state: String?, val soft: String?, val hard: String?)

    fun rfkills(): List<Rfkill> = Sys.list("/sys/class/rfkill").mapNotNull { n ->
        val b = "/sys/class/rfkill/$n"
        val type = Sys.line("$b/type") ?: return@mapNotNull null
        Rfkill(Sys.line("$b/name") ?: n, type, Sys.line("$b/state"), Sys.line("$b/soft"), Sys.line("$b/hard"))
    }
}
