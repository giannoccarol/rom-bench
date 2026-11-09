package org.rombench.areas.usb

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

object UsbArea : Area {
    override val id = "usb"
    override val title = R.string.usb_title
    override val subtitle = R.string.usb_subtitle
    override val icon: ImageVector = Icons.Outlined.Usb
    override val group = AreaGroup.SYSTEM

    /** sysfs and framework reads, plus up to 2 s for a port that is still enumerating. */
    override val autoSeconds = 4

    override suspend fun autoCheck(ctx: Context): List<TestResult> = runChecks(ctx)

    @Composable
    override fun Content() = UsbContent(this)
}
