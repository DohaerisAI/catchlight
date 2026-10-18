package com.phoneaf.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import kotlin.math.abs
import kotlin.math.sign

/**
 * Stage 2: find the head and eyes inside a subject box.
 *
 * - Birds / animals: optional YOLO-pose model `subject_pose.tflite` (see tools/train_pose.py),
 *   keypoints = [beak-or-nose, eye A, eye B, crown, nape].
 * - People: MediaPipe BlazeFace on the upper body crop (gives both eyes).
 * - Without a model: geometric estimate (head at the top / leading edge of the box), flagged
 *   with [Detection.partsEstimated] so the UI can show it.
 */
class PartLocator(context: Context, poseModel: MappedByteBuffer?) : AutoCloseable {

    private val pose: Interpreter?
    private var poseSize = 256
    private var poseInputType = DataType.FLOAT32
    private var poseOutShape = intArrayOf()
    private val face: FaceDetector?

    val hasPoseModel get() = pose != null
    // Reused between frames (the pose model runs on every frame that has a bird).
    private var poseInput: ByteBuffer? = null
    private var posePixels: IntArray? = null
    private var poseFloats: FloatArray? = null
    val hasFaceModel get() = face != null

    init {
        val assets = context.assets.list("")?.toSet().orEmpty()
        pose = if (poseModel != null) runCatching {
            Interpreter(poseModel, Interpreter.Options().setNumThreads(4)).also {
                val inShape = it.getInputTensor(0).shape() // [1, S, S, 3]
                poseSize = inShape[1]
                poseInputType = it.getInputTensor(0).dataType()
                poseOutShape = it.getOutputTensor(0).shape()
                Log.i(TAG, "Pose model in=${inShape.contentToString()} $poseInputType out=${poseOutShape.contentToString()}")
            }
        }.onFailure { Log.e(TAG, "pose model failed", it) }.getOrNull() else null

        face = if (FACE_MODEL in assets) runCatching {
            FaceDetector.createFromOptions(
                context,
                FaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(FACE_MODEL).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMinDetectionConfidence(0.45f)
                    .build(),
            )
        }.getOrNull() else null
    }

    /** @param motion subject velocity (normalized units / s), used to guess which end is the head. */
    fun locate(frame: Bitmap, d: Detection, motion: Pt?): Detection {
        val refined = when (d.kind) {
            SubjectKind.HUMAN -> face?.let { faceParts(frame, d) }
            SubjectKind.OBJECT -> null
            else -> pose?.let { poseParts(frame, d) }
        }
        return refined ?: estimate(d, motion)
    }

    // ---- People -------------------------------------------------------------------------

    private fun faceParts(frame: Bitmap, d: Detection): Detection? {
        val upper = Box(d.box.l, d.box.t, d.box.r, d.box.t + maxOf(d.box.h * 0.5f, d.box.w))
        val (crop, region) = squareCrop(frame, upper, 1.1f, null)
        val res = face!!.detect(BitmapImageBuilder(crop).build())
        val f = res.detections().maxByOrNull { it.categories().firstOrNull()?.score() ?: 0f } ?: return null
        val kps = f.keypoints().orElse(null) ?: return null
        if (kps.size < 2) return null
        fun map(nx: Float, ny: Float) = Pt(region.l + nx * region.w, region.t + ny * region.h)
        val e1 = map(kps[0].x(), kps[0].y())
        val e2 = map(kps[1].x(), kps[1].y())
        val bb = f.boundingBox()
        val fb = Box(
            region.l + bb.left / crop.width * region.w, region.t + bb.top / crop.height * region.h,
            region.l + bb.right / crop.width * region.w, region.t + bb.bottom / crop.height * region.h,
        )
        val (left, right) = if (e1.x <= e2.x) e1 to e2 else e2 to e1
        return d.copy(head = fb.center, headBox = fb, leftEye = left, rightEye = right, partsEstimated = false)
    }

    // ---- Birds & animals ----------------------------------------------------------------

