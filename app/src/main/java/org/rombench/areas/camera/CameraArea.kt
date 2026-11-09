package org.rombench.areas.camera

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.TestResult

object CameraArea : Area {
    override val id = "camera"
    override val title = R.string.camera_title
    override val subtitle = R.string.camera_subtitle
    override val icon: ImageVector = Icons.Outlined.PhotoCamera
    override val group = AreaGroup.MEDIA

    override suspend fun autoCheck(ctx: Context): List<TestResult> = emptyList()

    @Composable
    override fun Content() {
    }
}
