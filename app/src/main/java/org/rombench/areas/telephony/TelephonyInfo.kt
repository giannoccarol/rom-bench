package org.rombench.areas.telephony

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import android.telephony.AccessNetworkConstants
import android.telephony.CellInfo
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager

/** Names and parsers shared by the automatic checks and the live screen. Labels stay English. */
internal object Tel {
    const val UNAVAILABLE = CellInfo.UNAVAILABLE

    fun ratName(t: Int): String = when (t) {
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        4 -> "CDMA"
        5 -> "EVDO_0"
        6 -> "EVDO_A"
        7 -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        11 -> "IDEN"
        12 -> "EVDO_B"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        14 -> "EHRPD"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        19 -> "LTE_CA"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        else -> "type $t"
    }

    /** Generation of a RAT, used to tell VoLTE from a circuit-switched fallback during a call. */
    fun ratGeneration(t: Int): Int = when (t) {
        TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, 4,
        7, TelephonyManager.NETWORK_TYPE_GSM, 11 -> 2
        TelephonyManager.NETWORK_TYPE_UMTS, 5, 6,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
        12, 14, TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> 3
        TelephonyManager.NETWORK_TYPE_LTE, 19 -> 4
        TelephonyManager.NETWORK_TYPE_NR -> 5
        TelephonyManager.NETWORK_TYPE_IWLAN -> 9
        else -> 0
    }

    fun overrideName(o: Int): String = when (o) {
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE -> "NONE"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE_CA"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE_ADVANCED_PRO"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "NR_NSA"
        4 -> "NR_NSA_MMWAVE"
        5 -> "NR_ADVANCED"
        else -> "override $o"
    }

    fun simStateName(s: Int): String = when (s) {
        TelephonyManager.SIM_STATE_UNKNOWN -> "UNKNOWN"
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM_DISABLED"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD_IO_ERROR"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "CARD_RESTRICTED"
        10 -> "LOADED"
        11 -> "PRESENT"
        else -> "state $s"
    }

    fun serviceStateName(s: Int): String = when (s) {
        ServiceState.STATE_IN_SERVICE -> "IN_SERVICE"
        ServiceState.STATE_OUT_OF_SERVICE -> "OUT_OF_SERVICE"
        ServiceState.STATE_EMERGENCY_ONLY -> "EMERGENCY_ONLY"
        ServiceState.STATE_POWER_OFF -> "POWER_OFF"
        else -> "state $s"
    }

    fun dataStateName(s: Int): String = when (s) {
        TelephonyManager.DATA_DISCONNECTED -> "DISCONNECTED"
        TelephonyManager.DATA_CONNECTING -> "CONNECTING"
        TelephonyManager.DATA_CONNECTED -> "CONNECTED"
        TelephonyManager.DATA_SUSPENDED -> "SUSPENDED"
        4 -> "DISCONNECTING"
        5 -> "HANDOVER_IN_PROGRESS"
        else -> "UNKNOWN"
    }

    fun dataActivityName(a: Int): String = when (a) {
        TelephonyManager.DATA_ACTIVITY_NONE -> "NONE"
        TelephonyManager.DATA_ACTIVITY_IN -> "IN"
        TelephonyManager.DATA_ACTIVITY_OUT -> "OUT"
        TelephonyManager.DATA_ACTIVITY_INOUT -> "INOUT"
        TelephonyManager.DATA_ACTIVITY_DORMANT -> "DORMANT"
        else -> "UNKNOWN"
    }

    fun callStateName(s: Int): String = when (s) {
        TelephonyManager.CALL_STATE_IDLE -> "IDLE"
        TelephonyManager.CALL_STATE_RINGING -> "RINGING"
        TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
        else -> "state $s"
    }

    fun phoneTypeName(t: Int): String = when (t) {
        TelephonyManager.PHONE_TYPE_NONE -> "NONE"
        TelephonyManager.PHONE_TYPE_GSM -> "GSM"
        2 -> "CDMA"
        TelephonyManager.PHONE_TYPE_SIP -> "SIP"
        else -> "type $t"
    }

