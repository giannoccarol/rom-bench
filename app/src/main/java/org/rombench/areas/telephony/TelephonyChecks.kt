package org.rombench.areas.telephony

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.telephony.CarrierConfigManager
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.rombench.R
import org.rombench.core.Detail
import org.rombench.core.Perms
import org.rombench.core.Settings as AppSettings
import org.rombench.core.Status
import org.rombench.core.Sys
import org.rombench.core.TestResult
import org.rombench.core.Txt
import org.rombench.core.d
import org.rombench.core.raw
import org.rombench.core.result
import org.rombench.core.skippedForPermission
import org.rombench.core.txt
import kotlin.coroutines.resume

/** Automatic checks of [TelephonyArea]. Permissions are checked before each group (or caught). */
@SuppressLint("MissingPermission")
internal object TelephonyChecks {
    private val A = TelephonyArea

    const val PHH_IMS = "me.phh.ims"

    /** What one subscription looks like right now. */
    class SubState(
        val label: String,
        val subId: Int,
        val slot: Int,
        val info: SubscriptionInfo?,
        val tm: TelephonyManager,
        val service: ServiceState?,
        val signal: SignalStrength?,
    ) {
        val regs = Tel.regs(service)
        val psWwan get() = regs.firstOrNull { it.wwan && it.domain.contains("PS") }
        val csWwan get() = regs.firstOrNull { it.wwan && it.domain.contains("CS") }
        val inService get() = service?.state == ServiceState.STATE_IN_SERVICE
        val psRegistered get() = psWwan?.registered == true
    }

    suspend fun run(ctx: Context): List<TestResult> {
        val out = mutableListOf<TestResult>()
        val pm = ctx.packageManager
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val hasTel = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val props = withContext(Dispatchers.IO) { rilProps() }

        out += feature(ctx, tm, hasTel, props)
        out += ril(props)
        if (!hasTel || tm == null) return out

        val phoneMissing = Perms.missing(ctx, listOf(Perms.PHONE))
        if (phoneMissing.isNotEmpty()) {
            for ((n, t) in listOf(
                "sim" to R.string.telephony_t_sim, "registration" to R.string.telephony_t_registration,
                "data_state" to R.string.telephony_t_data_state, "signal" to R.string.telephony_t_signal,
                "cell_info" to R.string.telephony_t_cell_info, "ims" to R.string.telephony_t_ims,
            )) out += A.skippedForPermission(n, txt(t), phoneMissing)
            out += dataProbe(ctx, null)
            return out
        }

        val subs = subscriptions(ctx, tm)
        out += sim(ctx, tm, subs)
        val states = subs.ifEmpty { listOf(Triple("SIM", SubscriptionManager.INVALID_SUBSCRIPTION_ID, null)) }.map { (label, subId, info) ->
            val t = if (SubscriptionManager.isValidSubscriptionId(subId)) tm.createForSubscriptionId(subId) else tm
            val (ss, sig) = snapshot(ctx, subId, t)
            SubState(label, subId, info?.simSlotIndex ?: 0, info, t, ss, sig)
        }
        val simReady = (0 until tm.activeModemCount.coerceAtLeast(1)).any { simUsable(tm.getSimState(it)) }
        out += registration(ctx, states, simReady)
        out += dataState(ctx, tm, states)
        out += signal(states)
        out += cellInfo(ctx, tm, states)
        out += withContext(Dispatchers.IO) { ims(ctx, states, simReady) }
        out += dataProbe(ctx, states)
        return out
    }

    fun simUsable(s: Int) = s == TelephonyManager.SIM_STATE_READY || s == 10

