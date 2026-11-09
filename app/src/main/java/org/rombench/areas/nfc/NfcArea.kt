package org.rombench.areas.nfc

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

object NfcArea : Area {
    override val id = "nfc"
    override val title = R.string.nfc_title
    override val subtitle = R.string.nfc_subtitle
    override val icon: ImageVector = Icons.Outlined.Nfc
    override val group = AreaGroup.CONNECTIVITY

    override suspend fun autoCheck(ctx: Context): List<TestResult> = emptyList()

    @Composable
    override fun Content() {
    }
}