    /** EMM/MM reject causes (3GPP TS 24.301 / 24.008) that a ROM developer meets most often. */
    fun rejectCauseName(c: Int): String = when (c) {
        0 -> "none"
        2 -> "IMSI unknown in HSS/HLR"
        3 -> "illegal UE"
        5 -> "IMEI not accepted"
        6 -> "illegal ME"
        7 -> "EPS services not allowed"
        8 -> "EPS and non-EPS services not allowed"
        9 -> "UE identity cannot be derived"
        10 -> "implicitly detached"
        11 -> "PLMN not allowed"
        12 -> "tracking area not allowed"
        13 -> "roaming not allowed in this tracking area"
        14 -> "EPS services not allowed in this PLMN"
        15 -> "no suitable cells in tracking area"
        16 -> "MSC temporarily not reachable"
        17 -> "network failure"
        18 -> "CS domain not available"
        19 -> "ESM failure"
        22 -> "congestion"
        25 -> "not authorized for this CSG"
        35 -> "requested service option not authorized"
        40 -> "no EPS bearer context activated"
        42 -> "severe network failure"
        111 -> "protocol error, unspecified"
        else -> "cause $c"
    }

    /** One entry of ServiceState.getNetworkRegistrationInfoList(). */
    data class Reg(
        val domain: String,
        val transport: String,
        val rat: Int,
        val state: String,
        val rejectCause: Int?,
        val plmn: String?,
        val emergencyEnabled: Boolean,
    ) {
        val registered get() = state == "HOME" || state == "ROAMING"
        val denied get() = state == "DENIED"
        val searching get() = state == "NOT_REG_SEARCHING" || state == "SEARCHING"
        val wwan get() = transport == "WWAN"
        val label get() = "$domain/$transport"
    }

    private val reNetState = Regex("""networkRegistrationState=([A-Z_]+)""")
    private val reState = Regex("""\bregistrationState=([A-Z_]+)""")
    private val reReject = Regex("""rejectCause=(-?\d+)""")
    private val reEmergency = Regex("""emergencyEnabled=(true|false)""")

