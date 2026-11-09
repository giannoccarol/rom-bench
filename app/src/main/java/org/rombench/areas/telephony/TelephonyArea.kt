package org.rombench.areas.telephony

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

object TelephonyArea : Area {
    override val id = "telephony"
    override val title = R.string.telephony_title
    override val subtitle = R.string.telephony_subtitle
    override val icon: ImageVector = Icons.Outlined.SignalCellularAlt
    override val group = AreaGroup.CONNECTIVITY

    override suspend fun autoCheck(ctx: Context): List<TestResult> = emptyList()

    @Composable
    override fun Content() {
    }
}
