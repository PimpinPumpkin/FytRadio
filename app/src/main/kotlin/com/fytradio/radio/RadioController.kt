package com.fytradio.radio

import android.content.Context
import android.util.Log
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
    private val _tuner = MutableStateFlow(
        TunerState(band = Band.FM, frequencyKhz = region.fmMinKhz + 24 * region.fmStepKhz),
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
    }

    /** True while WE are holding a global mute (powered off but still owning the source).
     *  Used to make sure we never leave the head unit muted when our app goes away. */
    private var mutedByUs = false

    fun register() {
        bridge.bind()
    }

    fun unregister() {
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
        _tuner.update { it.copy(band = band, frequencyKhz = lastKnownOrDefault(band), rdsPs = null, rdsRt = null) }
        bridge.setBand(band)
    }

    fun toggleBand() = setBand(if (_tuner.value.band == Band.FM) Band.AM else Band.FM)

    fun tuneUp() {
        val s = _tuner.value
        val next = region.nextOnGrid(s.band, s.frequencyKhz, +1)
        _tuner.update { it.copy(frequencyKhz = next, rdsPs = null, rdsRt = null) }
        bridge.stepUp()
    }

    fun tuneDown() {
        val s = _tuner.value
        val next = region.nextOnGrid(s.band, s.frequencyKhz, -1)
        _tuner.update { it.copy(frequencyKhz = next, rdsPs = null, rdsRt = null) }
        bridge.stepDown()
    }

    fun seekUp() {
        _tuner.update { it.copy(rdsPs = null, rdsRt = null, searching = true) }
        bridge.seekUp()
    }

    fun seekDown() {
        _tuner.update { it.copy(rdsPs = null, rdsRt = null, searching = true) }
        bridge.seekDown()
    }

    fun tuneTo(khz: Int) {
        val s = _tuner.value
        val snapped = region.clampToGrid(s.band, khz)
        _tuner.update { it.copy(frequencyKhz = snapped, rdsPs = null, rdsRt = null) }
        val mcuFreq = khzToMcu(s.band, snapped)
        bridge.setFrequency(mcuFreq)
    }

    fun recallPreset(index: Int) {
        val band = _tuner.value.band
        val preset = presets.get(band, index) ?: return
        // Show the saved name immediately on recall; it'll refresh from live RDS if/when it arrives.
        _tuner.update { it.copy(frequencyKhz = preset.freqKhz, rdsPs = preset.name, rdsRt = null) }
        bridge.recallPreset(index)
    }

    fun savePresetHere(index: Int) {
        val s = _tuner.value
        // Capture the current RDS station name (if we have one) so the tile can show it.
        presets.set(s.band, index, Preset(freqKhz = s.frequencyKhz, name = s.rdsPs))
        bridge.savePreset(index)
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
                _tuner.update { it.copy(band = band, confirmedByMcu = true) }
            }
            SyuRadioBridge.Updates.FREQ -> {
                val raw = ints?.firstOrNull() ?: return
                val band = _tuner.value.band
                val khz = mcuToKhz(band, raw)
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
    }
}