    private fun subscriptions(ctx: Context, tm: TelephonyManager): List<Triple<String, Int, SubscriptionInfo?>> {
        val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val list = try {
            sm.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        val multi = tm.activeModemCount > 1 || list.size > 1
        return list.sortedBy { it.simSlotIndex }.map { s ->
            Triple(if (multi) "SIM${s.simSlotIndex + 1}" else "SIM", s.subscriptionId, s)
        }
    }

    /** ServiceState and SignalStrength, from the getters or, when they give nothing, from a listener. */
    suspend fun snapshot(ctx: Context, subId: Int, tm: TelephonyManager): Pair<ServiceState?, SignalStrength?> {
        var ss = try { tm.serviceState } catch (e: Exception) { null }
        var sig = try { tm.signalStrength } catch (e: Exception) { null }
        if (ss != null && sig != null) return ss to sig
        val mon = withContext(Dispatchers.Main) { RadioMonitor(ctx, subId).also { it.start() } }
        try {
            if (ss == null) ss = withTimeoutOrNull(4000) { mon.service.first { it != null } }
            if (sig == null) sig = withTimeoutOrNull(3000) { mon.signal.first { it != null } }
        } finally {
            withContext(Dispatchers.Main) { mon.stop() }
        }
        return ss to sig
    }

    // ---------------------------------------------------------------- feature and RIL

    private fun rilProps(): Map<String, String> {
        val all = Sys.allProps()
        val keys = listOf(
            "gsm.version.ril-impl", "gsm.version.baseband", "ro.baseband", "ro.boot.baseband", "rild.libpath",
            "vendor.rild.libpath", "ro.radio.noril", "ro.telephony.default_network", "persist.radio.multisim.config",
            "persist.vendor.radio.multisim.config", "gsm.sim.state", "gsm.network.type", "gsm.operator.numeric",
            "ro.telephony.iwlan_operation_mode", "persist.dbg.volte_avail_ovr", "persist.dbg.vt_avail_ovr",
            "persist.dbg.wfc_avail_ovr", "persist.dbg.ims_volte_enable", "persist.radio.calls.on.ims",
            "persist.vendor.radio.calls.on.ims", "ro.telephony.sim_slots.count",
        )
        val m = LinkedHashMap<String, String>()
        keys.forEach { k -> (all[k] ?: Sys.prop(k)).takeIf { it.isNotEmpty() }?.let { m[k] = it } }
        val svc = Regex("""^init\.svc\..*(ril|qmux|netmgr|ims|cnd|dataservices|ipacm|rmt_storage|tftp|pd_mapper|qrtr|modem|mdm|port-bridge|per_mgr).*""")
        all.filterKeys { svc.matches(it) }.forEach { (k, v) -> m[k] = v }
        return m
    }

    private fun feature(ctx: Context, tm: TelephonyManager?, hasTel: Boolean, props: Map<String, String>): TestResult {
        val t = txt(R.string.telephony_t_feature)
        val pm = ctx.packageManager
        val det = mutableListOf<Detail>()
        listOf(
            "android.hardware.telephony", "android.hardware.telephony.gsm", "android.hardware.telephony.cdma",
            "android.hardware.telephony.ims", "android.hardware.telephony.radio.access",
            "android.hardware.telephony.subscription", "android.hardware.telephony.calling",
            "android.hardware.telephony.messaging", "android.hardware.telephony.data",
        ).forEach { f -> det += d(f.removePrefix("android.hardware."), pm.hasSystemFeature(f)) }
        val rilRunning = props.any { (k, v) -> k.startsWith("init.svc.") && k.contains("ril") && v == "running" }
        if (!hasTel || tm == null) {
            return if (rilRunning || !props["gsm.version.ril-impl"].isNullOrEmpty()) A.result(
                "feature", t, Status.FAIL, txt(R.string.telephony_feature_missing),
                txt(R.string.telephony_feature_missing_ril), det,
            ) else A.result("feature", t, Status.INFO, txt(R.string.telephony_feature_none), txt(R.string.telephony_feature_none_reason), det)
        }
        val active = tm.activeModemCount
        val supported = tm.supportedModemCount
        det.add(0, d("activeModemCount", active))
        det.add(1, d("supportedModemCount", supported))
        det.add(2, d("phoneType", Tel.phoneTypeName(tm.phoneType)))
        det.add(3, d("multiSimConfig", props["persist.radio.multisim.config"] ?: props["persist.vendor.radio.multisim.config"]))
        if (active == 0) return A.result(
            "feature", t, Status.FAIL, txt(R.string.telephony_feature_no_modem), txt(R.string.telephony_feature_no_modem_reason), det,
        )
        return A.result("feature", t, Status.PASS, txt(R.string.telephony_feature_ok, active, supported), null, det)
    }

    private fun ril(props: Map<String, String>): TestResult {
        val t = txt(R.string.telephony_t_ril)
        val det = props.map { (k, v) -> d(k, v) }
        val svcs = props.filterKeys { it.startsWith("init.svc.") }
        val rilSvcs = svcs.filterKeys { it.contains("ril") }
        val baseband = props["gsm.version.baseband"].orEmpty()
        val impl = props["gsm.version.ril-impl"].orEmpty()
        val apq = (props["ro.baseband"] ?: props["ro.boot.baseband"]) == "apq"
        val restarting = svcs.filterValues { it == "restarting" }.keys.map { it.removePrefix("init.svc.") }
        val stoppedNet = svcs.filter { (k, v) -> k.contains("netmgr") && v != "running" }.keys.map { it.removePrefix("init.svc.") }
        return when {
            props["ro.radio.noril"] in listOf("1", "true", "yes") -> A.result("ril", t, Status.INFO, txt(R.string.telephony_ril_noril), txt(R.string.telephony_ril_noril_reason), det)
            restarting.isNotEmpty() -> A.result(
                "ril", t, Status.FAIL, txt(R.string.telephony_ril_restarting, restarting.joinToString()),
                txt(R.string.telephony_ril_restarting_reason), det,
            )
            rilSvcs.isNotEmpty() && rilSvcs.values.none { it == "running" } -> A.result(
                "ril", t, Status.FAIL, txt(R.string.telephony_ril_stopped, rilSvcs.keys.joinToString { it.removePrefix("init.svc.") }),
                txt(R.string.telephony_ril_stopped_reason), det,
            )
            stoppedNet.isNotEmpty() -> A.result(
                "ril", t, Status.WARN, txt(R.string.telephony_ril_netmgr, stoppedNet.joinToString()),
                txt(R.string.telephony_ril_netmgr_reason), det,
            )
            baseband.isEmpty() || impl.isEmpty() -> A.result(
                "ril", t, Status.WARN, txt(R.string.telephony_ril_no_version),
                txt(if (apq) R.string.telephony_ril_apq else R.string.telephony_ril_no_version_reason), det,
            )
            svcs.isEmpty() -> A.result("ril", t, Status.INFO, txt(R.string.telephony_ril_versions, impl, baseband), txt(R.string.telephony_ril_svc_hidden), det)
            else -> A.result("ril", t, Status.PASS, txt(R.string.telephony_ril_versions, impl, baseband), null, det)
        }
    }

    // ---------------------------------------------------------------- SIM

    @Suppress("DEPRECATION")
    private fun sim(ctx: Context, tm: TelephonyManager, subs: List<Triple<String, Int, SubscriptionInfo?>>): TestResult {
        val t = txt(R.string.telephony_t_sim)
        val det = mutableListOf<Detail>()
        val slots = tm.activeModemCount.coerceAtLeast(1)
        val states = (0 until slots).map { tm.getSimState(it) }
        states.forEachIndexed { i, s -> det += d("Slot ${i + 1} SIM state", Tel.simStateName(s)) }
        subs.forEach { (label, subId, info) ->
            val st = tm.createForSubscriptionId(subId)
            det += d("$label subId", subId)
            det += d("$label carrier", info?.carrierName)
            det += d("$label display name", info?.displayName)
            det += d("$label MCC/MNC", "${info?.mccString ?: "?"}/${info?.mncString ?: "?"}")
            det += d("$label SIM operator", "${st.simOperator} ${st.simOperatorName}".trim())
            det += d("$label carrier id", "${info?.carrierId} ${st.simCarrierIdName ?: ""}".trim())
            val iccid = try { info?.iccId } catch (e: Exception) { null }
            det += if (iccid.isNullOrEmpty()) d("$label ICCID", ctx.getString(R.string.telephony_iccid_hidden))
            else d("$label ICCID", iccid, sensitive = true)
            val number = try { info?.number } catch (e: Exception) { null }
            if (!number.isNullOrEmpty()) det += d("$label number", number, sensitive = true)
            det += d("$label country", info?.countryIso)
            det += d("$label data roaming", if (info?.dataRoaming == SubscriptionManager.DATA_ROAMING_ENABLE) "enabled" else "disabled")
            det += d("$label embedded", info?.isEmbedded)
        }
        det += d("default data sub", SubscriptionManager.getDefaultDataSubscriptionId())
        det += d("active data sub", SubscriptionManager.getActiveDataSubscriptionId())
        det += d("default voice sub", SubscriptionManager.getDefaultVoiceSubscriptionId())
        det += d("default SMS sub", SubscriptionManager.getDefaultSmsSubscriptionId())

        val names = states.joinToString(" / ") { Tel.simStateName(it) }
        val errors = states.filter { it == TelephonyManager.SIM_STATE_CARD_IO_ERROR || it == TelephonyManager.SIM_STATE_CARD_RESTRICTED || it == TelephonyManager.SIM_STATE_PERM_DISABLED }
        val ready = states.count { simUsable(it) }
        val locked = states.any { it == TelephonyManager.SIM_STATE_PIN_REQUIRED || it == TelephonyManager.SIM_STATE_PUK_REQUIRED || it == TelephonyManager.SIM_STATE_NETWORK_LOCKED }
        return when {
            errors.isNotEmpty() -> A.result("sim", t, Status.FAIL, txt(R.string.telephony_sim_error, names), txt(R.string.telephony_sim_error_reason), det)
            ready > 0 && subs.isEmpty() -> A.result("sim", t, Status.WARN, txt(R.string.telephony_sim_no_sub, names), txt(R.string.telephony_sim_no_sub_reason), det)
            ready > 0 -> {
                val first = subs.firstOrNull()?.third
                A.result(
                    "sim", t, Status.PASS,
                    txt(R.string.telephony_sim_ok, ready, first?.carrierName ?: first?.displayName ?: "?", "${first?.mccString ?: "?"}${first?.mncString ?: ""}"),
                    null, det,
                )
            }
            locked -> A.result("sim", t, Status.WARN, txt(R.string.telephony_sim_locked, names), txt(R.string.telephony_sim_locked_reason), det)
            states.all { it == TelephonyManager.SIM_STATE_ABSENT } -> A.result("sim", t, Status.WARN, txt(R.string.telephony_sim_absent), txt(R.string.telephony_sim_absent_reason), det)
            else -> A.result("sim", t, Status.WARN, txt(R.string.telephony_sim_not_ready, names), txt(R.string.telephony_sim_not_ready_reason), det)
        }
    }

    // ---------------------------------------------------------------- registration

    fun regLine(r: Tel.Reg): String = buildString {
        append(r.state); append(' '); append(Tel.ratName(r.rat))
        r.plmn?.let { append(" plmn $it") }
        if (r.rejectCause != null && r.rejectCause != 0) append(" reject ${r.rejectCause} (${Tel.rejectCauseName(r.rejectCause)})")
    }

    private fun registration(ctx: Context, states: List<SubState>, simReady: Boolean): TestResult {
        val t = txt(R.string.telephony_t_registration)
        val det = mutableListOf<Detail>()
        val airplane = Tel.airplaneMode(ctx)
        det += d("airplane mode", airplane)
        states.forEach { s ->
            val ss = s.service
            det += d("${s.label} state", ss?.let { Tel.serviceStateName(it.state) } ?: "no ServiceState")
            if (ss != null) {
                det += d("${s.label} operator", "${ss.operatorAlphaLong ?: ""} ${ss.operatorNumeric ?: ""}".trim())
                det += d("${s.label} roaming", ss.roaming)
                det += d("${s.label} manual selection", ss.isManualSelection)
                ss.channelNumber.takeIf { it != Tel.UNAVAILABLE && it >= 0 }?.let { det += d("${s.label} channel (ARFCN)", it) }
                ss.cellBandwidths.takeIf { it.isNotEmpty() }?.let { bw -> det += d("${s.label} bandwidths kHz", bw.joinToString()) }
                s.regs.forEach { r -> det += d("${s.label} ${r.label}", regLine(r)) }
            }
        }
        val s = states.first()
        val ss = s.service
        val reject = s.regs.firstOrNull { it.wwan && (it.rejectCause ?: 0) != 0 }
        val rejectTxt: Any = reject?.let { "${it.rejectCause} (${Tel.rejectCauseName(it.rejectCause!!)})" } ?: "—"
        if (airplane) return A.result("registration", t, Status.WARN, txt(R.string.telephony_reg_airplane), txt(R.string.telephony_reg_airplane_reason), det)
        if (ss == null) return A.result("registration", t, Status.WARN, txt(R.string.telephony_reg_no_state), txt(R.string.telephony_reg_no_state_reason), det)
        val op = ss.operatorAlphaLong?.takeIf { it.isNotBlank() } ?: ss.operatorNumeric ?: "?"
        return when (ss.state) {
            ServiceState.STATE_IN_SERVICE -> {
                val ps = s.psWwan
                val cs = s.csWwan
                val rat = Tel.ratName((ps?.takeIf { it.registered } ?: cs)?.rat ?: 0)
                val worstOther = states.drop(1).firstOrNull { o -> o.service?.state != ServiceState.STATE_IN_SERVICE && simUsable(o.tm.simState) }
                when {
                    ps != null && !ps.registered && cs?.registered == true -> A.result(
                        "registration", t, Status.WARN, txt(R.string.telephony_reg_voice_only, op, rat, ps.state),
                        txt(R.string.telephony_reg_voice_only_reason, rejectTxt), det,
                    )
                    worstOther != null -> A.result(
                        "registration", t, Status.WARN, txt(R.string.telephony_reg_ok, op, rat),
                        txt(R.string.telephony_reg_other_sim, worstOther.label, worstOther.service?.let { Tel.serviceStateName(it.state) } ?: "?"), det,
                    )
                    cs != null && !cs.registered && ps?.registered == true -> A.result(
                        "registration", t, Status.PASS, txt(R.string.telephony_reg_ok, op, rat), txt(R.string.telephony_reg_ps_only), det,
                    )
                    else -> A.result("registration", t, Status.PASS, txt(R.string.telephony_reg_ok, op, rat), null, det)
                }
            }
            ServiceState.STATE_EMERGENCY_ONLY -> A.result(
                "registration", t, Status.FAIL, txt(R.string.telephony_reg_emergency), txt(R.string.telephony_reg_emergency_reason, rejectTxt), det,
            )
            ServiceState.STATE_POWER_OFF -> A.result(
                "registration", t, Status.FAIL, txt(R.string.telephony_reg_power_off), txt(R.string.telephony_reg_power_off_reason), det,
            )
            else -> {
                if (!simReady) A.result("registration", t, Status.WARN, txt(R.string.telephony_reg_no_sim), txt(R.string.telephony_reg_no_sim_reason), det)
                else {
                    val denied = s.regs.any { it.wwan && it.denied }
                    val searching = s.regs.any { it.wwan && it.searching }
                    val reason: Txt = when {
                        denied -> txt(R.string.telephony_reg_denied_reason, rejectTxt)
                        searching -> txt(R.string.telephony_reg_searching_reason)
                        else -> txt(R.string.telephony_reg_idle_reason)
                    }
                    A.result("registration", t, Status.FAIL, txt(R.string.telephony_reg_not_registered), reason, det)
                }
            }
        }
    }

    // ---------------------------------------------------------------- data state

    private fun dataState(ctx: Context, tm: TelephonyManager, states: List<SubState>): TestResult {
        val t = txt(R.string.telephony_t_data_state)
        val dataSub = SubscriptionManager.getActiveDataSubscriptionId().takeIf { SubscriptionManager.isValidSubscriptionId(it) }
            ?: SubscriptionManager.getDefaultDataSubscriptionId()
        val s = states.firstOrNull { it.subId == dataSub } ?: states.first()
        val dtm = s.tm
        val dataRat = try { dtm.dataNetworkType } catch (e: Exception) { 0 }
        val voiceRat = try { dtm.voiceNetworkType } catch (e: Exception) { 0 }
        val ds = try { dtm.dataState } catch (e: Exception) { -1 }
        val act = try { dtm.dataActivity } catch (e: Exception) { -1 }
        val enabled = try { dtm.isDataEnabled } catch (e: Exception) { null }
        val roamingEnabled = try { dtm.isDataRoamingEnabled } catch (e: Exception) { null }
        val alwaysOn = Settings.Global.getInt(ctx.contentResolver, "mobile_data_always_on", 1) == 1
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val defaultCaps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val wifiDefault = defaultCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val det = listOf(
            d("data sub", "${s.label} (${s.subId})"),
            d("dataNetworkType", Tel.ratName(dataRat)),
            d("voiceNetworkType", Tel.ratName(voiceRat)),
            d("dataState", Tel.dataStateName(ds)),
            d("dataActivity", Tel.dataActivityName(act)),
            d("isDataEnabled", enabled),
            d("isDataRoamingEnabled", roamingEnabled),
            d("mobile_data_always_on", alwaysOn),
            d("default network", when {
                defaultCaps == null -> "none"
                wifiDefault -> "Wi-Fi"
                defaultCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }),
            d("voice capable", if (Build.VERSION.SDK_INT >= 35) tm.isDeviceVoiceCapable else @Suppress("DEPRECATION") tm.isVoiceCapable),
            d("SMS capable", if (Build.VERSION.SDK_INT >= 35) tm.isDeviceSmsCapable else @Suppress("DEPRECATION") tm.isSmsCapable),
        )
        val summary = txt(R.string.telephony_data_summary, Tel.ratName(dataRat), Tel.ratName(voiceRat), Tel.dataStateName(ds))
        return when {
            enabled == false -> A.result("data_state", t, Status.INFO, summary, txt(R.string.telephony_data_disabled), det)
            ds == TelephonyManager.DATA_CONNECTED -> A.result("data_state", t, Status.PASS, summary, null, det)
            !s.psRegistered -> A.result("data_state", t, Status.INFO, summary, txt(R.string.telephony_data_no_ps), det)
            wifiDefault && !alwaysOn -> A.result("data_state", t, Status.INFO, summary, txt(R.string.telephony_data_wifi_idle), det)
            else -> A.result("data_state", t, Status.WARN, summary, txt(R.string.telephony_data_not_connected), det)
        }
    }

    // ---------------------------------------------------------------- signal

    private fun signal(states: List<SubState>): TestResult {
        val t = txt(R.string.telephony_t_signal)
        val det = mutableListOf<Detail>()
        states.forEach { s ->
            val list = Tel.sigs(s.signal)
            det += d("${s.label} level", s.signal?.level)
            s.signal?.let { sig ->
                det += d("${s.label} reported", "${(android.os.SystemClock.elapsedRealtime() - sig.timestampMillis) / 1000} s ago")
            }
            list.forEach { g ->
                det += d("${s.label} ${g.tech} level", g.level)
                g.fields.forEach { (n, v) -> det += d("${s.label} $n", v ?: "unavailable") }
            }
        }
        val s = states.first()
        val list = Tel.sigs(s.signal)
        val registeredWwan = s.regs.any { it.wwan && it.registered }
        if (list.isEmpty()) {
            return if (registeredWwan) A.result("signal", t, Status.FAIL, txt(R.string.telephony_sig_none), txt(R.string.telephony_sig_none_reason), det)
            else A.result("signal", t, Status.INFO, txt(R.string.telephony_sig_none), txt(R.string.telephony_sig_none_unreg), det)
        }
        val main = list.first()
        val mainName = when (main.tech) {
            "LTE" -> "LTE RSRP"; "NR" -> "NR ssRSRP"; "WCDMA" -> "WCDMA RSCP"; "GSM" -> "GSM RSSI"; else -> null
        }
        val mainVal = mainName?.let { main.field(it) } ?: main.dbm
        val summary = txt(R.string.telephony_sig_summary, main.tech, mainName ?: "dBm", mainVal ?: "—", main.level)
        val implausible = list.flatMap { it.implausible }
        val missing = list.flatMap { g ->
            val want = when (g.tech) {
                "LTE" -> listOf("LTE RSRP", "LTE RSRQ", "LTE RSSNR"); "NR" -> listOf("NR ssRSRP", "NR ssRSRQ", "NR ssSINR")
                "WCDMA" -> listOf("WCDMA RSCP"); "GSM" -> listOf("GSM RSSI"); else -> emptyList()
            }
            want.filter { g.field(it) == null }
        }
        val lte = list.firstOrNull { it.tech == "LTE" }
        val rsrp = lte?.field("LTE RSRP")
        val rssi = lte?.field("LTE RSSI")
        return when {
            implausible.isNotEmpty() -> A.result(
                "signal", t, Status.WARN, summary,
                txt(R.string.telephony_sig_implausible, implausible.joinToString { "${it.first}=${it.second}" }), det,
            )
            rsrp != null && rssi != null && rssi < rsrp -> A.result(
                "signal", t, Status.WARN, summary, txt(R.string.telephony_sig_rssi_below, rssi, rsrp), det,
            )
            missing.isNotEmpty() -> A.result("signal", t, Status.WARN, summary, txt(R.string.telephony_sig_missing, missing.joinToString()), det)
            main.level == 0 && mainVal != null && mainVal > -110 -> A.result(
                "signal", t, Status.WARN, summary, txt(R.string.telephony_sig_level_zero), det,
            )
            else -> A.result("signal", t, Status.PASS, summary, null, det)
        }
    }

    // ---------------------------------------------------------------- cell info

    private suspend fun cellInfo(ctx: Context, tm: TelephonyManager, states: List<SubState>): TestResult {
        val t = txt(R.string.telephony_t_cell_info)
        val missing = Perms.missing(ctx, listOf(Perms.LOCATION))
        if (missing.isNotEmpty()) return A.skippedForPermission("cell_info", t, missing)
        val s = states.first()
        val ctm = s.tm
        var err: String? = null
        val fresh = withTimeoutOrNull(6000) {
            suspendCancellableCoroutine<List<CellInfo>?> { cont ->
                try {
                    ctm.requestCellInfoUpdate(ContextCompat.getMainExecutor(ctx), object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) { if (cont.isActive) cont.resume(cellInfo) }
                        override fun onError(errorCode: Int, detail: Throwable?) {
                            err = "error $errorCode ${detail?.message ?: ""}".trim()
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                } catch (e: Exception) {
                    err = e.toString()
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        val cached = if (fresh.isNullOrEmpty()) try { ctm.allCellInfo } catch (e: Exception) { err = err ?: e.toString(); null } else null
        val cells = fresh?.takeIf { it.isNotEmpty() } ?: cached ?: emptyList()
        val det = mutableListOf<Detail>()
        det += d("source", if (!fresh.isNullOrEmpty()) "requestCellInfoUpdate" else if (!cached.isNullOrEmpty()) "getAllCellInfo (cached)" else "none")
        err?.let { det += d("error", it) }
        val now = android.os.SystemClock.elapsedRealtime()
        cells.take(10).forEachIndexed { i, c -> det += cellDetails(i + 1, c, now) }
        val registered = cells.count { it.isRegistered }
        val inService = s.inService
        val summary = txt(R.string.telephony_cell_summary, cells.size, registered)
        return when {
            cells.isEmpty() && inService -> A.result("cell_info", t, Status.WARN, summary, txt(R.string.telephony_cell_empty), det)
            cells.isEmpty() -> A.result("cell_info", t, Status.INFO, summary, txt(R.string.telephony_cell_empty_unreg), det)
            registered == 0 && inService -> A.result("cell_info", t, Status.WARN, summary, txt(R.string.telephony_cell_no_serving), det)
            else -> A.result("cell_info", t, Status.PASS, summary, null, det)
        }
    }

    private fun cellDetails(n: Int, c: CellInfo, now: Long): List<Detail> {
        val id = c.cellIdentity
        val head = "Cell $n"
        val reg = if (c.isRegistered) "serving" else "neighbour"
        val age = (now - c.timestampMillis) / 1000
        val sig = Tel.sig(c.cellSignalStrength)
        val out = mutableListOf<Detail>()
        when (id) {
            is CellIdentityLte -> {
                out += d(head, "LTE $reg, EARFCN ${id.earfcn}, PCI ${id.pci}, bands ${id.bands.joinToString()}, ${id.bandwidth.takeIf { it != Tel.UNAVAILABLE }?.let { "${it / 1000} MHz" } ?: "bw ?"}, ${id.mccString ?: "?"}/${id.mncString ?: "?"}, ${sig.dbm ?: "?"} dBm, ${age}s")
                out += d("$head TAC/CI", "${id.tac}/${id.ci}", sensitive = true)
            }
            is CellIdentityNr -> {
                out += d(head, "NR $reg, NRARFCN ${id.nrarfcn}, PCI ${id.pci}, bands ${id.bands.joinToString()}, ${id.mccString ?: "?"}/${id.mncString ?: "?"}, ${sig.dbm ?: "?"} dBm, ${age}s")
                out += d("$head TAC/NCI", "${id.tac}/${id.nci}", sensitive = true)
            }
            is CellIdentityWcdma -> {
                out += d(head, "WCDMA $reg, UARFCN ${id.uarfcn}, PSC ${id.psc}, ${id.mccString ?: "?"}/${id.mncString ?: "?"}, ${sig.dbm ?: "?"} dBm, ${age}s")
                out += d("$head LAC/CID", "${id.lac}/${id.cid}", sensitive = true)
            }
            is CellIdentityGsm -> {
                out += d(head, "GSM $reg, ARFCN ${id.arfcn}, BSIC ${id.bsic}, ${id.mccString ?: "?"}/${id.mncString ?: "?"}, ${sig.dbm ?: "?"} dBm, ${age}s")
                out += d("$head LAC/CID", "${id.lac}/${id.cid}", sensitive = true)
            }
            else -> out += d(head, "${c.javaClass.simpleName.removePrefix("CellInfo")} $reg, ${sig.dbm ?: "?"} dBm, ${age}s")
        }
        return out
    }

    // ---------------------------------------------------------------- IMS

    class ImsFacts(
        val registered: Boolean?,
        val source: String?,
        val volteCarrier: Boolean?,
        val volteDevice: Boolean?,
        val details: List<Detail>,
        val phhInstalled: Boolean,
        val mmtelPackage: String?,
    )

    @Suppress("DEPRECATION")
    fun imsFacts(ctx: Context, s: SubState, withDump: Boolean): ImsFacts {
        val det = mutableListOf<Detail>()
        val ccm = ctx.getSystemService(CarrierConfigManager::class.java)
        val cfg = try { ccm?.getConfigForSubId(s.subId) } catch (e: Exception) { null }
        val volteCarrier = cfg?.getBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL)
        if (cfg != null) {
            det += d("carrier config identified", CarrierConfigManager.isConfigForIdentifiedCarrier(cfg))
            det += d("carrier_volte_available", volteCarrier)
            det += d("carrier_wfc_ims_available", cfg.getBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL))
            det += d("carrier_vt_available", cfg.getBoolean(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL))
            det += d("carrier_volte_provisioning_required", cfg.getBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL))
            det += d("hide_enhanced_4g_lte", cfg.getBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL))
            det += d("enhanced_4g_lte_on_by_default", cfg.getBoolean(CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL))
            cfg.getString(CarrierConfigManager.KEY_CARRIER_CONFIG_VERSION_STRING)?.let { det += d("carrier_config_version", it) }
        } else det += d("carrier config", "unavailable")
        val volteDevice = Tel.sysBool("config_device_volte_available")
        det += d("config_device_volte_available", volteDevice)
        det += d("config_device_wfc_ims_available", Tel.sysBool("config_device_wfc_ims_available"))
        det += d("config_device_vt_available", Tel.sysBool("config_device_vt_available"))
        det += d("config_dynamic_bind_ims", Tel.sysBool("config_dynamic_bind_ims"))
        val mmtel = Tel.sysString("config_ims_mmtel_package")?.takeIf { it.isNotEmpty() } ?: Tel.sysString("config_ims_package")
        det += d("config_ims_mmtel_package", mmtel)

        val phh = try {
            ctx.packageManager.getPackageInfo(PHH_IMS, 0)
        } catch (e: Exception) {
            null
        }
        if (phh != null) {
            val ai = phh.applicationInfo
            det += d("phh IMS", "${phh.versionName} (${if (ai != null && ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) "system" else "user"} app, ${if (ai?.enabled != false) "enabled" else "disabled"})")
        } else det += d("phh IMS", "not installed")

        val hidden = listOf("isImsRegistered", "isVolteAvailable", "isWifiCallingAvailable", "isVideoTelephonyAvailable")
            .associateWith { Tel.hiddenBool(s.tm, it) }
        hidden.forEach { (k, v) -> det += d("TelephonyManager.$k", v ?: ctx.getString(R.string.telephony_not_exposed)) }

        var registered = hidden["isImsRegistered"]
        var source: String? = if (registered != null) "TelephonyManager.isImsRegistered" else null
        if (withDump) {
            val lines = mutableListOf<String>()
            val reBool = Regex("""(?i)(?:m|is)?ImsRegistered\s*[=:]\s*(true|false)""")
            val reState = Regex("""(?i)ImsRegistrationState\s*[=:]\s*(\w+)""")
            for (svc in listOf("telephony.registry", "phone")) {
                val r = Sys.exec("dumpsys", svc, timeoutMs = 10_000)
                r.out.lineSequence().filter { it.contains("ims", ignoreCase = true) && it.contains("regist", ignoreCase = true) }
                    .map { it.trim().take(160) }.distinct().take(8).forEach { lines += "$svc: $it" }
                if (registered == null) {
                    reBool.find(r.out)?.let { registered = it.groupValues[1].equals("true", true); source = "dumpsys $svc" }
                }
                if (registered == null) {
                    reState.find(r.out)?.groupValues?.get(1)?.let { v ->
                        registered = v.equals("REGISTERED", true) || v == "2"
                        source = "dumpsys $svc"
                    }
                }
            }
            lines.take(10).forEachIndexed { i, l -> det += d("dumpsys ${i + 1}", l) }
        }
        listOf("persist.dbg.volte_avail_ovr", "persist.dbg.vt_avail_ovr", "persist.dbg.wfc_avail_ovr", "persist.dbg.ims_volte_enable")
            .forEach { k -> Sys.prop(k).takeIf { it.isNotEmpty() }?.let { det += d(k, it) } }
        return ImsFacts(registered, source, volteCarrier, volteDevice, det, phh != null, mmtel)
    }

    private fun ims(ctx: Context, states: List<SubState>, simReady: Boolean): TestResult {
        val t = txt(R.string.telephony_t_ims)
        val s = states.first()
        val dump = Perms.hasDump(ctx)
        val f = imsFacts(ctx, s, dump)
        val det = f.details + listOfNotNull(f.source?.let { d("registration source", it) })
        val volteOvr = Sys.prop("persist.dbg.volte_avail_ovr") == "1"
        val wanted = f.volteCarrier == true || volteOvr
        val phhNote = f.phhInstalled && f.mmtelPackage != null && f.mmtelPackage != PHH_IMS
        return when {
            f.registered == true -> A.result("ims", t, Status.PASS, txt(R.string.telephony_ims_registered), null, det)
            f.volteDevice == false && !volteOvr -> A.result("ims", t, Status.INFO, txt(R.string.telephony_ims_device_off), txt(R.string.telephony_ims_device_off_reason), det)
            f.registered == false && wanted && simReady && s.inService -> A.result(
                "ims", t, Status.WARN, txt(R.string.telephony_ims_not_registered),
                if (phhNote) txt(R.string.telephony_ims_phh_not_selected, f.mmtelPackage) else txt(R.string.telephony_ims_not_registered_reason), det,
            )
            f.registered == false -> A.result("ims", t, Status.INFO, txt(R.string.telephony_ims_not_registered), txt(R.string.telephony_ims_not_wanted), det)
            else -> A.result(
                "ims", t, Status.INFO, txt(R.string.telephony_ims_unknown),
                txt(if (dump) R.string.telephony_ims_unknown_dump else R.string.telephony_ims_unknown_reason), det,
            )
        }
    }

    // ---------------------------------------------------------------- mobile data probe

    suspend fun dataProbe(ctx: Context, states: List<SubState>?): TestResult {
        val t = txt(R.string.telephony_t_data_probe)
        val url = AppSettings.probeUrl(ctx)
        val dataSub = SubscriptionManager.getActiveDataSubscriptionId().takeIf { SubscriptionManager.isValidSubscriptionId(it) }
            ?: SubscriptionManager.getDefaultDataSubscriptionId()
        val s = states?.let { l -> l.firstOrNull { it.subId == dataSub } ?: l.first() }
        val enabled = if (s != null) try { s.tm.isDataEnabled } catch (e: Exception) { null } else null
        val o = DataProbe.run(ctx, url)
        val det = mutableListOf(d("URL", url), d("setup", o.setupMs?.let { "$it ms" } ?: "not available"))
        o.requestError?.let { det += d("requestNetwork", it) }
        o.iface?.let { det += d("interface", it) }
        if (o.addresses.isNotEmpty()) det += d("addresses", o.addresses.joinToString(), sensitive = true)
        if (o.dns.isNotEmpty()) det += d("DNS servers", o.dns.joinToString())
        o.mtu?.let { det += d("MTU", it) }
        if (o.downKbps != null) det += d("estimated bandwidth", "${o.downKbps} / ${o.upKbps} kbps")
        o.dnsMs?.let { det += d("DNS lookup", "$it ms") }
        o.httpCode?.let { det += d("HTTP", "$it in ${o.httpMs} ms") }
        o.error?.let { det += d("error", it) }
        enabled?.let { det += d("isDataEnabled", it) }
        if (Tel.airplaneMode(ctx)) return A.result("data_probe", t, Status.SKIP, txt(R.string.telephony_probe_airplane), null, det)
        return when {
            o.requestError != null -> A.result("data_probe", t, Status.FAIL, txt(R.string.telephony_probe_request_failed), raw(o.requestError), det)
            !o.up && enabled == false -> A.result("data_probe", t, Status.WARN, txt(R.string.telephony_probe_disabled), txt(R.string.telephony_probe_disabled_reason), det)
            !o.up && s != null && !s.psRegistered -> A.result("data_probe", t, Status.FAIL, txt(R.string.telephony_probe_no_network), txt(R.string.telephony_probe_not_registered), det)
            !o.up -> A.result("data_probe", t, Status.FAIL, txt(R.string.telephony_probe_no_network), txt(R.string.telephony_probe_setup_failed), det)
            o.httpOk -> A.result("data_probe", t, Status.PASS, txt(R.string.telephony_probe_ok, o.iface ?: "?", o.httpMs, o.setupMs), null, det)
            o.httpCode != null -> A.result("data_probe", t, Status.WARN, txt(R.string.telephony_probe_http, o.httpCode), txt(R.string.telephony_probe_http_reason), det)
            else -> A.result("data_probe", t, Status.WARN, txt(R.string.telephony_probe_no_traffic, o.iface ?: "?"), txt(R.string.telephony_probe_no_traffic_reason), det)
        }
    }
}
