package com.fytradio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.fytradio.ui.theme.AccentSwatches
import com.fytradio.ui.theme.ThemeMode

/**
 * Lightweight settings sheet. Today it just holds the accent-color picker (tapping a
 * swatch recolors the app live and persists). Lives behind the corner gear so it stays
 * out of the way (no full tab).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    selectedAccent: Int,
    onPickAccent: (Int) -> Unit,
    dynamicColor: Boolean,
    onSetDynamicColor: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onSetThemeMode: (ThemeMode) -> Unit,
    autoStart: Boolean,
    onSetAutoStart: (Boolean) -> Unit,
    forceMono: Boolean,
    onSetForceMono: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var showColorPicker by remember { mutableStateOf(false) }
    if (showColorPicker) {
        ColorPickerDialog(
            initial = selectedAccent,
            onConfirm = { onPickAccent(it); showColorPicker = false },
            onDismiss = { showColorPicker = false },
        )
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer16()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Start radio when app opens",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Takes over audio from Bluetooth / other apps",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoStart, onCheckedChange = onSetAutoStart)
                }
                Spacer16()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Force mono",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Cleaner audio on weak FM stations",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = forceMono, onCheckedChange = onSetForceMono)
                }
                Spacer16()
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (mode in ThemeMode.entries) {
                        ThemeSegment(
                            label = when (mode) {
                                ThemeMode.SYSTEM -> "System"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            },
                            selected = mode == themeMode,
                            onClick = { onSetThemeMode(mode) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer16()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Material You",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Pull the accent from the device's colors",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = dynamicColor, onCheckedChange = onSetDynamicColor)
                }
                if (!dynamicColor) {
                    Spacer16()
                    Text(
                        text = "Accent color",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer16()
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        for (argb in AccentSwatches) {
                            Swatch(
                                color = Color(argb),
                                selected = argb == selectedAccent,
                                onClick = { onPickAccent(argb) },
                            )
                        }
                        CustomSwatch(
                            currentAccent = selectedAccent,
                            isCustom = selectedAccent !in AccentSwatches,
                            onClick = { showColorPicker = true },
                        )
                    }
                }
                Spacer16()
                Box(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun ThemeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Trailing swatch that opens the arbitrary-color picker. Shows a rainbow + "+" to invite,
 *  or the active custom color with an edit pencil + selection ring when one is in use. */
@Composable
private fun CustomSwatch(currentAccent: Int, isCustom: Boolean, onClick: () -> Unit) {
    val rainbow = Brush.sweepGradient(
        (0..6).map { Color.hsv(it * 60f, 0.85f, 1f) } + Color.hsv(0f, 0.85f, 1f)
    )
    val onColor = if (Color(currentAccent).luminance() > 0.5f) Color.Black else Color.White
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .then(if (isCustom) Modifier.background(Color(currentAccent)) else Modifier.background(rainbow))
            .border(
                width = if (isCustom) 3.dp else 0.dp,
                color = if (isCustom) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isCustom) Icons.Filled.Edit else Icons.Filled.Add,
            contentDescription = "Custom color",
            tint = if (isCustom) onColor else Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(color)
            .border(width = if (selected) 3.dp else 0.dp, color = ring, shape = CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun Spacer16() {
    androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
}
