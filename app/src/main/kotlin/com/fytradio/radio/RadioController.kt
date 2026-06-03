package com.fytradio.radio

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The only class that talks to the SYU radio surface. Owns optimistic tuner state and
 * reconciles it against IPC updates from the stock radio. UI consumes everything as
 * StateFlow and stays dumb.
 *
 * Lifecycle: call [register] in `onStart`, [unregister] in `onStop`. Register is
 * idempotent — the underlying [SyuRadioBridge.bind] no-ops if already bound.
 *
 * Frequency unit handling: the SYU MCU has its own unit for frequencies which varies
 * by firmware. We read the band's `[min, max, step, count]` quad off the U_EXTRA_FREQ_INFO
 * update and use that as the source of truth for both display formatting and grid
 * snapping. Until that arrives, we fall back to the [region]-default grid.
 */
class RadioController(
    private val appContext: Context,
    private val presets: PresetStore,
    val region: Region = Region.US,
) {
    // Remembers the last station across launches. These units' MCU resumes the last station on
    // power-up, so showing it immediately (and syncing to the MCU's U_FREQ) "just works".
    private val statePrefs = appContext.getSharedPreferences("fytradio_state", Context.MODE_PRIVATE)
    private val initialBand =
        runCatching { Band.valueOf(statePrefs.getString("last_band", Band.FM.name)!!) }.getOrDefault(Band.FM)
    private val initialFreq =
        statePrefs.getInt("last_freq", region.fmMinKhz + 24 * region.fmStepKhz)

    private val _tuner = MutableStateFlow(
        TunerState(band = initialBand, frequencyKhz = initialFreq),
    )
    val tuner: StateFlow<TunerState> = _tuner

    private val _diagnostics = MutableStateFlow<List<String>>(emptyList())
    val diagnostics: StateFlow<List<String>> = _diagnostics

    val presetState: StateFlow<Map<Band, List<Preset?>>> = presets.state

    /**
     * kHz per MCU frequency unit, per band. The MCU encodes FM two ways depending on
     * firmware: dekahertz (raw 8750..10790, ×10 = kHz) on UNISOC, or kHz directly
     * (87500..107900, ×1) on PX. AM is kHz on every unit seen (×1).
     *
     * `0` = not yet learned. We can't depend on U_EXTRA_FREQ_INFO to tell us — it only
     * fires on a band *switch*, so a session that boots already on FM and just changes
     * stations never receives it. Instead we learn the multiplier from the magnitude of
     * the first raw frequency we see (FM dekahertz tops out ~10800; kHz starts ~87500;
     * the gap is unambiguous). [EXTRA_FREQ_INFO] refines it when it does arrive.
     */
    private var fmUnitKhz = 0
    private var amUnitKhz = 0

    private val bridge = SyuRadioBridge(appContext) { u, ints, _, strs ->
        handleUpdate(u, ints, strs)
    }.also { b ->
        b.onConnected = {
            // Fire queued work only once the toolkit is actually connected, so the powerOn()
            // command isn't dropped on the floor before the binder is ready.
            when {
                pendingAutoStart -> {
                    pendingAutoStart = false
                    Log.i(TAG, "auto-start: powering on radio")
                    powerOn()
                }
                // Warm reconnect (e.g. came back from Bluetooth) while the radio was on —
                // reclaim the MCU audio source so BT/other audio actually stops.
                _tuner.value.isOnAir -> {
                    Log.i(TAG, "reconnect: reclaiming radio source")
                    powerOn()
                }
            }
        }
    }

    /** Set by the activity on a fresh open when the "start radio on open" setting is on;
     *  consumed once the bridge connects. */
    private var pendingAutoStart = false

    /** True while WE are holding a global mute (powered off but still owning the source).
     *  Used to make sure we never leave the head unit muted when our app goes away. */
    private var mutedByUs = false

    /** The band the user just switched to, pending MCU confirmation. While set, we ignore
     *  contradicting U_BAND echoes (the MCU briefly re-reports the old band). Self-heals
     *  after [BAND_ECHO_WINDOW_MS] so a genuine external band change is still picked up. */
    private var pendingBand: Band? = null
    private var pendingBandAtMs = 0L

    /** Last MCU-confirmed frequency (from U_FREQ) — the tuner's true position, used as the
     *  starting point when stepping to a preset. 0 = not yet known. */
    private var lastConfirmedKhz = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Active "step to a preset frequency" job; cancelled if the user tunes manually. */
    private var recallJob: Job? = null

    /** Queue an auto power-on for the next bridge connection. Call before [register] on a
     *  fresh app open when the user's "start radio on open" setting is enabled. */
    fun armAutoStart() {
        pendingAutoStart = true
    }

    /** Re-assert the radio source when returning to the app, if the radio was on. Covers the
     *  warm switch-back from Bluetooth where the binder stayed connected (so [onConnected]
     *  doesn't refire). Safe no-op if the binder isn't ready — [onConnected] then handles it. */
    fun reassertIfOn() {
        if (_tuner.value.isOnAir) {
            Log.i(TAG, "resume: re-asserting radio source")
            powerOn()
        }
    }

    fun register() {
        bridge.bind()
    }

    fun unregister() {
        recallJob?.cancel()
        // Remember the current station so the next launch opens on it.
        val s = _tuner.value
        statePrefs.edit().putString("last_band", s.band.name).putInt("last_freq", s.frequencyKhz).apply()
        // Safety: if we backgrounded while powered-off (radio source held + muted), undo
        // both so we don't leave the unit silently muted or stuck on a dead radio source.
        if (mutedByUs) {
            bridge.unmute()
            bridge.makeRadioInactive()
            mutedByUs = false
        }
        bridge.unbind()
    }

    // ---- Commands ----

    fun setBand(band: Band) {
        if (band == _tuner.value.band) return
        recallJob?.cancel()
        // Record the target so we can ignore the MCU's stale U_BAND echo of the OLD band
        // that arrives right after the switch (it would otherwise revert the UI).
        pendingBand = band
        pendingBandAtMs = SystemClock.uptimeMillis()
        _tuner.update { it.copy(band = band, frequencyKhz = lastKnownOrDefault(band), rdsPs = null, rdsRt = null, pty = null) }
        bridge.setBand(band)
    }

    fun toggleBand() = setBand(if (_tuner.value.band == Band.FM) Band.AM else Band.FM)

    fun tuneUp() {
        recallJob?.cancel()
        val s = _tuner.value
        val next = region.nextOnGrid(s.band, s.frequencyKhz, +1)
        _tuner.update { it.copy(frequencyKhz = next, rdsPs = null, rdsRt = null, pty = null) }
        bridge.stepUp()
    }

    fun tuneDown() {
        recallJob?.cancel()
        val s = _tuner.value
        val next = region.nextOnGrid(s.band, s.frequencyKhz, -1)
        _tuner.update { it.copy(frequencyKhz = next, rdsPs = null, rdsRt = null, pty = null) }
        bridge.stepDown()
    }

    fun seekUp() {
        recallJob?.cancel()
        _tuner.update { it.copy(rdsPs = null, rdsRt = null, searching = true) }
        bridge.seekUp()
    }

    fun seekDown() {
        recallJob?.cancel()
        _tuner.update { it.copy(rdsPs = null, rdsRt = null, searching = true) }
        bridge.seekDown()
    }

    fun tuneTo(khz: Int) {
        val band = _tuner.value.band
        _tuner.update { it.copy(rdsPs = null, rdsRt = null, pty = null) }
        stepToward(band, region.clampToGrid(band, khz), "tuneTo")
    }

    fun recallPreset(index: Int) {
        val band = _tuner.value.band
        val preset = presets.get(band, index) ?: return
        val target = region.clampToGrid(band, preset.freqKhz)
        // The MCU ignores direct frequency-set (C_FREQ) and won't reliably store our presets in
        // its own table, but FREQ_UP/FREQ_DOWN are rock-solid — so step from where the tuner
        // actually is to our saved frequency. Each step echoes U_FREQ, keeping the UI honest.
        _tuner.update { it.copy(rdsPs = preset.name, rdsRt = null, pty = null) }
        stepToward(band, target, "recall[$index]")
    }

    /** Step the tuner from its current position to [targetKhz] using verified FREQ_UP/DOWN. */
    private fun stepToward(band: Band, targetKhz: Int, why: String) {
        recallJob?.cancel()
        val stepKhz = region.stepKhz(band)
        val start = region.clampToGrid(band, if (lastConfirmedKhz > 0) lastConfirmedKhz else _tuner.value.frequencyKhz)
        val delta = targetKhz - start
        val steps = (Math.abs(delta) / stepKhz).coerceAtMost(MAX_RECALL_STEPS)
        val up = delta > 0
        Log.i(TAG, "$why: step $start -> $targetKhz ($steps ${if (up) "up" else "down"})")
        if (steps == 0) {
            _tuner.update { it.copy(frequencyKhz = targetKhz) }
            return
        }
        recallJob = scope.launch {
            repeat(steps) {
                if (!isActive) return@launch
                if (up) bridge.stepUp() else bridge.stepDown()
                delay(RECALL_STEP_DELAY_MS)
            }
        }
    }

    fun savePresetHere(index: Int) {
        val s = _tuner.value
        // Presets are entirely ours — recall steps the tuner to this exact frequency, so we
        // just need the frequency + RDS name stored locally (no dependence on the MCU's own
        // preset table, which it doesn't let us write reliably).
        val freq = if (lastConfirmedKhz > 0) lastConfirmedKhz else s.frequencyKhz
        Log.i(TAG, "savePresetHere[$index] band=${s.band} freq=${freq}kHz name=${s.rdsPs}")
        presets.set(s.band, index, Preset(freqKhz = freq, name = s.rdsPs))
    }

    fun clearPreset(band: Band, index: Int) = presets.clear(band, index)

    /** Power toggle. On = radio is the active source + audio unmuted; off = audio muted
     *  while we keep holding the radio source, so no other app (BT/Spotify) auto-resumes. */
    fun togglePower() {
        if (_tuner.value.isOnAir) powerOff() else powerOn()
    }

    fun powerOn() {
        bridge.makeRadioActiveSource()
        bridge.unmute()
        mutedByUs = false
        _tuner.update { it.copy(isOnAir = true) }
    }

    fun powerOff() {
        // Mute the output but DON'T relinquish the source — releasing it makes the MCU
        // fall back to its previous source (e.g. Spotify/BT), which then resumes playing.
        // Holding radio + muting gives a truly silent "off". Cleaned up on app background.
        bridge.mute()
        mutedByUs = true
        _tuner.update { it.copy(isOnAir = false, searching = false) }
    }

    /** Force mono (helps weak FM) vs. allow stereo. The MCU echoes U_STEREO back either way. */
    fun setForceMono(mono: Boolean) = bridge.setForceMono(mono)

    /** Auto-discover: sweep the band and let the MCU refill its preset list. */
    fun scan() {
        if (!_tuner.value.isOnAir) powerOn()
        _tuner.update { it.copy(searching = true) }
        bridge.scan()
    }

    // ---- Update handling ----

    private fun handleUpdate(u: Int, ints: IntArray?, strs: Array<String?>?) {
        recordDiagnostic(u, ints, strs)
        when (u) {
            SyuRadioBridge.Updates.BAND -> {
                val raw = ints?.firstOrNull() ?: return
                val band = mcuBand(raw) ?: return
                val pend = pendingBand
                Log.i(TAG, "U_BAND raw=$raw -> $band (cur=${_tuner.value.band} pending=$pend)")
                if (pend != null) {
                    when {
                        band == pend -> pendingBand = null  // MCU confirmed our switch
                        SystemClock.uptimeMillis() - pendingBandAtMs < BAND_ECHO_WINDOW_MS -> {
                            return  // stale echo of the old band — ignore it
                        }
                        else -> pendingBand = null  // window expired; accept external change below
                    }
                }
                _tuner.update { it.copy(band = band, confirmedByMcu = true) }
            }
            SyuRadioBridge.Updates.FREQ -> {
                val raw = ints?.firstOrNull() ?: return
                val band = _tuner.value.band
                val khz = mcuToKhz(band, raw)
                lastConfirmedKhz = khz
                Log.i(TAG, "U_FREQ raw=$raw -> ${khz}kHz (band=$band, fmUnit=$fmUnitKhz amUnit=$amUnitKhz)")
                // A fresh frequency means any seek/scan has landed — clear the searching flag.
                _tuner.update { it.copy(frequencyKhz = khz, confirmedByMcu = true, searching = false) }
            }
            SyuRadioBridge.Updates.EXTRA_FREQ_INFO -> {
                if (ints == null || ints.size < 4) return
                val max = ints[1]
                // Refine the learned unit from the band ceiling. Attribute by magnitude, not
                // local band state — this update can race U_BAND on a switch.
                when {
                    max >= 50_000 -> fmUnitKhz = 1   // FM in kHz (PX)
                    max >= 2_000 -> fmUnitKhz = 10   // FM in dekahertz (UNISOC, this unit)
                    else -> amUnitKhz = 1            // AM in kHz
                }
                Log.i(TAG, "EXTRA_FREQ_INFO min=${ints[0]} max=$max step=${ints[2]} cnt=${ints[3]} -> fmUnit=$fmUnitKhz amUnit=$amUnitKhz")
            }
            SyuRadioBridge.Updates.STEREO -> {
                val on = ints?.firstOrNull()?.let { it != 0 } ?: return
                _tuner.update { it.copy(stereo = on) }
            }
            SyuRadioBridge.Updates.RDS_CHANNEL_TEXT -> {
                val ps = strs?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return
                _tuner.update { it.copy(rdsPs = ps) }
            }
            SyuRadioBridge.Updates.RDS_TEXT -> {
                val rt = strs?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return
                _tuner.update { it.copy(rdsRt = rt) }
            }
            SyuRadioBridge.Updates.PTY_ID -> {
                val code = ints?.firstOrNull() ?: return
                _tuner.update { it.copy(pty = ptyName(region, code)) }
            }
            SyuRadioBridge.Updates.CHANNEL -> {
                // The MCU also tracks its own preset list, but we don't surface that yet —
                // FytRadio owns its own presets via PresetStore. Diagnostic only.
            }
            SyuRadioBridge.Updates.CHANNEL_FREQ -> {
                // ints carries [bandFlag, index, freq] or similar; not used yet.
            }
            SyuRadioBridge.Updates.SEARCH_STATE -> {
                // 0 = NONE (idle), 1 = AUTO, 2 = FORE, 3 = BACK. Anything non-zero = actively
                // searching; drives the "Seeking…" indicator and the spinning scan button.
                val state = ints?.firstOrNull() ?: return
                _tuner.update { it.copy(searching = state != 0) }
            }
        }
    }

    private fun mcuBand(raw: Int): Band? = when {
        raw == SyuRadioBridge.Cmds.BAND_SWITCH_FM -> Band.FM
        raw == SyuRadioBridge.Cmds.BAND_SWITCH_AM -> Band.AM
        // Some firmwares report band as 0 = FM, 1 = AM in U_BAND; others mirror the
        // BAND_SWITCH_* sentinels. Disambiguate by value.
        raw == 0 -> Band.FM
        raw == 1 -> Band.AM
        else -> null
    }

    private fun mcuToKhz(band: Band, raw: Int): Int = when (band) {
        Band.FM -> {
            // Learn the FM unit lazily from the raw magnitude: dekahertz values top out
            // ~10800, kHz values start ~87500 — split cleanly at 20000.
            if (fmUnitKhz == 0 && raw > 0) fmUnitKhz = if (raw < 20_000) 10 else 1
            raw * fmUnitKhz.coerceAtLeast(1)
        }
        Band.AM -> {
            if (amUnitKhz == 0 && raw > 0) amUnitKhz = 1
            raw * amUnitKhz.coerceAtLeast(1)
        }
    }

    /** Inverse of [mcuToKhz] — convert a kHz frequency to the MCU's raw unit for direct tune. */
    private fun khzToMcu(band: Band, khz: Int): Int = when (band) {
        // Default FM to dekahertz (÷10) if we haven't learned the unit yet — that's this
        // hardware; on a kHz unit the first FREQ update corrects fmUnitKhz to 1.
        Band.FM -> khz / (if (fmUnitKhz > 0) fmUnitKhz else 10)
        Band.AM -> khz / (if (amUnitKhz > 0) amUnitKhz else 1)
    }

    private fun lastKnownOrDefault(band: Band): Int =
        presets.get(band, 0)?.freqKhz ?: when (band) {
            Band.FM -> region.fmMinKhz + 24 * region.fmStepKhz
            Band.AM -> region.amMinKhz + 50 * region.amStepKhz
        }

    private fun recordDiagnostic(u: Int, ints: IntArray?, strs: Array<String?>?) {
        val label = updateName(u)
        val intPart = ints?.joinToString(",")?.let { "ints=[$it]" } ?: ""
        val strPart = strs?.filterNotNull()?.takeIf { it.isNotEmpty() }
            ?.joinToString(",")?.let { "strs=[$it]" } ?: ""
        val payload = listOf(intPart, strPart).filter { it.isNotEmpty() }.joinToString(" ")
        val line = "u=$u/$label${if (payload.isNotEmpty()) "  $payload" else ""}"
        _diagnostics.update { (listOf(line) + it).take(20) }
    }

    private fun updateName(u: Int): String = when (u) {
        SyuRadioBridge.Updates.BAND -> "BAND"
        SyuRadioBridge.Updates.FREQ -> "FREQ"
        SyuRadioBridge.Updates.AREA -> "AREA"
        SyuRadioBridge.Updates.CHANNEL -> "CHANNEL"
        SyuRadioBridge.Updates.CHANNEL_FREQ -> "CHANNEL_FREQ"
        SyuRadioBridge.Updates.RDS_TEXT -> "RDS_TEXT"
        SyuRadioBridge.Updates.RDS_CHANNEL_TEXT -> "RDS_PS"
        SyuRadioBridge.Updates.RDS_ENABLE -> "RDS_ENABLE"
        SyuRadioBridge.Updates.EXTRA_FREQ_INFO -> "FREQ_INFO"
        SyuRadioBridge.Updates.STEREO -> "STEREO"
        SyuRadioBridge.Updates.SEARCH_STATE -> "SEARCH"
        SyuRadioBridge.Updates.LOC -> "LOC"
        else -> "u$u"
    }

    companion object {
        private const val TAG = "FytRadio"
        /** How long after a user band switch we suppress a contradicting U_BAND echo. */
        private const val BAND_ECHO_WINDOW_MS = 2_000L
        /** Pacing + safety cap for stepping the tuner to a preset frequency. */
        private const val RECALL_STEP_DELAY_MS = 30L
        private const val MAX_RECALL_STEPS = 250
    }
}
