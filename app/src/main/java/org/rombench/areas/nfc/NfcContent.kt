package org.rombench.areas.nfc

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import org.rombench.R
import org.rombench.core.Detail
import org.rombench.core.Kind
import org.rombench.core.ResultStore
import org.rombench.core.Status
import org.rombench.core.d
import org.rombench.core.result
import org.rombench.core.txt
import org.rombench.ui.SubHeader
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.DetailsTable
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.StatusChip
import org.rombench.ui.components.findActivity
import org.rombench.ui.components.rememberPolled
import org.rombench.ui.theme.Mono
import java.nio.charset.Charset

/** Decoded tag; [details] carry the UID and NDEF content as sensitive values. */
internal data class TagInfo(val uid: String, val techs: List<String>, val details: List<Detail>, val records: List<String>, val readAt: Long)

private fun hex(b: ByteArray?): String = b?.joinToString(":") { "%02X".format(it) } ?: "—"

private fun recordText(r: NdefRecord): String = runCatching {
    val p = r.payload ?: ByteArray(0)
    when {
        r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) && p.isNotEmpty() -> {
            val status = p[0].toInt()
            val langLen = status and 0x3F
            val cs = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
            val lang = String(p, 1, langLen, Charset.forName("US-ASCII"))
            "Text [$lang]: " + String(p, 1 + langLen, p.size - 1 - langLen, cs)
        }
        r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_SMART_POSTER) ->
            "Smart poster: " + (r.toUri()?.toString() ?: "${p.size} bytes")
        r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_URI) -> "URI: " + r.toUri()
        r.tnf == NdefRecord.TNF_ABSOLUTE_URI -> "URI: " + r.toUri()
        r.tnf == NdefRecord.TNF_MIME_MEDIA -> "MIME ${r.toMimeType()}: ${p.size} bytes"
        r.tnf == NdefRecord.TNF_EXTERNAL_TYPE -> "External ${String(r.type)}: ${p.size} bytes"
        r.tnf == NdefRecord.TNF_EMPTY -> "Empty record"
        else -> "TNF ${r.tnf} type ${String(r.type)}: ${p.size} bytes"
    }
}.getOrElse { "Record (${it.javaClass.simpleName})" }

internal fun parseTag(tag: Tag): TagInfo {
    val techs = tag.techList.map { it.substringAfterLast('.') }
    val uid = hex(tag.id)
    val det = mutableListOf(
        d("UID", uid, sensitive = true),
        d("UID length", "${tag.id?.size ?: 0} bytes"),
        d("Technologies", techs.joinToString()),
    )
    NfcA.get(tag)?.let { a ->
        det += d("ATQA", hex(a.atqa))
        det += d("SAK", "0x%02X".format(a.sak.toInt()))
        det += d("Max transceive", "${a.maxTransceiveLength} bytes")
    }
    NfcB.get(tag)?.let { b ->
        det += d("Application data", hex(b.applicationData))
        det += d("Protocol info", hex(b.protocolInfo))
    }
    NfcF.get(tag)?.let { f ->
        det += d("Manufacturer (PMm)", hex(f.manufacturer))
        det += d("System code", hex(f.systemCode))
    }
    NfcV.get(tag)?.let { v ->
        det += d("DSF ID", "0x%02X".format(v.dsfId.toInt()))
        det += d("Response flags", "0x%02X".format(v.responseFlags.toInt()))
    }
    IsoDep.get(tag)?.let { i ->
        det += d("Historical bytes", hex(i.historicalBytes))
        det += d("Hi-layer response", hex(i.hiLayerResponse))
        det += d("Extended APDU", i.isExtendedLengthApduSupported)
    }
    MifareClassic.get(tag)?.let { m ->
        val type = when (m.type) {
            MifareClassic.TYPE_CLASSIC -> "Classic"; MifareClassic.TYPE_PLUS -> "Plus"; MifareClassic.TYPE_PRO -> "Pro"; else -> "?"
        }
        det += d("MIFARE Classic", "$type, ${m.size} bytes, ${m.sectorCount} sectors")
    }
    MifareUltralight.get(tag)?.let { u ->
        det += d("MIFARE Ultralight", if (u.type == MifareUltralight.TYPE_ULTRALIGHT_C) "Ultralight C" else "Ultralight")
    }
    val records = mutableListOf<String>()
    Ndef.get(tag)?.let { n ->
        det += d("NDEF type", n.type)
        det += d("NDEF max size", "${n.maxSize} bytes")
        det += d("NDEF writable", n.isWritable)
        val msg: NdefMessage? = n.cachedNdefMessage
        msg?.records?.forEach { records += recordText(it) }
        det += d("NDEF records", records.size)
        records.take(8).forEachIndexed { i, s -> det += d("Record ${i + 1}", s, sensitive = true) }
    }
    return TagInfo(uid, techs, det, records, SystemClock.elapsedRealtime())
}

