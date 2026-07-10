package com.fytradio.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fytradio.radio.Band
import com.fytradio.radio.Preset
import com.fytradio.radio.PresetStore
import com.fytradio.radio.formatFrequency

/**
 * Six-cell preset grid (2 rows × 3 cols). Tap = recall, long-press = save the current
 * station into that slot. Matches the Pixel dialer "grouped action surface" pattern: each
 * cell is its own rounded surface, separated by a narrow gutter, no shared background.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PresetGrid(
    band: Band,
    presets: List<Preset?>,
    currentKhz: Int,
    onRecall: (Int) -> Unit,
    onSave: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        for (row in 0 until 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (col in 0 until 3) {
                    val slot = row * 3 + col
                    val preset = presets.getOrNull(slot)
                    PresetCell(
                        slot = slot,
                        band = band,
                        preset = preset,
                        tunedHere = preset != null && preset.freqKhz == currentKhz,
                        modifier = Modifier.weight(1f),
                        onTap = { if (preset != null) onRecall(slot) },
                        onLongPress = { onSave(slot) },
                    )
                }
            }
            if (row == 0) Spacer(Modifier.height(10.dp))
        }
        // Only nudge people about long-press while the band has no saved stations; once
        // they've saved one, they get it.
        if (presets.all { it == null }) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Long-press a slot to save the current station",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetCell(
    slot: Int,
    band: Band,
    preset: Preset?,
    tunedHere: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Tint + ring the tile we're currently tuned to.
    val bg = if (tunedHere) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    else MaterialTheme.colorScheme.surface
    val fg = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .then(
                if (tunedHere) Modifier.border(
                    2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp),
                ) else Modifier
            )
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Column {
            Text(
                text = "P${slot + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            if (preset == null) {
                Text(
                    text = "empty",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatFrequency(band, preset.freqKhz),
                        style = MaterialTheme.typography.headlineMedium,
                        color = fg,
                    )
                    Spacer(Modifier.padding(start = 4.dp))
                    Text(
                        text = if (band == Band.FM) "MHz" else "kHz",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                // RDS station name captured when the preset was saved (FM only, signal permitting).
                if (!preset.name.isNullOrBlank()) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Convenience overload that pulls presets straight out of [PresetStore.state] for a band. */
@Composable
fun PresetGridForBand(
    band: Band,
    statePresets: Map<Band, List<Preset?>>,
    currentKhz: Int,
    onRecall: (Int) -> Unit,
    onSave: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = statePresets[band] ?: List(PresetStore.SLOTS_PER_BAND) { null }
    PresetGrid(
        band = band,
        presets = list,
        currentKhz = currentKhz,
        onRecall = onRecall,
        onSave = onSave,
        modifier = modifier,
    )
}
