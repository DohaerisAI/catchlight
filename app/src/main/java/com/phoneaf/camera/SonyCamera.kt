package com.phoneaf.camera

import android.util.Log
import com.phoneaf.ptp.Ptp
import com.phoneaf.ptp.PtpResult
import com.phoneaf.ptp.PtpTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One AF frame the camera itself reports alongside a live-view image (PTP3 bodies only). */
data class CameraFocusFrame(
    val cx: Float, val cy: Float, val w: Float, val h: Float, // normalized 0..1
    val type: Int, val state: Int,
)

class LiveViewFrame(val jpeg: ByteArray, val cameraFrames: List<CameraFocusFrame>)

/** Raw Sony codes for the two settings that decide whether phoneAF can steer focus. */
data class FocusSettings(val focusMode: Int?, val focusModeProp: Int?, val focusArea: Int?)

/**
 * Sony Alpha PC-Remote session over USB.
 *
 * Camera setup (a7C): Menu → Network → PC Remote Function → PC Remote On, Cnct Method USB.
 * (Older bodies: Setup → USB Connection → PC Remote.)
 * Focus Mode AF-C, Focus Area "Flexible Spot" (S/M/L) or "Tracking: Flexible Spot".
 */
class SonyCamera(private val t: PtpTransport) {

    var model: String = "Sony camera"
        private set
    var protocol: Int = 200
        private set
    private var propOption = false

    /** Full handshake. Returns null on success or a human-readable failure reason. */
    fun connect(): String? {
        // Session first, then DeviceInfo – the order Sony's own clients use.
        t.resetTransactionId()
        var open = t.transact(Ptp.OP_OPEN_SESSION, intArrayOf(SESSION_ID), timeoutMs = 1500)
        if (open.code == Ptp.RESP_SESSION_ALREADY_OPEN) {
            // A previous remote owner is still attached; release it and retry.
            release()
            t.resetTransactionId()
            open = t.transact(Ptp.OP_OPEN_SESSION, intArrayOf(SESSION_ID), timeoutMs = 1500)
        }
        if (!open.ok) {
            // Camera was left mid-transaction (another app, Android MTP, earlier crash): reset once.
            t.recover()
            open = t.transact(Ptp.OP_OPEN_SESSION, intArrayOf(SESSION_ID), timeoutMs = 2500)
        }
        if (!open.ok) {
            val err = t.lastError
            // Session-less probe: tells us whether the camera talks PTP at all.
            val probe = t.transact(Ptp.OP_GET_DEVICE_INFO, timeoutMs = 2000)
            val probeText = if (probe.ok) "DeviceInfo OK (${probe.data.size}B)" else "DeviceInfo: ${t.lastError}"
            return "OpenSession failed: $err\n$probeText\nUSB: ${t.describe}"
        }

        val info = t.transact(Ptp.OP_GET_DEVICE_INFO)
        if (info.ok) model = parseModel(info.data) ?: model
        Log.i(TAG, "Device: $model")

        val c1 = t.transact(Ptp.OP_SDIO_CONNECT, intArrayOf(1, 0, 0), timeoutMs = 2000)
        if (!c1.ok) return "SDIO connect(1) failed: $c1 ${t.lastError}. Turn on Network → PC Remote Function → PC Remote (method: USB)."
        val c2 = t.transact(Ptp.OP_SDIO_CONNECT, intArrayOf(2, 0, 0), timeoutMs = 2000)
        if (!c2.ok) return "SDIO connect(2) failed: $c2"

        // Same sequence as libgphoto2: ask for protocol 3.00 and let the body answer with the
        // highest it supports. Asking for 2.00 makes protocol-3 bodies such as the original a7C
        // hide newer controls like AF Area Position (0xD2DC). The camera may answer with an
        // empty list while the vendor session warms up, so retry.
        var ext: PtpResult? = null
        for (attempt in 1..20) {
            val r = t.transact(Ptp.OP_SDIO_GET_EXT_DEVICE_INFO, intArrayOf(0x012C, 1), timeoutMs = 1500)
            if (r.ok && r.data.size > 2) { ext = r; break }
            Thread.sleep(100)
        }
        if (ext == null) {
            // Very old bodies: the 2.00 request is the only one they understand.
            ext = t.transact(Ptp.OP_SDIO_GET_EXT_DEVICE_INFO, intArrayOf(0x00C8), timeoutMs = 2500)
        }
        if (ext?.ok != true) return "GetExtDeviceInfo failed: $ext"
        val data = ext!!.data
        protocol = if (data.size >= 2) (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8) else 200
        // libgphoto2 sends SDIO commands with only the property code, also in protocol 3.
        propOption = false
        parseCapabilities(data)

        // libgphoto2 completes phase 3 for every body before taking priority.
        val c3 = t.transact(Ptp.OP_SDIO_CONNECT, intArrayOf(3, 0, 0), timeoutMs = 2000)
        Log.i(TAG, "SDIOConnect(3): ${Ptp.name(c3.code)}")
        Thread.sleep(150)

        // Take host priority so the camera accepts remote AF / shutter control.
        t.transact(Ptp.OP_SDIO_SET_EXT_DEVICE_PROP, intArrayOf(Ptp.PROP_PRIORITY_MODE), byteArrayOf(1))
        Log.i(TAG, "Connected: $model, protocol $protocol")
        return null
    }

