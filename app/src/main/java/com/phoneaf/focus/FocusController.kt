package com.phoneaf.focus

import com.phoneaf.detect.AfMode
import com.phoneaf.detect.Box
import com.phoneaf.detect.EyePref
import com.phoneaf.detect.FocusTarget
import com.phoneaf.detect.Pt
import com.phoneaf.detect.SubjectKind
import com.phoneaf.ptp.Ptp
import com.phoneaf.track.Track
import com.phoneaf.track.smoothFactor

/** Where phoneAF wants the camera's AF point, and why. */
data class FocusDecision(
    val point: Pt,
    val box: Box,
    val part: FocusTarget,
    val estimated: Boolean,
    val trackId: Int,
)

data class FocusTuning(
    /** Live-view + inference + USB latency to lead moving subjects by. */
    val leadMs: Int = 120,
    /** Seconds of smoothing on the focus point (lower = snappier, higher = calmer). */
    val smoothingS: Float = 0.08f,
    /** Don't re-send unless the point moved this many AF-grid units (640x480 grid). */
    val deadbandGrid: Int = 6,
    /** Hard cap on AF-point commands per second (USB is shared with live view). */
    val maxRateHz: Int = 15,
    /** How long to hold the last point after the subject disappears (Sony "lock-on" feel). */
    val holdMs: Int = 600,
)

/**
 * Turns the primary track into an AF point, smooths it, leads it, and rate-limits commands.
 */
class FocusController {
    private var smoothed: Pt? = null
    private var lastSent: Pt? = null
    private var lastSentMs = 0L
    private var lastUpdateMs = 0L
    private var lastDecision: FocusDecision? = null
    private var lastSeenMs = 0L

    fun reset() {
        smoothed = null; lastSent = null; lastDecision = null; lastSentMs = 0; lastUpdateMs = 0
    }

    fun decide(track: Track?, mode: AfMode, eyePref: EyePref, tuning: FocusTuning, nowMs: Long): FocusDecision? {
        if (track == null || track.misses > 0 && nowMs - track.lastSeenMs > tuning.holdMs) {
            // Hold the last point for a moment, then give up.
            return lastDecision?.takeIf { nowMs - lastSeenMs <= tuning.holdMs }
        }
        lastSeenMs = nowMs
        val d = track.det
        val box = track.box

        // 1) Which part, in this frame's detection coordinates.
        var part = mode.target
        if (mode == AfMode.AUTO && d.kind != SubjectKind.HUMAN && d.partsEstimated) part = FocusTarget.HEAD
        // Eyes on a tiny or fast bird are a guess; the head is a bigger, safer target.
        if (mode.flight && track.inFlight && d.partsEstimated) part = FocusTarget.BODY

        val (raw, partBox) = when (part) {
            FocusTarget.EYE -> {
                val eye = pickEye(d.leftEye, d.rightEye, d.headBox, eyePref, d.kind)
                (eye ?: d.head ?: d.box.center) to Box.around(eye ?: d.box.center, d.box.w * 0.12f, d.box.w * 0.12f)
            }
            FocusTarget.HEAD -> (d.head ?: d.box.center) to (d.headBox ?: d.box)
            FocusTarget.BODY -> bodyPoint(d.box, d.kind) to d.box
        }

        // 2) Express relative to the box and re-apply to the *filtered* box (removes detector jitter).
        val db = d.box
        val off = Pt((raw.x - db.cx) / maxOf(db.w, 1e-3f), (raw.y - db.cy) / maxOf(db.h, 1e-3f))
        val dtS = if (lastUpdateMs == 0L) 0.05f else ((nowMs - lastUpdateMs) / 1000f).coerceIn(0.001f, 0.5f)
        lastUpdateMs = nowMs
        val prevOff = track.partOffset
        val k = smoothFactor(dtS, 0.12f)
        val o = if (prevOff == null) off else prevOff + (off - prevOff) * k
        track.partOffset = o

        // 3) Lead the subject by the system latency.
        val lead = tuning.leadMs / 1000f
        val predicted = Pt(box.cx + o.x * box.w, box.cy + o.y * box.h) + track.velocity * lead

        val s = smoothed
        val k2 = smoothFactor(dtS, if (mode.flight) tuning.smoothingS * 0.5f else tuning.smoothingS)
        val p = if (s == null || s.dist(predicted) > 0.25f) predicted else s + (predicted - s) * k2
        val clamped = Pt(p.x.coerceIn(0.01f, 0.99f), p.y.coerceIn(0.01f, 0.99f))
        smoothed = clamped

        val shift = clamped - raw
        val shownBox = Box(partBox.l + shift.x, partBox.t + shift.y, partBox.r + shift.x, partBox.b + shift.y)
        return FocusDecision(clamped, shownBox, part, d.partsEstimated, track.id).also { lastDecision = it }
    }

    /** True when [p] should be sent to the camera now. */
    fun shouldSend(p: Pt, tuning: FocusTuning, nowMs: Long): Boolean {
        if (nowMs - lastSentMs < 1000 / tuning.maxRateHz) return false
        val prev = lastSent
        val moved = prev == null ||
            kotlin.math.abs((p.x - prev.x) * Ptp.AF_GRID_W) > tuning.deadbandGrid ||
            kotlin.math.abs((p.y - prev.y) * Ptp.AF_GRID_H) > tuning.deadbandGrid
        if (!moved) return false
        lastSent = p
        lastSentMs = nowMs
        return true
    }

    private fun bodyPoint(b: Box, kind: SubjectKind) = when (kind) {
        SubjectKind.BIRD -> Pt(b.cx, b.t + b.h * 0.42f)     // breast, not the tail/legs
        SubjectKind.ANIMAL -> Pt(b.cx, b.t + b.h * 0.40f)   // shoulders
        SubjectKind.HUMAN -> Pt(b.cx, b.t + b.h * 0.3f)     // chest
        SubjectKind.OBJECT -> b.center
    }

    private fun pickEye(left: Pt?, right: Pt?, head: Box?, pref: EyePref, kind: SubjectKind): Pt? {
        if (left == null || right == null) return left ?: right
        if (left == right) return left
        return when (pref) {
            EyePref.LEFT -> left
            EyePref.RIGHT -> right
            // When a head is turned, the eye nearer the camera sits farther from the head's centre line.
            EyePref.AUTO -> when {
                head == null -> left
                kind == SubjectKind.HUMAN ->
                    if (kotlin.math.abs(left.x - head.cx) >= kotlin.math.abs(right.x - head.cx)) left else right
                // Birds/animals are usually in profile: the eye nearer the head centre is the visible one.
                else -> if (left.dist(head.center) <= right.dist(head.center)) left else right
            }
        }
    }
}
