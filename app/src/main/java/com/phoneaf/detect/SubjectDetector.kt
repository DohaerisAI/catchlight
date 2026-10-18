package com.phoneaf.detect

import android.graphics.Bitmap

/**
 * Stage 1: whole-subject boxes from whichever [ObjectModel] the user picked.
 *
 * Far-away birds are tiny in a 320px detector input, so when we already track something small
 * we run a second pass on a zoomed crop around it ("ROI zoom") and merge the results.
 */
class SubjectDetector(private val model: ObjectModel, val modelTitle: String) : AutoCloseable {

    val labels: List<String> get() = model.labels

    /**
     * @param classes labels to keep; empty = every label.
     * @param zoomAround a small tracked box to re-examine at higher resolution, if any.
     */
    fun detect(frame: Bitmap, minScore: Float, classes: Set<String>, zoomAround: Box? = null): List<Detection> {
        val out = run(frame, Box(0f, 0f, 1f, 1f), minScore, classes).toMutableList()

        if (zoomAround != null && zoomAround.w < 0.18f) {
            val side = (maxOf(zoomAround.w, zoomAround.h) * 4f).coerceAtLeast(0.25f)
            val crop = Box.around(zoomAround.center, side, side)
                .let { Box(it.l.coerceIn(0f, 1f), it.t.coerceIn(0f, 1f), it.r.coerceIn(0f, 1f), it.b.coerceIn(0f, 1f)) }
            if (crop.w > 0.05f && crop.h > 0.05f) {
                for (d in run(frame, crop, minScore * 0.85f, classes)) {
                    val dup = out.indexOfFirst { it.kind == d.kind && it.box.iou(d.box) > 0.3f }
                    if (dup < 0) out += d else if (d.score > out[dup].score) out[dup] = d
                }
            }
        }
        return out
    }

    private fun run(frame: Bitmap, region: Box, minScore: Float, classes: Set<String>): List<Detection> {
        val full = region.w >= 0.999f && region.h >= 0.999f
        val img = if (full) frame else {
            val x = (region.l * frame.width).toInt()
            val y = (region.t * frame.height).toInt()
            val w = (region.w * frame.width).toInt().coerceIn(8, frame.width - x)
            val h = (region.h * frame.height).toInt().coerceIn(8, frame.height - y)
            Bitmap.createBitmap(frame, x, y, w, h)
        }
        return model.run(img, minScore).mapNotNull { r ->
            if (classes.isNotEmpty() && r.label !in classes) return@mapNotNull null
            val b = r.box
            val box = Box(
                region.l + b.l * region.w, region.t + b.t * region.h,
                region.l + b.r * region.w, region.t + b.b * region.h,
            )
            Detection(kindOf(r.label), r.label, r.score, box)
        }
    }

    override fun close() = model.close()
}
