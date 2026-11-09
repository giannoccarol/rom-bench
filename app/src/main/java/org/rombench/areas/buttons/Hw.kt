package org.rombench.areas.buttons

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import android.view.KeyEvent
import androidx.annotation.StringRes
import org.rombench.R
import org.rombench.core.Sys

// ---- Keys -----------------------------------------------------------------------------------

/** "VOLUME_UP" for KEYCODE_VOLUME_UP. */
internal fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

/** Stable result name for a key: "key_volume_up". */
internal fun keyResultName(code: Int): String = "key_" + keyName(code).lowercase()

@StringRes
internal fun keyLabel(code: Int): Int? = when (code) {
    KeyEvent.KEYCODE_VOLUME_UP -> R.string.buttons_key_volume_up
    KeyEvent.KEYCODE_VOLUME_DOWN -> R.string.buttons_key_volume_down
    KeyEvent.KEYCODE_POWER -> R.string.buttons_key_power
    KeyEvent.KEYCODE_CAMERA -> R.string.buttons_key_camera
    KeyEvent.KEYCODE_FOCUS -> R.string.buttons_key_focus
    KeyEvent.KEYCODE_HOME -> R.string.buttons_key_home
    KeyEvent.KEYCODE_BACK -> R.string.buttons_key_back
    KeyEvent.KEYCODE_MENU -> R.string.buttons_key_menu
    KeyEvent.KEYCODE_APP_SWITCH -> R.string.buttons_key_app_switch
    KeyEvent.KEYCODE_SEARCH -> R.string.buttons_key_search
    KeyEvent.KEYCODE_HEADSETHOOK -> R.string.buttons_key_headsethook
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> R.string.buttons_key_play_pause
    KeyEvent.KEYCODE_VOLUME_MUTE -> R.string.buttons_key_mute
    KeyEvent.KEYCODE_ASSIST -> R.string.buttons_key_assist
    else -> null
}

/** Keys asked to the key maps; volume and power are expected on every phone. */
internal val CLAIMABLE_KEYS = listOf(
    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_POWER,
    KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_FOCUS, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_SEARCH,
    KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_VOLUME_MUTE,
    KeyEvent.KEYCODE_ASSIST,
)

/** HOME and APP_SWITCH are handled by the system and never reach an app. */
internal val UNCAPTURABLE = setOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_ASSIST)

internal fun claimedKeys(): Map<Int, Boolean> {
    val has = runCatching { android.view.KeyCharacterMap.deviceHasKeys(CLAIMABLE_KEYS.toIntArray()) }.getOrNull()
    return CLAIMABLE_KEYS.mapIndexed { i, c -> c to (has?.getOrNull(i) ?: false) }.toMap()
}

/** Keys of the interactive checklist: volume and power always, the others when a key map has them. */
internal fun checklistKeys(): List<Int> {
    val claimed = claimedKeys()
    return CLAIMABLE_KEYS.filter { c ->
        c !in UNCAPTURABLE && (c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_VOLUME_DOWN || c == KeyEvent.KEYCODE_POWER || claimed[c] == true)
    }
}

// ---- Input devices --------------------------------------------------------------------------

/** Source classes; SOURCE_SENSOR (API 31) is only a bit mask here, harmless on API 30. */
@SuppressLint("InlinedApi")
private val SOURCES = listOf(
    InputDevice.SOURCE_KEYBOARD to "keyboard",
    InputDevice.SOURCE_DPAD to "dpad",
    InputDevice.SOURCE_GAMEPAD to "gamepad",
    InputDevice.SOURCE_TOUCHSCREEN to "touchscreen",
    InputDevice.SOURCE_MOUSE to "mouse",
    InputDevice.SOURCE_STYLUS to "stylus",
    InputDevice.SOURCE_TRACKBALL to "trackball",
    InputDevice.SOURCE_TOUCHPAD to "touchpad",
    InputDevice.SOURCE_JOYSTICK to "joystick",
    InputDevice.SOURCE_ROTARY_ENCODER to "rotary",
    InputDevice.SOURCE_HDMI to "hdmi",
    InputDevice.SOURCE_SENSOR to "sensor",
)

internal fun sourcesOf(src: Int): String = SOURCES.filter { (src and it.first) == it.first }.joinToString("+") { it.second }.ifEmpty { "0x%x".format(java.util.Locale.US, src) }

