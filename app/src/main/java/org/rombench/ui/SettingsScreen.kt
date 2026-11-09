package org.rombench.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.rombench.BuildConfig
import org.rombench.R
import org.rombench.core.Perms
import org.rombench.core.Settings
import org.rombench.ui.components.Hint
import org.rombench.ui.components.SectionCard
import org.rombench.ui.theme.Mono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Settings.version.intValue
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(stringResource(R.string.set_report), icon = Icons.Outlined.Shield) {
                Toggle(R.string.set_redact, R.string.set_redact_sub, Settings.redact(ctx)) { Settings.set(ctx, Settings.REDACT, it) }
                Toggle(R.string.set_english, R.string.set_english_sub, Settings.reportInEnglish(ctx)) { Settings.set(ctx, Settings.REPORT_ENGLISH, it) }
                Toggle(R.string.set_props, R.string.set_props_sub, Settings.includeAllProps(ctx)) { Settings.set(ctx, Settings.ALL_PROPS, it) }
            }
            SectionCard(stringResource(R.string.set_network), icon = Icons.Outlined.Tune, subtitle = stringResource(R.string.set_network_sub)) {
                Field(R.string.set_ping, Settings.pingHost(ctx)) { Settings.set(ctx, Settings.PING_HOST, it) }
                Field(R.string.set_probe, Settings.probeUrl(ctx)) { Settings.set(ctx, Settings.PROBE_URL, it) }
                Field(R.string.set_speed, Settings.speedUrl(ctx)) { Settings.set(ctx, Settings.SPEED_URL, it) }
            }
            if (Build.VERSION.SDK_INT >= 31) SectionCard(stringResource(R.string.set_look)) {
                Toggle(R.string.set_dynamic, R.string.set_dynamic_sub, Settings.dynamicColor(ctx)) { Settings.set(ctx, Settings.DYNAMIC_COLOR, it) }
            }
            SectionCard(stringResource(R.string.set_adb), icon = Icons.Outlined.Code, subtitle = stringResource(R.string.set_adb_sub)) {
                val pkg = ctx.packageName
                Hint(stringResource(R.string.set_adb_grant, if (Perms.hasDump(ctx)) "✓" else "✗", if (Perms.hasReadLogs(ctx)) "✓" else "✗"))
                Command("adb shell pm grant $pkg android.permission.DUMP")
                Command("adb shell pm grant $pkg android.permission.READ_LOGS")
                Hint(stringResource(R.string.set_adb_autorun))
                Command("adb shell am start -n $pkg/org.rombench.MainActivity --ez autorun true")
                Command("adb pull /sdcard/Android/data/$pkg/files/reports/latest.json")
            }
            SectionCard(stringResource(R.string.about), icon = Icons.Outlined.Info) {
                Text("ROM Bench ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                Hint(stringResource(R.string.about_body))
                Text(
                    "github.com/giannoccarol/rom-bench", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/giannoccarol/rom-bench"))) }
                    },
                )
                Hint(stringResource(R.string.about_credits))
            }
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
}

@Composable
private fun Toggle(title: Int, sub: Int, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Hint(stringResource(sub))
        }
        Switch(value, onChange)
    }
}

@Composable
private fun Field(label: Int, value: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        text, { text = it; onSave(it) }, label = { Text(stringResource(label)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), textStyle = Mono,
    )
}

/** Copyable shell command. */
@Composable
fun Command(cmd: String) {
    val ctx = LocalContext.current
    Surface(
        Modifier.fillMaxWidth().clickable {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("command", cmd))
            Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
        },
        shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) { Text(cmd, style = Mono, modifier = Modifier.padding(10.dp)) }
}
