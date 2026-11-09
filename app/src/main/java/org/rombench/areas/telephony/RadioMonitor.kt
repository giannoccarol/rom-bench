package org.rombench.areas.telephony

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Live radio state of one subscription: TelephonyCallback on Android 12+, PhoneStateListener on
 * Android 11. Callbacks arrive on the main thread; consumers read the StateFlows.
 * It keeps running while the activity is in the background (the dialer is in front during the
 * call test), so call events are handled here and not in a composable.
 */
@SuppressLint("MissingPermission")
internal class RadioMonitor(ctx: Context, val subId: Int) {
    data class CallEvent(val elapsed: Long, val state: Int, val voiceRat: Int, val dataRat: Int)

    private val app = ctx.applicationContext
    val tm: TelephonyManager = app.getSystemService(TelephonyManager::class.java).let {
        if (SubscriptionManager.isValidSubscriptionId(subId)) it.createForSubscriptionId(subId) else it
    }
    private val main = Handler(Looper.getMainLooper())

    private val _service = MutableStateFlow<ServiceState?>(null)
    val service: StateFlow<ServiceState?> = _service
    private val _signal = MutableStateFlow<SignalStrength?>(null)
    val signal: StateFlow<SignalStrength?> = _signal
    private val _call = MutableStateFlow(TelephonyManager.CALL_STATE_IDLE)
    val call: StateFlow<Int> = _call
    private val _data = MutableStateFlow(-1 to TelephonyManager.NETWORK_TYPE_UNKNOWN)
    /** Data connection state and the data RAT reported with it. */
    val data: StateFlow<Pair<Int, Int>> = _data
    private val _activity = MutableStateFlow(-1)
    val activity: StateFlow<Int> = _activity
    private val _display = MutableStateFlow<TelephonyDisplayInfo?>(null)
    val display: StateFlow<TelephonyDisplayInfo?> = _display
    private val _events = MutableStateFlow<List<CallEvent>>(emptyList())
    /** Call state transitions since the monitor started (newest last, capped). */
    val events: StateFlow<List<CallEvent>> = _events
    private val _lastSignalAt = MutableStateFlow(0L)
    val lastSignalAt: StateFlow<Long> = _lastSignalAt

    /** Called on every call state change (main thread). */
    var onCallState: ((CallEvent, previous: Int) -> Unit)? = null

    private var callback: Any? = null
    var error: String? = null
        private set

    fun start() {
        if (callback != null) return
        try {
            _service.value = tm.serviceState
        } catch (e: Exception) {
            // Location or phone permission missing: wait for the listener.
        }
        try {
            _signal.value = tm.signalStrength
        } catch (e: Exception) {
        }
        try {
            callback = if (Build.VERSION.SDK_INT >= 31) startCallback() else startListener()
        } catch (e: Exception) {
            error = e.toString()
        }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            if (Build.VERSION.SDK_INT >= 31 && cb is TelephonyCallback) tm.unregisterTelephonyCallback(cb)
            else if (cb is PhoneStateListener) @Suppress("DEPRECATION") tm.listen(cb, PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) {
        }
        main.removeCallbacksAndMessages(null)
    }

    fun voiceRat(): Int = try { tm.voiceNetworkType } catch (e: Exception) { TelephonyManager.NETWORK_TYPE_UNKNOWN }
    fun dataRat(): Int = try { tm.dataNetworkType } catch (e: Exception) { TelephonyManager.NETWORK_TYPE_UNKNOWN }

    private fun onService(s: ServiceState) { _service.value = s }
    private fun onSignal(s: SignalStrength) { _signal.value = s; _lastSignalAt.value = SystemClock.elapsedRealtime() }
    private fun onData(state: Int, rat: Int) { _data.value = state to rat }
    private fun onActivity(a: Int) { _activity.value = a }
    private fun onDisplay(d: TelephonyDisplayInfo) { _display.value = d }

    private fun onCall(state: Int) {
        val prev = _call.value
        _call.value = state
        val ev = CallEvent(SystemClock.elapsedRealtime(), state, voiceRat(), dataRat())
        _events.update { (it + ev).takeLast(12) }
        onCallState?.invoke(ev, prev)
    }

    /** Runs [block] on the main thread after [ms], cancelled by [stop]. */
    fun later(ms: Long, block: () -> Unit) {
        main.postDelayed(block, ms)
    }

    @RequiresApi(31)
    private inner class Cb : TelephonyCallback(),
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.CallStateListener,
        TelephonyCallback.DataConnectionStateListener,
        TelephonyCallback.DataActivityListener,
        TelephonyCallback.DisplayInfoListener {
        override fun onServiceStateChanged(serviceState: ServiceState) = onService(serviceState)
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = onSignal(signalStrength)
        override fun onCallStateChanged(state: Int) = onCall(state)
        override fun onDataConnectionStateChanged(state: Int, networkType: Int) = onData(state, networkType)
        override fun onDataActivity(direction: Int) = onActivity(direction)
        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) = onDisplay(telephonyDisplayInfo)
    }

    @RequiresApi(31)
    private fun startCallback(): Any {
        val cb = Cb()
        tm.registerTelephonyCallback(ContextCompat.getMainExecutor(app), cb)
        return cb
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private fun startListener(): Any {
        val l = object : PhoneStateListener(ContextCompat.getMainExecutor(app)) {
            override fun onServiceStateChanged(serviceState: ServiceState?) { serviceState?.let { onService(it) } }
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) { signalStrength?.let { onSignal(it) } }
            override fun onCallStateChanged(state: Int, phoneNumber: String?) = onCall(state)
            override fun onDataConnectionStateChanged(state: Int, networkType: Int) = onData(state, networkType)
            override fun onDataActivity(direction: Int) = onActivity(direction)
            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) = onDisplay(telephonyDisplayInfo)
        }
        tm.listen(
            l,
            PhoneStateListener.LISTEN_SERVICE_STATE or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                PhoneStateListener.LISTEN_CALL_STATE or PhoneStateListener.LISTEN_DATA_CONNECTION_STATE or
                PhoneStateListener.LISTEN_DATA_ACTIVITY or PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED,
        )
        return l
    }
}
