package org.rombench.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.Detail
import org.rombench.core.Kind
import org.rombench.core.ResultStore
import org.rombench.core.Status
import org.rombench.core.Txt
import org.rombench.core.raw
import org.rombench.core.result
import org.rombench.core.text
import org.rombench.core.txt
import org.rombench.ui.theme.LocalStatusColors

/**
 * A question the tester answers after doing something (hearing a tone, seeing the LED...).
 * The answer is stored as a guided result "<area>.<name>". "Problem" asks for an optional note
 * that becomes the reason in the report. [details] are attached to the stored result.
 */
@Composable
fun GuidedCheck(
    area: Area,
    name: String,
    title: Txt,
    question: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    details: () -> List<Detail> = { emptyList() },
) {
    val results by ResultStore.results.collectAsState()
    val current = results["${area.id}.$name"]
    var askNote by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    fun record(s: Status, reason: Txt?) {
        val summary = when (s) {
            Status.PASS -> txt(R.string.guided_ok)
            Status.FAIL -> txt(R.string.guided_problem)
            else -> txt(R.string.guided_na)
        }
        ResultStore.put(area.result(name, title, s, summary, reason, details(), Kind.GUIDED))
    }

    val sc = LocalStatusColors.current
    Surface(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = current?.let { sc.container(it.status).copy(alpha = 0.45f) } ?: MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(question, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                current?.let { StatusChip(it.status, Modifier.padding(start = 8.dp, top = 2.dp)) }
            }
            current?.reason?.let { Hint(it.text()) }
            ButtonRow {
                OutlinedButton(
                    onClick = { record(Status.PASS, null) }, enabled = enabled,
                    border = BorderStroke(1.dp, sc.pass), colors = ButtonDefaults.outlinedButtonColors(contentColor = sc.pass),
                ) { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.guided_btn_ok)) }
                OutlinedButton(
                    onClick = { note = ""; askNote = true }, enabled = enabled,
                    border = BorderStroke(1.dp, sc.fail), colors = ButtonDefaults.outlinedButtonColors(contentColor = sc.fail),
                ) { Icon(Icons.Outlined.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.guided_btn_problem)) }
                TextButton(onClick = { record(Status.SKIP, null) }, enabled = enabled) { Text(stringResource(R.string.guided_btn_na)) }
            }
        }
    }

    if (askNote) AlertDialog(
        onDismissRequest = { askNote = false },
        title = { Text(stringResource(R.string.guided_note_title)) },
        text = {
            OutlinedTextField(note, { note = it }, placeholder = { Text(stringResource(R.string.guided_note_hint)) }, minLines = 2)
        },
        confirmButton = {
            TextButton(onClick = {
                askNote = false
                record(Status.FAIL, note.trim().takeIf { it.isNotEmpty() }?.let { raw(it) })
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = { askNote = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