    private fun poseParts(frame: Bitmap, d: Detection): Detection? {
        val interp = pose!!
        val (crop, region) = squareCrop(frame, d.box, 1.15f, poseSize)

        val input = poseInput ?: ByteBuffer.allocateDirect(poseSize * poseSize * 3 * if (poseInputType == DataType.FLOAT32) 4 else 1)
            .order(ByteOrder.nativeOrder()).also { poseInput = it }
        val px = posePixels ?: IntArray(poseSize * poseSize).also { posePixels = it }
        crop.getPixels(px, 0, poseSize, 0, 0, poseSize, poseSize)
        input.rewind()
        if (poseInputType == DataType.FLOAT32) {
            val f = poseFloats ?: FloatArray(poseSize * poseSize * 3).also { poseFloats = it }
            var j = 0
            for (p in px) {
                f[j++] = ((p shr 16) and 0xFF) / 255f; f[j++] = ((p shr 8) and 0xFF) / 255f; f[j++] = (p and 0xFF) / 255f
            }
            input.asFloatBuffer().put(f)
        } else {
            val off = if (poseInputType == DataType.INT8) 128 else 0
            val b = ByteArray(poseSize * poseSize * 3)
            var j = 0
            for (p in px) {
                b[j++] = (((p shr 16) and 0xFF) - off).toByte(); b[j++] = (((p shr 8) and 0xFF) - off).toByte(); b[j++] = ((p and 0xFF) - off).toByte()
            }
            input.put(b)
        }
        input.rewind()

        // YOLO-pose export: [1, C, A] (or [1, A, C]) with C = 4 box + 1 score + 3*K keypoints.
        val d1 = poseOutShape[1]; val d2 = poseOutShape[2]
        val channelsFirst = (d1 - 5) % 3 == 0 && d1 < d2
        val c = if (channelsFirst) d1 else d2
        val a = if (channelsFirst) d2 else d1
        val k = (c - 5) / 3
        if (k < 3) return null
        val out = Array(1) { Array(d1) { FloatArray(d2) } }
        interp.run(input, out)
        fun v(ch: Int, anchor: Int) = if (channelsFirst) out[0][ch][anchor] else out[0][anchor][ch]

        var best = -1; var bestScore = 0f
        for (i in 0 until a) {
            val s = v(4, i)
            if (s > bestScore) { bestScore = s; best = i }
        }
        if (best < 0 || bestScore < 0.3f) return null

        // Coordinates are either normalized (0..1) or in input pixels depending on export.
        val scale = if (v(0, best) > 1.5f || v(1, best) > 1.5f) poseSize.toFloat() else 1f
        fun kp(j: Int): Pt? {
            val conf = v(5 + j * 3 + 2, best)
            if (conf < KP_CONF) return null
            val x = v(5 + j * 3, best) / scale; val y = v(5 + j * 3 + 1, best) / scale
            return Pt(region.l + x * region.w, region.t + y * region.h)
        }
        val tip = kp(0); val eyeA = kp(1); val eyeB = kp(2)
        val crown = if (k > 3) kp(3) else null
        val nape = if (k > 4) kp(4) else null

        val headPts = listOfNotNull(tip, eyeA, eyeB, crown, nape)
        if (headPts.isEmpty()) return null
        val head = Pt(headPts.map { it.x }.average().toFloat(), headPts.map { it.y }.average().toFloat())
        val spread = headPts.maxOf { it.dist(head) }.coerceAtLeast(maxOf(d.box.w, d.box.h) * 0.08f)
        val headBox = Box.around(head, spread * 2.4f, spread * 2.4f)

        val eyes = listOfNotNull(eyeA, eyeB).sortedBy { it.x }
        return d.copy(
            head = head, headBox = headBox,
            leftEye = eyes.getOrNull(0), rightEye = eyes.getOrNull(1) ?: eyes.getOrNull(0),
            partsEstimated = false,
        )
    }

    // ---- No model: geometry ---------------------------------------------------------------

    private fun estimate(d: Detection, motion: Pt?): Detection {
        val b = d.box
        val dir = motion?.let { if (abs(it.x) > 0.03f) sign(it.x) else 0f } ?: 0f
        val head = when (d.kind) {
            SubjectKind.BIRD -> Pt(b.cx + dir * b.w * 0.25f, b.t + b.h * 0.2f)
            SubjectKind.ANIMAL -> Pt(b.cx + dir * b.w * 0.33f, b.t + b.h * 0.22f)
            SubjectKind.HUMAN -> Pt(b.cx, b.t + minOf(b.h * 0.1f, b.w * 0.5f))
            SubjectKind.OBJECT -> b.center
        }
        val s = minOf(b.w, b.h) * if (d.kind == SubjectKind.HUMAN) 0.6f else 0.35f
        return d.copy(head = head, headBox = Box.around(head, s, s), leftEye = head, rightEye = head, partsEstimated = true)
    }

    /** Square, black-padded crop around [box]; returns the bitmap and the normalized region it covers. */
    private fun squareCrop(frame: Bitmap, box: Box, grow: Float, outSize: Int?): Pair<Bitmap, Box> {
        val fw = frame.width.toFloat(); val fh = frame.height.toFloat()
        val sidePx = maxOf(box.w * fw, box.h * fh) * grow
        val cxPx = box.cx * fw; val cyPx = box.cy * fh
        val src = Rect((cxPx - sidePx / 2).toInt(), (cyPx - sidePx / 2).toInt(), (cxPx + sidePx / 2).toInt(), (cyPx + sidePx / 2).toInt())
        val size = outSize ?: src.width().coerceIn(64, 512)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val clipped = Rect(src)
        if (clipped.intersect(0, 0, frame.width, frame.height)) {
            val k = size / src.width().toFloat()
            val dst = Rect(
                ((clipped.left - src.left) * k).toInt(), ((clipped.top - src.top) * k).toInt(),
                ((clipped.right - src.left) * k).toInt(), ((clipped.bottom - src.top) * k).toInt(),
            )
            canvas.drawBitmap(frame, clipped, dst, null)
        }
        val region = Box(src.left / fw, src.top / fh, src.right / fw, src.bottom / fh)
        return out to region
    }

    override fun close() {
        pose?.close()
        face?.close()
    }

    companion object {
        private const val TAG = "PartLocator"
        const val POSE_MODEL = "subject_pose.tflite"
        const val FACE_MODEL = "blaze_face_short_range.tflite"
        private const val KP_CONF = 0.4f
    }
}
