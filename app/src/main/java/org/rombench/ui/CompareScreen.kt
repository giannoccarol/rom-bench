package org.rombench.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.rombench.R
import org.rombench.core.Status
import org.rombench.report.Compare
import org.rombench.report.Report
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.StatusChip
import org.rombench.ui.theme.LocalStatusColors
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(oldFile: String, newFile: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pair by produceState<Pair<Report, Report>?>(null, oldFile, newFile) {
        value = withContext(Dispatchers.IO) {
            val dir = File(ctx.filesDir, "reports")
            runCatching {
                Report.fromJson(JSONObject(File(dir, oldFile).readText())) to Report.fromJson(JSONObject(File(dir, newFile).readText()))
            }.getOrNull()
        }
    }
    var showSame by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.compare)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        val p = pair
        if (p == null) {
            Hint(stringResource(R.string.loading), Modifier.padding(pad).padding(16.dp))
            return@Scaffold
        }
        val (old, new) = p
        val rows = Compare.diff(old, new)
        val byChange = rows.groupBy { it.change }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionCard(stringResource(R.string.compare_builds)) {
                    KeyValue(stringResource(R.string.compare_old), old.title)
                    KeyValue(stringResource(R.string.compare_new), new.title)
                    Compare.deviceDiff(old, new).forEach { (l, a, b) -> KeyValue(l, "${a ?: "—"}\n→ ${b ?: "—"}") }
                }
            }
            item {
                ButtonRow {
                    Compare.Change.entries.filter { it != Compare.Change.SAME }.forEach { c ->
                        val n = byChange[c]?.size ?: 0
                        if (n > 0) Surface(shape = RoundedCornerShape(50), color = changeColor(c).copy(alpha = 0.15f)) {
                            Text("${changeLabel(c)}: $n", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = changeColor(c), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    FilterChip(showSame, { showSame = !showSame }, label = { Text(stringResource(R.string.compare_show_same, byChange[Compare.Change.SAME]?.size ?: 0)) })
                }
            }
            items(rows.filter { showSame || it.change != Compare.Change.SAME }, key = { it.key }) { r ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(4.dp).padding(vertical = 18.dp).background(changeColor(r.change), RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${r.area} · ${r.title}", style = MaterialTheme.typography.titleSmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                r.old?.let { StatusChip(it.status) } ?: Text("—")
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(horizontal = 6.dp))
                                r.new?.let { StatusChip(it.status) } ?: Text("—")
                            }
                            if (r.old?.summary != r.new?.summary) {
                                r.old?.let { Hint("− ${it.summary}") }
                                r.new?.let { Hint("+ ${it.summary}") }
                            } else r.new?.let { Hint(it.summary) }
                            r.new?.reason?.takeIf { r.new.status == Status.FAIL || r.new.status == Status.WARN }?.let { Hint(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun changeColor(c: Compare.Change): Color {
    val sc = LocalStatusColors.current
    return when (c) {
        Compare.Change.REGRESSION -> sc.fail
        Compare.Change.IMPROVEMENT -> sc.pass
        Compare.Change.CHANGED -> sc.warn
        Compare.Change.SAME -> sc.skip
        Compare.Change.ONLY_OLD, Compare.Change.ONLY_NEW -> sc.info
    }
}

@Composable
private fun changeLabel(c: Compare.Change) = stringResource(
    when (c) {
        Compare.Change.REGRESSION -> R.string.change_regression
        Compare.Change.IMPROVEMENT -> R.string.change_improvement
        Compare.Change.CHANGED -> R.string.change_changed
        Compare.Change.SAME -> R.string.change_same
        Compare.Change.ONLY_OLD -> R.string.change_only_old
        Compare.Change.ONLY_NEW -> R.string.change_only_new
    },
)
