package com.phoneaf.detect

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

enum class ModelRole { SUBJECT, PARTS }
enum class Engine { MEDIAPIPE, YOLO, POSE }
enum class Origin { BUNDLED, DOWNLOAD, IMPORTED }

data class ModelSpec(
    val id: String,
    val title: String,
    val role: ModelRole,
    val engine: Engine,
    val license: String,
    val origin: Origin,
    val fileName: String,
    val url: String? = null,
    val sizeMb: Int = 0,
    val blurb: String = "",
)

/**
 * The model menu: what ships in the APK, what can be downloaded on demand, and anything the
 * user imports (their own trained .tflite files, e.g. a bird-eye pose model).
 */
class ModelStore(private val context: Context) {

    val dir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val assets = context.assets.list("")?.toSet().orEmpty()

    fun all(): List<ModelSpec> {
        val known = CATALOG.filter { it.origin != Origin.BUNDLED || it.fileName in assets }
            .let { list ->
                // "Auto" needs the three YOLO tiers bundled.
                if (AutoDetector.Tier.entries.all { t -> list.any { it.id == t.modelId } }) listOf(AUTO_SPEC) + list else list
            }
        val knownFiles = CATALOG.map { it.fileName }.toSet()
        val imported = dir.listFiles { f -> f.extension == "tflite" && f.name !in knownFiles }.orEmpty()
            .sortedBy { it.name }
            .map { f ->
                val pose = f.name.contains("pose", ignoreCase = true)
                ModelSpec(
                    id = "import:${f.name}", title = f.nameWithoutExtension,
                    role = if (pose) ModelRole.PARTS else ModelRole.SUBJECT,
                    engine = if (pose) Engine.POSE else Engine.YOLO,
                    license = "your own", origin = Origin.IMPORTED, fileName = f.name,
                    sizeMb = (f.length() / 1_000_000).toInt(),
                    blurb = if (pose) "Imported keypoint model (eye / head)" else "Imported YOLO-format detector",
                )
            }
        return known + imported
    }

    fun isAvailable(spec: ModelSpec) = if (spec.id == AutoDetector.ID) true else when (spec.origin) {
        Origin.BUNDLED -> spec.fileName in assets
        else -> File(dir, spec.fileName).exists()
    }

    fun map(spec: ModelSpec): MappedByteBuffer =
        if (spec.origin == Origin.BUNDLED) {
            val fd = context.assets.openFd(spec.fileName)
            FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        } else {
            FileInputStream(File(dir, spec.fileName)).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
        }

    /** Label list for a model: sidecar "<name>.txt", labels embedded in the file, or COCO. */
    fun labelsFor(spec: ModelSpec): List<String>? {
        if (spec.engine == Engine.MEDIAPIPE) return COCO80
        if (spec.origin == Origin.BUNDLED) return null // bundled YOLO are COCO-80
        val f = File(dir, spec.fileName)
        val sidecar = File(dir, f.nameWithoutExtension + ".txt")
        if (sidecar.exists()) return sidecar.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        return embeddedLabels(f).takeIf { it.isNotEmpty() }
    }

    fun open(spec: ModelSpec): ObjectModel = when (spec.engine) {
        Engine.MEDIAPIPE -> MediaPipeModel(context, map(spec), COCO80)
        Engine.YOLO -> YoloModel(map(spec), labelsFor(spec))
        Engine.POSE -> error("${spec.title} is a keypoint model, not a detector")
    }

    /** Blocking download with progress 0..1. */
    fun download(spec: ModelSpec, progress: (Float) -> Unit) {
        val url = spec.url ?: error("no URL")
        val tmp = File(dir, spec.fileName + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (spec.sizeMb * 1_000_000L)
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
            check(tmp.renameTo(File(dir, spec.fileName))) { "could not save model" }
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    /** Copies a user-picked .tflite (or its labels .txt) into the model folder. */
    fun import(uri: Uri): String {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: "model_${System.currentTimeMillis()}.tflite"
        require(name.endsWith(".tflite") || name.endsWith(".txt")) { "Pick a .tflite model (or its labels .txt)" }
        context.contentResolver.openInputStream(uri)!!.use { input ->
            File(dir, name).outputStream().use { input.copyTo(it) }
        }
        return name
    }

    fun delete(spec: ModelSpec) {
        if (spec.origin == Origin.BUNDLED) return
        File(dir, spec.fileName).delete()
        File(dir, spec.fileName.removeSuffix(".tflite") + ".txt").delete()
    }

    fun spec(id: String) = CATALOG.firstOrNull { it.id == id }

    companion object {
        val AUTO_SPEC = ModelSpec(
            AutoDetector.ID, "Auto (by bird size)", ModelRole.SUBJECT, Engine.YOLO, "AGPL-3.0",
            Origin.BUNDLED, "", blurb = "Recommended. YOLO11s·320 for big birds, YOLO11s·640 for small, distant and flying ones.",
        )
        val CATALOG = listOf(
            ModelSpec(
                "effdet-lite0", "EfficientDet-Lite0", ModelRole.SUBJECT, Engine.MEDIAPIPE, "Apache-2.0",
                Origin.BUNDLED, "efficientdet_lite0.tflite", sizeMb = 4,
                blurb = "Fastest. 80 classes. Fine for big, close subjects.",
            ),
            // YOLO11, INT8 (calibrated on bird photos; tools/export_yolo.py --int8). Mac M1 CPU timings
            // from tools/bench_birds.py: 196 photos + distance sweep at a7C live-view size.
            ModelSpec(
                "yolo11s-320", "YOLO11s · 320", ModelRole.SUBJECT, Engine.YOLO, "AGPL-3.0",
                Origin.BUNDLED, "yolo11s_320_int8.tflite", sizeMb = 10,
                blurb = "Fast all-rounder. 82% of birds found · ~15 ms.",
            ),
            ModelSpec(
                "yolo11s-640", "YOLO11s · 640", ModelRole.SUBJECT, Engine.YOLO, "AGPL-3.0",
                Origin.BUNDLED, "yolo11s_640_int8.tflite", sizeMb = 10,
                blurb = "Most accurate: 90% of birds, 97% in flight, 75% at 5% of frame · ~42 ms.",
            ),
            ModelSpec(
                "yolo11n-320", "YOLO11n · 320", ModelRole.SUBJECT, Engine.YOLO, "AGPL-3.0",
                Origin.BUNDLED, "yolo11n_320_int8.tflite", sizeMb = 3,
                blurb = "Lightest. For older phones · ~12 ms.",
            ),
            ModelSpec(
                "yolo11n-640", "YOLO11n · 640", ModelRole.SUBJECT, Engine.YOLO, "AGPL-3.0",
                Origin.BUNDLED, "yolo11n_640_int8.tflite", sizeMb = 3,
                blurb = "Light model, high resolution · ~23 ms.",
            ),
            ModelSpec(
                "effdet-lite2", "EfficientDet-Lite2", ModelRole.SUBJECT, Engine.MEDIAPIPE, "Apache-2.0",
                Origin.DOWNLOAD, "efficientdet_lite2.tflite",
                url = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/float32/1/efficientdet_lite2.tflite",
                sizeMb = 22, blurb = "Fully open licence, more accurate than Lite0, slower.",
            ),
        )
    }
}
