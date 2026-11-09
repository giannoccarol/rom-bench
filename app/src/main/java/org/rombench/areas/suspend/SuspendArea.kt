package org.rombench.areas.suspend

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

/**
 * Deep sleep counters, kernel suspend interface, wakeup sources, doze and PowerManager state,
 * plus a guided suspend test (exact alarm from deep sleep) and a partial wakelock demo.
 */
object SuspendArea : Area {
    override val id = "suspend"
    override val title = R.string.suspend_title
    override val subtitle = R.string.suspend_subtitle
    override val icon: ImageVector = Icons.Outlined.Bedtime
    override val group = AreaGroup.SYSTEM
    override val autoSeconds = 8

    override suspend fun autoCheck(ctx: Context): List<TestResult> = SuspendChecks.run(ctx)

    @Composable
    override fun Content() = SuspendContent()
}
