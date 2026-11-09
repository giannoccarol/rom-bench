package org.rombench.areas.bluetooth

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

object BluetoothArea : Area {
    override val id = "bluetooth"
    override val title = R.string.bluetooth_title
    override val subtitle = R.string.bluetooth_subtitle
    override val icon: ImageVector = Icons.Outlined.Bluetooth
    override val group = AreaGroup.CONNECTIVITY

    override suspend fun autoCheck(ctx: Context): List<TestResult> = emptyList()

    @Composable
    override fun Content() {
    }
}
