package org.rombench.areas.cpu

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

object CpuArea : Area {
    override val id = "cpu"
    override val title = R.string.cpu_title
    override val subtitle = R.string.cpu_subtitle
    override val icon: ImageVector = Icons.Outlined.DeveloperBoard
    override val group = AreaGroup.SYSTEM

    override suspend fun autoCheck(ctx: Context): List<TestResult> = emptyList()

    @Composable
    override fun Content() {
    }
}
