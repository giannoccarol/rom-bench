package org.rombench.areas.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.annotation.StringRes
import org.rombench.R
import org.rombench.core.Txt
import org.rombench.core.txt

/** Names and labels of audio devices, shared by the audio output and microphone areas. */
internal object AudioDevices {
    /** AudioDeviceInfo.TYPE_ECHO_REFERENCE (28) is a system API, not visible to apps. */
    const val TYPE_ECHO_REFERENCE = 28

    fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "BUILTIN_SPEAKER_SAFE"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX_LINE"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_FM -> "FM"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV_TUNER"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDevices.TYPE_ECHO_REFERENCE -> "ECHO_REFERENCE"
        else -> "TYPE_$type"
    }

    @StringRes
    fun typeLabel(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> R.string.audio_dev_speaker
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> R.string.audio_dev_earpiece
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> R.string.audio_dev_wired_headset
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> R.string.audio_dev_wired_headphones
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> R.string.audio_dev_bt_a2dp
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> R.string.audio_dev_bt_sco
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID -> R.string.audio_dev_ble
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET -> R.string.audio_dev_usb
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> R.string.audio_dev_hdmi
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> R.string.audio_dev_builtin_mic
        AudioDeviceInfo.TYPE_TELEPHONY -> R.string.audio_dev_telephony
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> R.string.audio_dev_line
        else -> R.string.audio_dev_other
    }

    /** Localised label plus the distinguishing part (mic address, product name of accessories). */
    fun label(ctx: Context, dev: AudioDeviceInfo): String = labelTxt(dev).resolve(ctx)

    fun labelTxt(dev: AudioDeviceInfo): Txt {
        val extra = when {
            dev.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> dev.address.takeIf { it.isNotBlank() }
            isBuiltin(dev.type) -> null
            else -> dev.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
        }
        return if (extra == null) txt(typeLabel(dev.type)) else txt(R.string.audio_dev_with, txt(typeLabel(dev.type)), extra)
    }

    fun isBuiltin(type: Int) = type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
        type == AudioDeviceInfo.TYPE_BUILTIN_MIC || type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE ||
        type == AudioDeviceInfo.TYPE_TELEPHONY || type == AudioDeviceInfo.TYPE_FM_TUNER || type == AudioDevices.TYPE_ECHO_REFERENCE

    /** Bluetooth and similar addresses identify the accessory: redact them in reports. */
    fun addressSensitive(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_IP -> true
        else -> false
    }

    /** Wired accessories whose plug/unplug the jack detection test watches. */
    fun isJack(type: Int) = type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        type == AudioDeviceInfo.TYPE_USB_HEADSET

    /** Outputs a tester can sensibly play a signal on. */
    fun isPlayable(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_FM, AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
        AudioDevices.TYPE_ECHO_REFERENCE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_IP,
        AudioDeviceInfo.TYPE_BUS, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> false
        else -> true
    }

    fun encodingName(e: Int): String = when (e) {
        AudioFormat.ENCODING_PCM_8BIT -> "PCM_8"
        AudioFormat.ENCODING_PCM_16BIT -> "PCM_16"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM_24"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM_32"
        AudioFormat.ENCODING_AC3 -> "AC3"
        AudioFormat.ENCODING_E_AC3 -> "E_AC3"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS_HD"
        AudioFormat.ENCODING_MP3 -> "MP3"
        AudioFormat.ENCODING_AAC_LC -> "AAC_LC"
        AudioFormat.ENCODING_IEC61937 -> "IEC61937"
        else -> "enc$e"
    }

    /** "rates 48000,44100 · ch 1,2 · PCM_16" ("any" when the HAL profile is dynamic). */
    fun formats(dev: AudioDeviceInfo): String {
        val rates = dev.sampleRates.distinct().sortedDescending().joinToString(",").ifEmpty { "any" }
        val ch = dev.channelCounts.distinct().sorted().joinToString(",").ifEmpty { "any" }
        val enc = dev.encodings.distinct().joinToString(",") { encodingName(it) }.ifEmpty { "any" }
        return "rates $rates · ch $ch · $enc"
    }

    fun modeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
        AudioManager.MODE_CALL_REDIRECT -> "CALL_REDIRECT"
        AudioManager.MODE_COMMUNICATION_REDIRECT -> "COMMUNICATION_REDIRECT"
        else -> "MODE_$mode"
    }

    /** The earpiece as the communication device API wants it (Android 12+), else from the output list. */
    fun commEarpiece(am: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= 31) {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }?.let { return it }
        }
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
    }
}

/**
 * Puts the audio system in VoIP mode routed to the earpiece and puts everything back on [exit]:
 * the previous mode, the communication device (Android 12+) or the speakerphone flag (Android 11).
 */
internal class CommMode(private val am: AudioManager) {
    private var prevMode = AudioManager.MODE_NORMAL
    private var prevSpeaker = false
    @Volatile var active = false
        private set

    /** Returns whether the earpiece was accepted as the communication route. */
    @Suppress("DEPRECATION")
    @Synchronized
    fun enter(earpiece: AudioDeviceInfo?): Boolean {
        if (!active) {
            prevMode = am.mode
            prevSpeaker = am.isSpeakerphoneOn
            active = true
        }
        return try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                earpiece != null && am.setCommunicationDevice(earpiece)
            } else {
                am.isSpeakerphoneOn = false
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    @Synchronized
    fun exit() {
        if (!active) return
        active = false
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else am.isSpeakerphoneOn = prevSpeaker
        } catch (_: Exception) {
        }
        try {
            am.mode = prevMode
        } catch (_: Exception) {
        }
    }
}
