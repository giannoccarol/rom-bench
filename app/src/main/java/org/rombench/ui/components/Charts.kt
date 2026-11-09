package org.rombench.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.rombench.ui.theme.Mono
import kotlin.math.abs
import kotlin.math.max

/**
 * Fixed-size ring buffer of samples for [LineChart]. Thread-safe for one writer (sensor or
 * polling thread) and the UI reader; [version] triggers recomposition.
 */
class SeriesBuffer(val capacity: Int, val channels: Int) {
    private val data = Array(channels) { FloatArray(capacity) }
    private var head = 0
    var size = 0
        private set
    var version by mutableIntStateOf(0)
        private set

    @Synchronized
    fun add(vararg values: Float) {
        for (c in 0 until channels) data[c][head] = values.getOrElse(c) { Float.NaN }
        head = (head + 1) % capacity
        if (size < capacity) size++
        version++
    }

    @Synchronized
    fun clear() {
        head = 0; size = 0; version++
    }

    /** Samples of one channel, oldest first. */
    @Synchronized
    fun channel(c: Int): FloatArray {
        val out = FloatArray(size)
        val start = (head - size + capacity) % capacity
        for (i in 0 until size) out[i] = data[c][(start + i) % capacity]
        return out
    }

    @Synchronized
    fun last(c: Int): Float = if (size == 0) Float.NaN else data[c][(head - 1 + capacity) % capacity]
}

val ChartPalette = listOf(Color(0xFFE5484D), Color(0xFF30A46C), Color(0xFF3E63DD), Color(0xFFF5A524), Color(0xFF8E4EC6))

/**
 * Live line chart. With [range] null the Y axis fits the visible data (with 10% margin).
 * [labels] name the channels in a legend.
 */
@Composable
fun LineChart(
    buffer: SeriesBuffer,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    range: ClosedFloatingPointRange<Float>? = null,
    labels: List<String> = emptyList(),
    colors: List<Color> = ChartPalette,
    unit: String = "",
) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    @Suppress("UNUSED_VARIABLE") val v = buffer.version
    val series = (0 until buffer.channels).map { buffer.channel(it) }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            var lo = range?.start ?: Float.POSITIVE_INFINITY
            var hi = range?.endInclusive ?: Float.NEGATIVE_INFINITY
            if (range == null) {
                series.forEach { s -> s.forEach { if (!it.isNaN()) { lo = minOf(lo, it); hi = maxOf(hi, it) } } }
                if (lo == Float.POSITIVE_INFINITY) { lo = 0f; hi = 1f }
                val span = max(hi - lo, max(abs(hi), 1f) * 0.02f)
                lo -= span * 0.1f; hi += span * 0.1f
            }
            val span = (hi - lo).takeIf { it > 0 } ?: 1f
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            for (i in 0..4) {
                val y = size.height * i / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = if (i in 1..3) dash else null)
            }
            if (lo < 0 && hi > 0) {
                val y0 = size.height * (1 - (0 - lo) / span)
                drawLine(grid, Offset(0f, y0), Offset(size.width, y0), strokeWidth = 2f)
            }
            series.forEachIndexed { c, s ->
                if (s.size < 2) return@forEachIndexed
                val path = Path()
                var started = false
                val dx = size.width / (buffer.capacity - 1).coerceAtLeast(1)
                val x0 = size.width - dx * (s.size - 1)
                s.forEachIndexed { i, value ->
                    if (value.isNaN()) { started = false; return@forEachIndexed }
                    val x = x0 + dx * i
                    val y = size.height * (1 - (value - lo) / span)
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                drawPath(path, colors[c % colors.size], style = Stroke(width = 3f))
            }
            drawText(measurer, "${fmtAxis(hi)}$unit", Offset(4f, 2f), Mono.copy(color = textColor))
            val low = measurer.measure("${fmtAxis(lo)}$unit", Mono.copy(color = textColor))
            drawText(low, topLeft = Offset(4f, size.height - low.size.height - 2f))
        }
        if (labels.isNotEmpty()) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { i, l ->
                Canvas(Modifier.size(10.dp)) { drawCircle(colors[i % colors.size]) }
                Spacer(Modifier.width(4.dp))
                val last = buffer.last(i)
                Text("$l ${if (last.isNaN()) "—" else fmtAxis(last)}", style = Mono, color = textColor)
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

private fun fmtAxis(v: Float): String = when {
    abs(v) >= 1000 -> String.format(java.util.Locale.US, "%.0f", v)
    abs(v) >= 10 -> String.format(java.util.Locale.US, "%.1f", v)
    else -> String.format(java.util.Locale.US, "%.2f", v)
}

/** Horizontal bar 0..1 with colour, e.g. audio level or C/N0. */
@Composable
fun LevelBar(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, height: Dp = 10.dp) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val w = size.width * fraction.coerceIn(0f, 1f)
        if (w > 0) drawRoundRect(color, size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = r)
    }
}
