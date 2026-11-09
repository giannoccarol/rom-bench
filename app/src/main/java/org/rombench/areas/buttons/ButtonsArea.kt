package org.rombench.areas.buttons

import android.content.Context
import android.os.Build
import android.os.VibratorManager
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.Detail
import org.rombench.core.Status
import org.rombench.core.Sys
import org.rombench.core.TestResult
import org.rombench.core.d
import org.rombench.core.result
import org.rombench.core.txt

object ButtonsArea : Area {
    override val id = "buttons"
    override val title = R.string.buttons_title
    override val subtitle = R.string.buttons_subtitle
    override val icon: ImageVector = Icons.Outlined.Vibration
    override val group = AreaGroup.MEDIA
    override val autoSeconds = 2

    override suspend fun autoCheck(ctx: Context): List<TestResult> = withContext(Dispatchers.IO) {
        val out = mutableListOf<TestResult>()
        val devices = runCatching { inputDevices() }.getOrDefault(emptyList())
        out += inputDevicesResult(devices)
        out += touchscreenResult(devices)
        out += keysResult()
        out += vibratorResults(ctx)
        out += ledsResult()
        out
    }

    private fun inputDevicesResult(devices: List<InputDevice>): TestResult {
        val details = devices.map { d("[${it.id}] ${it.name}", describeDevice(it)) }
        return result(
            "input_devices", txt(R.string.buttons_t_input_devices), if (devices.isEmpty()) Status.FAIL else Status.INFO,
            txt(R.string.buttons_s_input_devices, devices.count { !it.isVirtual }, devices.count { it.isExternal }),
            if (devices.isEmpty()) txt(R.string.buttons_r_no_input) else null, details,
        )
    }

    private fun touchscreenResult(devices: List<InputDevice>): TestResult {
        val touch = devices.filter { (it.sources and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN }
        val details = touch.map { dev ->
            val x = dev.getMotionRange(android.view.MotionEvent.AXIS_X)
            val y = dev.getMotionRange(android.view.MotionEvent.AXIS_Y)
            d(dev.name, "vendor 0x%04x product 0x%04x · X 0…%.0f · Y 0…%.0f".format(java.util.Locale.US, dev.vendorId, dev.productId, x?.max ?: 0f, y?.max ?: 0f))
        }
        return if (touch.isEmpty()) result(
            "touchscreen", txt(R.string.buttons_t_touchscreen), Status.WARN,
            txt(R.string.buttons_s_no_touch), txt(R.string.buttons_r_no_touch), details,
        ) else result(
            "touchscreen", txt(R.string.buttons_t_touchscreen), Status.PASS,
            txt(R.string.buttons_s_touch, touch.joinToString { it.name }), null, details,
        )
    }

    private fun keysResult(): TestResult {
        val claimed = claimedKeys()
        val details = CLAIMABLE_KEYS.map { d(keyName(it), if (claimed[it] == true) "mapped" else "not mapped") }
        val volumeOk = claimed[KeyEvent.KEYCODE_VOLUME_UP] == true && claimed[KeyEvent.KEYCODE_VOLUME_DOWN] == true
        val powerOk = claimed[KeyEvent.KEYCODE_POWER] == true
        val mapped = CLAIMABLE_KEYS.filter { claimed[it] == true }.joinToString { keyName(it) }
        return when {
            !volumeOk -> result("keys", txt(R.string.buttons_t_keys), Status.WARN, txt(R.string.buttons_s_keys, mapped.ifEmpty { "—" }), txt(R.string.buttons_r_no_volume), details)
            !powerOk -> result("keys", txt(R.string.buttons_t_keys), Status.WARN, txt(R.string.buttons_s_keys, mapped), txt(R.string.buttons_r_no_power), details)
            else -> result("keys", txt(R.string.buttons_t_keys), Status.PASS, txt(R.string.buttons_s_keys, mapped), null, details)
        }
    }

    private fun vibratorResults(ctx: Context): List<TestResult> {
        val v = vibratorOf(ctx)
        val sysfs = listOf("/sys/class/timed_output/vibrator/enable", "/sys/class/leds/vibrator/activate", "/sys/class/leds/vibrator/duration")
            .filter { Sys.exists(it) }
        val base = mutableListOf<Detail>(d("Kernel interface", sysfs.joinToString().ifEmpty { "none found" }))
        if (v == null || !v.hasVibrator()) {
            return listOf(
                result("vibrator", txt(R.string.buttons_t_vibrator), Status.WARN, txt(R.string.buttons_s_no_vibrator), txt(R.string.buttons_r_no_vibrator), base),
            )
        }
        val amp = v.hasAmplitudeControl()
        base += d("Amplitude control", amp)
        if (Build.VERSION.SDK_INT >= 31) {
            val vm = ctx.getSystemService(VibratorManager::class.java)
            base += d("Vibrator ids", vm?.vibratorIds?.joinToString() ?: "—")
            base += d("Default id", v.id)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            base += d("Resonant frequency", v.resonantFrequency.takeIf { !it.isNaN() }?.let { "%.1f Hz".format(java.util.Locale.US, it) })
            base += d("Q factor", v.qFactor.takeIf { !it.isNaN() })
        }
        val presence = result(
            "vibrator", txt(R.string.buttons_t_vibrator), Status.PASS,
            txt(if (amp) R.string.buttons_s_vibrator_amp else R.string.buttons_s_vibrator_noamp), null, base,
        )
        val eff = effectSupport(v)
        val prim = primitiveSupport(v)
        val effDetails = EFFECTS.map { d("Effect ${it.name}", supportName(eff[it.id] ?: 0)) } +
            PRIMITIVES.map { d("Primitive ${it.name}", if (prim[it.id] == true) "yes" else "no") }
        val effYes = eff.values.count { it == android.os.Vibrator.VIBRATION_EFFECT_SUPPORT_YES }
        val effects = result(
            "vibrator_effects", txt(R.string.buttons_t_effects), Status.INFO,
            txt(R.string.buttons_s_effects, effYes, EFFECTS.size, prim.values.count { it }, prim.size),
            if (prim.values.none { it }) txt(R.string.buttons_r_effects_basic) else null, effDetails,
        )
        return listOf(presence, effects)
    }

    private fun ledsResult(): TestResult {
        val leds = readLeds()
        val details = leds.map { l ->
            d("${l.name} (${l.kind})", "brightness ${l.brightness ?: "?"}/${l.max ?: "?"}" + (l.trigger?.let { " · trigger $it" } ?: ""))
        }
        val indicators = leds.filter { it.kind == "indicator" }
        return result(
            "leds", txt(R.string.buttons_t_leds), Status.INFO,
            if (indicators.isEmpty()) txt(R.string.buttons_s_leds_none, leds.size)
            else txt(R.string.buttons_s_leds, leds.size, indicators.joinToString { it.name }),
            if (indicators.isEmpty()) txt(R.string.buttons_r_leds_none) else null, details,
        )
    }

    @Composable
    override fun Content() = ButtonsContent()
}
