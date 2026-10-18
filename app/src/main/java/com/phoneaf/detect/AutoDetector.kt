package com.phoneaf.detect

/**
 * "Auto" subject model: picks the detector per frame from the tracked bird's size.
 *
 * Thresholds come from tools/bench_birds.py (distance sweep at a7C live-view width, INT8 models):
 *   bird >= 15% of frame  -> YOLO11s·320 finds 85–95% in ~15 ms
 *   below 15% and in BIF  -> YOLO11s·640: 97% of birds in flight, 75% at 5% width, ~42 ms
 */
class AutoDetector(private val open: (Tier) -> SubjectDetector?) : AutoCloseable {

    // INT8 benchmark (tools/bench_birds.py): s·640 INT8 equals s·640 FP16 in accuracy at a third
    // of the time, so the middle and strong tiers now share one model; the strong tier only
    // keeps its own hysteresis band for tiny, perched birds.
    enum class Tier(val modelId: String, val short: String) {
        FAST("yolo11s-320", "s320"),
        MID("yolo11s-640", "s640"),
        STRONG("yolo11s-640", "s640+"),
    }

    /** One detector per model file, shared by tiers that use the same model. */
    private val loaded = HashMap<String, SubjectDetector?>()
    var current = Tier.MID      // searching for a subject: start wide-and-sensitive
        private set
    private var pending: Tier? = null
    private var pendingFrames = 0
    private var lostFrames = 0
    /** s·640 measured slower than [SLOW_MS] on this phone. */
    var strongTooSlow = false
        private set

    fun detector(tier: Tier = current): SubjectDetector? =
        loaded.getOrPut(tier.modelId) { open(tier) } ?: loaded.getOrPut(Tier.FAST.modelId) { open(Tier.FAST) }

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
