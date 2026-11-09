package org.rombench

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.rombench.core.Areas
import org.rombench.core.AutoRunner
import org.rombench.core.KeyBus
import org.rombench.core.NavBus
import org.rombench.core.Settings
import org.rombench.ui.App
import org.rombench.ui.theme.RomBenchTheme

/**
 * Single activity. Extras for automation from a PC:
 *  - `--ez autorun true`: run every automatic check and save the report
 *    (Android/data/<package>/files/reports/latest.*);
 *  - `--es areas wifi,cpu`: limit autorun to those areas;
 *  - `--es open area/wifi`: open a screen (area/<id>, report, history, settings).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleAutomation(intent)
        setContent {
            Settings.version.intValue
            RomBenchTheme(dynamic = Settings.dynamicColor(this)) { App() }
        }
        lifecycleScope.launch {
            AutoRunner.state.collect { s ->
                if (s.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutomation(intent)
    }

    private fun handleAutomation(intent: Intent?) {
        intent?.getStringExtra(EXTRA_OPEN)?.let { NavBus.request.value = it }
        if (intent?.getBooleanExtra(EXTRA_AUTORUN, false) != true) return
        val only = intent.getStringExtra(EXTRA_AREAS)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val areas = if (only.isNullOrEmpty()) Areas.all else Areas.all.filter { it.id in only }
        AutoRunner.runAreas(this, areas)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyBus.emit(event)
        if (KeyBus.capture && event.keyCode != KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_AUTORUN = "autorun"
        const val EXTRA_AREAS = "areas"
        const val EXTRA_OPEN = "open"
    }
}