internal fun keyboardType(t: Int): String = when (t) {
    InputDevice.KEYBOARD_TYPE_ALPHABETIC -> "alphabetic"
    InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC -> "non-alphabetic"
    else -> "none"
}

internal fun inputDevices(): List<InputDevice> =
    InputDevice.getDeviceIds().toList().mapNotNull { runCatching { InputDevice.getDevice(it) }.getOrNull() }

internal fun describeDevice(dev: InputDevice): String =
    "vendor 0x%04x product 0x%04x · %s · keys %s · %s%s".format(
        java.util.Locale.US,
        dev.vendorId, dev.productId, sourcesOf(dev.sources), keyboardType(dev.keyboardType),
        if (dev.isExternal) "external" else "internal", if (dev.isVirtual) " · virtual" else "",
    )

// ---- Vibrator -------------------------------------------------------------------------------

internal fun vibratorOf(ctx: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else ctx.getSystemService(Vibrator::class.java)

internal class Effect(val id: Int, val name: String)

internal val EFFECTS = listOf(
    Effect(VibrationEffect.EFFECT_CLICK, "CLICK"),
    Effect(VibrationEffect.EFFECT_DOUBLE_CLICK, "DOUBLE_CLICK"),
    Effect(VibrationEffect.EFFECT_TICK, "TICK"),
    Effect(VibrationEffect.EFFECT_HEAVY_CLICK, "HEAVY_CLICK"),
)

/** Composition primitives; THUD, SPIN and LOW_TICK exist from Android 12. */
internal val PRIMITIVES: List<Effect>
    get() = buildList {
        add(Effect(VibrationEffect.Composition.PRIMITIVE_CLICK, "CLICK"))
        add(Effect(VibrationEffect.Composition.PRIMITIVE_TICK, "TICK"))
        add(Effect(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, "QUICK_RISE"))
        add(Effect(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, "SLOW_RISE"))
        add(Effect(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, "QUICK_FALL"))
        if (Build.VERSION.SDK_INT >= 31) {
            add(Effect(VibrationEffect.Composition.PRIMITIVE_THUD, "THUD"))
            add(Effect(VibrationEffect.Composition.PRIMITIVE_SPIN, "SPIN"))
            add(Effect(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, "LOW_TICK"))
        }
    }

@SuppressLint("WrongConstant")
internal fun effectSupport(v: Vibrator): Map<Int, Int> {
    val r = runCatching { v.areEffectsSupported(*EFFECTS.map { it.id }.toIntArray()) }.getOrNull()
    return EFFECTS.mapIndexed { i, e -> e.id to (r?.getOrNull(i) ?: Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN) }.toMap()
}

@SuppressLint("WrongConstant")
internal fun primitiveSupport(v: Vibrator): Map<Int, Boolean> {
    val p = PRIMITIVES
    val r = runCatching { v.arePrimitivesSupported(*p.map { it.id }.toIntArray()) }.getOrNull()
    return p.mapIndexed { i, e -> e.id to (r?.getOrNull(i) ?: false) }.toMap()
}

internal fun supportName(s: Int): String = when (s) {
    Vibrator.VIBRATION_EFFECT_SUPPORT_YES -> "yes"
    Vibrator.VIBRATION_EFFECT_SUPPORT_NO -> "no"
    else -> "unknown"
}

// ---- LEDs -----------------------------------------------------------------------------------

internal data class Led(val name: String, val brightness: Int?, val max: Int?, val trigger: String?) {
    val kind: String
        get() {
            val n = name.lowercase()
            return when {
                "vibrator" in n -> "vibrator"
                "flash" in n || "torch" in n -> "flash"
                "button" in n || "kpd" in n || "keyboard" in n -> "buttons"
                "lcd" in n || "backlight" in n || "wled" in n -> "backlight"
                listOf("red", "green", "blue", "white", "rgb", "notification", "charging", "indicator", "lp5523", "amber").any { it in n } -> "indicator"
                else -> "other"
            }
        }
}

private const val LEDS = "/sys/class/leds"

internal fun readLeds(): List<Led> = Sys.list(LEDS).map { n ->
    val trig = Sys.read("$LEDS/$n/trigger", 8192)?.let { Regex("""\[([^\]]+)]""").find(it)?.groupValues?.get(1) }
    Led(n, Sys.long("$LEDS/$n/brightness")?.toInt(), Sys.long("$LEDS/$n/max_brightness")?.toInt(), trig)
}