    fun release() {
        runCatching {
            t.transact(Ptp.OP_SDIO_SET_EXT_DEVICE_PROP, intArrayOf(Ptp.PROP_PRIORITY_MODE), byteArrayOf(0), timeoutMs = 800)
            t.transact(Ptp.OP_CLOSE_SESSION, timeoutMs = 800)
        }
    }

    /** One live-view frame, or null if the camera had none ready. */
    fun liveView(): LiveViewFrame? {
        val r = t.transact(Ptp.OP_GET_OBJECT, intArrayOf(Ptp.LIVEVIEW_HANDLE), timeoutMs = 1500)
        if (!r.ok || r.data.size < 16) return null
        return parseLiveView(r.data)
    }

    /**
     * Move the AF point. [nx]/[ny] are normalized (0..1) coordinates in the live-view image.
     * In AF-C with Flexible Spot the camera refocuses on the new spot immediately while S1 is held.
     */
    fun setAfPoint(nx: Float, ny: Float): PtpResult {
        val x = (nx * (Ptp.AF_GRID_W - 1)).toInt().coerceIn(0, Ptp.AF_GRID_W - 1)
        val y = (ny * (Ptp.AF_GRID_H - 1)).toInt().coerceIn(0, Ptp.AF_GRID_H - 1)
        val packed = (x shl 16) or y
        val data = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(packed).array()

        // The original a7C accepts D2DC (ctl) but ignores it while S1 is held, so when AF is
        // engaged we release S1, move the frame and press again (AF-C then re-acquires there).
        if (afPath == null && afAuto) {
            return if (s1Down) withS1Released { send(AfPath.CONTROL, data) } else send(AfPath.CONTROL, data)
        }
        // A method forced from settings.
        when (afPath) {
            AfPath.CONTROL -> return send(AfPath.CONTROL, data)
            AfPath.PROPERTY -> return send(AfPath.PROPERTY, data)
            AfPath.CONTROL_RELEASE_S1 -> return withS1Released { send(AfPath.CONTROL, data) }
            AfPath.PROPERTY_RELEASE_S1 -> return withS1Released { send(AfPath.PROPERTY, data) }
            null -> Unit
        }
        // First time: try each known way until the camera accepts one.
        val attempts = listOf(
            AfPath.CONTROL to { send(AfPath.CONTROL, data) },
            AfPath.PROPERTY to { send(AfPath.PROPERTY, data) },
            AfPath.CONTROL_RELEASE_S1 to { withS1Released { send(AfPath.CONTROL, data) } },
            AfPath.PROPERTY_RELEASE_S1 to { withS1Released { send(AfPath.PROPERTY, data) } },
        )
        var last = PtpResult(Ptp.RESP_TRANSPORT_ERROR)
        val log = StringBuilder()
        for ((path, run) in attempts) {
            if (path.releasesS1 && !s1Down) continue
            last = run()
            log.append(path.short).append('=').append(Ptp.name(last.code)).append(' ')
            if (last.ok) { afPath = path; break }
        }
        afProbeLog = log.toString().trim()
        Log.i(TAG, "AF path probe: $afProbeLog -> ${afPath ?: "none"}")
        return last
    }

