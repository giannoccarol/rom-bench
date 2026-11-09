package org.rombench.areas.device

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

object DeviceArea : Area {
    override val id = "device"
    override val title = R.string.device_title
    override val subtitle = R.string.device_subtitle
    override val icon: ImageVector = Icons.Outlined.PhoneAndroid
    override val group = AreaGroup.SYSTEM

    override suspend fun autoCheck(ctx: Context): List<TestResult> = emptyList()

    @Composable
    override fun Content() {
    }
}
