package org.rombench.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import org.rombench.core.TestResult
import org.rombench.core.text
import org.rombench.report.DeviceInfo
import org.rombench.ui.components.StatusBadge
import org.rombench.ui.components.icon
import org.rombench.ui.components.rememberPermissions
import org.rombench.ui.theme.LocalStatusColors

@Composable
fun Dashboard(nav: Nav) {
    val results by ResultStore.results.collectAsState()
    val run by AutoRunner.state.collectAsState()
    val problems = results.values.filter { it.status == Status.FAIL || it.status == Status.WARN }.sortedBy { it.order() }
    var showAll by rememberSaveable { mutableStateOf(false) }

    Scaffold { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp + pad.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header(nav) }
            item { VerdictCard(results, run, nav) }
            if (problems.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.dash_attention)) }
                item {
                    ListBox {
                        val shown = if (showAll) problems else problems.take(6)
                        shown.forEachIndexed { i, r ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ProblemRow(r) { nav.go("area/${r.area}") }
                        }
                        if (problems.size > 6) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text(stringResource(if (showAll) R.string.dash_show_less else R.string.dash_show_all, problems.size))
                            }
                        }
                    }
                }
            }
            AreaGroup.entries.forEach { g ->
                item(key = "g_${g.name}") { SectionTitle(stringResource(g.title)) }
                item(key = "l_${g.name}") {
                    ListBox {
                        Areas.all.filter { it.group == g }.forEachIndexed { i, a ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 60.dp))
                            AreaRow(a, results.values.filter { it.area == a.id }, run.running && run.current == a.id) { nav.go("area/${a.id}") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(nav: Nav) {
    val line by produceState("") {
        value = withContext(Dispatchers.IO) { "Android ${Build.VERSION.RELEASE} · ${DeviceInfo.romVersion()}" }
    }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { nav.go("history") }) { Icon(Icons.Outlined.History, stringResource(R.string.history)) }
        IconButton(onClick = { nav.go("settings") }) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

/** White bordered box holding list rows. */
@Composable
fun ListBox(content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) { Column { content() } }
}

@Composable
private fun VerdictCard(results: Map<String, TestResult>, run: AutoRunner.State, nav: Nav) {
    val ctx = LocalContext.current
    val sc = LocalStatusColors.current
    var preflight by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val allPerms = remember { Areas.all.flatMap { it.permissions } + listOf(Perms.NOTIFICATIONS) }
    val perms = rememberPermissions(allPerms) { AutoRunner.runAll(ctx) }
    val tally = Tally.of(results.values)
    val running = run.running && run.singleArea == null

    ListBox {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(40.dp)) }
                else StatusBadge(tally.worst, size = 56)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    if (running) {
                        val area = run.current?.let { Areas.byId(it) }
                        Text(stringResource(R.string.verdict_running), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(R.string.dash_running, area?.let { stringResource(it.title) } ?: "", run.done + 1, run.total),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(tally.headline(), style = MaterialTheme.typography.headlineSmall, color = if (tally.worst == Status.FAIL || tally.worst == Status.WARN) sc.of(tally.worst) else MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (tally.total == 0) stringResource(R.string.verdict_none_sub, Areas.all.sumOf { it.autoSeconds } / 60 + 1)
                            else stringResource(R.string.verdict_counts, tally.fail, tally.warn, tally.ok),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (running) {
                LinearProgressIndicator(progress = { run.done.toFloat() / run.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { AutoRunner.cancel() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stop)) }
            } else {
                Button(
                    onClick = { if (perms.allGranted) AutoRunner.runAll(ctx) else preflight = true },
                    enabled = !run.running, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (tally.total == 0) R.string.dash_start else R.string.dash_again), style = MaterialTheme.typography.titleSmall)
                }
                if (results.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { nav.go("report") }) {
                        Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.dash_report))
                    }
                    TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.clear)) }
                }
                Text(stringResource(R.string.dash_guided_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun ProblemRow(r: TestResult, onClick: () -> Unit) {
    val sc = LocalStatusColors.current
    val area = Areas.byId(r.area)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(r.status.icon(), null, tint = sc.of(r.status), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(area?.let { stringResource(it.title) }, r.title.text()).distinct().joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(r.summary.text(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AreaRow(area: Area, results: List<TestResult>, running: Boolean, onClick: () -> Unit) {
    val sc = LocalStatusColors.current
    val tally = Tally.of(results)
    val worst = tally.worst
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
            Icon(area.icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(area.title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else {
            if (worst != null && worst != Status.INFO && worst != Status.SKIP) {
                Icon(worst.icon(), null, tint = sc.of(worst), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                tally.shortState(), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                color = when (worst) { Status.FAIL, Status.WARN, Status.PASS -> sc.of(worst); else -> MaterialTheme.colorScheme.onSurfaceVariant },
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
