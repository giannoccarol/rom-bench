package org.rombench.areas.buttons

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

object ButtonsArea : Area {
    override val id = "buttons"
    override val title = R.string.buttons_title
    override val subtitle = R.string.buttons_subtitle
    override val icon: ImageVector = Icons.Outlined.Vibration
    override val group = AreaGroup.MEDIA

    override suspend fun autoCheck(ctx: Context): List<TestResult> = emptyList()

    @Composable
    override fun Content() {
    }
}
