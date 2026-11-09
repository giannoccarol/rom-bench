package org.rombench.areas.mic

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

object MicArea : Area {
    override val id = "mic"
    override val title = R.string.mic_title
    override val subtitle = R.string.mic_subtitle
    override val icon: ImageVector = Icons.Outlined.Mic
    override val group = AreaGroup.MEDIA

    override suspend fun autoCheck(ctx: Context): List<TestResult> = emptyList()

    @Composable
    override fun Content() {
    }
}
