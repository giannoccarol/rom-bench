package org.rombench.areas.telephony

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.telephony.CarrierConfigManager
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.WifiCalling3
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.rombench.R
import org.rombench.core.Kind
import org.rombench.core.Perms
import org.rombench.core.ResultStore
import org.rombench.core.Status
import org.rombench.core.TestResult
import org.rombench.core.d
import org.rombench.core.result
import org.rombench.core.text
import org.rombench.core.txt
import org.rombench.ui.SubHeader
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.GuidedCheck
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.LineChart
import org.rombench.ui.components.Metric
import org.rombench.ui.components.PermissionGate
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.SeriesBuffer
import org.rombench.ui.components.StatusChip
import org.rombench.ui.components.StatusDot
import org.rombench.ui.components.rememberPermissions
import org.rombench.ui.components.rememberPolled
import org.rombench.ui.theme.LocalStatusColors
import org.rombench.ui.theme.Mono

private data class SubChoice(val label: String, val subId: Int)

@Composable
internal fun TelephonyContent() {
    val ctx = LocalContext.current
    if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
        SectionCard(stringResource(R.string.telephony_live_title), icon = Icons.Outlined.CellTower) {
            Hint(stringResource(R.string.telephony_ui_no_feature))
        }
        return
    }
    val perms = rememberPermissions(listOf(Perms.PHONE))
    if (!perms.allGranted) {
        SectionCard(stringResource(R.string.telephony_live_title), icon = Icons.Outlined.CellTower) {
            Hint(stringResource(R.string.telephony_ui_need_phone))
        }
        return
    }
    val subs = remember { subChoices(ctx) }
    var subId by rememberSaveable {
        mutableIntStateOf(subs.firstOrNull { it.subId == SubscriptionManager.getDefaultDataSubscriptionId() }?.subId
            ?: subs.firstOrNull()?.subId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID)
    }
    val mon = remember(subId) { RadioMonitor(ctx, subId) }
    DisposableEffect(mon) {
        mon.onCallState = { ev, prev -> onCallEvent(ctx, mon, ev, prev) }
        mon.start()
        onDispose { mon.stop() }
    }

    LiveCard(mon, subs, subId) { subId = it }
    SignalCard(mon)
    ImsDataCard(mon)
    CallCard(mon)
    SmsCard(subId)
}

private fun subChoices(ctx: Context): List<SubChoice> {
    val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
    val list = try { sm.activeSubscriptionInfoList ?: emptyList() } catch (e: SecurityException) { emptyList() }
    return list.sortedBy { it.simSlotIndex }.map { SubChoice("SIM${it.simSlotIndex + 1} · ${it.displayName ?: it.carrierName ?: ""}".trim(), it.subscriptionId) }
}

private fun regStatus(r: Tel.Reg): Status = when {
    r.registered -> Status.PASS
    r.denied -> Status.FAIL
    r.searching -> Status.WARN
    else -> Status.SKIP
}

