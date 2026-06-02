package com.fytradio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Five-control row, symmetric: seek down, step down, [SCAN], step up, seek up.
 *  - skip ⏮⏭ = auto-seek to the next listenable station
 *  - chevron ◁▷ = single manual tuning step
 *  - center SCAN = sweep the band and auto-store presets
 * Power on/off lives in the frequency card's corner, not here.
 */
@Composable
fun TuneControls(
    onSeekDown: () -> Unit,
    onStepDown: () -> Unit,
    onScan: () -> Unit,
    onStepUp: () -> Unit,
    onSeekUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton(icon = Icons.Default.SkipPrevious, onClick = onSeekDown)
        CircleButton(icon = Icons.Default.ChevronLeft, onClick = onStepDown, small = true)
        CenterButton(onClick = onScan)
        CircleButton(icon = Icons.Default.ChevronRight, onClick = onStepUp, small = true)
        CircleButton(icon = Icons.Default.SkipNext, onClick = onSeekUp)
    }
}

@Composable
private fun CircleButton(icon: ImageVector, onClick: () -> Unit, small: Boolean = false) {
    val outer = if (small) 64.dp else 76.dp
    val inner = if (small) 30.dp else 38.dp
    Box(
        modifier = Modifier
            .size(outer)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(inner),
        )
    }
}

/** "SCAN" — auto-store sweep. Primary-tinted pill, large hit area for driving. */
@Composable
private fun CenterButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 132.dp, height = 76.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "SCAN",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
