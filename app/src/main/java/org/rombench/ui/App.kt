package org.rombench.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import org.rombench.core.Areas
import org.rombench.core.NavBus

/** Minimal back-stack navigation: routes are strings ("area/wifi", "compare/a.json/b.json"). */
class Nav(initial: List<String>) {
    val stack = mutableStateListOf<String>().apply { addAll(initial) }
    val current get() = stack.last()
    fun go(route: String) { stack.add(route) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

@Composable
fun App() {
    val nav = rememberSaveable(saver = listSaver(save = { it.stack.toList() }, restore = { Nav(it) })) { Nav(listOf("dash")) }
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    val requested by NavBus.request.collectAsState()
    LaunchedEffect(requested) {
        requested?.let { r ->
            nav.home()
            if (r != "dash") nav.go(r)
            NavBus.request.value = null
        }
    }
    AnimatedContent(nav.current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { route ->
        val parts = route.split('/')
        when (parts[0]) {
            "area" -> Areas.byId(parts[1])?.let { AreaScreen(it, onBack = { nav.back() }) } ?: Dashboard(nav)
            "report" -> ReportScreen(file = parts.getOrNull(1), onBack = { nav.back() })
            "history" -> HistoryScreen(nav)
            "compare" -> CompareScreen(parts[1], parts[2], onBack = { nav.back() })
            "settings" -> SettingsScreen(onBack = { nav.back() })
            else -> Dashboard(nav)
        }
    }
}
