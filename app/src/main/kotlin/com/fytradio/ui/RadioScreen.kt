package com.fytradio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fytradio.radio.Band
import com.fytradio.radio.Preset
import com.fytradio.radio.TunerState
import com.fytradio.ui.theme.ThemeMode

/**
 * Top-level radio screen. Stateless w.r.t. the controller — everything comes in via
 * params, every action goes out via callback. Easy to preview, easy to unit-test, no
 * coupling to the SYU bridge.
 */
@Composable
fun RadioScreen(
    tuner: TunerState,
    presets: Map<Band, List<Preset?>>,
    diagnostics: List<String>,
    accentArgb: Int,
    onPickAccent: (Int) -> Unit,
    dynamicColor: Boolean,
    onSetDynamicColor: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onSetThemeMode: (ThemeMode) -> Unit,
    autoStart: Boolean,
    onSetAutoStart: (Boolean) -> Unit,
    forceMono: Boolean,
    onSetForceMono: (Boolean) -> Unit,
    onSetBand: (Band) -> Unit,
    onTogglePower: () -> Unit,
    onSeekDown: () -> Unit,
    onStepDown: () -> Unit,
    onScan: () -> Unit,
    onStepUp: () -> Unit,
    onSeekUp: () -> Unit,
    onRecallPreset: (Int) -> Unit,
    onSavePreset: (Int) -> Unit,
) {
    var showDebug by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Slim header: just a settings gear tucked in the corner — no full tab bar.
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = { showSettings = true },
                    modifier = Modifier.align(Alignment.CenterEnd).size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FrequencyDisplay(
                band = tuner.band,
                frequencyKhz = tuner.frequencyKhz,
                rdsPs = tuner.rdsPs,
                rdsRt = tuner.rdsRt,
                pty = tuner.pty,
                stereo = tuner.stereo,
                confirmedByMcu = tuner.confirmedByMcu,
                powered = tuner.isOnAir,
                searching = tuner.searching,
                onTogglePower = onTogglePower,
            )

            BandSelector(
                selected = tuner.band,
                onSelect = onSetBand,
            )

            TuneControls(
                onSeekDown = onSeekDown,
                onStepDown = onStepDown,
                onScan = onScan,
                onStepUp = onStepUp,
                onSeekUp = onSeekUp,
            )

            PresetGridForBand(
                band = tuner.band,
                statePresets = presets,
                currentKhz = tuner.frequencyKhz,
                onRecall = onRecallPreset,
                onSave = onSavePreset,
            )

            Spacer(Modifier.height(4.dp))

            if (!tuner.confirmedByMcu) {
                UnconfirmedHint(
                    showingDebug = showDebug,
                    onToggleDebug = { showDebug = !showDebug },
                )
            }

            if (showSettings) {
                SettingsDialog(
                    selectedAccent = accentArgb,
                    onPickAccent = onPickAccent,
                    dynamicColor = dynamicColor,
                    onSetDynamicColor = onSetDynamicColor,
                    themeMode = themeMode,
                    onSetThemeMode = onSetThemeMode,
                    autoStart = autoStart,
                    onSetAutoStart = onSetAutoStart,
                    forceMono = forceMono,
                    onSetForceMono = onSetForceMono,
                    onDismiss = { showSettings = false },
                )
            }

            if (showDebug) {
                DebugLog(lines = diagnostics)
            }
        }
    }
}

/** Banner shown until we have seen at least one tuner update from the SYU side. */
@Composable
private fun UnconfirmedHint(showingDebug: Boolean, onToggleDebug: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "No tuner feedback yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Commands reach the tuner over the SYU module IPC, but the MCU isn't " +
                    "sending frequency/RDS back yet. Usually that means the head unit's MCU is " +
                    "in standby — check that both constant +12V (B+) and ACC/ignition are " +
                    "powered, and the antenna is connected. The live dot turns green on the " +
                    "first update received.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (showingDebug) "Hide diagnostics ▴" else "Show diagnostics ▾",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable(onClick = onToggleDebug),
            )
        }
    }
}

/** Scrolling list of the last ~20 SYU broadcasts. Used to discover the real action
 *  names on a given firmware. */
@Composable
private fun DebugLog(lines: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "SYU broadcasts (newest first)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            if (lines.isEmpty()) {
                Text(
                    text = "Nothing received yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (line in lines) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}
