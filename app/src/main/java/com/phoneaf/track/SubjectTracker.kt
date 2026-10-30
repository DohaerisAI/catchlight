package com.phoneaf.track

import com.phoneaf.detect.AfMode
import com.phoneaf.detect.Box
import com.phoneaf.detect.Detection
import com.phoneaf.detect.Pt
import com.phoneaf.detect.SubjectKind
import kotlin.math.exp

/** A subject followed across frames with a constant-velocity (alpha-beta) filter. */
class Track(val id: Int, var det: Detection, nowMs: Long) {
    var center: Pt = det.box.center
    var velocity = Pt(0f, 0f)          // normalized units / second
    var w = det.box.w
    var h = det.box.h
    var hits = 1
    var misses = 0
    var lastSeenMs = nowMs
    var firstSeenMs = nowMs
    /** Smoothed offset of the focus part (head/eye) from the box centre, in box units. */
    var partOffset: Pt? = null

    val box get() = Box.around(center, w, h)
    val speed get() = velocity.dist(Pt(0f, 0f))

    /** Moving fast relative to its own size, or with spread-wing proportions → treat as in flight. */
    val inFlight: Boolean
        get() = det.kind == SubjectKind.BIRD && hits >= 3 &&
            (speed / maxOf(w, 0.02f) > 0.8f || (w / maxOf(h, 0.001f) > 1.9f && speed > 0.05f))

    fun predict(dtS: Float) { center += velocity * dtS }

    fun correct(d: Detection, dtS: Float, nowMs: Long) {
        val measured = d.box.center
        val residual = measured - center
        center += residual * ALPHA
        if (dtS > 0f) velocity += residual * (BETA / dtS)
        // Very small subjects jitter a lot; clamp absurd velocities.
        velocity = Pt(velocity.x.coerceIn(-3f, 3f), velocity.y.coerceIn(-3f, 3f))
        w += (d.box.w - w) * 0.4f
        h += (d.box.h - h) * 0.4f
        det = d
        hits++
        misses = 0
        lastSeenMs = nowMs
    }

    companion object {
        private const val ALPHA = 0.6f
        private const val BETA = 0.25f
    }
}

/**
 * Multi-subject tracker with one "primary" target – the thing we actually focus on.
 * Primary selection is sticky (like Sony's tracking sensitivity) so AF doesn't hop between birds.
 */
class SubjectTracker {
    private val tracks = mutableListOf<Track>()
    private var nextId = 1
    private var lastMs = 0L

    var primaryId: Int? = null
        private set
    /** User tapped a subject: lock to it until it is lost. */
    private var userLockId: Int? = null

    val all: List<Track> get() = tracks

    val primary: Track? get() = tracks.firstOrNull { it.id == primaryId }

    fun update(detections: List<Detection>, mode: AfMode, stickiness: Float, nowMs: Long): Track? {
        val dt = if (lastMs == 0L) 0f else ((nowMs - lastMs) / 1000f).coerceIn(0f, 0.5f)
        lastMs = nowMs
        tracks.forEach { it.predict(dt) }

        // Greedy IoU / distance association.
        val unmatched = detections.toMutableList()
        val pairs = mutableListOf<Triple<Track, Detection, Float>>()
        for (t in tracks) for (d in unmatched) {
            if (t.det.kind != d.kind) continue
            val iou = t.box.iou(d.box)
            val dist = t.center.dist(d.box.center) / maxOf(t.w, t.h, 0.03f)
            val cost = if (iou > 0.05f) 1f - iou else 1f + dist
            if (cost < 2.2f) pairs += Triple(t, d, cost)
        }
        val usedT = HashSet<Int>()
        for ((t, d, _) in pairs.sortedBy { it.third }) {
            if (t.id in usedT || unmatched.none { it === d }) continue
            t.correct(d, dt, nowMs)
            usedT += t.id
            unmatched.removeAll { it === d }
        }
        tracks.filter { it.id !in usedT }.forEach { it.misses++ }
        unmatched.forEach { tracks += Track(nextId++, it, nowMs) }

        // Keep lost tracks briefly so a bird passing behind a branch keeps its identity.
        tracks.removeAll { it.misses > MAX_MISSES }
        if (userLockId != null && tracks.none { it.id == userLockId }) userLockId = null

        primaryId = choosePrimary(mode, stickiness)
        return primary
    }

    /** Tap on the live view: lock to the subject under the finger (or nearest one). */
    fun lockAt(p: Pt): Boolean {
        val hit = tracks.filter { it.misses == 0 }.minByOrNull {
            if (it.box.expand(0.3f).contains(p)) 0f else it.center.dist(p) + 1f
        } ?: return false
        if (!hit.box.expand(1.0f).contains(p)) return false
        userLockId = hit.id
        primaryId = hit.id
        return true
    }

    /** Lock onto a specific track (camera-dial subject switching). */
    fun lockTo(id: Int) {
        if (tracks.none { it.id == id }) return
        userLockId = id
        primaryId = id
    }

    fun clearLock() { userLockId = null }
    val isUserLocked get() = userLockId != null

    fun reset() {
        tracks.clear(); primaryId = null; userLockId = null; lastMs = 0L
    }

    private fun choosePrimary(mode: AfMode, stickiness: Float): Int? {
        userLockId?.let { return it }
        val candidates = tracks.filter { it.det.kind in mode.kinds && it.hits >= 2 && it.misses <= MAX_MISSES }
        if (candidates.isEmpty()) return null
        val current = candidates.firstOrNull { it.id == primaryId }

        fun score(t: Track): Float {
            val sizeScore = kotlin.math.sqrt(t.w * t.h) * 2.5f          // bigger = closer = more likely the subject
            val centre = 1f - t.center.dist(Pt(0.5f, 0.5f)) * 1.4f      // photographers frame near the middle
            val conf = t.det.score
            val fresh = if (t.misses == 0) 0f else -0.3f * t.misses
            val kindBias = when {
                mode == AfMode.AUTO && t.det.kind == SubjectKind.HUMAN -> -0.15f // wildlife app: prefer animals
                else -> 0f
            }
            val flight = if (mode.flight) (if (t.inFlight) 0.6f else 0f) + minOf(t.speed, 1f) * 0.4f else 0f
            return sizeScore + centre * 0.8f + conf + fresh + kindBias + flight
        }

        val best = candidates.maxBy(::score)
        if (current == null) return best.id
        // Hysteresis: a challenger must beat the incumbent by a margin that grows with stickiness.
        val margin = 0.15f + stickiness * 0.6f
        return if (score(best) > score(current) + margin) best.id else current.id
    }

    companion object {
        const val MAX_MISSES = 6
    }
}

/** Exponential smoothing helper that is frame-rate independent. */
fun smoothFactor(dtS: Float, timeConstantS: Float): Float =
    if (timeConstantS <= 0f) 1f else (1f - exp(-dtS / timeConstantS)).coerceIn(0f, 1f)