    /** How AF Area Position (0xD2DC) is delivered; bodies differ, so it is probed once. */
    enum class AfPath(val short: String, val releasesS1: Boolean) {
        CONTROL("ctl", false),                 // SDIO_ControlDevice 0x9207 (a7C II, a6600)
        PROPERTY("prop", false),               // SDIO_SetExtDevicePropValue 0x9205
        CONTROL_RELEASE_S1("ctl-S1", true),    // same, but with the half-press released
        PROPERTY_RELEASE_S1("prop-S1", true),
    }

    @Volatile var afPath: AfPath? = null
    /** Default: ctl, wrapped in an S1 release when the half-press is held. */
    @Volatile var afAuto = true
    @Volatile var afProbeLog: String = ""
        private set
    @Volatile private var s1Down = false

    /** Back to the automatic method. */
    fun resetAfPath() { afPath = null; afAuto = true }

    private fun send(path: AfPath, data: ByteArray): PtpResult = when (path) {
        AfPath.CONTROL, AfPath.CONTROL_RELEASE_S1 -> {
            val r = control(Ptp.PROP_AF_AREA_POSITION, data)
            if (r.code == Ptp.RESP_DEVICE_BUSY) { Thread.sleep(30); control(Ptp.PROP_AF_AREA_POSITION, data) } else r
        }
        AfPath.PROPERTY, AfPath.PROPERTY_RELEASE_S1 -> t.transact(
            Ptp.OP_SDIO_SET_EXT_DEVICE_PROP,
            if (propOption) intArrayOf(Ptp.PROP_AF_AREA_POSITION, 1) else intArrayOf(Ptp.PROP_AF_AREA_POSITION),
            data, timeoutMs = 800,
        )
    }

    /** Some bodies refuse to move the AF frame while S1 (half-press) is held. */
    private fun withS1Released(block: () -> PtpResult): PtpResult {
        val wasDown = s1Down
        if (wasDown) halfPress(false)
        val r = block()
        if (wasDown) halfPress(true)
        return r
    }

    /** Reads Focus Mode and Focus Area from the camera's full property dump (0x9209). */
    fun readFocusSettings(): FocusSettings? {
        val r = t.transact(
            Ptp.OP_SDIO_GET_ALL_EXT_PROP_INFO,
            if (propOption) intArrayOf(0, 1) else IntArray(0),
            timeoutMs = 1500,
        )
        if (!r.ok || r.data.isEmpty()) return null
        lastPropDump = r.data
        val ptpMode = findU16Prop(r.data, Ptp.PROP_FOCUS_MODE_PTP, FOCUS_MODE_CODES)
        val sonyMode = if (ptpMode == null) findU16Prop(r.data, Ptp.PROP_FOCUS_MODE_SONY, FOCUS_MODE_CODES) else null
        return FocusSettings(
            focusMode = ptpMode ?: sonyMode,
            focusModeProp = if (ptpMode != null) Ptp.PROP_FOCUS_MODE_PTP else if (sonyMode != null) Ptp.PROP_FOCUS_MODE_SONY else null,
            focusArea = findU16Prop(r.data, Ptp.PROP_FOCUS_AREA, FOCUS_AREA_CODES),
        )
    }

    /** Writes a 16-bit Sony setting (e.g. Focus Area = Flexible Spot M). */
    fun setU16Setting(prop: Int, value: Int): PtpResult =
        t.transact(
            Ptp.OP_SDIO_SET_EXT_DEVICE_PROP,
            if (propOption) intArrayOf(prop, 1) else intArrayOf(prop),
            u16(value),
            timeoutMs = 1500,
        )

    /**
     * Finds a UINT16 property descriptor (code, type=0x0004, get/set, [enabled], default, current)
     * and returns its current value if it is one of [valid].
     */
    /** Property and control codes the body says it supports (from GetExtDeviceInfo). */
    var supportedProps: Set<Int> = emptySet()
        private set
    var supportedControls: Set<Int> = emptySet()
        private set

