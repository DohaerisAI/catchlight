package com.phoneaf.ptp

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.SocketFactory

/**
 * PTP over TCP/IP (CIPA DC-005), as used by Sony "PC Remote" over Wi-Fi Direct / Access Point.
 * Same PTP transactions as USB, wrapped in PTP/IP packets: [u32 length][u32 type][payload].
 * Packet layouts follow libgphoto2's camlibs/ptp2/ptpip.c.
 */
class PtpIpTransport private constructor(
    private val cmd: Socket,
    private val evt: Socket,
    private val host: String,
) : PtpTransport {

    private val cmdIn = DataInputStream(cmd.getInputStream().buffered(256 * 1024))
    private val cmdOut: OutputStream = cmd.getOutputStream()
    private var transactionId = 0
    @Volatile private var open = true

    @Volatile override var lastError: String = ""
        private set
    override val describe get() = "PTP/IP $host:$PORT"

    init {
        // The camera pushes events (property changes, object added) on the second socket; if
        // nobody reads it the camera's send buffer fills and command replies stall.
        Thread({
            val buf = ByteArray(4096)
            runCatching { val s = evt.getInputStream(); while (open && s.read(buf) >= 0) Unit }
        }, "phoneAF-ptpip-events").apply { isDaemon = true; start() }
    }

    @Synchronized
    override fun resetTransactionId() { transactionId = 0 }

    @Synchronized
    override fun transact(op: Int, params: IntArray, dataOut: ByteArray?, timeoutMs: Int, stallIsOk: Boolean): PtpResult {
        transactionId = if (transactionId >= 0x0FFFFFFF) 1 else transactionId + 1
        val tid = transactionId
        val opName = "0x" + op.toString(16)
        try {
            cmd.soTimeout = timeoutMs.coerceAtLeast(500)

            // Operation request: dataphase 2 = we send data, 1 = no data or camera sends data.
            val req = le(18 + params.size * 4)
                .putInt(18 + params.size * 4).putInt(TYPE_CMD_REQUEST)
                .putInt(if (dataOut != null) 2 else 1)
                .putShort(op.toShort()).putInt(tid)
            params.forEach { req.putInt(it) }
            cmdOut.write(req.array())

            if (dataOut != null) {
                cmdOut.write(le(20).putInt(20).putInt(TYPE_START_DATA).putInt(tid).putLong(dataOut.size.toLong()).array())
                var off = 0
                do {
                    val n = minOf(CHUNK, dataOut.size - off)
                    val last = off + n >= dataOut.size
                    val hdr = le(12).putInt(12 + n).putInt(if (last) TYPE_END_DATA else TYPE_DATA).putInt(tid).array()
                    cmdOut.write(hdr); cmdOut.write(dataOut, off, n)
                    off += n
                } while (off < dataOut.size)
            }
            cmdOut.flush()

            val data = ByteArrayOutputStream()
            while (true) {
                val len = Integer.reverseBytes(cmdIn.readInt())
                val type = Integer.reverseBytes(cmdIn.readInt())
                val body = ByteArray(len - 8).also { cmdIn.readFully(it) }
                val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                when (type) {
                    TYPE_START_DATA -> Unit // tid + total length; we just accumulate
                    TYPE_DATA, TYPE_END_DATA -> {
                        val ptid = bb.int
                        if (ptid == tid) data.write(body, 4, body.size - 4)
                    }
                    TYPE_CMD_RESPONSE -> {
                        val code = bb.short.toInt() and 0xFFFF
                        val rtid = bb.int
                        if (rtid != tid) continue // stale reply from a timed-out transaction
                        val rp = IntArray((bb.remaining() / 4).coerceAtMost(5)) { bb.int }
                        return PtpResult(code, data.toByteArray(), rp)
                    }
                    else -> Unit // ping / cancel / unknown
                }
            }
        } catch (e: SocketTimeoutException) {
            if (stallIsOk) return PtpResult(Ptp.RESP_OK)
            return fail("$opName: no response over Wi-Fi in ${timeoutMs}ms")
        } catch (e: Exception) {
            return fail("$opName: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        }
    }

    /** PTP/IP has no device reset; a stale session is cleared by reconnecting. */
    override fun recover() { transactionId = 0 }

    @Synchronized
    override fun close() {
        open = false
        runCatching { cmd.close() }
        runCatching { evt.close() }
    }

    private fun fail(reason: String): PtpResult {
        lastError = reason
        Log.w(TAG, reason)
        return PtpResult(Ptp.RESP_TRANSPORT_ERROR)
    }

    companion object {
        private const val TAG = "PtpIp"
        const val PORT = 15740
        private const val TYPE_INIT_COMMAND_REQUEST = 1
        private const val TYPE_INIT_COMMAND_ACK = 2
        private const val TYPE_INIT_EVENT_REQUEST = 3
        private const val TYPE_INIT_EVENT_ACK = 4
        private const val TYPE_INIT_FAIL = 5
        private const val TYPE_CMD_REQUEST = 6
        private const val TYPE_CMD_RESPONSE = 7
        private const val TYPE_START_DATA = 9
        private const val TYPE_DATA = 10
        private const val TYPE_END_DATA = 12
        private const val CHUNK = 64 * 1024

        private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        /**
         * Opens the command and event connections.
         * @param guid 16 bytes, stable per phone: Access Point pairing on the camera remembers it.
         * @param factory socket factory bound to the camera's Wi-Fi network.
         */
        fun connect(host: String, guid: ByteArray, name: String, factory: SocketFactory, connectTimeoutMs: Int = 5000): Pair<PtpIpTransport?, String> {
            var cmd: Socket? = null
            var evt: Socket? = null
            try {
                cmd = factory.createSocket().apply {
                    tcpNoDelay = true; soTimeout = 5000
                    connect(InetSocketAddress(host, PORT), connectTimeoutMs)
                }
                // Init Command Request: GUID, UCS-2 name (NUL-terminated), version 1.0.
                val nameBytes = (name + "\u0000").toCharArray()
                val len = 8 + 16 + nameBytes.size * 2 + 4
                val req = le(len).putInt(len).putInt(TYPE_INIT_COMMAND_REQUEST).put(guid, 0, 16)
                nameBytes.forEach { req.putShort(it.code.toShort()) }
                req.putShort(0).putShort(1)
                cmd.getOutputStream().write(req.array())

                val (type, body) = readPacket(cmd.getInputStream())
                if (type == TYPE_INIT_FAIL) {
                    val reason = if (body.size >= 4) ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).int else -1
                    cmd.close()
                    return null to "Camera refused the connection (reason $reason). For Access Point mode, run Pairing on the camera first."
                }
                if (type != TYPE_INIT_COMMAND_ACK) { cmd.close(); return null to "Unexpected PTP/IP reply type $type" }
                val connNumber = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).int

                evt = factory.createSocket().apply {
                    tcpNoDelay = true; soTimeout = 5000
                    connect(InetSocketAddress(host, PORT), connectTimeoutMs)
                }
                evt.getOutputStream().write(le(12).putInt(12).putInt(TYPE_INIT_EVENT_REQUEST).putInt(connNumber).array())
                val (etype, _) = readPacket(evt.getInputStream())
                if (etype != TYPE_INIT_EVENT_ACK) {
                    cmd.close(); evt.close()
                    return null to "Camera did not accept the event channel (type $etype)"
                }
                evt.soTimeout = 0
                return PtpIpTransport(cmd, evt, host) to ""
            } catch (e: Exception) {
                runCatching { cmd?.close() }; runCatching { evt?.close() }
                return null to "$host: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        private fun readPacket(s: InputStream): Pair<Int, ByteArray> {
            val d = DataInputStream(s)
            val len = Integer.reverseBytes(d.readInt())
            val type = Integer.reverseBytes(d.readInt())
            val body = ByteArray((len - 8).coerceAtLeast(0)).also { d.readFully(it) }
            return type to body
        }
    }
}
