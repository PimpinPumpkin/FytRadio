package com.fytradio.radio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A saved station: its frequency plus, if we knew it at save time, the RDS station name. */
data class Preset(val freqKhz: Int, val name: String?)

/**
 * Six presets per band, persisted in SharedPreferences. Null = empty slot. Each slot stores
 * a frequency (`preset_<band>_<i>`, -1 sentinel = empty) and an optional RDS name
 * (`presetname_<band>_<i>`) captured when the preset was saved.
 */
class PresetStore(context: Context) {

    private val prefs = context.getSharedPreferences("fytradio_presets", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Map<Band, List<Preset?>>> = _state

    fun get(band: Band, index: Int): Preset? = _state.value[band]?.getOrNull(index)

    fun set(band: Band, index: Int, preset: Preset?) {
        if (index !in 0 until SLOTS_PER_BAND) return
        val cur = _state.value.toMutableMap()
        val list: MutableList<Preset?> =
            cur[band]?.toMutableList() ?: MutableList<Preset?>(SLOTS_PER_BAND) { null }
        list[index] = preset
        cur[band] = list
        _state.value = cur
        prefs.edit()
            .putInt(freqKey(band, index), preset?.freqKhz ?: EMPTY)
            .putString(nameKey(band, index), preset?.name)
            .apply()
    }

    fun clear(band: Band, index: Int) = set(band, index, null)

    private fun load(): Map<Band, List<Preset?>> = buildMap {
        for (band in Band.entries) {
            put(band, List(SLOTS_PER_BAND) { i ->
                val v = prefs.getInt(freqKey(band, i), EMPTY)
                if (v == EMPTY) null else Preset(v, prefs.getString(nameKey(band, i), null))
            })
        }
    }

    private fun freqKey(band: Band, index: Int) = "preset_${band.name}_$index"
    private fun nameKey(band: Band, index: Int) = "presetname_${band.name}_$index"

    companion object {
        const val SLOTS_PER_BAND = 6
        private const val EMPTY = -1
    }
}