private const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE

@Composable
internal fun NfcContent(area: NfcArea) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val adapter = remember { nfcAdapter(ctx) }
    val activity = remember(ctx) { ctx.findActivity() }
    val enabled by rememberPolled(1000, adapter) { adapter?.isEnabled ?: false }
    var reading by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf<TagInfo?>(null) }
    var count by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    SectionCard(
        stringResource(R.string.nfc_ui_state), icon = Icons.Outlined.Nfc,
        actions = { if (adapter != null) StatusChip(if (enabled == true) Status.PASS else Status.WARN) },
    ) {
        when {
            adapter == null -> Hint(stringResource(R.string.nfc_ui_no_adapter))
            enabled == true -> Hint(stringResource(R.string.nfc_ui_on))
            else -> Hint(stringResource(R.string.nfc_ui_off))
        }
        ButtonRow {
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }) {
                Icon(Icons.Outlined.Settings, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.nfc_ui_settings))
            }
        }
    }

    if (adapter == null) return

    DisposableEffect(reading, activity) {
        if (!reading || activity == null) return@DisposableEffect onDispose { }
        val main = Handler(Looper.getMainLooper())
        val cb = NfcAdapter.ReaderCallback { t ->
            val info = runCatching { parseTag(t) }.getOrNull()
            main.post {
                if (info == null) {
                    error = res.getString(R.string.nfc_ui_parse_error)
                    return@post
                }
                tag = info; count++
                val status = if (info.uid == "—" || info.techs.isEmpty()) Status.WARN else Status.PASS
                ResultStore.put(
                    area.result(
                        "tag_read", txt(R.string.nfc_t_tag), status,
                        txt(R.string.nfc_s_tag, info.techs.joinToString().ifEmpty { "—" }),
                        if (status == Status.WARN) txt(R.string.nfc_r_tag_odd) else null,
                        info.details, Kind.GUIDED,
                    ),
                )
            }
        }
        val ok = runCatching { adapter.enableReaderMode(activity, cb, READER_FLAGS, Bundle()) }
        if (ok.isFailure) {
            error = res.getString(R.string.nfc_ui_reader_error, ok.exceptionOrNull()?.javaClass?.simpleName ?: "?")
            reading = false
        }
        onDispose { runCatching { adapter.disableReaderMode(activity) } }
    }

    SectionCard(
        stringResource(R.string.nfc_ui_reader), subtitle = stringResource(R.string.nfc_ui_reader_sub),
        icon = Icons.Outlined.Contactless,
    ) {
        if (activity == null) {
            Hint(stringResource(R.string.nfc_ui_no_activity)); return@SectionCard
        }
        if (enabled != true) Hint(stringResource(R.string.nfc_ui_turn_on_first))
        ButtonRow {
            if (!reading) FilledTonalButton(onClick = { error = null; reading = true }, enabled = enabled == true) {
                Icon(Icons.Outlined.Contactless, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.nfc_ui_start))
            } else OutlinedButton(onClick = { reading = false }) {
                Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.stop))
            }
        }
        if (reading) {
            Hint(stringResource(R.string.nfc_ui_waiting))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        error?.let { Hint(it) }
        tag?.let { t ->
            SubHeader(stringResource(R.string.nfc_ui_last_tag, count))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t.uid, style = Mono.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize))
                KeyValue("Technologies", t.techs.joinToString())
                DetailsTable(t.details.filter { it.label != "UID" && it.label != "Technologies" && !it.label.startsWith("Record ") })
                if (t.records.isNotEmpty()) {
                    SubHeader(stringResource(R.string.nfc_ui_records))
                    t.records.take(8).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Hint(stringResource(R.string.nfc_ui_reader_hint))
    }
}
