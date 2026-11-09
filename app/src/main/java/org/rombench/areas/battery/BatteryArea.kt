package org.rombench.areas.battery

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

object BatteryArea : Area {
    override val id = "battery"
    override val title = R.string.battery_title
    override val subtitle = R.string.battery_subtitle
    override val icon: ImageVector = Icons.Outlined.BatteryChargingFull
    override val group = AreaGroup.SYSTEM

    override suspend fun autoCheck(ctx: Context): List<TestResult> = emptyList()

    @Composable
    override fun Content() {
    }
}
