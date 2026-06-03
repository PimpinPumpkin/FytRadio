package com.fytradio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fytradio.radio.PresetStore
import com.fytradio.radio.RadioController
import com.fytradio.ui.RadioScreen
import com.fytradio.ui.theme.DefaultAccentArgb
import com.fytradio.ui.theme.FytRadioTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var controller: RadioController

    private val prefs by lazy { getSharedPreferences("fytradio", MODE_PRIVATE) }
    private val accentArgb = MutableStateFlow(DefaultAccentArgb)
    private fun setAccent(argb: Int) {
        accentArgb.value = argb
        prefs.edit().putInt("accent", argb).apply()
    }

    private val autoStart = MutableStateFlow(true)
    private fun setAutoStart(enabled: Boolean) {
        autoStart.value = enabled
        prefs.edit().putBoolean("auto_start", enabled).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val presets = PresetStore(applicationContext)
        controller = RadioController(applicationContext, presets)
        accentArgb.value = prefs.getInt("accent", DefaultAccentArgb)
        autoStart.value = prefs.getBoolean("auto_start", true)

        // Fresh open: if enabled, queue a power-on that fires once the tuner is connected
        // (grabs the MCU audio source, pausing whatever else was playing).
        if (autoStart.value) controller.armAutoStart()

        setContent {
            val accent by accentArgb.collectAsState()
            FytRadioTheme(accentArgb = accent) {
                val tuner by controller.tuner.collectAsState()
                val presetState by controller.presetState.collectAsState()
                val diagnostics by controller.diagnostics.collectAsState()
                val autoStartOn by autoStart.collectAsState()

                RadioScreen(
                    tuner = tuner,
                    presets = presetState,
                    diagnostics = diagnostics,
                    accentArgb = accent,
                    onPickAccent = { setAccent(it) },
                    autoStart = autoStartOn,
                    onSetAutoStart = { setAutoStart(it) },
                    onSetBand = { controller.setBand(it) },
                    onTogglePower = { controller.togglePower() },
                    onSeekDown = { controller.seekDown() },
                    onStepDown = { controller.tuneDown() },
                    onScan = { controller.scan() },
                    onStepUp = { controller.tuneUp() },
                    onSeekUp = { controller.seekUp() },
                    onRecallPreset = { controller.recallPreset(it) },
                    onSavePreset = { controller.savePresetHere(it) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.register()
    }

    override fun onStop() {
        controller.unregister()
        super.onStop()
    }
}
