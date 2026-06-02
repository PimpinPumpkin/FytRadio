package com.fytradio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fytradio.radio.Band
import com.fytradio.radio.bandUnit
import com.fytradio.radio.formatFrequency

/**
 * The big slab at the top — frequency, RDS, and a subtle "live" pulse when the MCU has
 * confirmed our state at least once. Sits inside a rounded surface so it reads as a
 * tuner panel rather than floating text on black.
 */
@Composable
fun FrequencyDisplay(
    band: Band,
    frequencyKhz: Int,
    rdsPs: String?,
    rdsRt: String?,
    stereo: Boolean,
    signal: Int,
    confirmedByMcu: Boolean,
    powered: Boolean,
    searching: Boolean,
    onTogglePower: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusStrip(
                band = band,
                stereo = stereo,
                signal = signal,
                live = confirmedByMcu,
                powered = powered,
                searching = searching,
                onTogglePower = onTogglePower,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            FrequencyText(band = band, frequencyKhz = frequencyKhz)
            Spacer(Modifier.height(4.dp))
            Text(
                text = bandUnit(band),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rdsPs != null || rdsRt != null) {
                Spacer(Modifier.height(20.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (rdsPs != null) {
                            Text(
                                text = rdsPs,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (rdsRt != null) {
                            if (rdsPs != null) Spacer(Modifier.height(4.dp))
                            Text(
                                text = rdsRt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrequencyText(band: Band, frequencyKhz: Int) {
    val text = formatFrequency(band, frequencyKhz)
    val (whole, fraction) = when (band) {
        Band.FM -> {
            val parts = text.split('.')
            parts[0] to (parts.getOrNull(1) ?: "")
        }
        Band.AM -> text to ""
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = whole,
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (fraction.isNotEmpty()) {
            Text(
                text = ".$fraction",
                fontSize = 72.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

/** Top strip of the frequency card: band tag, signal bars, stereo/seek pills, live dot,
 *  and the power button in the corner. */
@Composable
private fun StatusStrip(
    band: Band,
    stereo: Boolean,
    signal: Int,
    live: Boolean,
    powered: Boolean,
    searching: Boolean,
    onTogglePower: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = band.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(12.dp))
            SignalBars(signal = signal)
            if (searching) {
                Spacer(Modifier.size(10.dp))
                Pill(text = "SEEKING…", tint = MaterialTheme.colorScheme.tertiary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (band == Band.FM && stereo) {
                Pill(text = "STEREO", tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.size(8.dp))
            }
            LiveDot(active = live)
            Spacer(Modifier.size(14.dp))
            PowerButton(powered = powered, onClick = onTogglePower)
        }
    }
}

/** Corner power toggle: lit primary when the radio is the active source, muted when off. */
@Composable
private fun PowerButton(powered: Boolean, onClick: () -> Unit) {
    val bg = if (powered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (powered) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PowerSettingsNew,
            contentDescription = if (powered) "Turn radio off" else "Turn radio on",
            tint = fg,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun SignalBars(signal: Int) {
    val on = signal.coerceIn(0, 5)
    Row(verticalAlignment = Alignment.Bottom) {
        for (i in 1..5) {
            val height = (6 + i * 3).dp
            val color = if (i <= on)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            Box(
                modifier = Modifier
                    .padding(end = 3.dp)
                    .size(width = 4.dp, height = height)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun Pill(text: String, tint: Color) {
    Surface(
        color = tint.copy(alpha = 0.15f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LiveDot(active: Boolean) {
    val color = if (active)
        MaterialTheme.colorScheme.secondary
    else
        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}