    /** Layout (libgphoto2): u16 version, u32 n, n×u16 property codes, u32 m, m×u16 control codes. */
    private fun parseCapabilities(d: ByteArray) {
        runCatching {
            val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            bb.short
            val n = bb.int
            supportedProps = (0 until n).map { bb.short.toInt() and 0xFFFF }.toSet()
            if (bb.remaining() >= 4) {
                val m = bb.int
                supportedControls = (0 until minOf(m, bb.remaining() / 2)).map { bb.short.toInt() and 0xFFFF }.toSet()
            }
        }.onFailure { Log.w(TAG, "capabilities parse", it) }
    }

    /** One-line answer to "can this body be steered?" plus raw Focus Area bytes, for the settings screen. */
    fun capabilityReport(allProps: ByteArray?): String {
        fun has(code: Int): String {
            val p = code in supportedProps; val c = code in supportedControls
            return "%04X:".format(code) + when {
                p && c -> "prop+ctl"; p -> "prop"; c -> "ctl"; else -> "NO"
            }
        }
        val area = allProps?.let { rawProp(it, Ptp.PROP_FOCUS_AREA) } ?: "not in dump"
        fun named(codes: Set<Int>) = codes.sorted().joinToString(", ") { c -> "%04X".format(c) + (SONY_NAMES[c]?.let { "·$it" } ?: "") }
        return "proto $protocol · ${supportedProps.size} props ${supportedControls.size} ctls\n" +
            "AF pos ${has(Ptp.PROP_AF_AREA_POSITION)} · touch ${has(0xD2E4)} · area ${has(Ptp.PROP_FOCUS_AREA)} · " +
            "S1 ${has(Ptp.PROP_SHUTTER_HALF)}\nkeys ${has(0xD2CD)} · MF adjust ${has(0xD2D1)} · magnifier ${has(0xD2CB)}" +
            "\nD22C raw: $area" +
            "\n\nCONTROLS: ${named(supportedControls)}\n\nPROPERTIES: ${named(supportedProps)}"
    }

    /** Hex of the first ~16 bytes of a property descriptor in a 0x9209 dump. */
    private fun rawProp(d: ByteArray, code: Int): String? {
        for (i in 0 until d.size - 4) {
            if ((d[i].toInt() and 0xFF) == (code and 0xFF) && (d[i + 1].toInt() and 0xFF) == (code shr 8)) {
                val type = (d[i + 2].toInt() and 0xFF) or ((d[i + 3].toInt() and 0xFF) shl 8)
                if (type !in setOf(0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006, 0x4002, 0x4004)) continue
                return (i until minOf(i + 16, d.size)).joinToString(" ") { "%02X".format(d[it]) }
            }
        }
        return null
    }

    @Volatile var lastPropDump: ByteArray? = null
        private set

    private fun findU16Prop(d: ByteArray, code: Int, valid: Set<Int>): Int? {
        fun u16At(i: Int) = if (i + 1 < d.size) (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8) else -1
        for (i in 0 until d.size - 12) {
            val type = u16At(i + 2)
            if (u16At(i) != code || (type != 0x0004 && type != 0x0002)) continue
            if (type == 0x0002) {
                // UINT8 property: code, type, get/set, enabled, default(1), current(1)
                for (curOff in intArrayOf(i + 7, i + 6)) {
                    val v = d.getOrNull(curOff)?.toInt()?.and(0xFF) ?: continue
                    if (v in valid) return v
                }
                continue
            }
            // Sony inserts an "enabled" byte after get/set; try that layout first.
            for (curOff in intArrayOf(i + 8, i + 7)) {
                val v = u16At(curOff)
                if (v in valid) return v
            }
        }
        return null
    }

    /** Hold / release the shutter half-press (S1) – this is what keeps AF-C tracking. */
    fun halfPress(pressed: Boolean): PtpResult {
        s1Down = pressed
        return control(Ptp.PROP_SHUTTER_HALF, u16(if (pressed) 2 else 1))
    }

    /** Full press + release (S2). Leaves S1 in whatever state [holdAf] says. */
    fun shoot(holdAf: Boolean) {
        halfPress(true)
        control(Ptp.PROP_SHUTTER_FULL, u16(2))
        Thread.sleep(120)
        control(Ptp.PROP_SHUTTER_FULL, u16(1))
        if (!holdAf) halfPress(false)
    }

