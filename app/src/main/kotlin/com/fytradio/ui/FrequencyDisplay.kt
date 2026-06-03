package com.fytradio.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
 * The big slab at the top — frequency (animated on change), RDS station name + radio text
 * (marquee'd if long), genre, and stereo/live status. There is no signal meter: the SYU
 * radio module doesn't expose a reception level, so we show what's real (RDS lock + stereo)
 * rather than a fake bar graph.
 */
@Composable
fun FrequencyDisplay(
    band: Band,
    frequencyKhz: Int,
    rdsPs: String?,
    rdsRt: String?,
    pty: String?,
    stereo: Boolean,
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
                pty = pty,
                stereo = stereo,
                live = confirmedByMcu,
                powered = powered,
                searching = searching,
                onTogglePower = onTogglePower,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            AnimatedFrequency(band = band, frequencyKhz = frequencyKhz)
            Spacer(Modifier.height(4.dp))
            Text(
                text = bandUnit(band),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rdsPs != null || rdsRt != null) {
                Spacer(Modifier.height(20.dp))
                RdsCard(rdsPs = rdsPs, rdsRt = rdsRt)
            }
        }
    }
}

@Composable
private fun AnimatedFrequency(band: Band, frequencyKhz: Int) {
    AnimatedContent(
        targetState = frequencyKhz,
        transitionSpec = {
            val dir = if (targetState > initialState) 1 else -1
            (slideInVertically(tween(180)) { h -> dir * h } + fadeIn(tween(180))) togetherWith
                (slideOutVertically(tween(180)) { h -> -dir * h } + fadeOut(tween(180)))
        },
        label = "frequency",
    ) { freq ->
        FrequencyText(band = band, frequencyKhz = freq)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RdsCard(rdsPs: String?, rdsRt: String?) {
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
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth().basicMarquee(),
                )
            }
        }
    }
}

/** Top strip: band tag + genre + seeking on the left; stereo, live dot, power on the right. */
@Composable
private fun StatusStrip(
    band: Band,
    pty: String?,
    stereo: Boolean,
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
            if (pty != null) {
                Spacer(Modifier.size(10.dp))
                Pill(text = pty.uppercase(), tint = MaterialTheme.colorScheme.primary)
            }
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