@SuppressLint("MissingPermission")
@Composable
private fun LiveCard(mon: RadioMonitor, subs: List<SubChoice>, subId: Int, onSub: (Int) -> Unit) {
    val ctx = LocalContext.current
    val ss by mon.service.collectAsState()
    val data by mon.data.collectAsState()
    val act by mon.activity.collectAsState()
    val display by mon.display.collectAsState()
    val rats by rememberPolled(1000, mon) { mon.voiceRat() to mon.dataRat() }
    val airplane by rememberPolled(2000) { Tel.airplaneMode(ctx) }
    val regs = Tel.regs(ss)

    SectionCard(
        stringResource(R.string.telephony_live_title), icon = Icons.Outlined.CellTower,
        subtitle = stringResource(R.string.telephony_live_sub),
    ) {
        if (subs.size > 1) ButtonRow {
            subs.forEach { s -> FilterChip(selected = s.subId == subId, onClick = { onSub(s.subId) }, label = { Text(s.label) }) }
        }
        if (subs.isEmpty()) Hint(stringResource(R.string.telephony_ui_no_sub))
        if (airplane == true) Hint(stringResource(R.string.telephony_reg_airplane))
        val state = ss?.state
        val stStatus = when (state) {
            ServiceState.STATE_IN_SERVICE -> Status.PASS
            ServiceState.STATE_EMERGENCY_ONLY, ServiceState.STATE_OUT_OF_SERVICE -> Status.FAIL
            null -> null
            else -> Status.WARN
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Metric(state?.let { Tel.serviceStateName(it).replace('_', ' ') } ?: "—", stringResource(R.string.telephony_ui_service), Modifier.weight(1.4f), stStatus)
            Metric(rats?.first?.let { Tel.ratName(it) } ?: "—", stringResource(R.string.telephony_ui_voice), Modifier.weight(1f))
            Metric(rats?.second?.let { Tel.ratName(it) } ?: "—", stringResource(R.string.telephony_ui_data), Modifier.weight(1f))
        }
        ss?.let { s ->
            KeyValue(stringResource(R.string.telephony_ui_operator), "${s.operatorAlphaLong ?: ""} ${s.operatorNumeric ?: ""}".trim().ifEmpty { "—" })
            KeyValue(stringResource(R.string.telephony_ui_roaming), s.roaming.toString())
        }
        display?.let { KeyValue("override", Tel.overrideName(it.overrideNetworkType)) }
        KeyValue("dataState", "${Tel.dataStateName(data.first)} · ${Tel.dataActivityName(act)}")
        if (regs.isNotEmpty()) {
            SubHeader(stringResource(R.string.telephony_ui_regs))
            regs.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(regStatus(r))
                    Spacer(Modifier.width(8.dp))
                    Text(r.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(92.dp))
                    Text(TelephonyChecks.regLine(r), style = Mono, modifier = Modifier.weight(1f))
                }
            }
        } else if (ss == null) Hint(stringResource(R.string.telephony_ui_waiting))
        mon.error?.let { Hint(it) }
    }
}

@Composable
private fun SignalCard(mon: RadioMonitor) {
    val sig by mon.signal.collectAsState()
    val lastAt by mon.lastSignalAt.collectAsState()
    val buf = remember(mon) { SeriesBuffer(180, 2) }
    LaunchedEffect(mon) {
        while (true) {
            val (a, b) = Tel.chartValues(Tel.sigs(mon.signal.value))
            buf.add(a, b)
            delay(1000)
        }
    }
    val now by rememberPolled(1000) { SystemClock.elapsedRealtime() }
    val list = Tel.sigs(sig)
    SectionCard(
        stringResource(R.string.telephony_signal_title), icon = Icons.AutoMirrored.Outlined.ShowChart,
        subtitle = stringResource(R.string.telephony_signal_sub),
    ) {
        if (list.isEmpty()) Hint(stringResource(R.string.telephony_sig_none))
        list.forEach { g ->
            val sc = LocalStatusColors.current
            Text("${g.tech} · level ${g.level}/4", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                g.fields.take(3).forEach { (n, v) ->
                    val bad = g.implausible.any { it.first == n }
                    Metric(
                        v?.toString() ?: "—", n.substringAfter(' '), Modifier.weight(1f),
                        status = if (bad) Status.WARN else if (v == null) Status.SKIP else null,
                    )
                }
            }
            g.fields.drop(3).let { rest ->
                if (rest.isNotEmpty()) Text(
                    rest.joinToString("   ") { (n, v) -> "${n.substringAfter(' ')} ${v ?: "—"}" },
                    style = Mono, color = if (g.implausible.isNotEmpty()) sc.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LineChart(buf, range = -140f..-40f, labels = listOf("RSRP", "RSSI"), unit = " dBm")
        val n = now
        if (lastAt > 0 && n != null) Hint(stringResource(R.string.telephony_signal_last, (n - lastAt) / 1000))
    }
}

@Composable
private fun ImsDataCard(mon: RadioMonitor) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ims by rememberPolled(3000, mon) {
        listOf("isImsRegistered", "isVolteAvailable", "isWifiCallingAvailable").map { it to Tel.hiddenBool(mon.tm, it) }
    }
    val phh = remember {
        try { ctx.packageManager.getPackageInfo(TelephonyChecks.PHH_IMS, 0).versionName ?: "?" } catch (e: Exception) { null }
    }
    var probing by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<TestResult?>(null) }
    SectionCard(
        stringResource(R.string.telephony_ims_title), icon = Icons.Outlined.WifiCalling3,
        subtitle = stringResource(R.string.telephony_ims_sub),
    ) {
        ims?.forEach { (k, v) -> KeyValue(k, v?.toString() ?: stringResource(R.string.telephony_not_exposed)) }
        KeyValue("phh IMS", phh ?: stringResource(R.string.telephony_ui_not_installed))
        if (ims?.all { it.second == null } == true) Hint(stringResource(R.string.telephony_ui_hidden_blocked))
        SubHeader(stringResource(R.string.telephony_t_data_probe))
        Hint(stringResource(R.string.telephony_ui_probe_hint))
        Button(onClick = {
            probing = true
            scope.launch {
                val r = TelephonyChecks.dataProbe(ctx, null)
                ResultStore.put(r)
                probe = r
                probing = false
            }
        }, enabled = !probing) {
            if (probing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.NetworkCheck, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (probing) R.string.telephony_ui_probing else R.string.telephony_ui_probe))
        }
        probe?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(r.status)
                Spacer(Modifier.width(8.dp))
                Text(r.summary.text(), style = MaterialTheme.typography.bodyMedium)
            }
            r.reason?.let { Hint(it.text()) }
        }
    }
}

