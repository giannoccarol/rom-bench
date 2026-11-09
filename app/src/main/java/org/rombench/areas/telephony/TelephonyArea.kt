package org.rombench.areas.telephony

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.Perm
import org.rombench.core.Perms
import org.rombench.core.TestResult

/**
 * SIM, registration (per domain and transport), signal plausibility, cell info, IMS/VoLTE,
 * a mobile data probe that works with Wi-Fi on, and guided call and SMS loopback tests.
 */
object TelephonyArea : Area {
    override val id = "telephony"
    override val title = R.string.telephony_title
    override val subtitle = R.string.telephony_subtitle
    override val icon: ImageVector = Icons.Outlined.SignalCellularAlt
    override val group = AreaGroup.CONNECTIVITY
    override val permissions: List<Perm> = listOf(Perms.PHONE, Perms.LOCATION)
    override val autoSeconds = 25

    override suspend fun autoCheck(ctx: Context): List<TestResult> = TelephonyChecks.run(ctx)

    @Composable
    override fun Content() = TelephonyContent()
}
