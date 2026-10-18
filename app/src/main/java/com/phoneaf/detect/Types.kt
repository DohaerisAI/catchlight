package com.phoneaf.detect

/** All coordinates in this package are normalized to the live-view image (0..1). */
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Float) = Pt(x * k, y * k)
    fun dist(o: Pt) = kotlin.math.hypot(x - o.x, y - o.y)
}

data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
    val center get() = Pt(cx, cy)
    val area get() = w.coerceAtLeast(0f) * h.coerceAtLeast(0f)

    fun contains(p: Pt) = p.x in l..r && p.y in t..b

    fun iou(o: Box): Float {
        val iw = (minOf(r, o.r) - maxOf(l, o.l)).coerceAtLeast(0f)
        val ih = (minOf(b, o.b) - maxOf(t, o.t)).coerceAtLeast(0f)
        val inter = iw * ih
        val union = area + o.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun expand(k: Float): Box {
        val dw = w * k / 2; val dh = h * k / 2
        return Box((l - dw).coerceAtLeast(0f), (t - dh).coerceAtLeast(0f), (r + dw).coerceAtMost(1f), (b + dh).coerceAtMost(1f))
    }

    companion object {
        fun around(c: Pt, w: Float, h: Float) = Box(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
    }
}

enum class SubjectKind { BIRD, ANIMAL, HUMAN, OBJECT }

private val ANIMAL_LABELS = setOf(
    "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe",
    "animal", "mammal", "deer", "fox", "monkey",
)

/** Which family a detector label belongs to (custom models may use e.g. "bird_head"). */
fun kindOf(label: String): SubjectKind {
    val l = label.lowercase()
    return when {
        l == "bird" || l.startsWith("bird") -> SubjectKind.BIRD
        l == "person" || l == "human" || l == "face" -> SubjectKind.HUMAN
        l in ANIMAL_LABELS || l.startsWith("animal") -> SubjectKind.ANIMAL
        else -> SubjectKind.OBJECT
    }
}

/** How the camera is held; live view is rotated upright before detection. Degrees clockwise. */
enum class CamRotation(val label: String, val degrees: Int) {
    LANDSCAPE("Camera level", 0),
    GRIP_UP("Vertical · grip up", 270),
    GRIP_DOWN("Vertical · grip down", 90),
    UPSIDE_DOWN("Upside down", 180),
}

/** Maps a point from the camera's live-view frame to the upright display frame. */
fun Pt.camToView(r: CamRotation) = when (r.degrees) {
    90 -> Pt(1 - y, x)
    180 -> Pt(1 - x, 1 - y)
    270 -> Pt(y, 1 - x)
    else -> this
}

/** Inverse of [camToView]: upright display point → camera AF coordinates. */
fun Pt.viewToCam(r: CamRotation) = when (r.degrees) {
    90 -> Pt(y, 1 - x)
    180 -> Pt(1 - x, 1 - y)
    270 -> Pt(1 - y, x)
    else -> this
}

enum class FocusTarget { EYE, HEAD, BODY }

enum class EyePref { AUTO, LEFT, RIGHT }

/** The AF menu. [kinds] = subjects the mode will lock on to; [flight] = BIF behaviour. */
enum class AfMode(
    val label: String,
    val short: String,
    val kinds: Set<SubjectKind>,
    val target: FocusTarget,
    val flight: Boolean = false,
) {
    AUTO("Auto (any eye)", "AUTO", setOf(SubjectKind.BIRD, SubjectKind.ANIMAL, SubjectKind.HUMAN), FocusTarget.EYE),
    BIRD_EYE("Bird · Eye", "B-EYE", setOf(SubjectKind.BIRD), FocusTarget.EYE),
    BIRD_HEAD("Bird · Head", "B-HEAD", setOf(SubjectKind.BIRD), FocusTarget.HEAD),
    BIRD_BODY("Bird · Body", "B-BODY", setOf(SubjectKind.BIRD), FocusTarget.BODY),
    BIRD_FLIGHT("Bird in Flight", "BIF", setOf(SubjectKind.BIRD), FocusTarget.HEAD, flight = true),
    ANIMAL_EYE("Animal · Eye", "A-EYE", setOf(SubjectKind.ANIMAL), FocusTarget.EYE),
    ANIMAL_HEAD("Animal · Head", "A-HEAD", setOf(SubjectKind.ANIMAL), FocusTarget.HEAD),
    ANIMAL_BODY("Animal · Body", "A-BODY", setOf(SubjectKind.ANIMAL), FocusTarget.BODY),
    ANIMAL_RUN("Animal in Motion", "A-RUN", setOf(SubjectKind.ANIMAL), FocusTarget.HEAD, flight = true),
    HUMAN_EYE("Human · Eye", "H-EYE", setOf(SubjectKind.HUMAN), FocusTarget.EYE),
    HUMAN_FACE("Human · Face", "H-FACE", setOf(SubjectKind.HUMAN), FocusTarget.HEAD),
    OBJECT("Objects (pick classes)", "OBJ", setOf(SubjectKind.OBJECT), FocusTarget.BODY),
    OBJECT_MOVING("Moving objects", "OBJ-M", setOf(SubjectKind.OBJECT), FocusTarget.BODY, flight = true),
}

/** One subject found in a frame, with whatever finer parts the models could locate. */
data class Detection(
    val kind: SubjectKind,
    val label: String,
    val score: Float,
    val box: Box,
    val head: Pt? = null,
    val headBox: Box? = null,
    val leftEye: Pt? = null,
    val rightEye: Pt? = null,
    /** True when head/eye came from geometry heuristics rather than a keypoint model. */
    val partsEstimated: Boolean = true,
)
