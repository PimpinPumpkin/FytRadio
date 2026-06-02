# Dev notes

## Tuning quirks learned on-device (IMPORTANT)

Verified by driving the real MCU and watching `U_FREQ` echoes (logcat tag `FytRadio`):

- **Direct frequency-set does NOT work.** `radio.cmd(C_FREQ=13, [freqDekahertz])` dispatches
  fine but the MCU never tunes or echoes. Tried it; after a subsequent `FREQ_UP` the tuner was
  still where it started. So C_FREQ is effectively read-only here.
- **`C_SAVE_CHANNEL=8` did not persist our frequency** into the MCU preset slot (recalling the
  slot afterwards still tuned to its old value). Possibly only writes when locked onto a real
  station — unverifiable on a signal-less bench, so we don't depend on it.
- **`C_SELECT_CHANNEL=7` works** but tunes to the MCU's *own* preset table (independent of ours),
  so it's the wrong tool for our presets.
- **`C_FREQ_UP=3` / `C_FREQ_DOWN=4` / `C_SEEK_*` / `C_BAND=11` are rock-solid** and each echoes
  `U_FREQ`. So **preset recall is implemented as step-to-target**: from the last MCU-confirmed
  frequency, fire N FREQ_UP/DOWN (30 ms apart, capped) until we reach the saved kHz. Verified:
  saved 105.5, moved to 107.1, recall stepped `107100 -> 105500 (8 down)` and landed exactly.
  Presets are stored entirely in our own `PresetStore` (freq + RDS name); the MCU preset table
  is not used.

- **Band switch echoes the OLD band once.** After `cmd(C_BAND,[BAND_SWITCH_AM])` the MCU first
  sends `U_BAND raw=0` (FM, the previous band) before settling on AM — that stale echo used to
  revert the UI, so you had to tap twice. Fixed by tracking a `pendingBand` and ignoring a
  contradicting `U_BAND` for `BAND_ECHO_WINDOW_MS` (2 s), self-healing afterwards so external
  band changes still register.

## SYU IPC surface — REVERSE-ENGINEERED AND VERIFIED

The stock `com.syu.carradio` does NOT use broadcasts; it talks to the MCU over SYU's
private AIDL IPC, hosted by `com.syu.ms`. We use the same protocol — bypassing the
AIDL stubs and doing raw `IBinder.transact()` so we control the wire format.

### Bind target

- Service: `com.syu.ms/app.ToolkitService`
- Action: `com.syu.ms.toolkit`
- Returns: an `IBinder` implementing `IRemoteToolkit` (descriptor `com.syu.ipc.IRemoteToolkit`)

`com.syu.ms` also exposes `app.ModuleService` with per-module action filters
(`com.syu.ms.radio`, `com.syu.ms.main`, `com.syu.ms.bt`, ...) — but the established
flow is to bind the toolkit once and then ask it for individual modules by integer
code.

### IRemoteToolkit (1 method)

| TX  | Signature                                       |
|-----|-------------------------------------------------|
| 1   | `IRemoteModule getRemoteModule(int moduleCode)` |

Module codes (`FinalMainServer.MODULE_CODE_*`):

| code | module |
|------|--------|
| 0    | MAIN   |
| 1    | RADIO  |
| 2    | BT     |
| 3    | DVD    |
| 4    | SOUND  |
| 5    | IPOD   |
| 6    | TV     |
| 7    | CANBUS |
| 8    | TPMS   |

### IRemoteModule (4 methods)

