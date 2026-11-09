package org.rombench.areas.telephony

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import org.rombench.R
import org.rombench.core.Detail
import org.rombench.core.Kind
import org.rombench.core.ResultStore
import org.rombench.core.Status
import org.rombench.core.d
import org.rombench.core.result
import org.rombench.core.txt
import kotlin.random.Random

/**
 * SMS loopback: sends one SMS to the number the tester types (normally the phone's own) and
 * waits for it to come back through SMS_RECEIVED. Sent and delivery reports come from the
 * PendingIntents given to SmsManager.
 */
internal class SmsLoopback(ctx: Context) {
    enum class Phase { IDLE, SENDING, SENT, DELIVERED, RECEIVED, FAILED, TIMEOUT }

    data class State(
        val phase: Phase = Phase.IDLE,
        val token: String = "",
        val sentAt: Long = 0,
        val sentCode: Int? = null,
        val sentError: Int? = null,
        val sentMs: Long? = null,
        val deliveryStatus: Int? = null,
        val deliveredMs: Long? = null,
        val receivedMs: Long? = null,
        val error: String? = null,
    )

    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    val state = mutableStateOf(State())
    private var registered = false
    private var subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val s = state.value
            if (intent.getStringExtra(EXTRA_TOKEN) != s.token) return
            val code = resultCode
            val err = intent.getIntExtra("errorCode", -1).takeIf { it != -1 }
            val ms = SystemClock.elapsedRealtime() - s.sentAt
            if (code == Activity.RESULT_OK) {
                if (s.phase == Phase.SENDING) state.value = s.copy(phase = Phase.SENT, sentCode = code, sentError = err, sentMs = ms)
                else state.value = s.copy(sentCode = code, sentError = err, sentMs = ms)
            } else {
                state.value = s.copy(phase = Phase.FAILED, sentCode = code, sentError = err, sentMs = ms)
                finish()
            }
        }
    }

    private val deliveredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val s = state.value
            if (intent.getStringExtra(EXTRA_TOKEN) != s.token) return
            val status = try {
                val pdu = intent.getByteArrayExtra("pdu")
                val format = intent.getStringExtra("format")
                if (pdu != null) SmsMessage.createFromPdu(pdu, format)?.status else null
            } catch (e: Exception) {
                null
            }
            val ms = SystemClock.elapsedRealtime() - s.sentAt
            state.value = s.copy(
                phase = if (s.phase == Phase.SENT || s.phase == Phase.SENDING) Phase.DELIVERED else s.phase,
                deliveryStatus = status, deliveredMs = ms,
            )
        }
    }

    private val incomingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val s = state.value
            if (s.token.isEmpty() || s.phase == Phase.IDLE || s.phase == Phase.RECEIVED) return
            val body = try {
                Telephony.Sms.Intents.getMessagesFromIntent(intent)?.joinToString("") { it?.messageBody ?: "" } ?: ""
            } catch (e: Exception) {
                ""
            }
            if (!body.contains(s.token)) return
            state.value = s.copy(phase = Phase.RECEIVED, receivedMs = SystemClock.elapsedRealtime() - s.sentAt)
            finish()
        }
    }

    private val timeout = Runnable {
        val s = state.value
        if (s.phase in listOf(Phase.SENDING, Phase.SENT, Phase.DELIVERED)) {
            state.value = s.copy(phase = Phase.TIMEOUT)
            finish()
        }
    }

    fun register() {
        if (registered) return
        registered = true
        val own = IntentFilter().apply { addAction(ACTION_SENT) }
        ContextCompat.registerReceiver(app, sentReceiver, own, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(app, deliveredReceiver, IntentFilter(ACTION_DELIVERED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            app, incomingReceiver, IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION), ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun unregister() {
        if (!registered) return
        registered = false
        main.removeCallbacks(timeout)
        listOf(sentReceiver, deliveredReceiver, incomingReceiver).forEach {
            try {
                app.unregisterReceiver(it)
            } catch (e: Exception) {
            }
        }
    }

    fun send(number: String, sub: Int) {
        val token = "RB" + Random.nextInt(1000, 9999)
        subId = sub
        val now = SystemClock.elapsedRealtime()
        state.value = State(Phase.SENDING, token, now)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val sent = PendingIntent.getBroadcast(
            app, 41, Intent(ACTION_SENT).setPackage(app.packageName).putExtra(EXTRA_TOKEN, token), flags,
        )
        val delivered = PendingIntent.getBroadcast(
            app, 42, Intent(ACTION_DELIVERED).setPackage(app.packageName).putExtra(EXTRA_TOKEN, token), flags,
        )
        try {
            val sm = smsManager(sub)
            sm.sendTextMessage(number, null, "ROM Bench SMS test $token", sent, delivered)
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, TIMEOUT_MS)
        } catch (e: Exception) {
            state.value = state.value.copy(phase = Phase.FAILED, error = e.toString())
            finish()
        }
    }

    fun reset() {
        main.removeCallbacks(timeout)
        state.value = State()
    }

    @Suppress("DEPRECATION")
    private fun smsManager(sub: Int): SmsManager {
        val valid = SubscriptionManager.isValidSubscriptionId(sub)
        return if (Build.VERSION.SDK_INT >= 31) {
            val base = app.getSystemService(SmsManager::class.java)
            if (valid) base.createForSubscriptionId(sub) else base
        } else {
            if (valid) SmsManager.getSmsManagerForSubscriptionId(sub) else SmsManager.getDefault()
        }
    }

    /** Stores the result of the finished attempt as a guided result. */
    private fun finish() {
        main.removeCallbacks(timeout)
        val s = state.value
        val a = TelephonyArea
        val t = txt(R.string.telephony_t_sms)
        val det = details(s)
        val r = when (s.phase) {
            Phase.RECEIVED -> a.result(
                "sms_loopback", t, Status.PASS, txt(R.string.telephony_sms_pass, fmtSec(s.receivedMs)), null, det, Kind.GUIDED,
            )
            Phase.FAILED -> a.result(
                "sms_loopback", t, Status.FAIL,
                txt(R.string.telephony_sms_fail, s.sentCode?.let { codeName(it) } ?: s.error ?: "?"),
                txt(explain(s.sentCode ?: 1)), det, Kind.GUIDED,
            )
            Phase.TIMEOUT -> if (s.sentCode == Activity.RESULT_OK) a.result(
                "sms_loopback", t, Status.WARN, txt(R.string.telephony_sms_timeout_sent, TIMEOUT_MS / 1000),
                txt(R.string.telephony_sms_timeout_sent_reason), det, Kind.GUIDED,
            ) else a.result(
                "sms_loopback", t, Status.FAIL, txt(R.string.telephony_sms_timeout_nosent, TIMEOUT_MS / 1000),
                txt(R.string.telephony_sms_timeout_nosent_reason), det, Kind.GUIDED,
            )
            else -> return
        }
        ResultStore.put(r)
    }

    private fun details(s: State): List<Detail> = listOf(
        d("subId", subId),
        d("sent result", s.sentCode?.let { "${codeName(it)} ($it)" } ?: "no callback"),
        d("sent errorCode", s.sentError),
        d("sent after", s.sentMs?.let { "$it ms" }),
        d("delivery report", s.deliveryStatus?.let { "TP-Status $it (${tpStatus(it)})" } ?: if (s.deliveredMs != null) "received, no PDU" else "none"),
        d("delivered after", s.deliveredMs?.let { "$it ms" }),
        d("received after", s.receivedMs?.let { "$it ms" }),
        d("exception", s.error),
    )

    companion object {
        const val ACTION_SENT = "org.rombench.telephony.SMS_SENT"
        const val ACTION_DELIVERED = "org.rombench.telephony.SMS_DELIVERED"
        const val EXTRA_TOKEN = "token"
        const val TIMEOUT_MS = 180_000L

        private val names: Map<Int, String> by lazy {
            SmsManager::class.java.fields.filter { it.name.startsWith("RESULT_") && it.type == Int::class.javaPrimitiveType }
                .mapNotNull { f -> try { f.getInt(null) to f.name.removePrefix("RESULT_") } catch (e: Exception) { null } }
                .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.first() }
        }

        fun codeName(code: Int): String = if (code == Activity.RESULT_OK) "OK" else names[code] ?: "code $code"

        fun tpStatus(s: Int): String = when {
            s == 0 -> "delivered"
            s in 1..0x1F -> "delivered, unconfirmed"
            s in 0x20..0x3F -> "temporary error, still trying"
            else -> "permanent error"
        }

        fun fmtSec(ms: Long?): String = ms?.let { String.format(java.util.Locale.US, "%.1f", it / 1000.0) } ?: "—"

        @StringRes
        fun explain(code: Int): Int = when (code) {
            Activity.RESULT_OK -> R.string.telephony_sms_x_ok
            SmsManager.RESULT_ERROR_RADIO_OFF, SmsManager.RESULT_RADIO_NOT_AVAILABLE, SmsManager.RESULT_RIL_RADIO_NOT_AVAILABLE -> R.string.telephony_sms_x_radio
            SmsManager.RESULT_ERROR_NO_SERVICE, SmsManager.RESULT_RIL_NETWORK_NOT_READY, SmsManager.RESULT_RIL_NO_NETWORK_FOUND -> R.string.telephony_sms_x_service
            SmsManager.RESULT_INVALID_SMSC_ADDRESS, SmsManager.RESULT_RIL_INVALID_SMSC_ADDRESS -> R.string.telephony_sms_x_smsc
            SmsManager.RESULT_REQUEST_NOT_SUPPORTED, SmsManager.RESULT_RIL_REQUEST_NOT_SUPPORTED -> R.string.telephony_sms_x_unsupported
            SmsManager.RESULT_NETWORK_REJECT, SmsManager.RESULT_RIL_NETWORK_REJECT, SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY,
            SmsManager.RESULT_NETWORK_ERROR, SmsManager.RESULT_RIL_NETWORK_ERR -> R.string.telephony_sms_x_network
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED,
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE, SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> R.string.telephony_sms_x_policy
            else -> R.string.telephony_sms_x_ril
        }
    }
}
