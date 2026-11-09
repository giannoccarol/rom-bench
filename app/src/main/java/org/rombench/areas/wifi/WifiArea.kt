package org.rombench.areas.wifi

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

object WifiArea : Area {
    override val id = "wifi"
    override val title = R.string.wifi_title
    override val subtitle = R.string.wifi_subtitle
    override val icon: ImageVector = Icons.Outlined.Wifi
    override val group = AreaGroup.CONNECTIVITY

    override suspend fun autoCheck(ctx: Context): List<TestResult> = emptyList()

    @Composable
    override fun Content() {
    }
}
