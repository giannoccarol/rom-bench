package org.rombench.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import org.rombench.core.Status
import org.rombench.ui.components.StatusBadge
import org.rombench.ui.theme.LocalStatusColors
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AutoRunner
import org.rombench.core.Kind
import org.rombench.core.ResultStore
import org.rombench.ui.components.PermissionCard
import org.rombench.ui.components.ResultCard
import org.rombench.ui.components.rememberPermissions

/** Frame shared by every area: automatic checks and their results, then the area's own content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AreaScreen(area: Area, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val results by ResultStore.results.collectAsState()
    val run by AutoRunner.state.collectAsState()
    val mine = results.values.filter { it.area == area.id }.sortedWith(compareBy({ it.order() }, { if (it.kind == Kind.AUTO) 0 else 1 }))
    val running = run.running && run.current == area.id
    val busy = run.running
    val perms = rememberPermissions(area.permissions)
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(area.title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val tally = Tally.of(mine)
            val sc = LocalStatusColors.current
            ListBox {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (running) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) }
                        else StatusBadge(tally.worst)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (running) stringResource(R.string.area_running) else tally.headline(),
                                style = MaterialTheme.typography.titleLarge,
                                color = if (!running && (tally.worst == Status.FAIL || tally.worst == Status.WARN)) sc.of(tally.worst) else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(stringResource(area.subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Button(onClick = { AutoRunner.runArea(ctx, area) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(if (mine.isEmpty()) R.string.area_run else R.string.area_run_again))
                    }
                }
            }
            PermissionCard(perms)
            if (mine.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle(stringResource(R.string.area_results)) }
                    if (!running) TextButton(onClick = { ResultStore.clearArea(area.id) }) { Text(stringResource(R.string.clear)) }
                }
                mine.forEach { ResultCard(it) }
            }
            SectionTitle(stringResource(R.string.area_tests))
            area.Content()
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
}

/** Title for sections inside area content. */
@Composable
fun SubHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
