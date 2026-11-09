package org.rombench.areas.wifi

import android.Manifest
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.Perm
import org.rombench.core.Perms
import org.rombench.core.TestResult

object WifiArea : Area {
    override val id = "wifi"
    override val title = R.string.wifi_title
    override val subtitle = R.string.wifi_subtitle
    override val icon: ImageVector = Icons.Outlined.Wifi
    override val group = AreaGroup.CONNECTIVITY

    /** Android 13+ asks for "nearby Wi-Fi devices" for scans in addition to location. */
    internal val NEARBY = Perm(
        Manifest.permission.NEARBY_WIFI_DEVICES, R.string.wifi_perm_nearby, R.string.wifi_perm_nearby_why, minSdk = 33,
    )

    override val permissions: List<Perm> = listOf(Perms.LOCATION, NEARBY)

    /** State + scan (up to 12 s) + two pings + DNS + HTTP probe. */
    override val autoSeconds = 20

    override suspend fun autoCheck(ctx: Context): List<TestResult> = runChecks(ctx)

    @Composable
    override fun Content() = WifiContent(this)
}
