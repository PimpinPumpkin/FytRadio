package com.fytradio.radio

/**
 * Tuner band. FM and AM are the only ones we target; the FYT MCU tuner does not expose
 * DAB or shortwave in any unit I've seen.
 */
enum class Band { FM, AM }

/**
 * Region picks the step grid and band limits. US is the default; EU is offered because the
 * unit has been seen reflashed for European markets. All units are kHz so the controller
 * does not have to special-case MHz vs. kHz internally.
 */
enum class Region(
    val fmMinKhz: Int,
    val fmMaxKhz: Int,
    val fmStepKhz: Int,
    val amMinKhz: Int,
    val amMaxKhz: Int,
    val amStepKhz: Int,
) {
    US(
        fmMinKhz = 87_500, fmMaxKhz = 107_900, fmStepKhz = 200,
        amMinKhz = 530, amMaxKhz = 1_710, amStepKhz = 10,
    ),
    EU(
        fmMinKhz = 87_500, fmMaxKhz = 108_000, fmStepKhz = 100,
        amMinKhz = 531, amMaxKhz = 1_611, amStepKhz = 9,
    );

    fun minKhz(band: Band): Int = if (band == Band.FM) fmMinKhz else amMinKhz
    fun maxKhz(band: Band): Int = if (band == Band.FM) fmMaxKhz else amMaxKhz
    fun stepKhz(band: Band): Int = if (band == Band.FM) fmStepKhz else amStepKhz

    fun clampToGrid(band: Band, khz: Int): Int {
        val min = minKhz(band); val max = maxKhz(band); val step = stepKhz(band)
        val clamped = khz.coerceIn(min, max)
        val offset = clamped - min
        val snapped = min + (offset / step) * step
        return snapped
    }

    fun nextOnGrid(band: Band, khz: Int, dir: Int): Int {
        val step = stepKhz(band)
        val min = minKhz(band); val max = maxKhz(band)
        val next = khz + dir * step
        return when {
            next > max -> min  // wrap
            next < min -> max
            else -> next
        }
    }
}

/**
 * Human-readable frequency text. FM rendered as "101.1", AM as "1010". The display
 * component splits this further to draw the decimal in a quieter weight.
 */
fun formatFrequency(band: Band, khz: Int): String = when (band) {
    Band.FM -> {
        val mhz = khz / 1000
        val tenths = (khz % 1000) / 100
        "$mhz.$tenths"
    }
    Band.AM -> khz.toString()
}

fun bandUnit(band: Band): String = if (band == Band.FM) "MHz" else "kHz"

/**
 * One frame of tuner status, as we last observed it. `confirmedByMcu` flips true the
 * first time we receive a frequency broadcast from the SYU layer; until then the
 * display is our optimistic local model and the UI shows a small "unconfirmed" hint.
 */
data class TunerState(
    val band: Band = Band.FM,
    val frequencyKhz: Int = 101_100,
    val stereo: Boolean = false,
    val rdsPs: String? = null,     // RDS program service name (8 chars)
    val rdsRt: String? = null,     // RDS radio text (up to 64 chars)
    val pty: String? = null,       // RDS program type / genre, region-mapped
    val isOnAir: Boolean = false,  // radio is the active MCU source (= power on)
    val searching: Boolean = false,// auto-seek / scan in progress
    val confirmedByMcu: Boolean = false,
)

/**
 * RDS Program-Type name for a raw PTY code. The US (RBDS) and European (RDS) tables differ,
 * so we pick by region. Code 0 (and unknowns) return null = "don't show a genre chip".
 */
fun ptyName(region: Region, code: Int): String? {
    if (code <= 0 || code > 31) return null
    val table = if (region == Region.US) RBDS_PTY else RDS_PTY
    return table.getOrNull(code)?.takeIf { it.isNotEmpty() }
}

private val RBDS_PTY = listOf(
    "", "News", "Information", "Sports", "Talk", "Rock", "Classic Rock", "Adult Hits",
    "Soft Rock", "Top 40", "Country", "Oldies", "Soft", "Nostalgia", "Jazz", "Classical",
    "R&B", "Soft R&B", "Language", "Religious Music", "Religious Talk", "Personality",
    "Public", "College", "", "", "", "", "", "Weather", "Emergency Test", "Emergency",
)

private val RDS_PTY = listOf(
    "", "News", "Current Affairs", "Information", "Sport", "Education", "Drama", "Culture",
    "Science", "Varied", "Pop", "Rock", "Easy Listening", "Light Classical", "Classical",
    "Other Music", "Weather", "Finance", "Children", "Social", "Religion", "Phone In",
    "Travel", "Leisure", "Jazz", "Country", "National Music", "Oldies", "Folk",
    "Documentary", "Alarm Test", "Alarm",
)
