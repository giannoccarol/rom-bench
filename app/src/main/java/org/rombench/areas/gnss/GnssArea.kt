package org.rombench.areas.gnss

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

object GnssArea : Area {
    override val id = "gnss"
    override val title = R.string.gnss_title
    override val subtitle = R.string.gnss_subtitle
    override val icon: ImageVector = Icons.Outlined.SatelliteAlt
    override val group = AreaGroup.CONNECTIVITY

    override suspend fun autoCheck(ctx: Context): List<TestResult> = emptyList()

    @Composable
    override fun Content() {
    }
}
