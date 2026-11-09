package org.rombench.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.rombench.R
import org.rombench.report.Report
import org.rombench.report.ReportFormat
import org.rombench.report.ReportRepo
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.Hint
import org.rombench.ui.theme.Mono
import java.io.File

/** Preview and export of the current session ([file] null) or of a saved report. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(file: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var fmt by rememberSaveable { mutableStateOf(ReportFormat.TEXT) }
    var details by rememberSaveable { mutableStateOf(true) }
    var savedName by remember { mutableStateOf<String?>(null) }
    val report by produceState<Report?>(null, file) {
        value = withContext(Dispatchers.IO) {
            if (file == null) Report.fromSession(ctx)
            else runCatching { Report.fromJson(JSONObject(File(File(ctx.filesDir, "reports"), file).readText())) }.getOrNull()
        }
    }
    val rendered by produceState("", report, fmt, details) {
        value = ""
        val r = report ?: return@produceState
        value = withContext(Dispatchers.IO) { runCatching { ReportRepo.render(ctx, r, fmt, details) }.getOrElse { it.toString() } }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(rendered.toByteArray()) }
            Toast.makeText(ctx, R.string.report_saved_file, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(if (file == null) R.string.report else R.string.report_saved)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton(onClick = {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("ROM Bench report", rendered))
                    Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
                }, enabled = report != null) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy)) }
                IconButton(onClick = {
                    val r = report ?: return@IconButton
                    saver.launch(ReportRepo.shareFile(ctx, r, fmt, rendered).name)
                }, enabled = report != null && rendered.isNotEmpty()) { Icon(Icons.Outlined.Save, stringResource(R.string.save_file)) }
                IconButton(onClick = {
                    val r = report ?: return@IconButton
                    val f = ReportRepo.shareFile(ctx, r, fmt, rendered)
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                    val send = Intent(Intent.ACTION_SEND).setType(fmt.mime)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, "ROM Bench: ${r.title}")
                        .putExtra(Intent.EXTRA_TEXT, if (rendered.length < 60_000) rendered else null)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.share)))
                }, enabled = report != null && rendered.isNotEmpty()) { Icon(Icons.Outlined.Share, stringResource(R.string.share)) }
            },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ButtonRow {
                ReportFormat.entries.forEach { f ->
                    FilterChip(selected = fmt == f, onClick = { fmt = f }, label = { Text(f.label) })
                }
            }
            Box(Modifier.fillMaxWidth()) {
                Column {
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.report_details), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(details, { details = it })
                    }
                    if (file == null) androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        Hint(savedName?.let { stringResource(R.string.report_saved_as, it) } ?: stringResource(R.string.report_save_hint), Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = {
                            report?.let { savedName = ReportRepo.save(ctx, it).name }
                        }, enabled = report != null && savedName == null) { Text(stringResource(R.string.report_keep)) }
                    }
                }
            }
            Surface(
                Modifier.fillMaxWidth().weight(1f).navigationBarsPadding().padding(bottom = 12.dp),
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                if (report == null || rendered.isEmpty()) Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else SelectionContainer {
                    Text(
                        rendered, style = Mono,
                        modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            }
        }
    }
}
