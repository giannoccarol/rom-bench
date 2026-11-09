package org.rombench.areas.display

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.rombench.R
import org.rombench.ui.components.ChartPalette
import org.rombench.ui.theme.Mono
import kotlin.math.roundToInt

private data class Touch(val pos: Offset, val pressure: Float, val index: Int)

private val TouchColors = ChartPalette + listOf(Color(0xFF12A594), Color(0xFFD6409F), Color(0xFF8D8D8D), Color(0xFFFFE629), Color(0xFF00A2C7))

/** Draws one trail per finger; reports the most simultaneous touches seen when closed. */
@Composable
fun MultiTouchTest(onClose: (Int) -> Unit) {
    val trails = remember { mutableStateMapOf<Long, List<Offset>>() }
    val active = remember { mutableStateMapOf<Long, Touch>() }
    var max by remember { mutableIntStateOf(0) }
    var nextIndex by remember { mutableIntStateOf(0) }
    val index = remember { mutableMapOf<Long, Int>() }
    Box(
        Modifier.fillMaxSize().background(Color.Black).systemGestureExclusion().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    ev.changes.forEach { c ->
                        val id = c.id.value
                        if (c.pressed) {
                            val i = index.getOrPut(id) { nextIndex++ }
                            active[id] = Touch(c.position, c.pressure, i)
                            trails[id] = ((trails[id] ?: emptyList()) + c.position).takeLast(400)
                        } else {
                            active.remove(id)
                        }
                        c.consume()
                    }
                    max = maxOf(max, ev.changes.count { it.pressed })
                }
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 48.dp.toPx()
            var x = 0f
            while (x < size.width) { drawLine(Color(0xFF1E1E1E), Offset(x, 0f), Offset(x, size.height)); x += step }
            var y = 0f
            while (y < size.height) { drawLine(Color(0xFF1E1E1E), Offset(0f, y), Offset(size.width, y)); y += step }
            trails.forEach { (id, pts) ->
                if (pts.size < 2) return@forEach
                val color = TouchColors[(index[id] ?: 0) % TouchColors.size]
                val p = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                drawPath(p, color.copy(alpha = 0.8f), style = Stroke(width = 4.dp.toPx()))
            }
            active.values.forEach { t ->
                val color = TouchColors[t.index % TouchColors.size]
                drawLine(color.copy(alpha = 0.5f), Offset(0f, t.pos.y), Offset(size.width, t.pos.y))
                drawLine(color.copy(alpha = 0.5f), Offset(t.pos.x, 0f), Offset(t.pos.x, size.height))
                drawCircle(color, radius = 28.dp.toPx(), center = t.pos, style = Stroke(width = 3.dp.toPx()))
            }
        }
        Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text(stringResource(R.string.display_touch_count, active.size, max), color = Color.White, style = Mono)
            active.values.sortedBy { it.index }.take(5).forEach { t ->
                Text("#${t.index} x=${t.pos.x.roundToInt()} y=${t.pos.y.roundToInt()} p=${"%.2f".format(t.pressure)}", color = Color(0xFFBBBBBB), style = Mono)
            }
        }
        IconButton(onClick = { onClose(max) }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(Icons.Filled.Close, stringResource(R.string.close), tint = Color.White)
        }
    }
}

/** Grid of cells to swipe over; reports covered cells when complete or closed. */
@Composable
fun GridTest(onDone: (covered: Int, total: Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val cols = 7
        val cell = wPx / cols
        val rows = (hPx / cell).roundToInt().coerceAtLeast(1)
        val cellH = hPx / rows
        val filled = remember(rows) { mutableStateListOf<Boolean>().apply { repeat(cols * rows) { add(false) } } }
        var finished by remember { mutableStateOf(false) }
        val count = filled.count { it }
        LaunchedEffect(count) {
            if (count == filled.size && !finished) { finished = true; delay(300); onDone(count, filled.size) }
        }
        Box(
            Modifier.fillMaxSize().systemGestureExclusion().pointerInput(rows) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { c ->
                            if (c.pressed) {
                                val cx = (c.position.x / cell).toInt().coerceIn(0, cols - 1)
                                val cy = (c.position.y / cellH).toInt().coerceIn(0, rows - 1)
                                val i = cy * cols + cx
                                if (!filled[i]) filled[i] = true
                            }
                            c.consume()
                        }
                    }
                }
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                for (r in 0 until rows) for (c in 0 until cols) {
                    val tl = Offset(c * cell, r * cellH)
                    if (filled[r * cols + c]) drawRect(Color(0xFF30A46C), tl, Size(cell - 2, cellH - 2))
                    else drawRect(Color(0xFF3A3A3A), tl, Size(cell - 2, cellH - 2), style = Stroke(1.5f))
                }
            }
            Text(
                stringResource(R.string.display_grid_hint, count, filled.size),
                color = Color.White, style = Mono,
                modifier = Modifier.align(Alignment.Center).background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(12.dp),
            )
        }
        IconButton(onClick = { onDone(count, filled.size) }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(Icons.Filled.Close, stringResource(R.string.close), tint = Color.White)
        }
    }
}

/** Solid colours and gradients for dead pixels, tint and banding; tap to advance. */
@Composable
fun ColorTest(onClose: () -> Unit) {
    val screens: List<Pair<String, Brush>> = remember {
        listOf(
            "Red" to Brush.linearGradient(listOf(Color.Red, Color.Red)),
            "Green" to Brush.linearGradient(listOf(Color.Green, Color.Green)),
            "Blue" to Brush.linearGradient(listOf(Color.Blue, Color.Blue)),
            "White" to Brush.linearGradient(listOf(Color.White, Color.White)),
            "Black" to Brush.linearGradient(listOf(Color.Black, Color.Black)),
            "Gray 50%" to Brush.linearGradient(listOf(Color(0xFF808080), Color(0xFF808080))),
            "Gray ramp" to Brush.horizontalGradient(listOf(Color.Black, Color.White)),
            "Red ramp" to Brush.horizontalGradient(listOf(Color.Black, Color.Red)),
            "Green ramp" to Brush.horizontalGradient(listOf(Color.Black, Color.Green)),
            "Blue ramp" to Brush.horizontalGradient(listOf(Color.Black, Color.Blue)),
            "Hue" to Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)),
        )
    }
    var i by remember { mutableIntStateOf(0) }
    var showLabel by remember { mutableStateOf(true) }
    LaunchedEffect(i) { showLabel = true; delay(1200); showLabel = false }
    Box(
        Modifier.fillMaxSize().background(screens[i].second).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.any { it.pressed && !it.previousPressed }) {
                        if (i == screens.lastIndex) onClose() else i++
                    }
                    ev.changes.forEach { it.consume() }
                }
            }
        },
    ) {
        if (showLabel) Text(
            "${i + 1}/${screens.size} ${screens[i].first}", color = Color.White, style = Mono,
            modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(8.dp),
        )
    }
}
