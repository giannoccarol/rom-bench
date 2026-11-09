package org.rombench.core

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.rombench.R

enum class AreaGroup(@StringRes val title: Int) {
    CONNECTIVITY(R.string.group_connectivity),
    MEDIA(R.string.group_media),
    SYSTEM(R.string.group_system),
}

/**
 * One hardware area (Wi-Fi, camera, ...). An area has automatic checks, which run without any
 * user interaction (also from `adb shell am start ... --ez autorun true`), and an interactive
 * screen with live data and guided tests.
 */
interface Area {
    /** Short stable id; prefix of every result key. */
    val id: String

    @get:StringRes val title: Int

    /** One line shown on the dashboard tile and at the top of the area screen. */
    @get:StringRes val subtitle: Int

    val icon: ImageVector
    val group: AreaGroup

    /** Runtime permissions that the automatic checks and the screen need (any SDK). */
    val permissions: List<Perm> get() = emptyList()

    /** Rough duration of [autoCheck], used for progress and timeouts. */
    val autoSeconds: Int get() = 3

    /**
     * Non-interactive checks. Runs on a background dispatcher: switch to Dispatchers.Main only
     * for APIs that need the main looper, use Dispatchers.IO for blocking I/O and honour
     * cancellation. When a permission is missing, return SKIP results that say which one.
     */
    suspend fun autoCheck(ctx: Context): List<TestResult>

    /** Interactive part of the area screen (live values, guided tests). Not lazily scrolled. */
    @Composable
    fun Content()
}
