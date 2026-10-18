package com.phoneaf.ptp

/** One PTP transaction pipe: USB bulk endpoints or a PTP/IP TCP connection. */
interface PtpTransport {
    /** Human-readable reason for the most recent TransportError. */
    val lastError: String

    /** Short description of the link for error reports. */
    val describe: String

    fun resetTransactionId()

    /**
     * Run one PTP transaction.
     * @param dataOut payload for a data-out phase, or null.
     * @param stallIsOk some Sony bodies accept SDIO control commands but never send the response;
     *        when true a response timeout after a successful send counts as OK.
     */
    fun transact(
        op: Int,
        params: IntArray = IntArray(0),
        dataOut: ByteArray? = null,
        timeoutMs: Int = 3000,
        stallIsOk: Boolean = false,
    ): PtpResult

    /** Best-effort recovery after a failed OpenSession. */
    fun recover()

    fun close()
}
