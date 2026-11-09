package org.rombench.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.rombench.R
import org.rombench.core.Status
import org.rombench.core.TestResult

/** Counts that drive the plain-language verdicts. */
data class Tally(val fail: Int, val warn: Int, val ok: Int, val info: Int, val skip: Int) {
    val total get() = fail + warn + ok + info + skip
    val worst: Status?
        get() = when {
            fail > 0 -> Status.FAIL; warn > 0 -> Status.WARN; ok > 0 -> Status.PASS
            info > 0 -> Status.INFO; skip > 0 -> Status.SKIP; else -> null
        }

    companion object {
        fun of(results: Collection<TestResult>) = Tally(
            results.count { it.status == Status.FAIL }, results.count { it.status == Status.WARN },
            results.count { it.status == Status.PASS }, results.count { it.status == Status.INFO },
            results.count { it.status == Status.SKIP },
        )
    }
}

/** Short state of an area for lists: "Works", "2 problems", "1 to check", "Not tested". */
@Composable
fun Tally.shortState(): String = when {
    total == 0 -> stringResource(R.string.state_not_tested)
    fail > 0 -> pluralStringResource(R.plurals.state_problems, fail, fail)
    warn > 0 -> pluralStringResource(R.plurals.state_to_check, warn, warn)
    ok > 0 -> stringResource(R.string.state_works)
    info > 0 -> stringResource(R.string.state_info_only)
    else -> stringResource(R.string.state_not_run)
}

/** Headline for the whole phone or one area. */
@Composable
fun Tally.headline(): String = when {
    total == 0 -> stringResource(R.string.verdict_none)
    fail > 0 -> pluralStringResource(R.plurals.verdict_problems, fail, fail)
    warn > 0 -> pluralStringResource(R.plurals.verdict_to_check, warn, warn)
    ok > 0 -> stringResource(R.string.verdict_ok)
    info > 0 -> stringResource(R.string.state_info_only)
    else -> stringResource(R.string.state_not_run)
}

/** "1 problem · 3 to check · 20 working", skipping zero counts. */
@Composable
fun Tally.countsLine(): String = listOfNotNull(
    if (fail > 0) pluralStringResource(R.plurals.state_problems, fail, fail) else null,
    if (warn > 0) pluralStringResource(R.plurals.state_to_check, warn, warn) else null,
    if (ok > 0) pluralStringResource(R.plurals.state_working, ok, ok) else null,
).joinToString(" · ").ifEmpty { stringResource(R.string.state_info_only) }

/** Severity order for lists: problems first. */
fun TestResult.order() = when (status) {
    Status.FAIL -> 0; Status.WARN -> 1; Status.PASS -> 2; Status.INFO -> 3; Status.SKIP -> 4
}