| TX  | Signature                                                                                    |
|-----|----------------------------------------------------------------------------------------------|
| 1   | `void cmd(int command, int[] ints, float[] flts, String[] strs)`                             |
| 2   | `ModuleObject get(int dataId, int[] ints, float[] flts, String[] strs)` *(we don't use)*     |
| 3   | `void register(IModuleCallback cb, int updateId, int p)`                                     |
| 4   | `void unregister(IModuleCallback cb, int updateId)`                                          |

### IModuleCallback (1 method)

| TX  | Signature                                                              |
|-----|------------------------------------------------------------------------|
| 1   | `void update(int updateId, int[] ints, float[] flts, String[] strs)`   |

## Radio command IDs (FinalRadio.C_*) — VERIFIED

Send via `cmd(commandId, intArrayOf(...))` on the radio module.

| C_*                | id  | args                              | what it does                       |
|--------------------|-----|-----------------------------------|------------------------------------|
| `NEXT_CHANNEL`     | 0   | none                              | recall next saved preset           |
| `PREV_CHANNEL`     | 1   | none                              | recall previous saved preset       |
| `FREQ_UP`          | 3   | none                              | step tuning up by one              |
| `FREQ_DOWN`        | 4   | none                              | step tuning down by one            |
| `SEEK_UP`          | 5   | none                              | auto-seek up                       |
| `SEEK_DOWN`        | 6   | none                              | auto-seek down                     |
| `SELECT_CHANNEL`   | 7   | `[index]`                         | recall preset N                    |
| `SAVE_CHANNEL`     | 8   | `[index]`                         | save current freq to preset N      |
| `SCAN`             | 9   | none                              | preset scan                        |
| `SAVE`             | 10  | none                              | save current state                 |
| `BAND`             | 11  | `[BAND_SWITCH_FM=-3 / AM=-2]`     | switch band                        |
| `AREA`             | 12  | `[AREA_USA=0..AREA_JAPAN=4]`      | set region/grid                    |
| `FREQ`             | 13  | `[mcuFreq]`                       | direct tune (units: see below)     |
| `SENSITY`/`AUTO_SENSITY` | 14/15 | `[level]`                  | seek sensitivity                   |
| `RDS_ENABLE`       | 16  | `[0/1]`                           | enable RDS                         |
| `STEREO`           | 17  | `[0/1]`                           | force mono                         |

## Radio update IDs (FinalRadio.U_*) — VERIFIED

Subscribe with `register(callback, updateId, 0)`. The callback fires `update(u, ints, flts, strs)`.

| U_*                  | id | payload                                        |
|----------------------|----|------------------------------------------------|
| `BAND`               | 0  | `ints[0]` = band (FM=0 or BAND_SWITCH_FM=-3)   |
| `FREQ`               | 1  | `ints[0]` = current freq (units: see below)    |
| `AREA`               | 2  | `ints[0]` = AREA_*                             |
| `CHANNEL`            | 3  | `ints[0]` = current preset index               |
| `CHANNEL_FREQ`       | 4  | `ints` = preset list                           |
| `RDS_TEXT`           | 13 | `strs[0]` = RDS Radio Text (RT)                |
| `RDS_CHANNEL_TEXT`   | 14 | `strs[0]` = RDS Program Service (PS)           |
| `EXTRA_FREQ_INFO`    | 16 | `ints = [min, max, stepLen, stepCnt]`          |
| `STEREO`             | 21 | `ints[0]` = 0/1                                |
| `SEARCH_STATE`       | 22 | `ints[0]` = NONE=0/AUTO=1/FORE=2/BACK=3        |

## Frequency unit encoding

The MCU encodes FM differently on different firmwares:

- **PX5/PX6-style:** FM in kHz directly. `EXTRA_FREQ_INFO` reports `max ≈ 107900`.
- **UNISOC UIS7870 / our unit:** FM in dekahertz (10 kHz units). `EXTRA_FREQ_INFO`
  reports `min=8750, max=10790, step=20` — multiply by 10 to get kHz.
- **AM** is always kHz on every firmware seen so far (`min=530, max=1720, step=10`).

`RadioController.BandParams.toKhz` disambiguates by `max`:
- `max ≥ 50_000` → already kHz, pass through.
- `max in 2_000..50_000` → dekahertz, multiply by 10.
- `max < 2_000` → AM kHz, pass through.

## Power on/off (source switching) — VERIFIED

The MAIN module's `cmd(C_APP_ID=0, [appid])` sets the MCU's active audio source.
Our power button toggles it:

- **On** → send `[APP_ID_CAR_RADIO=11]` then `[APP_ID_RADIO=1]`. Qin logs:
  ```
  E/Qin: -------------UI Change AppId to 11
  E/Qin: -------------UI Change AppId to 1
  E/Qin: checkMainVolume --- DataMain.sAppId : 1
  ```
  Sending both covers UNISOC (wants 11) and PX (wants 1) builds; whichever the MCU's
  internal map honors wins.
- **Off** → we do **not** relinquish the source. Releasing it (sending `APP_ID_NULL=0`)
  makes the MCU auto-advance to its next source — observed `UI Change AppId to 0 → 10`
  (THIRD_PLAYER = the user's Spotify), which then *resumes playing*. Not wanted. Instead,
  power-off keeps appid on radio and **mutes** via the sound module (see below). Verified:
  after `sound.cmd(C_VOL,[VOL_MUTE])` the appid stays `1` (no fallback, no Spotify resume).

## Mute (truly-silent off) — VERIFIED dispatch

The SOUND module (MODULE_CODE_SOUND=4) handles the head unit's global mute. Constants
from `com.syu.eq`'s `FinalSound`:

- `C_VOL = 0` — volume command. Pass a real level, or a sentinel:
- `VOL_MUTE = -3`, `VOL_UNMUTE = -4`, `VOL_MUTE_SWITCH = -5` (toggle).

`bridge.mute()` = `soundModule.cmd(C_VOL, [VOL_MUTE])`; `unmute()` = `[VOL_UNMUTE]`.

Power-off = `mute()` + hold radio source. Power-on = `makeRadioActiveSource()` + `unmute()`.
On app background while muted, `unregister()` runs `unmute()` + `makeRadioInactive()` so the
unit is never left stuck muted and returns to its normal source.

**Caveat:** the mute is at the MCU/amp level (like the analog radio audio itself), so it's
invisible to Android `dumpsys audio`. On a signal-less bench there's nothing audible to
confirm it silences — the command dispatches cleanly and the constant is the documented one,
but **confirm audible mute with a live antenna**. If `VOL_MUTE` doesn't engage on this
firmware, try `VOL_MUTE_SWITCH=-5` (toggle) or read `U_MUTE=3` back from the sound module.

If `com.syu.ms` isn't bindable, we fall back to launching `com.syu.carradio`'s
activity. **NOTE**: on this unit the stock activity is `enabled=2` (disabled) — but
the bind path is reliable, so the fallback never fires.

## SCAN / auto-discover — VERIFIED dispatch

Center SCAN button → `radio.cmd(C_SCAN=9)`. Dispatches cleanly. The actual band
sweep + preset auto-store happens on the MCU; verifying the preset list refills needs
the MCU awake (see power note above).

## What's verified on-device (UIS7870SC, Android 13)

- ✅ Toolkit bind succeeds, registers callbacks on the radio module.
- ✅ `main.cmd(C_APP_ID, [1])` → Qin logs `UI Change AppId to 1`.
- ✅ `radio.cmd(C_BAND, [-2])` (AM) → MCU echoes `U_EXTRA_FREQ_INFO` with
  AM band params `min=530 max=1720 step=10`.
- ✅ `radio.cmd(C_BAND, [-3])` (FM) → MCU echoes `U_EXTRA_FREQ_INFO` with
  FM band params `min=8750 max=10790 step=20` (dekahertz).
- ✅ `radio.cmd(C_FREQ_UP/DOWN/SEEK_UP/DOWN)` all dispatch cleanly.
- ⏳ `U_FREQ`, `U_STEREO`, `U_RDS_*`, `U_SEARCH_STATE` not yet observed —
  expected when the head unit is actually running (MCU state ≠ 23 / off).
  At the time of testing, Qin logs showed `mcuState 23` ("MCU off") and
  `DataMain.sTopAppWhenMcuOff`, i.e. the unit was in accessory standby.

## Outstanding work

- Verify `U_FREQ` encoding by re-running with the head unit awake. Once the first
  FREQ update is observed, confirm whether `BandParams.toKhz` matches our heuristic
  on this hardware.
- Wire `U_SEARCH_STATE` to a "seeking..." UI hint (state ≠ NONE → animate).
- Wire `U_CHANNEL_FREQ` to sync our local PresetStore with the MCU's preset list
  (so we don't drift from what the stock unit also remembers).
- The stock `com.syu.carradio` activity is `enabled=2` on this unit, presumably
  disabled by the user previously. Not blocking us — see "Source switching" above.