    private fun control(prop: Int, data: ByteArray): PtpResult =
        t.transact(
            Ptp.OP_SDIO_CONTROL_DEVICE,
            if (propOption) intArrayOf(prop, 1) else intArrayOf(prop),
            data,
            timeoutMs = 500,
            stallIsOk = true,
        )

    private fun u16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    private fun parseLiveView(data: ByteArray): LiveViewFrame? {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val imgOff = bb.int.toLong() and 0xFFFFFFFFL
        val imgLen = bb.int.toLong() and 0xFFFFFFFFL
        val focOff = bb.int.toLong() and 0xFFFFFFFFL
        val focLen = bb.int.toLong() and 0xFFFFFFFFL
        if (imgOff >= 16 && imgLen > 2 && imgOff + imgLen <= data.size) {
            val jpeg = data.copyOfRange(imgOff.toInt(), (imgOff + imgLen).toInt())
            val frames = if (focLen >= 72 && focOff >= 16 && focOff + focLen <= data.size)
                parseFocalFrames(data, focOff.toInt(), focLen.toInt()) else emptyList()
            return LiveViewFrame(jpegFrom(jpeg) ?: jpeg, frames)
        }
        // Some bodies hand back a bare JPEG with an unknown prefix.
        return jpegFrom(data)?.let { LiveViewFrame(it, emptyList()) }
    }

    /** Sony Camera Control PTP 3 "FocalFrameInfo". */
    private fun parseFocalFrames(data: ByteArray, off: Int, len: Int): List<CameraFocusFrame> = runCatching {
        val bb = ByteBuffer.wrap(data, off, len).slice().order(ByteOrder.LITTLE_ENDIAN)
        bb.short; bb.position(bb.position() + 6 + 40)
        val reserved = bb.short.toInt() and 0xFFFF
        bb.position(bb.position() + 6 + reserved * 24)
        val xDen = (bb.int.toLong() and 0xFFFFFFFFL).toFloat().coerceAtLeast(1f)
        val yDen = (bb.int.toLong() and 0xFFFFFFFFL).toFloat().coerceAtLeast(1f)
        val n = bb.short.toInt() and 0xFFFF
        bb.position(bb.position() + 6)
        List(minOf(n, bb.remaining() / 24)) {
            val type = bb.short.toInt() and 0xFFFF
            val state = bb.short.toInt() and 0xFFFF
            bb.position(bb.position() + 4)
            val x = (bb.int.toLong() and 0xFFFFFFFFL) / xDen
            val y = (bb.int.toLong() and 0xFFFFFFFFL) / yDen
            val h = (bb.int.toLong() and 0xFFFFFFFFL) / yDen
            val w = (bb.int.toLong() and 0xFFFFFFFFL) / xDen
            CameraFocusFrame(x, y, w, h, type, state)
        }
    }.getOrDefault(emptyList())

