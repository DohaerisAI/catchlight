package com.phoneaf.detect

/**
 * "Auto" subject model: picks the detector per frame from the tracked bird's size.
 *
 * Thresholds come from tools/bench_birds.py (distance sweep at a7C live-view width):
 *   bird >= 15% of frame  -> YOLO11n·320 finds ~92% in ~16 ms; bigger models add only latency
 *   6–15%                 -> n·320 collapses (30–75%); n·640 holds 85–100%
 *   < 6%                  -> only YOLO11s·640 still finds most birds (75% at 5% width)
 * Bird-in-flight modes never use s·640: at ~3x its speed n·640 keeps tracking a fast subject.
 */
class AutoDetector(private val open: (Tier) -> SubjectDetector?) : AutoCloseable {

    enum class Tier(val modelId: String, val short: String) {
        FAST("yolo11n-320", "n320"),
        MID("yolo11n-640", "n640"),
        STRONG("yolo11s-640", "s640"),
    }

    private val loaded = HashMap<Tier, SubjectDetector?>()
    var current = Tier.MID      // searching for a subject: start wide-and-sensitive
        private set
    private var pending: Tier? = null
    private var pendingFrames = 0
    private var lostFrames = 0
    /** s·640 measured slower than [SLOW_MS] on this phone. */
    var strongTooSlow = false
        private set

    fun detector(tier: Tier = current): SubjectDetector? =
        loaded.getOrPut(tier) { open(tier) } ?: loaded.getOrPut(Tier.FAST) { open(Tier.FAST) }

    /**
     * Decide which tier the *next* frame should use.
     * @param width tracked subject width as a fraction of the frame, or null if nothing tracked
     * @param flight bird-in-flight / moving-subject mode
     * @param lastMs inference time of the frame just processed
     */
    fun update(width: Float?, flight: Boolean, lastMs: Int): Tier {
        val want = when {
            width == null -> {
                // Lost the subject: after a short grace period, search with the sensitive model.
                if (++lostFrames < LOST_GRACE) current else Tier.MID
            }
            else -> {
                lostFrames = 0
                when (current) {
                    // Hysteresis: thresholds differ by direction so a bird near a boundary
                    // doesn't flip the model every frame.
                    Tier.FAST -> if (width < 0.13f || flight) Tier.MID else Tier.FAST
                    Tier.MID -> when {
                        width > 0.17f && !flight -> Tier.FAST
                        width < 0.05f && !flight -> Tier.STRONG
                        else -> Tier.MID
                    }
                    Tier.STRONG -> if (width > 0.07f || flight) Tier.MID else Tier.STRONG
                }
            }
        }
        // Too slow on this phone? Stop using s·640 for the rest of the session.
        if (current == Tier.STRONG && lastMs > SLOW_MS) strongTooSlow = true
        val capped = if (want == Tier.STRONG && (flight || strongTooSlow)) Tier.MID else want
        return commit(capped)
    }

    private fun commit(want: Tier): Tier {
        if (want == current) { pending = null; pendingFrames = 0; return current }
        if (want == pending) pendingFrames++ else { pending = want; pendingFrames = 1 }
        // Move to a stronger model quickly (the subject is getting lost); back to a faster
        // one only once that's clearly safe.
        val needed = if (want.ordinal < current.ordinal) 6 else 3
        if (pendingFrames >= needed) { current = want; pending = null; pendingFrames = 0 }
        return current
    }

    fun reset() { current = Tier.MID; pending = null; pendingFrames = 0; lostFrames = 0 }

    override fun close() { loaded.values.forEach { it?.close() }; loaded.clear() }

    companion object {
        const val ID = "auto"
        private const val LOST_GRACE = 8
        private const val SLOW_MS = 300
    }
}
