package com.phoneaf.detect

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.util.zip.ZipFile
import org.json.JSONObject

/** One raw detection from any model, box normalized to the image it ran on. */
data class RawDet(val label: String, val score: Float, val box: Box)

/** A whole-image object detector. Implementations are used from a single thread. */
interface ObjectModel : AutoCloseable {
    val labels: List<String>
    fun run(image: Bitmap, minScore: Float): List<RawDet>
}

/** EfficientDet-Lite via MediaPipe Tasks (labels embedded in the model). */
class MediaPipeModel(context: Context, buffer: MappedByteBuffer, embeddedLabels: List<String>) : ObjectModel {
    override val labels = embeddedLabels.filter { it.isNotBlank() && it != "???" }

    private val detector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            // CPU (XNNPACK): the GPU delegate needs a GL context on the calling thread and
            // rejects the int8 Lite0 model on many phones, failing at detect() time.
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buffer).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(15)
            .setScoreThreshold(0.15f)
            .build(),
    )

    override fun run(image: Bitmap, minScore: Float): List<RawDet> {
        val w = image.width.toFloat(); val h = image.height.toFloat()
        return detector.detect(BitmapImageBuilder(image).build()).detections().mapNotNull { d ->
            val c = d.categories().firstOrNull() ?: return@mapNotNull null
            if (c.score() < minScore) return@mapNotNull null
            val b = d.boundingBox()
            RawDet(c.categoryName(), c.score(), Box(b.left / w, b.top / h, b.right / w, b.bottom / h))
        }
    }

    override fun close() = detector.close()
}

/**
 * Ultralytics YOLO (v8 / 11) TFLite export, raw head without NMS:
 * output [1, 4 + C, N] (or transposed) = cx, cy, w, h, then C sigmoid class scores.
 */
class YoloModel(buffer: MappedByteBuffer, labelsOverride: List<String>?) : ObjectModel {
    private val interp = Interpreter(buffer, Interpreter.Options().setNumThreads(4))
    private val size: Int
    private val inputType: DataType
    private val outShape: IntArray
    private val channelsFirst: Boolean
    private val numClasses: Int
    private val anchors: Int
    override val labels: List<String>

    init {
        val inShape = interp.getInputTensor(0).shape() // [1, S, S, 3]
        size = inShape[1]
        inputType = interp.getInputTensor(0).dataType()
        outShape = interp.getOutputTensor(0).shape()
        val a = outShape[1]; val b = outShape[2]
        channelsFirst = a < b
        val channels = if (channelsFirst) a else b
        anchors = if (channelsFirst) b else a
        numClasses = channels - 4
        labels = labelsOverride?.takeIf { it.size >= numClasses }
            ?: if (numClasses == 80) COCO80 else List(numClasses) { "class $it" }
    }

    private val input = ByteBuffer.allocateDirect(size * size * 3 * if (inputType == DataType.FLOAT32) 4 else 1)
        .order(ByteOrder.nativeOrder())
    private val pixels = IntArray(size * size)
    private val output = Array(1) { Array(outShape[1]) { FloatArray(outShape[2]) } }

    override fun run(image: Bitmap, minScore: Float): List<RawDet> {
        val scaled = Bitmap.createScaledBitmap(image, size, size, true)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        input.rewind()
        for (p in pixels) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
            if (inputType == DataType.FLOAT32) {
                input.putFloat(r / 255f); input.putFloat(g / 255f); input.putFloat(bl / 255f)
            } else {
                input.put(r.toByte()); input.put(g.toByte()); input.put(bl.toByte())
            }
        }
        input.rewind()
        interp.run(input, output)
        val o = output[0]
        fun v(c: Int, i: Int) = if (channelsFirst) o[c][i] else o[i][c]

        // Coordinates are normalized in recent exports, pixels in older ones.
        var maxCoord = 0f
        val cands = ArrayList<RawDet>()
        for (i in 0 until anchors) {
            var best = 0f; var cls = -1
            for (c in 0 until numClasses) {
                val s = v(4 + c, i)
                if (s > best) { best = s; cls = c }
            }
            if (best < minScore || cls < 0) continue
            val cx = v(0, i); val cy = v(1, i); val w = v(2, i); val h = v(3, i)
            maxCoord = maxOf(maxCoord, cx, cy)
            cands += RawDet(
                labels[cls], best,
                Box((cx - w / 2).coerceAtLeast(0f), (cy - h / 2).coerceAtLeast(0f), cx + w / 2, cy + h / 2),
            )
        }
        val scale = if (maxCoord > 2f) size.toFloat() else 1f
        val norm = if (scale == 1f) cands else cands.map {
            it.copy(box = Box(it.box.l / scale, it.box.t / scale, it.box.r / scale, it.box.b / scale))
        }
        return nms(norm, 0.5f).take(20)
    }

    override fun close() = interp.close()

    companion object {
        fun nms(dets: List<RawDet>, iou: Float): List<RawDet> {
            val out = ArrayList<RawDet>()
            for (d in dets.sortedByDescending { it.score }) {
                if (out.none { it.label == d.label && it.box.iou(d.box) > iou }) out += d
            }
            return out
        }
    }
}

/**
 * Reads label names bundled inside a .tflite (metadata is zip-appended): Ultralytics writes
 * metadata.json with "names": {"0": "bird", ...}; TFLite Support writes a labels .txt.
 */
fun embeddedLabels(file: File): List<String> = runCatching {
    ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence().toList()
        entries.firstOrNull { it.name.endsWith("metadata.json") }?.let { e ->
            val names = JSONObject(zip.getInputStream(e).bufferedReader().readText()).optJSONObject("names")
            if (names != null) return@use names.keys().asSequence()
                .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }.map { names.getString(it) }.toList()
        }
        val txt = entries.firstOrNull { it.name.endsWith(".txt") } ?: return@use emptyList()
        zip.getInputStream(txt).bufferedReader().readLines()
    }
}.getOrDefault(emptyList())

val COCO80 = listOf(
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
    "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
    "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
    "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
    "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
    "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
    "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard",
    "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase",
    "scissors", "teddy bear", "hair drier", "toothbrush",
)