    private fun jpegFrom(d: ByteArray): ByteArray? {
        for (i in 0 until d.size - 2) {
            if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD8.toByte() && d[i + 2] == 0xFF.toByte())
                return if (i == 0) d else d.copyOfRange(i, d.size)
        }
        return null
    }

    /** DeviceInfo dataset: skip to the Model string. */
    private fun parseModel(d: ByteArray): String? = runCatching {
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        bb.short; bb.int; bb.short // std version, vendor ext id, vendor ext version
        readString(bb)              // vendor extension desc
        bb.short                    // functional mode
        repeat(5) { skipU16Array(bb) } // ops, events, props, capture formats, image formats
        readString(bb)              // manufacturer
        readString(bb)              // model
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun skipU16Array(bb: ByteBuffer) {
        val n = bb.int
        bb.position(bb.position() + n * 2)
    }

    private fun readString(bb: ByteBuffer): String {
        val n = bb.get().toInt() and 0xFF
        if (n == 0) return ""
        val chars = CharArray(n) { bb.short.toInt().toChar() }
        return String(chars).trimEnd('\u0000')
    }

    companion object {
        private const val TAG = "SonyCamera"
        private const val SESSION_ID = 1
        /** Sony PTP code names from libgphoto2 (camlibs/ptp2/ptp.h) plus standard PTP ones. */
        val SONY_NAMES: Map<Int, String> = mapOf(
            0xD001 to "IrisModeSetting",
            0xD004 to "FocalDistanceInMeter",
            0xD005 to "FocalDistanceInFeet",
            0xD006 to "FocalDistanceUnitSetting",
            0xD007 to "FocusModeSetting",
            0xD0D9 to "ImageStabilization",
            0xD0DB to "SilentMode",
            0xD0DF to "ShutterType",
            0xD156 to "ShutterLagTiming",
            0xD200 to "DPCCompensation",
            0xD201 to "DRangeOptimize",
            0xD203 to "ImageSize",
            0xD20D to "ShutterSpeed",
            0xD20E to "BatteryLevelIndicator",
            0xD20F to "ColorTemp",
            0xD210 to "CCFilter",
            0xD211 to "AspectRatio",
            0xD213 to "FocusFound",
            0xD214 to "Zoom",
            0xD215 to "ObjectInMemory",
            0xD216 to "ExposeIndex",
            0xD217 to "AELockIndication",
            0xD218 to "BatteryLevel",
            0xD219 to "SensorCrop",
            0xD21B to "PictureEffect",
            0xD21C to "ABFilter",
            0xD21D to "MovieRecordingState",
            0xD21E to "ISO",
            0xD21F to "FELockIndication",
            0xD221 to "LiveViewStatus",
            0xD222 to "StillImageStoreDestination",
            0xD223 to "DateTimeSet",
            0xD224 to "ExposureCompensation",
            0xD226 to "ISO2",
            0xD227 to "FormatMediaStatus",
            0xD229 to "ShutterSpeed2",
            0xD22C to "FocusArea",
            0xD22D to "FocusMagnifierStatus",
            0xD22F to "CurrentFocusMagnifierRatio",
            0xD230 to "FocusMagnifierPosition",
            0xD231 to "LiveViewSettingEffect",
            0xD235 to "ManualFocusAdjustEnableStatus",
            0xD239 to "PixelShiftShootingMode2",
            0xD23A to "PixelShiftShootingNumber",
            0xD23B to "PixelShiftShootingInterval",
            0xD23C to "PixelShiftShootingStatus",
            0xD23D to "PixelShiftShootingProgress",
            0xD23F to "PictureProfile",
            0xD240 to "CreativeStyle",
            0xD241 to "FileFormatMovie",
            0xD242 to "RecordingSettingMovie",
            0xD248 to "MediaSLOT1Status",
            0xD249 to "MediaSLOT1RemainingShots",
            0xD24A to "MediaSLOT1ShootingTime",
            0xD24C to "FocalPosition",
            0xD24E to "AWBLockIndication",
            0xD24F to "IntervalRECModel",
            0xD250 to "IntervalRECStatus",
            0xD251 to "DeviceOverheatStatus",
            0xD252 to "JpegQuality",
            0xD253 to "CompressionSetting",
            0xD254 to "FocusMagnifierSetting",
            0xD255 to "AFTrackingSensitivity",
            0xD256 to "MediaSLOT2Status",
            0xD257 to "MediaSLOT2RemainingShots",
            0xD258 to "MediaSLOT2ShootingTime",
            0xD25A to "PriorityMode",
            0xD25B to "ZoomEnableStatus",
            0xD25C to "ZoomScale",
            0xD25D to "ZoomBarInformation",
            0xD25E to "ZoomSpeedRange",
            0xD25F to "ZoomSetting",
            0xD260 to "ZoomTypeStatus",
            0xD262 to "WirelessFlashSetting",
            0xD263 to "RedEyeReduction",
            0xD264 to "RemoteControlRestrictionStatus",
            0xD267 to "LiveViewArea",
            0xD268 to "PcSaveImageSize",
            0xD269 to "PcSaveImageFormat",
            0xD26A to "LiveViewImageQuality",
            0xD26B to "CustomWBCaptureArea",
            0xD26C to "CustomWBCaptureFrameSize",
            0xD26D to "CustomWBCaptureStandbyEnable",
            0xD26E to "CustomWBCaptureStandbyCancelEnable",
            0xD26F to "CustomWBCaptureEnable",
            0xD270 to "CustomWBExecutionState",
            0xD271 to "SettingsSaveEnable",
            0xD272 to "SettingsReadEnable",
            0xD273 to "SettingsSaveReadState",
            0xD274 to "FTPSettingSaveEnable",
            0xD275 to "FTPSettingReadEnable",
            0xD276 to "FTPSettingSaveReadState",
            0xD2C1 to "ShutterHalfRelease",
            0xD2C2 to "ShutterRelease",
            0xD2C3 to "AELButton",
            0xD2C4 to "AFLButton",
            0xD2C5 to "ReleaseLock",
            0xD2C7 to "RequestOneShooting",
            0xD2C8 to "MovieRecButtonHold",
            0xD2C9 to "FELButton",
            0xD2CA to "FormatMedia",
            0xD2CB to "FocusMagnifier",
            0xD2CC to "FocusMagnifierCancel",
            0xD2CD to "RemoteKeyUp",
            0xD2CE to "RemoteKeyDown",
            0xD2CF to "RemoteKeyLeft",
            0xD2D0 to "RemoteKeyRight",
            0xD2D1 to "ManualFocusAdjust",
            0xD2D2 to "AFMFHold",
            0xD2D3 to "CancelPixelShiftShooting",
            0xD2D4 to "PixelShiftShootingMode",
            0xD2D5 to "HFRStandby",
            0xD2D6 to "HFRRecordingCancel",
            0xD2D7 to "FocusStepNear",
            0xD2D8 to "FocusStepFar",
            0xD2D9 to "AWBLButton",
            0xD2DC to "AFAreaPosition",
            0xD2DD to "ZoomOperation",
            0xD2DF to "CustomWBCaptureStandby",
            0xD2E0 to "CustomWBCaptureStandbyCancel",
            0xD2E1 to "CustomWBCapture",
            0xD2E2 to "FormatMediaCard",
            0xD2E4 to "RemoteTouch",
            0xD2E5 to "RemoteTouchCancel",
            0xD2E9 to "SaveZoomAndFocusPosition",
            0xD2EA to "LoadZoomAndFocusPosition",
            0xD60E to "QX_Zoom_Absolute",
            0xD60F to "QX_Movie_Rec",
            0xD612 to "QX_Request_For_Update",
            0xD613 to "QX_Zoom_Wide_For_One_Shot",
            0xD614 to "QX_Zoom_Tele_For_One_Shot",
            0xD617 to "QX_Capture",
            0xD61C to "QX_Media_Format",
            0xD61D to "QX_AutoFocus",
            0xD61E to "QX_AE_Lock",
            0xD620 to "QX_PictureProfileInitialize",
            0xD621 to "QX_PictureProfile",
            0xD622 to "QX_AFSPrioritySetting",
            0xD623 to "QX_AFCPrioritySetting",
            0xD624 to "QX_LensUpdateState",
            0xD625 to "QX_Request_For_Update_For_Lens",
            0xD626 to "QX_SilentShooting",
            0xD627 to "QX_HDMIInfoDisplay",
            0xD628 to "QX_TCUBDisp",
            0xD629 to "QX_TCPreset",
            0xD62A to "QX_TCMake",
            0xD62B to "QX_TCRun",
            0xD62C to "QX_UBPreset",
            0xD62D to "QX_TCFormat",
            0xD62E to "QX_LongExposureNR",
            0xD62F to "QX_UBTimeRec",
            0xD637 to "QX_Power_Off",
            0xD638 to "QX_RequestOneShooting",
            0xD63B to "QX_AF_Lock",
            0xD63C to "QX_Zoom_Tele",
            0xD63E to "QX_Zoom_Wide",
            0xD641 to "QX_Focus_Magnification",
            0xD6A1 to "QX_Focus_Near_For_One_Shot",
            0xD6A2 to "QX_Focus_Far_For_One_Shot",
            0xD6A3 to "QX_Focus_Near_For_Continuous",
            0xD6A4 to "QX_Focus_Far_For_Continuous",
            0xD6A7 to "QX_FocusMagnificationLevel",
            0xD6A8 to "QX_FocusMagnificationPosition",
            0xD6A9 to "QX_LensStatus",
            0xD6AA to "QX_LiveviewResolution",
            0xD6AF to "QX_NotifyFocusPosition",
            0xD6B0 to "QX_DriveMode",
            0xD6B1 to "QX_DateTime",
            0xD6B3 to "QX_AspectRatio",
            0xD6B7 to "QX_ImageSize",
            0xD6B8 to "QX_WhiteBalance",
            0xD6B9 to "QX_CompressionSetting",
            0xD6BA to "QX_CautionError",
            0xD6BB to "QX_StorageInformation",
            0xD6BC to "QX_MovieQualitySetting",
            0xD6BD to "QX_MovieFormatSetting",
            0xD6BE to "QX_ZoomSetAbsolute",
            0xD6BF to "QX_ZoomInformation",
            0xD6C1 to "QX_FocusSpeedForOneShot",
            0xD6C2 to "QX_FlashCompensation",
            0xD6C3 to "QX_ExposureCompensation",
            0xD6C5 to "QX_Aperture",
            0xD6C6 to "QX_ShootingFileInformation",
            0xD6C7 to "QX_MediaFormatState",
            0xD6C9 to "QX_ZoomMode",
            0xD6CA to "QX_FlashMode",
            0xD6CB to "QX_FocusMode",
            0xD6CC to "QX_ExposureMode",
            0xD6CD to "QX_MovieRecordingState",
            0xD6CF to "QX_SelectSaveMedia",
            0xD6D0 to "QX_StillSteady",
            0xD6D1 to "QX_MovieSteady",
            0xD6D2 to "QX_Housing",
            0xD6D3 to "QX_K4OutputSetting",
            0xD6D4 to "QX_HDMIRECControl",
            0xD6D5 to "QX_TimeCodeOutputToHDMI",
            0xD6D6 to "QX_HDMIResolution",
            0xD6D7 to "QX_NTSC_PAL_Selector",
            0xD6D8 to "QX_HDMIOutput",
            0xD6D9 to "QX_Camera_Setting_Reset",
            0xD6DA to "QX_Camera_Initialize",
            0xD6DB to "QX_ISOAutoMinimum",
            0xD6DC to "QX_ISOAutoMaximum",
            0xD6DD to "QX_APSCSuper35mm",
            0xD6DE to "QX_LiveviewStatus",
            0xD6DF to "QX_WhiteBalanceInitialize",
            0xD6E2 to "QX_OperatingMode",
            0xD6E3 to "QX_BiaxialFineTuningABDirection",
            0xD6E5 to "QX_HighISONr",
            0xD6E8 to "QX_AELockIndication",
            0xD6E9 to "QX_ElectronicFrontCurtainShutter",
            0xD6EA to "QX_ShutterSpeed",
            0xD6EC to "QX_FocusIndication",
            0xD6EF to "QX_BiaxialFineTuningGMDirection",
            0xD6F0 to "QX_ColorTemperature",
            0xD6F1 to "QX_BatteryLevelIndication",
            0xD6F2 to "QX_ISO",
            0xD6F3 to "QX_AutoSlowShutter",
            0xD6FE to "QX_DynamicRangeOptimizer",
            0x5001 to "BatteryLevel",
            0x5003 to "ImageSize",
            0x5004 to "CompressionSetting",
            0x5005 to "WhiteBalance",
            0x5007 to "FNumber",
            0x5008 to "FocalLength",
            0x500A to "FocusMode",
            0x500B to "ExposureMeteringMode",
            0x500C to "FlashMode",
            0x500E to "ExposureProgramMode",
            0x5010 to "ExposureBiasCompensation",
            0x5013 to "StillCaptureMode",
        )
        private val FOCUS_MODE_CODES = setOf(1, 2, 0x8004, 0x8005, 0x8006)
        // Older bodies number areas 1..15; newer ones (and the Camera Remote SDK) use 0x0001..0x0208.
        private val FOCUS_AREA_CODES = (1..20).toSet() + setOf(0x0101, 0x0102, 0x0103, 0x0104, 0x0105) + (0x0201..0x0208).toSet()
        private val PTP3_MODELS = listOf("ILCE-7CM2", "ILCE-7CR", "ILCE-6700", "ZV-E1")
    }
}
