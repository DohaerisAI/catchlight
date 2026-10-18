package com.phoneaf.ptp

/**
 * PTP + Sony "SDIO" (PC Remote) vendor constants.
 *
 * Opcodes and property codes are the ones used by Sony's Camera Remote SDK /
 * libgphoto2 and proven on real bodies by github.com/YLTai/sony-camera-android.
 */
object Ptp {
    const val SONY_VENDOR_ID = 0x054C

    const val TYPE_COMMAND = 1
    const val TYPE_DATA = 2
    const val TYPE_RESPONSE = 3
    const val TYPE_EVENT = 4
    const val HEADER = 12

    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_OPEN_SESSION = 0x1002
    const val OP_CLOSE_SESSION = 0x1003
    const val OP_GET_OBJECT = 0x1009

    const val OP_SDIO_CONNECT = 0x9201
    const val OP_SDIO_GET_EXT_DEVICE_INFO = 0x9202
    const val OP_SDIO_SET_EXT_DEVICE_PROP = 0x9205   // "SetControlDeviceA" – settings
    const val OP_SDIO_CONTROL_DEVICE = 0x9207        // "SetControlDeviceB" – buttons / AF point
    const val OP_SDIO_GET_ALL_EXT_PROP_INFO = 0x9209

    const val RESP_OK = 0x2001
    const val RESP_SESSION_ALREADY_OPEN = 0x201E
    const val RESP_DEVICE_BUSY = 0x2019
    const val RESP_TRANSPORT_ERROR = -1

    // Sony device properties
    const val PROP_PRIORITY_MODE = 0xD25A
    const val PROP_SHUTTER_HALF = 0xD2C1   // S1: 2 = press, 1 = release
    const val PROP_SHUTTER_FULL = 0xD2C2   // S2: 2 = press, 1 = release
    const val PROP_AF_AREA_POSITION = 0xD2DC // uint32 (x << 16) | y on a 640x480 grid
    const val PROP_FOCUS_AREA = 0xD22C
    const val PROP_FOCUS_MODE_PTP = 0x500A
    const val PROP_FOCUS_MODE_SONY = 0xD208

    // Focus Area raw values (Sony labels as reported by Camera Remote SDK clients).
    const val AREA_SPOT_M = 6
    const val FOCUS_AF_C = 0x8004

    const val LIVEVIEW_HANDLE = 0xFFFFC002.toInt()

    /** Sony maps AF positions onto this logical grid regardless of sensor aspect. */
    const val AF_GRID_W = 640
    const val AF_GRID_H = 480

    fun name(code: Int): String = when (code) {
        RESP_OK -> "OK"
        0x2002 -> "GeneralError"
        0x2003 -> "SessionNotOpen"
        0x2005 -> "OpNotSupported"
        0x2006 -> "ParamNotSupported"
        0x200F -> "AccessDenied"
        RESP_DEVICE_BUSY -> "DeviceBusy"
        RESP_SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
        0xA102 -> "SonyNotReady"
        RESP_TRANSPORT_ERROR -> "TransportError"
        else -> "0x" + code.toString(16)
    }
}

class PtpResult(
    val code: Int,
    val data: ByteArray = EMPTY,
    val params: IntArray = IntArray(0),
) {
    val ok get() = code == Ptp.RESP_OK
    override fun toString() = "${Ptp.name(code)} (${data.size}B)"

    companion object {
        val EMPTY = ByteArray(0)
    }
}
