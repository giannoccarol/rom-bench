package org.rombench.areas.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Display
import android.view.InputDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.rombench.core.Sys
import kotlin.coroutines.resume
import kotlin.math.hypot

object DisplayInfo {
    fun display(ctx: Context): Display? = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    @Suppress("DEPRECATION")
    fun metrics(d: Display): DisplayMetrics = DisplayMetrics().also { d.getRealMetrics(it) }

    fun diagonalInches(m: DisplayMetrics): Double? =
        if (m.xdpi > 0 && m.ydpi > 0) hypot(m.widthPixels / m.xdpi.toDouble(), m.heightPixels / m.ydpi.toDouble()) else null

    /** Backlight nodes: /sys/class/backlight/<x> and the legacy lcd-backlight LED. */
    fun backlightNodes(): List<String> =
        Sys.list("/sys/class/backlight").map { "/sys/class/backlight/$it" } +
            listOf("/sys/class/leds/lcd-backlight", "/sys/class/leds/wled", "/sys/class/leds/backlight").filter { Sys.exists("$it/brightness") }

    data class Backlight(val node: String, val brightness: Long?, val max: Long?, val actual: Long?)

    fun backlight(): List<Backlight> = backlightNodes().map { n ->
        Backlight(n, Sys.long("$n/brightness"), Sys.long("$n/max_brightness"), Sys.long("$n/actual_brightness"))
    }

    fun settingsBrightness(ctx: Context): Pair<Int?, Int?> = runCatching {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) to
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
    }.getOrDefault(null to null)

    fun touchscreens(ctx: Context): List<InputDevice> {
        return InputDevice.getDeviceIds().toList().mapNotNull { id -> InputDevice.getDevice(id) }
            .filter { dev -> dev.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) }
    }

    /** Frames per second delivered by Choreographer for [ms]; null when no frame arrives (screen off). */
    suspend fun measureFps(ms: Long = 1500): Double? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(ms + 1500) {
            suspendCancellableCoroutine { cont ->
                val ch = Choreographer.getInstance()
                var first = 0L
                var count = 0
                var active = true
                val cb = object : Choreographer.FrameCallback {
                    override fun doFrame(t: Long) {
                        if (!active) return
                        if (first == 0L) first = t else count++
                        if (first != 0L && t - first >= ms * 1_000_000) {
                            active = false
                            if (cont.isActive) cont.resume(count * 1e9 / (t - first))
                        } else ch.postFrameCallback(this)
                    }
                }
                ch.postFrameCallback(cb)
                cont.invokeOnCancellation { active = false; ch.removeFrameCallback(cb) }
            }
        }
    }
}
