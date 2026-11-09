package org.rombench.areas.memory

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

object MemoryArea : Area {
    override val id = "memory"
    override val title = R.string.memory_title
    override val subtitle = R.string.memory_subtitle
    override val icon: ImageVector = Icons.Outlined.Storage
    override val group = AreaGroup.SYSTEM

    override suspend fun autoCheck(ctx: Context): List<TestResult> = emptyList()

    @Composable
    override fun Content() {
    }
}
