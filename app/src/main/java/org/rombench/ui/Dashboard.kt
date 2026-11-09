package org.rombench.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.Areas
import org.rombench.core.AutoRunner
import org.rombench.core.Perms
import org.rombench.core.ResultStore
import org.rombench.core.Status
import org.rombench.core.Sys
import org.rombench.core.TestResult
import org.rombench.report.DeviceInfo
import org.rombench.ui.components.StatusChip
import org.rombench.ui.components.StatusDot
import org.rombench.ui.components.rememberPermissions
import org.rombench.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dashboard(nav: Nav) {
    val results by ResultStore.results.collectAsState()
    val run by AutoRunner.state.collectAsState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { nav.go("history") }) { Icon(Icons.Outlined.History, stringResource(R.string.history)) }
                    IconButton(onClick = { nav.go("settings") }) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) }
                },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(164.dp),
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { DeviceHeader() }
            item(span = { GridItemSpan(maxLineSpan) }) { RunCard(results, run, nav) }
            AreaGroup.entries.forEach { g ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(g.title), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
                items(Areas.all.filter { it.group == g }, key = { it.id }) { a ->
                    AreaTile(a, results.values.filter { it.area == a.id }, run.running && run.current == a.id) { nav.go("area/${a.id}") }
                }
            }
        }
    }
}

@Composable
private fun DeviceHeader() {
    val info by produceState<List<String>?>(null) {
        value = withContext(Dispatchers.IO) {
            listOf(
                "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                "Android ${Build.VERSION.RELEASE} · ${DeviceInfo.romVersion()}",
                "Kernel ${DeviceInfo.kernelShort()} · patch ${Build.VERSION.SECURITY_PATCH}",
                Sys.selinux(),
                Build.DEVICE,
            )
        }
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val i = info
            Text(i?.get(0) ?: Build.MODEL, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(i?.get(1) ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(i?.get(2) ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (i != null) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("SELinux ${i[3]}")
                Pill(i[4])
                Pill("API ${Build.VERSION.SDK_INT}")
            }
        }
    }
}

@Composable
private fun Pill(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun RunCard(results: Map<String, TestResult>, run: AutoRunner.State, nav: Nav) {
    val ctx = LocalContext.current
    var preflight by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val allPerms = remember { Areas.all.flatMap { it.permissions } + listOf(Perms.NOTIFICATIONS) }
    val perms = rememberPermissions(allPerms) { AutoRunner.runAll(ctx) }
    val counts = results.values.groupingBy { it.status }.eachCount()

    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (results.isEmpty()) Text(stringResource(R.string.dash_no_results), style = MaterialTheme.typography.bodyMedium)
                listOf(Status.PASS, Status.WARN, Status.FAIL, Status.SKIP).forEach { s ->
                    val n = counts[s] ?: 0
                    if (n > 0) CountChip(s, n)
                }
            }
            if (run.running && run.singleArea == null) {
                val area = run.current?.let { Areas.byId(it) }
                Text(
                    stringResource(R.string.dash_running, area?.let { stringResource(it.title) } ?: "", run.done + 1, run.total),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(progress = { (run.done.toFloat() / run.total.coerceAtLeast(1)) }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { AutoRunner.cancel() }) {
                    Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.stop))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (perms.allGranted) AutoRunner.runAll(ctx) else preflight = true }, enabled = !run.running) {
                        Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.dash_run_all))
                    }
                    OutlinedButton(onClick = { nav.go("report") }, enabled = results.isNotEmpty()) {
                        Icon(Icons.Outlined.Description, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.report))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.dash_run_hint, Areas.all.sumOf { it.autoSeconds } / 60 + 1),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (results.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.clear)) }
                }
            }
        }
    }

    if (preflight) AlertDialog(
        onDismissRequest = { preflight = false },
        title = { Text(stringResource(R.string.preflight_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.preflight_body), style = MaterialTheme.typography.bodyMedium)
                perms.missing.forEach { p ->
                    Column {
                        Text(stringResource(p.label), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(p.why), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { preflight = false; perms.request() }) { Text(stringResource(R.string.preflight_grant)) } },
        dismissButton = { TextButton(onClick = { preflight = false; AutoRunner.runAll(ctx) }) { Text(stringResource(R.string.preflight_skip)) } },
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.clear_title)) },
        text = { Text(stringResource(R.string.clear_body)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; ResultStore.clear() }) { Text(stringResource(R.string.clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CountChip(s: Status, n: Int) {
    val sc = LocalStatusColors.current
    Row(
        Modifier.background(sc.container(s), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(s, size = 8)
        Spacer(Modifier.width(6.dp))
        Text("$n", style = MaterialTheme.typography.labelLarge, color = sc.of(s))
    }
}

@Composable
private fun AreaTile(area: Area, results: List<TestResult>, running: Boolean, onClick: () -> Unit) {
    val worst = Status.worst(results.map { it.status })
    val sc = LocalStatusColors.current
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(148.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(
                        if (worst != null) sc.container(worst) else MaterialTheme.colorScheme.secondaryContainer, CircleShape,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        area.icon, null, modifier = Modifier.size(22.dp),
                        tint = if (worst != null) sc.of(worst) else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (running) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else if (worst != null) StatusChip(worst)
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(area.title), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(area.subtitle), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (results.isEmpty()) stringResource(R.string.tile_not_tested)
                else pluralStringResource(R.plurals.tile_checks, results.size, results.size),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
