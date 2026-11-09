package org.rombench.areas.display

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

object DisplayArea : Area {
    override val id = "display"
    override val title = R.string.display_title
    override val subtitle = R.string.display_subtitle
    override val icon: ImageVector = Icons.Outlined.TouchApp
    override val group = AreaGroup.MEDIA

    override suspend fun autoCheck(ctx: Context): List<TestResult> = emptyList()

    @Composable
    override fun Content() {
    }
}
