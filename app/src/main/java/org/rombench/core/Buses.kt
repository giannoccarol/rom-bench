package org.rombench.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Key events seen by MainActivity, for the hardware button test. */
object KeyBus {
    private val _events = MutableSharedFlow<KeyEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<KeyEvent> = _events

    /** When true, MainActivity consumes keys instead of letting the system act (e.g. volume). */
    @Volatile var capture = false

    fun emit(e: KeyEvent) = _events.tryEmit(e)
}

/**
 * Delivery of the alarms that the suspend test schedules. The receiver records when the alarm
 * actually fired so lateness after deep sleep can be measured.
 */
object AlarmBus {
    data class Fired(val tag: String, val elapsed: Long, val uptime: Long, val wall: Long)

    private val _last = MutableStateFlow<Fired?>(null)
    val last: StateFlow<Fired?> = _last

    const val EXTRA_TAG = "tag"

    fun fire(tag: String) {
        _last.value = Fired(tag, SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), System.currentTimeMillis())
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmBus.fire(intent.getStringExtra(AlarmBus.EXTRA_TAG) ?: "alarm")
        context.getSharedPreferences("alarm", Context.MODE_PRIVATE).edit()
            .putLong("elapsed", SystemClock.elapsedRealtime())
            .putLong("uptime", SystemClock.uptimeMillis())
            .putLong("wall", System.currentTimeMillis())
            .putString("tag", intent.getStringExtra(AlarmBus.EXTRA_TAG))
            .apply()
    }
}
