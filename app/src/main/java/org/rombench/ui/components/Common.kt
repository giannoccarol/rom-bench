package org.rombench.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.rombench.R
import org.rombench.core.Detail
import org.rombench.core.Kind
import org.rombench.core.Status
import org.rombench.core.TestResult
import org.rombench.core.text
import org.rombench.ui.theme.LocalStatusColors
import org.rombench.ui.theme.Mono

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun Status.icon(): ImageVector = when (this) {
    Status.PASS -> Icons.Outlined.CheckCircle
    Status.WARN -> Icons.Outlined.WarningAmber
    Status.FAIL -> Icons.Outlined.ErrorOutline
    Status.INFO -> Icons.Outlined.Info
    Status.SKIP -> Icons.Outlined.RemoveCircleOutline
}

@Composable
fun Status.label(): String = stringResource(
    when (this) {
        Status.PASS -> R.string.status_pass
        Status.WARN -> R.string.status_warn
        Status.FAIL -> R.string.status_fail
        Status.INFO -> R.string.status_info
        Status.SKIP -> R.string.status_skip
    },
)

/** Status as icon + plain word in its colour (no pill: colour and word carry the meaning). */
@Composable
fun StatusChip(status: Status, modifier: Modifier = Modifier) {
    val sc = LocalStatusColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(status.icon(), null, tint = sc.of(status), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(status.label(), style = MaterialTheme.typography.labelLarge, color = sc.of(status), maxLines = 1, softWrap = false)
    }
}

/** Big round status badge used in headers. */
@Composable
fun StatusBadge(status: Status?, modifier: Modifier = Modifier, size: Int = 44) {
    val sc = LocalStatusColors.current
    Box(modifier.size(size.dp).background(sc.container(status), CircleShape), contentAlignment = Alignment.Center) {
        Icon((status ?: Status.SKIP).icon(), null, tint = sc.of(status), modifier = Modifier.size((size * 0.58f).dp))
    }
}

@Composable
fun StatusDot(status: Status?, modifier: Modifier = Modifier, size: Int = 10) {
    Box(modifier.size(size.dp).background(LocalStatusColors.current.of(status), CircleShape))
}

/** Flat card with a title row and optional trailing actions. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) Text(
                        subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                actions()
            }
            content()
        }
    }
}

/** Label/value rows; values in monospace. */
@Composable
fun DetailsTable(details: List<Detail>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        details.forEach { KeyValue(it.label, it.value) }
    }
}

@Composable
fun KeyValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        Text(
            label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f).padding(end = 8.dp),
        )
        Text(value, style = Mono, modifier = Modifier.weight(0.58f))
    }
}

/** A result: coloured icon and word, plain summary, "why" line; technical details on demand. */
@Composable
fun ResultCard(r: TestResult, modifier: Modifier = Modifier) {
    var open by rememberSaveable(r.key) { mutableStateOf(false) }
    val sc = LocalStatusColors.current
    val problem = r.status == Status.FAIL || r.status == Status.WARN
    Surface(
        modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        color = if (problem) sc.container(r.status).copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(if (problem) sc.of(r.status) else Color.Transparent))
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.title.text(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (r.kind == Kind.GUIDED) Icon(
                        Icons.Outlined.TouchApp, stringResource(R.string.guided),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).padding(end = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusChip(r.status)
                }
                Text(r.summary.text(), style = MaterialTheme.typography.bodyMedium)
                r.reason?.let {
                    Text(
                        stringResource(R.string.why_prefix, it.text()), style = MaterialTheme.typography.bodySmall,
                        color = if (problem) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (r.details.isNotEmpty()) {
                    Text(
                        stringResource(if (open) R.string.details_hide else R.string.details_show),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { open = !open }.padding(vertical = 4.dp),
                    )
                    if (open) DetailsTable(r.details, Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** Small muted paragraph used for explanations. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** Big number with a caption, for live values. */
@Composable
fun Metric(value: String, caption: String, modifier: Modifier = Modifier, status: Status? = null) {
    Column(modifier) {
        Text(
            value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            color = status?.let { LocalStatusColors.current.of(it) } ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ButtonRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

/**
 * Value recomputed every [periodMs] on a background thread while the composable is shown
 * (e.g. reading sysfs once per second).
 */
@Composable
fun <T> rememberPolled(periodMs: Long, vararg keys: Any?, read: suspend () -> T): State<T?> =
    produceState<T?>(null, *keys) {
        while (isActive) {
            value = withContext(Dispatchers.IO) { runCatching { read() }.getOrNull() }
            delay(periodMs)
        }
    }

/** Runs [block] once per key change; convenience wrapper. */
@Composable
fun OnStart(key: Any?, block: suspend () -> Unit) = LaunchedEffect(key) { block() }

@Composable
fun <T> rememberMutable(initial: T) = remember { mutableStateOf(initial) }
