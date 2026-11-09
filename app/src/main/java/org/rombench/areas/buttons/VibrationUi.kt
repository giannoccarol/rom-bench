package org.rombench.areas.buttons

import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.rombench.R
import org.rombench.core.d
import org.rombench.core.txt
import org.rombench.ui.SubHeader
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.GuidedCheck
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.SectionCard

/** Plays [effect] with alarm usage, so that touch or notification intensity settings do not mute it. */
private fun Vibrator.play(effect: VibrationEffect) {
    runCatching {
        cancel()
        if (Build.VERSION.SDK_INT >= 33) {
            vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }
}

@Composable
internal fun VibrationCard() {
    val ctx = LocalContext.current
    val vib = remember { vibratorOf(ctx) }
    DisposableEffect(vib) { onDispose { runCatching { vib?.cancel() } } }

    SectionCard(
        stringResource(R.string.buttons_vib_title),
        subtitle = stringResource(R.string.buttons_vib_sub),
        icon = Icons.Outlined.Vibration,
    ) {
        if (vib == null || !vib.hasVibrator()) {
            Hint(stringResource(R.string.buttons_r_no_vibrator))
            return@SectionCard
        }
        val amp = remember(vib) { vib.hasAmplitudeControl() }
        val eff = remember(vib) { effectSupport(vib) }
        val prim = remember(vib) { primitiveSupport(vib) }
        val yes = stringResource(R.string.buttons_yes)
        val no = stringResource(R.string.buttons_no)
        KeyValue(stringResource(R.string.buttons_vib_amp), if (amp) yes else no)

        SubHeader(stringResource(R.string.buttons_vib_basic))
        ButtonRow {
            FilledTonalButton(onClick = {
                vib.play(
                    if (eff[VibrationEffect.EFFECT_TICK] == Vibrator.VIBRATION_EFFECT_SUPPORT_YES) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                    else VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE),
                )
            }) { Text(stringResource(R.string.buttons_vib_tick)) }
            FilledTonalButton(onClick = { vib.play(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)) }) {
                Text(stringResource(R.string.buttons_vib_500))
            }
            FilledTonalButton(onClick = { vib.play(VibrationEffect.createWaveform(longArrayOf(0, 80, 120, 80, 120, 300), -1)) }) {
                Text(stringResource(R.string.buttons_vib_pattern))
            }
            if (amp) FilledTonalButton(onClick = {
                val steps = 10
                vib.play(
                    VibrationEffect.createWaveform(
                        LongArray(steps) { 120L },
                        IntArray(steps) { ((it + 1) * 255 / steps).coerceIn(1, 255) },
                        -1,
                    ),
                )
            }) { Text(stringResource(R.string.buttons_vib_ramp)) }
        }
        if (!amp) Hint(stringResource(R.string.buttons_vib_no_amp))

        SubHeader(stringResource(R.string.buttons_vib_effects))
        ButtonRow {
            EFFECTS.forEach { e ->
                val s = eff[e.id] ?: Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN
                OutlinedButton(onClick = { vib.play(VibrationEffect.createPredefined(e.id)) }) {
                    Text("${e.name} · ${supportName(s)}")
                }
            }
        }
        Hint(stringResource(R.string.buttons_vib_effects_hint))

        val supported = PRIMITIVES.filter { prim[it.id] == true }
        SubHeader(stringResource(R.string.buttons_vib_primitives))
        if (supported.isEmpty()) Hint(stringResource(R.string.buttons_r_effects_basic))
        else ButtonRow {
            supported.forEach { p ->
                OutlinedButton(onClick = { vib.play(VibrationEffect.startComposition().addPrimitive(p.id).compose()) }) { Text(p.name) }
            }
        }

        Hint(stringResource(R.string.buttons_vib_settings_hint))
        GuidedCheck(
            ButtonsArea, "vibration", txt(R.string.buttons_t_vibration_guided), stringResource(R.string.buttons_vib_question),
            details = {
                listOf(
                    d("Amplitude control", amp),
                    d("Effects supported", EFFECTS.filter { eff[it.id] == Vibrator.VIBRATION_EFFECT_SUPPORT_YES }.joinToString { it.name }),
                    d("Primitives supported", supported.joinToString { it.name }),
                )
            },
        )
    }
}