// ------------------------------------------------------------------------------ call test

private fun onCallEvent(ctx: Context, mon: RadioMonitor, ev: RadioMonitor.CallEvent, prev: Int) {
    val a = TelephonyArea
    when (ev.state) {
        TelephonyManager.CALL_STATE_RINGING -> ResultStore.put(
            a.result(
                "call_in", txt(R.string.telephony_t_call_in), Status.PASS, txt(R.string.telephony_call_in_seen),
                null, listOf(d("voice RAT", Tel.ratName(ev.voiceRat)), d("data RAT", Tel.ratName(ev.dataRat))), Kind.GUIDED,
            ),
        )
        TelephonyManager.CALL_STATE_OFFHOOK -> if (prev != TelephonyManager.CALL_STATE_OFFHOOK) {
            val incoming = prev == TelephonyManager.CALL_STATE_RINGING
            mon.later(3000) {
                if (mon.call.value == TelephonyManager.CALL_STATE_OFFHOOK) ResultStore.put(callPath(ctx, mon, incoming))
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun callPath(ctx: Context, mon: RadioMonitor, incoming: Boolean): TestResult {
    val voice = mon.voiceRat()
    val data = mon.dataRat()
    val ims = Tel.hiddenBool(mon.tm, "isImsRegistered")
    val volteCarrier = try {
        ctx.getSystemService(CarrierConfigManager::class.java)?.getConfigForSubId(mon.subId)
            ?.getBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL)
    } catch (e: Exception) {
        null
    }
    val det = listOf(
        d("direction", if (incoming) "incoming" else "outgoing"),
        d("voiceNetworkType", Tel.ratName(voice)), d("dataNetworkType", Tel.ratName(data)),
        d("isImsRegistered", ims ?: "not exposed"), d("carrier_volte_available", volteCarrier),
    )
    val t = txt(R.string.telephony_t_call_path)
    val vg = Tel.ratGeneration(voice)
    val dg = Tel.ratGeneration(data)
    val a = TelephonyArea
    return when {
        vg == 9 || (vg == 0 && dg == 9) -> a.result("call_path", t, Status.PASS, txt(R.string.telephony_call_vowifi), null, det, Kind.GUIDED)
        vg >= 4 && vg != 9 -> a.result("call_path", t, Status.PASS, txt(R.string.telephony_call_volte, Tel.ratName(voice)), null, det, Kind.GUIDED)
        vg == 0 && dg >= 4 -> a.result("call_path", t, Status.PASS, txt(R.string.telephony_call_volte, Tel.ratName(data)), txt(R.string.telephony_call_volte_nocs), det, Kind.GUIDED)
        vg in 2..3 && volteCarrier == true -> a.result(
            "call_path", t, Status.WARN, txt(R.string.telephony_call_cs, Tel.ratName(voice)), txt(R.string.telephony_call_csfb_reason), det, Kind.GUIDED,
        )
        vg in 2..3 -> a.result("call_path", t, Status.INFO, txt(R.string.telephony_call_cs, Tel.ratName(voice)), null, det, Kind.GUIDED)
        else -> a.result("call_path", t, Status.INFO, txt(R.string.telephony_call_unknown), txt(R.string.telephony_call_unknown_reason), det, Kind.GUIDED)
    }
}

@Composable
private fun CallCard(mon: RadioMonitor) {
    val ctx = LocalContext.current
    val call by mon.call.collectAsState()
    val events by mon.events.collectAsState()
    val rats by rememberPolled(1000, mon) { mon.voiceRat() to mon.dataRat() }
    val now by rememberPolled(1000) { SystemClock.elapsedRealtime() }
    var number by rememberSaveable { mutableStateOf("") }
    val a = TelephonyArea
    SectionCard(
        stringResource(R.string.telephony_call_title), icon = Icons.Outlined.Call,
        subtitle = stringResource(R.string.telephony_call_sub),
    ) {
        OutlinedTextField(
            number, { number = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.telephony_call_number)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        ButtonRow {
            Button(onClick = {
                val i = if (number.isBlank()) Intent(Intent.ACTION_DIAL) else Intent(Intent.ACTION_DIAL, ("tel:" + Uri.encode(number.trim())).toUri())
                try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
            }) {
                Icon(Icons.Outlined.Dialpad, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.telephony_call_dial))
            }
        }
        val sc = LocalStatusColors.current
        val st = when (call) {
            TelephonyManager.CALL_STATE_RINGING -> Status.WARN
            TelephonyManager.CALL_STATE_OFFHOOK -> Status.PASS
            else -> Status.SKIP
        }
        Surface(shape = RoundedCornerShape(14.dp), color = sc.container(st), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        when (call) {
                            TelephonyManager.CALL_STATE_RINGING -> R.string.telephony_call_ringing
                            TelephonyManager.CALL_STATE_OFFHOOK -> R.string.telephony_call_offhook
                            else -> R.string.telephony_call_idle
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = sc.of(st),
                )
                rats?.let { (v, dd) ->
                    Text("voice ${Tel.ratName(v)} · data ${Tel.ratName(dd)}", style = Mono)
                    if (call == TelephonyManager.CALL_STATE_OFFHOOK) Text(
                        stringResource(
                            when (Tel.ratGeneration(v)) {
                                9 -> R.string.telephony_call_live_vowifi
                                4, 5 -> R.string.telephony_call_live_volte
                                2, 3 -> R.string.telephony_call_live_cs
                                else -> if (Tel.ratGeneration(dd) >= 4) R.string.telephony_call_live_volte else R.string.telephony_call_live_unknown
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (events.isNotEmpty()) {
            SubHeader(stringResource(R.string.telephony_call_events))
            events.takeLast(6).reversed().forEach { e ->
                val ago = now?.let { (it - e.elapsed) / 1000 } ?: 0
                Text("−${ago}s  ${Tel.callStateName(e.state)}  voice ${Tel.ratName(e.voiceRat)} · data ${Tel.ratName(e.dataRat)}", style = Mono)
            }
        }
        val callDetails = {
            listOfNotNull(
                ResultStore.results.value["telephony.call_path"]?.summary?.resolve(ctx)?.let { d("call path", it) },
                d("events", events.takeLast(6).joinToString { Tel.callStateName(it.state) }),
            )
        }
        GuidedCheck(a, "call_out", txt(R.string.telephony_t_call_out), stringResource(R.string.telephony_q_call_out), details = callDetails)
        GuidedCheck(a, "call_audio", txt(R.string.telephony_t_call_audio), stringResource(R.string.telephony_q_call_audio), details = callDetails)
        Hint(stringResource(R.string.telephony_call_in_hint))
        GuidedCheck(a, "call_in", txt(R.string.telephony_t_call_in), stringResource(R.string.telephony_q_call_in), details = callDetails)
    }
}

// ------------------------------------------------------------------------------ SMS loopback

@Composable
private fun SmsCard(subId: Int) {
    SectionCard(
        stringResource(R.string.telephony_sms_title), icon = Icons.Outlined.Sms,
        subtitle = stringResource(R.string.telephony_sms_sub),
    ) {
        PermissionGate(listOf(Perms.SMS_SEND, Perms.SMS_RECEIVE)) { SmsBody(subId) }
    }
}

@Composable
private fun SmsBody(subId: Int) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("telephony", Context.MODE_PRIVATE) }
    var number by remember { mutableStateOf(prefs.getString("sms_number", "") ?: "") }
    val loop = remember { SmsLoopback(ctx) }
    DisposableEffect(loop) {
        loop.register()
        onDispose { loop.unregister() }
    }
    val s by loop.state
    val now by rememberPolled(1000) { SystemClock.elapsedRealtime() }
    val busy = s.phase == SmsLoopback.Phase.SENDING || s.phase == SmsLoopback.Phase.SENT || s.phase == SmsLoopback.Phase.DELIVERED

    OutlinedTextField(
        number, { number = it; prefs.edit { putString("sms_number", it) } }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(stringResource(R.string.telephony_sms_number)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
    )
    ButtonRow {
        Button(onClick = { loop.send(number.trim(), subId) }, enabled = number.isNotBlank() && !busy) {
            Text(stringResource(R.string.telephony_sms_send))
        }
        OutlinedButton(onClick = {
            val uri = ("smsto:" + Uri.encode(number.trim())).toUri()
            try { ctx.startActivity(Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        }) { Text(stringResource(R.string.telephony_sms_open_app)) }
        if (s.phase != SmsLoopback.Phase.IDLE && !busy) TextButton(onClick = { loop.reset() }) { Text(stringResource(R.string.clear)) }
    }
    if (s.phase == SmsLoopback.Phase.IDLE) {
        Hint(stringResource(R.string.telephony_sms_hint))
        return
    }
    val elapsed = now?.let { (it - s.sentAt) / 1000 } ?: 0
    StepRow(
        stringResource(R.string.telephony_sms_step_sent),
        when {
            s.sentCode == null && s.phase == SmsLoopback.Phase.FAILED -> Status.FAIL
            s.sentCode == null -> null
            s.sentCode == android.app.Activity.RESULT_OK -> Status.PASS
            else -> Status.FAIL
        },
        s.sentCode?.let { "${SmsLoopback.codeName(it)}${s.sentError?.let { e -> " · errorCode $e" } ?: ""} · ${s.sentMs} ms" } ?: s.error,
        busy,
    )
    if (s.sentCode != null && s.sentCode != android.app.Activity.RESULT_OK) Hint(stringResource(SmsLoopback.explain(s.sentCode!!)))
    StepRow(
        stringResource(R.string.telephony_sms_step_delivered),
        when {
            s.deliveryStatus == 0 -> Status.PASS
            s.deliveryStatus != null && s.deliveryStatus!! >= 0x40 -> Status.FAIL
            s.deliveryStatus != null || s.deliveredMs != null -> Status.WARN
            else -> null
        },
        s.deliveredMs?.let { "${s.deliveryStatus?.let { st -> SmsLoopback.tpStatus(st) } ?: "—"} · $it ms" } ?: stringResource(R.string.telephony_sms_optional),
        busy,
    )
    StepRow(
        stringResource(R.string.telephony_sms_step_received),
        when (s.phase) {
            SmsLoopback.Phase.RECEIVED -> Status.PASS
            SmsLoopback.Phase.TIMEOUT, SmsLoopback.Phase.FAILED -> Status.FAIL
            else -> null
        },
        s.receivedMs?.let { stringResource(R.string.telephony_sms_rtt, SmsLoopback.fmtSec(it)) }
            ?: if (busy) stringResource(R.string.telephony_sms_waiting, elapsed, SmsLoopback.TIMEOUT_MS / 1000) else null,
        busy,
    )
}

@Composable
private fun StepRow(label: String, status: Status?, value: String?, pending: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status == null && pending) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        else StatusDot(status ?: Status.SKIP, size = 14)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
        Text(value ?: "—", style = Mono, modifier = Modifier.weight(1f))
    }
}
