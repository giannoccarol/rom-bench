package org.rombench.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import org.rombench.ui.components.Hint
import org.rombench.ui.components.PermissionCard
import org.rombench.ui.components.ResultCard
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.rememberPermissions

/** Frame shared by every area: automatic checks and their results, then the area's own content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AreaScreen(area: Area, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val results by ResultStore.results.collectAsState()
    val run by AutoRunner.state.collectAsState()
    val mine = results.values.filter { it.area == area.id }.sortedBy { if (it.kind == Kind.AUTO) 0 else 1 }
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
            Hint(stringResource(area.subtitle))
            PermissionCard(perms)
            SectionCard(
                stringResource(R.string.area_auto_title),
                subtitle = stringResource(R.string.area_auto_sub),
                actions = {
                    if (mine.isNotEmpty() && !running) IconButton(onClick = { ResultStore.clearArea(area.id) }) {
                        Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.clear))
                    }
                },
            ) {
                FilledTonalButton(onClick = { AutoRunner.runArea(ctx, area) }, enabled = !busy) {
                    if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(if (running) R.string.area_running else R.string.area_run))
                }
                if (mine.isEmpty()) Hint(stringResource(R.string.area_no_results))
                mine.forEach { ResultCard(it) }
            }
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
