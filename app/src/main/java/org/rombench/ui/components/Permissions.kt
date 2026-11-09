package org.rombench.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.rombench.R
import org.rombench.core.Perm
import org.rombench.core.Perms

/** Permission state that refreshes when the screen resumes (e.g. back from app settings). */
class PermissionRequest internal constructor(
    val missing: List<Perm>,
    val askedAndDenied: Boolean,
    private val launch: () -> Unit,
) {
    val allGranted get() = missing.isEmpty()
    fun request() = launch()
}

@Composable
fun rememberPermissions(perms: List<Perm>, onResult: (Boolean) -> Unit = {}): PermissionRequest {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var denied by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        tick++
        val ok = res.values.all { it }
        denied = !ok
        onResult(ok)
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val missing = Perms.missing(ctx, perms)
    return PermissionRequest(missing, denied) {
        if (missing.isEmpty()) onResult(true) else launcher.launch(missing.map { it.name }.toTypedArray())
    }
}

/** Explains why each missing permission is needed, with buttons to grant or open settings. */
@Composable
fun PermissionCard(req: PermissionRequest, modifier: Modifier = Modifier) {
    if (req.allGranted) return
    val ctx = LocalContext.current
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.titleSmall)
            }
            req.missing.forEach { p ->
                Column {
                    Text(stringResource(p.label), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(p.why), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { req.request() }) { Text(stringResource(R.string.perm_grant)) }
                if (req.askedAndDenied) OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text(stringResource(R.string.perm_settings)) }
            }
        }
    }
}

/** Shows [content] when [perms] are granted, otherwise the explanation card. */
@Composable
fun PermissionGate(perms: List<Perm>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val req = rememberPermissions(perms)
    if (req.allGranted) content() else PermissionCard(req, modifier)
}