    @Suppress("DEPRECATION")
    fun regs(ss: ServiceState?): List<Reg> {
        val list = try {
            ss?.networkRegistrationInfoList ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return list.map { nri ->
            // toString() carries the raw registration state (DENIED, SEARCHING...) and the reject
            // cause on every release; the public getters only arrived in API 34/35.
            val s = nri.toString()
            val state = reNetState.find(s)?.groupValues?.get(1)
                ?: reState.find(s)?.groupValues?.get(1)
                ?: when {
                    nri.isRegistered -> if (nri.isRoaming) "ROAMING" else "HOME"
                    nri.isSearching -> "NOT_REG_SEARCHING"
                    else -> "NOT_REG"
                }
            val reject = if (Build.VERSION.SDK_INT >= 35) nri.rejectCause else reReject.find(s)?.groupValues?.get(1)?.toIntOrNull()
            Reg(
                domain = when (nri.domain) {
                    NetworkRegistrationInfo.DOMAIN_CS -> "CS"
                    NetworkRegistrationInfo.DOMAIN_PS -> "PS"
                    NetworkRegistrationInfo.DOMAIN_CS_PS -> "CS+PS"
                    else -> "?"
                },
                transport = when (nri.transportType) {
                    AccessNetworkConstants.TRANSPORT_TYPE_WWAN -> "WWAN"
                    AccessNetworkConstants.TRANSPORT_TYPE_WLAN -> "WLAN"
                    else -> "?"
                },
                rat = nri.accessNetworkTechnology,
                state = state,
                rejectCause = reject,
                plmn = nri.registeredPlmn?.takeIf { it.isNotBlank() },
                emergencyEnabled = reEmergency.find(s)?.groupValues?.get(1) == "true",
            )
        }
    }

    /** One CellSignalStrength reduced to the fields a ROM developer checks. */
    data class Sig(
        val tech: String,
        val level: Int,
        val dbm: Int?,
        /** name → value (null = UNAVAILABLE) */
        val fields: List<Pair<String, Int?>>,
        /** Fields outside the physical range: name → value. */
        val implausible: List<Pair<String, Int>>,
    ) {
        fun field(name: String): Int? = fields.firstOrNull { it.first == name }?.second
    }

    private fun v(x: Int): Int? = x.takeIf { it != UNAVAILABLE && it != Int.MIN_VALUE }

    private val ranges = mapOf(
        "LTE RSRP" to -140..-44, "LTE RSRQ" to -34..3, "LTE RSSNR" to -20..30, "LTE RSSI" to -113..-25,
        "LTE CQI" to 0..15, "NR ssRSRP" to -156..-31, "NR ssRSRQ" to -43..20, "NR ssSINR" to -23..40,
        "WCDMA RSCP" to -120..-24, "WCDMA EcNo" to -24..1, "GSM RSSI" to -113..-51, "TDSCDMA RSCP" to -120..-24,
    )

    fun sig(c: CellSignalStrength): Sig {
        val (tech, fields) = when (c) {
            is CellSignalStrengthLte -> "LTE" to listOf(
                "LTE RSRP" to v(c.rsrp), "LTE RSRQ" to v(c.rsrq), "LTE RSSNR" to v(c.rssnr),
                "LTE RSSI" to v(c.rssi), "LTE CQI" to v(c.cqi), "LTE TA" to v(c.timingAdvance),
            )
            is CellSignalStrengthNr -> "NR" to listOf(
                "NR ssRSRP" to v(c.ssRsrp), "NR ssRSRQ" to v(c.ssRsrq), "NR ssSINR" to v(c.ssSinr),
                "NR csiRSRP" to v(c.csiRsrp), "NR csiSINR" to v(c.csiSinr),
            )
            is CellSignalStrengthWcdma -> "WCDMA" to listOf("WCDMA RSCP" to v(c.dbm), "WCDMA EcNo" to v(c.ecNo))
            is CellSignalStrengthGsm -> "GSM" to listOf(
                "GSM RSSI" to v(c.rssi), "GSM BER" to v(c.bitErrorRate), "GSM TA" to v(c.timingAdvance),
            )
            is CellSignalStrengthTdscdma -> "TDSCDMA" to listOf("TDSCDMA RSCP" to v(c.rscp))
            else -> c.javaClass.simpleName.removePrefix("CellSignalStrength").uppercase() to listOf("dBm" to v(c.dbm))
        }
        val bad = fields.mapNotNull { (n, x) -> val r = ranges[n]; if (x != null && r != null && x !in r) n to x else null }
        return Sig(tech, c.level, v(c.dbm), fields, bad)
    }

    fun sigs(ss: SignalStrength?): List<Sig> = try {
        ss?.cellSignalStrengths?.map { sig(it) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** RSRP-like value for the chart (LTE RSRP, NR SS-RSRP), and an RSSI-like one (LTE RSSI, GSM RSSI, WCDMA RSCP). */
    fun chartValues(list: List<Sig>): Pair<Float, Float> {
        val rsrp = list.firstNotNullOfOrNull { it.field("LTE RSRP") ?: it.field("NR ssRSRP") }
        val rssi = list.firstNotNullOfOrNull { it.field("LTE RSSI") ?: it.field("GSM RSSI") ?: it.field("WCDMA RSCP") }
        return (rsrp?.toFloat() ?: Float.NaN) to (rssi?.toFloat() ?: Float.NaN)
    }

    fun airplaneMode(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    /** Framework config resource (overlay value), readable by every app. */
    @SuppressLint("DiscouragedApi")
    fun sysBool(name: String): Boolean? = try {
        val id = Resources.getSystem().getIdentifier(name, "bool", "android")
        if (id == 0) null else Resources.getSystem().getBoolean(id)
    } catch (e: Exception) {
        null
    }

    @SuppressLint("DiscouragedApi")
    fun sysString(name: String): String? = try {
        val id = Resources.getSystem().getIdentifier(name, "string", "android")
        if (id == 0) null else Resources.getSystem().getString(id)
    } catch (e: Exception) {
        null
    }

    /** Hidden TelephonyManager boolean getter; null when the hidden-API policy blocks it. */
    fun hiddenBool(tm: TelephonyManager, name: String): Boolean? = try {
        TelephonyManager::class.java.getMethod(name).invoke(tm) as? Boolean
    } catch (e: Throwable) {
        null
    }
}
