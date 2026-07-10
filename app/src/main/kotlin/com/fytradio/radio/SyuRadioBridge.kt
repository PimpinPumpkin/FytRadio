package com.fytradio.radio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Real SYU IPC client for the radio module. Reverse-engineered from the stock
 * `com.syu.carradio` APK, which bundles the same `com.syu.ipc` client library that
 * NavRadio and friends use.
 *
 * Protocol (raw transact(); sidesteps AIDL stub generation, gives us total control
 * over the wire format):
 *
 * 1. `bindService(action = "com.syu.ms.toolkit", package = "com.syu.ms")` → IBinder
 *    implementing `IRemoteToolkit`. Descriptor: `com.syu.ipc.IRemoteToolkit`.
 *    Single method: `getRemoteModule(int code)` at transaction code 1.
 *
 * 2. Call `getRemoteModule(MODULE_CODE_RADIO = 1)` → IBinder implementing
 *    `IRemoteModule`. Descriptor: `com.syu.ipc.IRemoteModule`. Methods:
 *      - 1: `cmd(int command, int[] ints, float[] flts, String[] strs)`
 *      - 2: `get(int dataId, int[] ints, float[] flts, String[] strs) → ModuleObject`
 *           (we don't call this; everything we need flows via callbacks)
 *      - 3: `register(IModuleCallback cb, int updateId, int p)`
 *      - 4: `unregister(IModuleCallback cb, int updateId)`
 *
 * 3. Register a callback `Binder` for each [Updates] id we want notifications for.
 *    The descriptor is `com.syu.ipc.IModuleCallback`. Single transaction:
 *      - 1: `update(int updateId, int[] ints, float[] flts, String[] strs)` → void
 *
 * Commands ([Cmds]) are sent to the MCU through the radio module via [cmd]. Examples:
 *   - `cmd(C_SEEK_UP)`: auto-seek up
 *   - `cmd(C_FREQ_UP)`: one tuning step up
 *   - `cmd(C_BAND, intArrayOf(BAND_SWITCH_FM))`: switch to FM
 *   - `cmd(C_SELECT_CHANNEL, intArrayOf(presetIndex))`: recall preset N
 *   - `cmd(C_SAVE_CHANNEL, intArrayOf(presetIndex))`: save current to preset N
 */
class SyuRadioBridge(
    private val appContext: Context,
    private val onUpdate: (updateId: Int, ints: IntArray?, flts: FloatArray?, strs: Array<String?>?) -> Unit,
) {
    /** Invoked on the main thread once the toolkit + radio module are connected and subscribed.
     *  Used by the controller to fire auto-start only after commands can actually be sent. */
    var onConnected: (() -> Unit)? = null
    /** Stock SYU command IDs (FinalRadio.C_*). */
    object Cmds {
        const val NEXT_CHANNEL = 0
        const val PREV_CHANNEL = 1
        const val FREQ_UP = 3       // one tuning step up
        const val FREQ_DOWN = 4     // one tuning step down
        const val SEEK_UP = 5       // auto-seek up
        const val SEEK_DOWN = 6     // auto-seek down
        const val SELECT_CHANNEL = 7   // arg: preset index
        const val SAVE_CHANNEL = 8     // arg: preset index
        const val SCAN = 9
        const val SAVE = 10
        const val BAND = 11            // arg: BAND_SWITCH_FM / BAND_SWITCH_AM / BAND_SWITCH_ALL
        const val AREA = 12            // arg: AREA_USA/EUROPE/...
        const val FREQ = 13            // arg: freq value in MCU units
        const val SENSITY = 14
        const val AUTO_SENSITY = 15
        const val RDS_ENABLE = 16
        const val STEREO = 17
        const val LOC = 18
        const val RDS_TA_ENABLE = 19
        const val RDS_AF_ENABLE = 20
        const val RDS_PTY_ENABLE = 21
        const val SEARCH = 22

        const val BAND_SWITCH_ALL = -1
        const val BAND_SWITCH_AM = -2
        const val BAND_SWITCH_FM = -3

        const val AREA_USA = 0
        const val AREA_LATIN = 1
        const val AREA_EUROPE = 2  // also CHINA
        const val AREA_OIRT = 3
        const val AREA_JAPAN = 4
    }

    /** Update notification IDs (FinalRadio.U_*). Subscribe to each via [registerCallback]. */
    object Updates {
        const val BAND = 0
        const val FREQ = 1
        const val AREA = 2
        const val CHANNEL = 3                  // currently selected preset index
        const val CHANNEL_FREQ = 4             // preset list frequencies; ints carries [index, freq]
        const val PTY_ID = 5
        const val RDS_AF_ENABLE = 6
        const val RDS_TA = 7
        const val RDS_TP = 8
        const val RDS_TA_ENABLE = 9
        const val RDS_PI_SEEK = 10
        const val RDS_TA_SEEK = 11
        const val RDS_PTY_SEEK = 12
        const val RDS_TEXT = 13                // strs[0] = Radio Text (RT)
        const val RDS_CHANNEL_TEXT = 14        // strs[0] = Program Service name (PS)
        const val RDS_ENABLE = 15
        const val EXTRA_FREQ_INFO = 16         // ints = [min, max, stepLen, stepCnt]
        const val SENSITY_AM = 17
        const val SENSITY_FM = 18
        const val AUTO_SENSITY = 19
        const val SCAN = 20
        const val STEREO = 21                  // ints[0] = 0/1
        const val SEARCH_STATE = 22
        const val LOC = 23
    }

    /** Update IDs we subscribe to on bind. Drives the UI. */
    val SUBSCRIBED_UPDATES = intArrayOf(
        Updates.BAND,
        Updates.FREQ,
        Updates.EXTRA_FREQ_INFO,
        Updates.STEREO,
        Updates.RDS_TEXT,
        Updates.RDS_CHANNEL_TEXT,
        Updates.RDS_ENABLE,
        Updates.PTY_ID,
        Updates.SEARCH_STATE,
        Updates.CHANNEL,
        Updates.CHANNEL_FREQ,
    )

    /** MAIN module constants. C_APP_ID drives the MCU's active audio source. */
    object Main {
        const val C_APP_ID = 0
        const val APP_ID_RADIO = 1       // tuner, what we want
        const val APP_ID_BTAV = 3        // Bluetooth audio
        const val APP_ID_CAR_RADIO = 11  // alternate "car radio" id on some FYT builds
        const val APP_ID_NULL = 0
    }

    /** SOUND module constants (from com.syu.eq's FinalSound). C_VOL with a VOL_* sentinel
     *  toggles the head unit's global mute without disturbing the saved volume level. */
    object Sound {
        const val C_VOL = 0
        const val VOL_MUTE = -3
        const val VOL_UNMUTE = -4
        const val VOL_MUTE_SWITCH = -5
    }

    private var bound = false
    private var toolkit: IBinder? = null
    private var module: IBinder? = null
    private var mainModule: IBinder? = null
    private var soundModule: IBinder? = null

    private val callback = object : Binder() {
        init { attachInterface(null, IMODULE_CALLBACK_DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == CALLBACK_TRANS_UPDATE) {
                data.enforceInterface(IMODULE_CALLBACK_DESCRIPTOR)
                val u = data.readInt()
                val ints = data.createIntArray()
                val flts = data.createFloatArray()
                val strs = data.createStringArray()
                runCatching { onUpdate(u, ints, flts, strs) }
                    .onFailure { Log.w(TAG, "onUpdate(u=$u) handler threw: ${it.message}") }
                reply?.writeNoException()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.i(TAG, "Toolkit connected: $name")
            toolkit = service
            val radio = doGetRemoteModule(MODULE_CODE_RADIO)
            if (radio == null) {
                Log.w(TAG, "getRemoteModule(RADIO) returned null binder")
                return
            }
            module = radio
            mainModule = doGetRemoteModule(MODULE_CODE_MAIN)
            soundModule = doGetRemoteModule(MODULE_CODE_SOUND)
            for (u in SUBSCRIBED_UPDATES) doRegister(radio, u)
            Log.i(TAG, "Subscribed to ${SUBSCRIBED_UPDATES.size} updates (main=${mainModule != null} sound=${soundModule != null})")
            runCatching { onConnected?.invoke() }
                .onFailure { Log.w(TAG, "onConnected handler threw: ${it.message}") }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "Toolkit disconnected: $name")
            toolkit = null
            module = null
            mainModule = null
            soundModule = null
        }
    }

    fun bind(): Boolean {
        if (bound) return true
        val intent = Intent(TOOLKIT_ACTION).setPackage(SYU_MS_PKG)
        bound = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            Log.w(TAG, "bindService failed: ${it.message}")
            false
        }
        if (!bound) Log.w(TAG, "bindService($TOOLKIT_ACTION) returned false; is $SYU_MS_PKG present?")
        return bound
    }

    fun unbind() {
        if (!bound) return
        val mod = module
        if (mod != null && mod.isBinderAlive) {
            for (u in SUBSCRIBED_UPDATES) runCatching { doUnregister(mod, u) }
        }
        runCatching { appContext.unbindService(connection) }
        bound = false
        toolkit = null
        module = null
    }

    /** Send a radio command. Returns true if the transact succeeded; false on RemoteException
     *  or if we are not currently bound. Safe to call before bind(); it just no-ops. */
    fun cmd(command: Int, ints: IntArray? = null, flts: FloatArray? = null, strs: Array<String?>? = null): Boolean =
        cmdOn(module, "radio", command, ints, flts, strs)

    /** Same as [cmd] but targets the MAIN module, used for source switching via C_APP_ID. */
    fun cmdMain(command: Int, ints: IntArray? = null, flts: FloatArray? = null, strs: Array<String?>? = null): Boolean =
        cmdOn(mainModule, "main", command, ints, flts, strs)

    /** Same as [cmd] but targets the SOUND module, used for global mute/unmute. */
    fun cmdSound(command: Int, ints: IntArray? = null, flts: FloatArray? = null, strs: Array<String?>? = null): Boolean =
        cmdOn(soundModule, "sound", command, ints, flts, strs)

    /** Global mute (preserves the saved volume level). */
    fun mute() = cmdSound(Sound.C_VOL, intArrayOf(Sound.VOL_MUTE))

    /** Release the global mute. */
    fun unmute() = cmdSound(Sound.C_VOL, intArrayOf(Sound.VOL_UNMUTE))

    private fun cmdOn(
        mod: IBinder?,
        label: String,
        command: Int,
        ints: IntArray?,
        flts: FloatArray?,
        strs: Array<String?>?,
    ): Boolean {
        if (mod == null) {
            Log.d(TAG, "$label.cmd($command) ignored: not bound")
            return false
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IREMOTE_MODULE_DESCRIPTOR)
            data.writeInt(command)
            data.writeIntArray(ints)
            data.writeFloatArray(flts)
            data.writeStringArray(strs)
            mod.transact(MODULE_TRANS_CMD, data, reply, 0)
            reply.readException()
            Log.i(TAG, "$label.cmd($command, ints=${ints?.toList()}) ok")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "$label.cmd($command) transact threw: ${t.message}")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ---- Convenience wrappers around cmd() ----

    fun seekUp() = cmd(Cmds.SEEK_UP)
    fun seekDown() = cmd(Cmds.SEEK_DOWN)
    fun stepUp() = cmd(Cmds.FREQ_UP)
    fun stepDown() = cmd(Cmds.FREQ_DOWN)

    /** Band scan / auto-store: the MCU sweeps the band and refills the preset list. */
    fun scan() = cmd(Cmds.SCAN)

    /** Force mono (1) vs. allow stereo (0); helps with weak/noisy FM. Arg form unverified;
     *  the MCU echoes U_STEREO either way so the UI still reflects the real state. */
    fun setForceMono(mono: Boolean) = cmd(Cmds.STEREO, intArrayOf(if (mono) 1 else 0))
    fun setBand(band: Band) = cmd(
        Cmds.BAND,
        intArrayOf(if (band == Band.FM) Cmds.BAND_SWITCH_FM else Cmds.BAND_SWITCH_AM),
    )
    fun setFrequency(mcuFreq: Int) = cmd(Cmds.FREQ, intArrayOf(mcuFreq))
    fun recallPreset(index: Int) = cmd(Cmds.SELECT_CHANNEL, intArrayOf(index))
    fun savePreset(index: Int) = cmd(Cmds.SAVE_CHANNEL, intArrayOf(index))

    // ---- Raw transact plumbing ----

    private fun doGetRemoteModule(moduleCode: Int): IBinder? {
        val tk = toolkit ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IREMOTE_TOOLKIT_DESCRIPTOR)
            data.writeInt(moduleCode)
            tk.transact(TOOLKIT_TRANS_GET_REMOTE_MODULE, data, reply, 0)
            reply.readException()
            reply.readStrongBinder()
        } catch (t: Throwable) {
            Log.w(TAG, "getRemoteModule($moduleCode) failed: ${t.message}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun doRegister(mod: IBinder, updateId: Int, p: Int = 0) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IREMOTE_MODULE_DESCRIPTOR)
            data.writeStrongBinder(callback)
            data.writeInt(updateId)
            data.writeInt(p)
            mod.transact(MODULE_TRANS_REGISTER, data, reply, 0)
            reply.readException()
        } catch (t: Throwable) {
            Log.w(TAG, "register(u=$updateId) failed: ${t.message}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun doUnregister(mod: IBinder, updateId: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IREMOTE_MODULE_DESCRIPTOR)
            data.writeStrongBinder(callback)
            data.writeInt(updateId)
            mod.transact(MODULE_TRANS_UNREGISTER, data, reply, 0)
            reply.readException()
        } catch (_: Throwable) {
            // Best-effort on teardown; the host service may already be gone.
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Source switch: tell the MAIN module to set the MCU's active appid to radio. This is
     * the direct path NavRadio uses: no activity flash, no dependency on the stock radio
     * being installed/enabled.
     *
     * Tries `APP_ID_RADIO=1` first, then `APP_ID_CAR_RADIO=11` if the unit's MCU prefers
     * that one (varies by FYT build). Falls back to launching the stock activity if the
     * MAIN module isn't available, e.g. if the toolkit bind failed.
     */
    fun makeRadioActiveSource(): Boolean {
        if (mainModule != null) {
            // Fire BOTH appid candidates. UNISOC FYT builds (e.g. UIS7870SC) wake the
            // in-MCU FM tuner on APP_ID_CAR_RADIO=11; older PX5/PX6 builds use
            // APP_ID_RADIO=1. cmd() returns ok in both cases (the MAIN module accepts
            // the value either way) so we can't pick by return code; just send both.
            cmdMain(Main.C_APP_ID, intArrayOf(Main.APP_ID_CAR_RADIO))
            cmdMain(Main.C_APP_ID, intArrayOf(Main.APP_ID_RADIO))
            return true
        }
        // Activity-launch fallback. com.syu.carradio's launcher activity is `enabled=2`
        // (disabled) on units where the user replaced the stock radio with a 3rd-party app
        // like NavRadio; in that case this will fail silently and we just log.
        val intent = appContext.packageManager.getLaunchIntentForPackage(SYU_CARRADIO_PKG)
            ?: Intent(Intent.ACTION_MAIN).setPackage(SYU_CARRADIO_PKG).addCategory(Intent.CATEGORY_LAUNCHER)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { appContext.startActivity(intent); true }
            .getOrElse { Log.w(TAG, "launch $SYU_CARRADIO_PKG failed: ${it.message}"); false }
    }

    /**
     * Power off: release the radio as the active MCU source so its audio stops. Sends
     * `C_APP_ID = APP_ID_NULL`, which hands the MCU back to no/previous source.
     *
     * If APP_ID_NULL leaves the speakers on a dead source on some firmware, switching to
     * APP_ID_LAST (-1) instead is the alternative, left as a one-line change.
     */
    fun makeRadioInactive(): Boolean {
        if (mainModule == null) return false
        return cmdMain(Main.C_APP_ID, intArrayOf(Main.APP_ID_NULL))
    }

    companion object {
        private const val TAG = "FytRadio"

        const val SYU_MS_PKG = "com.syu.ms"
        const val SYU_CARRADIO_PKG = "com.syu.carradio"
        const val TOOLKIT_ACTION = "com.syu.ms.toolkit"

        // Descriptors must match the server side exactly; these are the strings
        // baked into FinalRadio / IRemoteToolkit$Stub / IRemoteModule$Stub etc.
        const val IREMOTE_TOOLKIT_DESCRIPTOR = "com.syu.ipc.IRemoteToolkit"
        const val IREMOTE_MODULE_DESCRIPTOR = "com.syu.ipc.IRemoteModule"
        const val IMODULE_CALLBACK_DESCRIPTOR = "com.syu.ipc.IModuleCallback"

        // Transaction codes (IBinder.FIRST_CALL_TRANSACTION + N, AIDL assigns by
        // declaration order). Verified against the dexdump of IRemoteToolkit$Stub /
        // IRemoteModule$Stub / IModuleCallback$Stub in the stock carradio APK.
        const val TOOLKIT_TRANS_GET_REMOTE_MODULE = IBinder.FIRST_CALL_TRANSACTION + 0  // 1
        const val MODULE_TRANS_CMD = IBinder.FIRST_CALL_TRANSACTION + 0                 // 1
        const val MODULE_TRANS_GET = IBinder.FIRST_CALL_TRANSACTION + 1                 // 2
        const val MODULE_TRANS_REGISTER = IBinder.FIRST_CALL_TRANSACTION + 2            // 3
        const val MODULE_TRANS_UNREGISTER = IBinder.FIRST_CALL_TRANSACTION + 3          // 4
        const val CALLBACK_TRANS_UPDATE = IBinder.FIRST_CALL_TRANSACTION + 0            // 1

        const val MODULE_CODE_MAIN = 0
        const val MODULE_CODE_RADIO = 1
        const val MODULE_CODE_BT = 2
        const val MODULE_CODE_DVD = 3
        const val MODULE_CODE_SOUND = 4
        const val MODULE_CODE_IPOD = 5
        const val MODULE_CODE_TV = 6
        const val MODULE_CODE_CANBUS = 7
        const val MODULE_CODE_TPMS = 8
    }
}
