package com.phoneaf.ptp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal PTP-over-USB (still image class) transport using Android USB host bulk transfers.
 *
 * Every transaction is synchronized: PTP allows exactly one transaction in flight, so the
 * live-view poller and the focus commander share this object and take turns.
 */
class UsbPtpTransport private constructor(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : PtpTransport {
    private var transactionId = 0
    private val readBuf = ByteArray(READ_CHUNK)

    /** Human-readable reason for the most recent TransportError, shown in the UI. */
    @Volatile override var lastError: String = ""
        private set

    @Synchronized
    override fun resetTransactionId() { transactionId = 0 }

    /**
     * Run one PTP transaction.
     * @param dataOut payload for a data-out phase, or null.
     * @param stallIsOk some Sony bodies accept SDIO control commands but never send the response
     *        container. When true, a response timeout after a successful send counts as OK.
     */
    @Synchronized
    override fun transact(
        op: Int,
        params: IntArray,
        dataOut: ByteArray?,
        timeoutMs: Int,
        stallIsOk: Boolean,
    ): PtpResult {
        // Sony (and libgphoto2) number transactions from 1, OpenSession included.
        transactionId = if (transactionId >= 0x0FFFFFFF) 1 else transactionId + 1
        val tid = transactionId
        val opName = "0x" + op.toString(16)

        val cmd = container(Ptp.TYPE_COMMAND, op, tid, params.size * 4)
        params.forEach { cmd.putInt(it) }
        writeAll(cmd.array(), timeoutMs)?.let { return fail("$opName send: $it") }

        if (dataOut != null) {
            val header = container(Ptp.TYPE_DATA, op, tid, dataOut.size)
            val packet = header.array().copyOf(Ptp.HEADER + dataOut.size)
            System.arraycopy(dataOut, 0, packet, Ptp.HEADER, dataOut.size)
            writeAll(packet, timeoutMs)?.let { return fail("$opName data send: $it") }
        }

        val data = ByteArrayOutputStream()
        val started = System.currentTimeMillis()
        val deadline = started + timeoutMs
        var lastRead = 0
        var reads = 0
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) return timeout(opName, stallIsOk, "after $reads reads, last=$lastRead")
            val n = conn.bulkTransfer(epIn, readBuf, readBuf.size, remaining)
            lastRead = n; reads++
            if (n < 0) return timeout(opName, stallIsOk, "read=$n after ${System.currentTimeMillis() - started} ms")
            if (n < Ptp.HEADER) continue // zero-length packet or junk

            val bb = ByteBuffer.wrap(readBuf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
            val length = bb.int
            val type = bb.short.toInt() and 0xFFFF
            val code = bb.short.toInt() and 0xFFFF
            val rtid = bb.int

            when (type) {
                Ptp.TYPE_DATA -> {
                    val payloadLen = length - Ptp.HEADER
                    val chunk = ByteArrayOutputStream()
                    chunk.write(readBuf, Ptp.HEADER, n - Ptp.HEADER)
                    while (chunk.size() < payloadLen) {
                        val m = conn.bulkTransfer(epIn, readBuf, readBuf.size, timeoutMs)
                        if (m < 0) return fail("$opName data read stalled at ${chunk.size()}/$payloadLen B")
                        if (m == 0) break
                        chunk.write(readBuf, 0, m)
                    }
                    // A leftover data phase from an earlier, timed-out transaction is discarded.
                    if (rtid == tid) data.write(chunk.toByteArray())
                }
                Ptp.TYPE_RESPONSE -> {
                    if (rtid != tid) continue // stale response from an earlier transaction
                    val count = ((minOf(length, n) - Ptp.HEADER) / 4).coerceIn(0, 5)
                    val rp = IntArray(count) { bb.int }
                    return PtpResult(code, data.toByteArray(), rp)
                }
                else -> Unit // stray event on the bulk pipe; ignore
            }
        }
    }

    /**
     * Still Image Class "Device Reset" + clear halts + drain. Used once when OpenSession fails,
     * e.g. because a previous app (or Android's MTP service) left the camera mid-transaction.
     */
    @Synchronized
    override fun recover() {
        Log.w(TAG, "PTP device reset")
        conn.controlTransfer(0x21, 0x66, 0, intf.id, null, 0, 1500)
        Thread.sleep(120)
        clearHalt(epOut)
        clearHalt(epIn)
        val drain = ByteArray(512)
        repeat(8) { if (conn.bulkTransfer(epIn, drain, drain.size, 60) <= 0) return@repeat }
        transactionId = 0
        Thread.sleep(180)
    }

    @Synchronized
    override fun close() {
        runCatching { conn.releaseInterface(intf) }
        runCatching { conn.close() }
    }

    private fun timeout(opName: String, stallIsOk: Boolean, detail: String): PtpResult {
        clearHalt(epIn)
        if (stallIsOk) return PtpResult(Ptp.RESP_OK)
        return fail("$opName: no response from camera ($detail)")
    }

    /** Endpoint layout for error reports, e.g. "if#0 cls6 in=0x81/512 out=0x02/512". */
    override val describe: String
        get() = "if#${intf.id} cls${intf.interfaceClass} in=0x${epIn.address.toString(16)}/${epIn.maxPacketSize} " +
            "out=0x${epOut.address.toString(16)}/${epOut.maxPacketSize} eps=${intf.endpointCount}"

    private fun fail(reason: String): PtpResult {
        lastError = reason
        Log.w(TAG, reason)
        return PtpResult(Ptp.RESP_TRANSPORT_ERROR)
    }

    /** @return null on success, or why the write failed. */
    private fun writeAll(bytes: ByteArray, timeoutMs: Int): String? {
        var offset = 0
        while (offset < bytes.size) {
            val len = minOf(WRITE_CHUNK, bytes.size - offset)
            val n = conn.bulkTransfer(epOut, bytes, offset, len, timeoutMs.coerceIn(500, 5000))
            if (n <= 0) {
                clearHalt(epOut)
                return "USB write returned $n"
            }
            offset += n
        }
        // A transfer that is an exact multiple of the packet size needs a ZLP terminator.
        if (bytes.size % epOut.maxPacketSize == 0) conn.bulkTransfer(epOut, ByteArray(0), 0, timeoutMs)
        return null
    }

    /** USB CLEAR_FEATURE(ENDPOINT_HALT). */
    private fun clearHalt(ep: UsbEndpoint) {
        conn.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 200)
    }

    private fun container(type: Int, code: Int, tid: Int, payload: Int): ByteBuffer =
        ByteBuffer.allocate(Ptp.HEADER + if (type == Ptp.TYPE_COMMAND) payload else 0)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(Ptp.HEADER + payload)
            .putShort(type.toShort())
            .putShort(code.toShort())
            .putInt(tid)

    companion object {
        private const val TAG = "PtpUsb"
        // Many phones' USB stacks reject single transfers much above 16 KB; large data phases
        // simply arrive over several reads.
        private const val READ_CHUNK = 16 * 1024
        private const val WRITE_CHUNK = 16 * 1024

        /** Finds the PTP interface on [device] and claims it. Returns the transport or a reason. */
        fun open(manager: UsbManager, device: UsbDevice): Pair<UsbPtpTransport?, String> {
            val ifaces = (0 until device.interfaceCount).map { device.getInterface(it) }
            val intf = ifaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
                ?: ifaces.firstOrNull { i -> (0 until i.endpointCount).count { i.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_BULK } >= 2 }
                ?: return null to "Camera has no PTP interface (classes: ${ifaces.joinToString { it.interfaceClass.toString() }}). " +
                    "Turn on Network → PC Remote Function → PC Remote."

            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn == null || epOut == null) return null to "Camera PTP interface has no bulk endpoints"

            val conn = manager.openDevice(device) ?: return null to "Android refused to open the USB device"
            // Android's MTP host service may grab the interface right after plug-in; force it off.
            for (attempt in 1..3) {
                if (conn.claimInterface(intf, true)) return UsbPtpTransport(conn, intf, epIn, epOut) to ""
                Thread.sleep(500)
            }
            conn.close()
            return null to "Another app is using the camera (Photos import / file transfer?). Close it and replug."
        }
    }
}
