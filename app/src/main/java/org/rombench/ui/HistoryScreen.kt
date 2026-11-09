package org.rombench.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rombench.R
import org.rombench.core.Status
import org.rombench.report.ReportRepo
import org.rombench.ui.components.Hint
import org.rombench.ui.components.StatusChip
import java.text.DateFormat
import java.util.Date

/** Saved reports: open one, or tick two and compare them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(nav: Nav) {
    val ctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val entries by produceState<List<ReportRepo.Entry>>(emptyList(), reload) { value = withContext(Dispatchers.IO) { ReportRepo.list(ctx) } }
    val picked = remember { mutableStateListOf<String>() }
    var toDelete by remember { mutableStateOf<ReportRepo.Entry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history)) },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        floatingActionButton = {
            if (picked.size == 2) ExtendedFloatingActionButton(
                onClick = {
                    // Older report first.
                    val (a, b) = picked.sorted()
                    nav.go("compare/$a/$b")
                },
                icon = { Icon(Icons.AutoMirrored.Outlined.CompareArrows, null) },
                text = { Text(stringResource(R.string.compare)) },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Hint(stringResource(if (entries.isEmpty()) R.string.history_empty else R.string.history_hint)) }
            items(entries, key = { it.file.name }) { e ->
                val name = e.file.name
                Card(
                    onClick = { nav.go("report/$name") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(name in picked, { on ->
                            if (on) { if (picked.size >= 2) picked.removeAt(0); picked.add(name) } else picked.remove(name)
                        })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(e.report.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.report.generated)) +
                                    " · " + (e.report.deviceValue("Kernel")?.let { k -> Regex("""#(\d+)""").find(k)?.groupValues?.get(1)?.let { "kernel #$it" } ?: k.substringBefore('-').let { "kernel $it" } } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(Status.PASS, Status.WARN, Status.FAIL).forEach { s ->
                                    val n = e.report.counts[s] ?: 0
                                    if (n > 0) Row(verticalAlignment = Alignment.CenterVertically) { StatusChip(s); Text(" $n", style = MaterialTheme.typography.labelMedium) }
                                }
                            }
                        }
                        IconButton(onClick = { toDelete = e }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    }
    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete_report_title)) },
            text = { Text(e.report.title) },
            confirmButton = {
                TextButton(onClick = { ReportRepo.delete(e); picked.remove(e.file.name); toDelete = null; reload++ }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
