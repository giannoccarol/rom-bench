package org.rombench.areas.sensors

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

object SensorsArea : Area {
    override val id = "sensors"
    override val title = R.string.sensors_title
    override val subtitle = R.string.sensors_subtitle
    override val icon: ImageVector = Icons.Outlined.Sensors
    override val group = AreaGroup.SYSTEM

    override suspend fun autoCheck(ctx: Context): List<TestResult> = emptyList()

    @Composable
    override fun Content() {
    }
}
