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
import com.fytradio.ui.theme.ThemeMode
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

    private val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    private fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    private val dynamicColor = MutableStateFlow(false)
    private fun setDynamicColor(enabled: Boolean) {
        dynamicColor.value = enabled
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
    }

    private val forceMono = MutableStateFlow(false)
    private fun setForceMono(enabled: Boolean) {
        forceMono.value = enabled
        prefs.edit().putBoolean("force_mono", enabled).apply()
        controller.setForceMono(enabled)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val presets = PresetStore(applicationContext)
        controller = RadioController(applicationContext, presets)
        accentArgb.value = prefs.getInt("accent", DefaultAccentArgb)
        autoStart.value = prefs.getBoolean("auto_start", true)
        themeMode.value = runCatching {
            ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM)
        dynamicColor.value = prefs.getBoolean("dynamic_color", false)
        forceMono.value = prefs.getBoolean("force_mono", false)

        // Fresh open: if enabled, queue a power-on that fires once the tuner is connected
        // (grabs the MCU audio source, pausing whatever else was playing).
        if (autoStart.value) controller.armAutoStart()

        setContent {
            val accent by accentArgb.collectAsState()
            val mode by themeMode.collectAsState()
            val dynamic by dynamicColor.collectAsState()
            FytRadioTheme(accentArgb = accent, themeMode = mode, dynamicColor = dynamic) {
                val tuner by controller.tuner.collectAsState()
                val presetState by controller.presetState.collectAsState()
                val diagnostics by controller.diagnostics.collectAsState()
                val autoStartOn by autoStart.collectAsState()
                val forceMonoOn by forceMono.collectAsState()

                RadioScreen(
                    tuner = tuner,
                    presets = presetState,
                    diagnostics = diagnostics,
                    accentArgb = accent,
                    onPickAccent = { setAccent(it) },
                    dynamicColor = dynamic,
                    onSetDynamicColor = { setDynamicColor(it) },
                    themeMode = mode,
                    onSetThemeMode = { setThemeMode(it) },
                    autoStart = autoStartOn,
                    onSetAutoStart = { setAutoStart(it) },
                    forceMono = forceMonoOn,
                    onSetForceMono = { setForceMono(it) },
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

    override fun onResume() {
        super.onResume()
        // Returning to the app (e.g. after Bluetooth took over) reclaims the radio source
        // if it was on, so the other audio actually stops.
        controller.reassertIfOn()
    }

    override fun onStop() {
        controller.unregister()
        super.onStop()
    }
}
