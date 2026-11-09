package org.rombench.core

import org.rombench.areas.wifi.WifiArea
import org.rombench.areas.bluetooth.BluetoothArea
import org.rombench.areas.telephony.TelephonyArea
import org.rombench.areas.nfc.NfcArea
import org.rombench.areas.gnss.GnssArea
import org.rombench.areas.audio.AudioArea
import org.rombench.areas.mic.MicArea
import org.rombench.areas.camera.CameraArea
import org.rombench.areas.display.DisplayArea
import org.rombench.areas.buttons.ButtonsArea
import org.rombench.areas.sensors.SensorsArea
import org.rombench.areas.battery.BatteryArea
import org.rombench.areas.usb.UsbArea
import org.rombench.areas.suspend.SuspendArea
import org.rombench.areas.cpu.CpuArea
import org.rombench.areas.memory.MemoryArea
import org.rombench.areas.device.DeviceArea

/** Every area, in dashboard order (grouped by [AreaGroup]). */
object Areas {
    val all: List<Area> = listOf(
        WifiArea,
        BluetoothArea,
        TelephonyArea,
        NfcArea,
        GnssArea,
        AudioArea,
        MicArea,
        CameraArea,
        DisplayArea,
        ButtonsArea,
        SensorsArea,
        BatteryArea,
        UsbArea,
        SuspendArea,
        CpuArea,
        MemoryArea,
        DeviceArea,
    )

    fun byId(id: String): Area? = all.firstOrNull { it.id == id }
}
