package com.phoneaf.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoneaf.camera.CameraFocusFrame
import com.phoneaf.camera.CameraSession
import com.phoneaf.camera.ConnState
import com.phoneaf.camera.LiveViewFrame
import com.phoneaf.detect.AfMode
import com.phoneaf.detect.AutoDetector
import com.phoneaf.detect.Box
import com.phoneaf.detect.CamRotation
import com.phoneaf.detect.Engine
import com.phoneaf.detect.EyePref
import com.phoneaf.detect.ModelRole
import com.phoneaf.detect.ModelSpec
import com.phoneaf.detect.ModelStore
import com.phoneaf.detect.PartLocator
import com.phoneaf.detect.Pt
import com.phoneaf.detect.SubjectDetector
import com.phoneaf.detect.SubjectKind
import com.phoneaf.detect.camToView
import com.phoneaf.detect.kindOf
import com.phoneaf.detect.viewToCam
import com.phoneaf.focus.AfReadiness
import com.phoneaf.focus.FocusController
import com.phoneaf.focus.FocusDecision
import com.phoneaf.focus.FocusTuning
import com.phoneaf.focus.evaluateReadiness
import com.phoneaf.track.SubjectTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** When phoneAF holds the shutter half-press (S1) so the camera's AF-C keeps driving the lens. */
enum class AfDrive(val label: String) {
    OFF("Point only (use Pre-AF / AF-ON)"),
    SUBJECT("AF while subject found"),
    ALWAYS("AF always (AF-C hold)"),
}

data class TrackBox(val box: Box, val kind: SubjectKind, val label: String, val primary: Boolean, val inFlight: Boolean)

data class ModelEntry(val spec: ModelSpec, val available: Boolean, val downloading: Float? = null)

data class UiState(
    val conn: ConnState = ConnState.NoCamera,
    val mode: AfMode = AfMode.BIRD_EYE,
    val eyePref: EyePref = EyePref.AUTO,
    val drive: AfDrive = AfDrive.SUBJECT,
    val tuning: FocusTuning = FocusTuning(),
    val stickiness: Float = 0.5f,
    val minScore: Float = 0.35f,
    val roiZoom: Boolean = true,
    /** Hybrid tracking: Catchlight picks and seeds the subject, the camera's own tracker follows it. */
    val hybrid: Boolean = false,
    val hybridState: String = "",
    val hybridNote: String = "",
    val showAllSubjects: Boolean = true,
    val showCameraFrames: Boolean = true,
    val rotation: CamRotation = CamRotation.LANDSCAPE,
    /** Labels the user restricted detection to (empty = everything the mode allows). */
    val classes: Set<String> = emptySet(),
    val models: List<ModelEntry> = emptyList(),
    val subjectModelId: String = DEFAULT_SUBJECT_MODEL,
    val partsModelId: String = NO_PARTS_MODEL,
    val modelLabels: List<String> = emptyList(),
    val decision: FocusDecision? = null,
    val tracks: List<TrackBox> = emptyList(),
    val cameraFrames: List<CameraFocusFrame> = emptyList(),
    val readiness: AfReadiness = evaluateReadiness(null),
    val afFollows: Boolean? = null,
    val locked: Boolean = false,
    val liveFps: Float = 0f,
    val inferMs: Int = 0,
    val detCount: Int = 0,
    /** Model actually running this frame (Auto shows its current tier). */
    val modelShort: String = "",
    val topDet: String? = null,
    val afResult: String = "–",
    val capability: String = "",
    val modelInfo: String = "loading models…",
    val modelError: String? = null,
    val frameAspect: Float = 3f / 2f,
) {
    companion object {
        const val DEFAULT_SUBJECT_MODEL = AutoDetector.ID
        const val NO_PARTS_MODEL = "estimate"
    }
}

class PhoneAfViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("phoneaf", Context.MODE_PRIVATE)
    private val store = ModelStore(app)

    private val _ui = MutableStateFlow(
        UiState(
            subjectModelId = prefs.getString("subjectModel", UiState.DEFAULT_SUBJECT_MODEL)!!,
            partsModelId = prefs.getString("partsModel", UiState.NO_PARTS_MODEL)!!,
            rotation = runCatching { CamRotation.valueOf(prefs.getString("rotation", "")!!) }.getOrDefault(CamRotation.LANDSCAPE),
            classes = prefs.getStringSet("classes", emptySet())!!.toSet(),
            mode = runCatching { AfMode.valueOf(prefs.getString("mode", "")!!) }.getOrDefault(AfMode.BIRD_EYE),
            hybrid = prefs.getBoolean("hybrid", false),
        ),
    )
    val ui: StateFlow<UiState> = _ui
    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame

    private val rawFrames = Channel<LiveViewFrame>(Channel.CONFLATED)
    private val detectFrames = Channel<Pair<Bitmap, Long>>(Channel.CONFLATED)

    private val session = CameraSession(app) { rawFrames.trySend(it) }
    @Volatile private var detector: SubjectDetector? = null
    /** Non-null when the "Auto (by bird size)" model is selected. */
    @Volatile private var auto: AutoDetector? = null
    @Volatile private var parts: PartLocator? = null
    private val reloadModels = AtomicBoolean(true)
    private val tracker = SubjectTracker()
    private val focus = FocusController()
    @Volatile private var s1Held = false
    private val pendingTap = AtomicReference<Pt?>(null)
    private val unlockRequested = AtomicBoolean(false)

    /** Last AF point sent, in camera coordinates, for checking that the camera follows it. */
    @Volatile private var lastSentCam: Pt? = null
    @Volatile private var lastSentMs = 0L
    @Volatile private var alreadyThere = false

    init {
        viewModelScope.launch {
            var wasLive = false
            session.state.collect { s ->
                _ui.update { it.copy(conn = s) }
                val live = s is ConnState.Live
                // Re-apply Hybrid's Tracking focus area on every new connection.
                if (live && !wasLive && _ui.value.hybrid) session.setTrackingArea(true)
                wasLive = live
            }
        }
        viewModelScope.launch {
            session.focusSettings.collect { s -> _ui.update { it.copy(readiness = evaluateReadiness(s)) } }
        }
        refreshModelList()
        viewModelScope.launch(Dispatchers.Default) { decodeLoop() }
        viewModelScope.launch(Dispatchers.Default) { detectLoop() }
        session.connectFirst()
    }

    // ---- models ---------------------------------------------------------------------------------

    private fun refreshModelList() {
        _ui.update { st ->
            st.copy(models = store.all().map { spec ->
                ModelEntry(spec, store.isAvailable(spec), st.models.firstOrNull { it.spec.id == spec.id }?.downloading)
            })
        }
    }

    /** Runs on the detection coroutine: (re)opens the chosen models. */
    private fun loadModels() {
        val st = _ui.value
        val specs = store.all()
        val subject = specs.firstOrNull { it.id == st.subjectModelId && it.role == ModelRole.SUBJECT && store.isAvailable(it) }
            ?: specs.firstOrNull { it.id == UiState.DEFAULT_SUBJECT_MODEL && store.isAvailable(it) }
            ?: specs.first { it.role == ModelRole.SUBJECT && store.isAvailable(it) && it.id != AutoDetector.ID }
        val pose = specs.firstOrNull { it.id == st.partsModelId && it.engine == Engine.POSE && store.isAvailable(it) }

        detector?.close(); parts?.close(); auto?.close()
        detector = null; parts = null; auto = null
        runCatching {
            val d = if (subject.id == AutoDetector.ID) {
                val a = AutoDetector { tier ->
                    store.spec(tier.modelId)?.let { s -> runCatching { SubjectDetector(store.open(s), s.title) }.getOrNull() }
                }
                auto = a
                // Load every tier now so the first switch doesn't stall a frame.
                AutoDetector.Tier.entries.forEach { t -> a.detector(t) }
                a.detector() ?: error("Auto: no YOLO models bundled")
            } else SubjectDetector(store.open(subject), subject.title)
            val p = PartLocator(getApplication(), pose?.let { store.map(it) })
            detector = d; parts = p
            val partsInfo = buildList {
                add(if (p.hasPoseModel) "eye: ${pose!!.title}" else "bird/animal eye: estimated")
                add(if (p.hasFaceModel) "human eye ✓" else "human eye: estimated")
            }.joinToString(" · ")
            _ui.update {
                it.copy(
                    subjectModelId = subject.id, modelLabels = d.labels,
                    modelInfo = "${subject.title} · $partsInfo", modelError = null,
                )
            }
        }.onFailure { e ->
            Log.e(TAG, "model load", e)
            _ui.update { it.copy(modelError = "model: ${e.message}", modelInfo = "model failed: ${subject.title}") }
        }
        tracker.reset(); focus.reset()
    }

    fun selectSubjectModel(id: String) {
        prefs.edit().putString("subjectModel", id).apply()
        _ui.update { it.copy(subjectModelId = id, classes = it.classes.filter { c -> c in it.modelLabels }.toSet()) }
        reloadModels.set(true)
    }

    fun selectPartsModel(id: String) {
        prefs.edit().putString("partsModel", id).apply()
        _ui.update { it.copy(partsModelId = id) }
        reloadModels.set(true)
    }

    fun downloadModel(spec: ModelSpec) {
        setDownloading(spec.id, 0f)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.download(spec) { p -> setDownloading(spec.id, p) } }
                .onFailure { e -> _ui.update { it.copy(modelError = "download: ${e.message}") } }
            setDownloading(spec.id, null)
            refreshModelList()
        }
    }

    private fun setDownloading(id: String, p: Float?) = _ui.update { st ->
        st.copy(models = st.models.map { if (it.spec.id == id) it.copy(downloading = p) else it })
    }

    fun importModels(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (u in uris) runCatching { store.import(u) }
                .onFailure { e -> _ui.update { it.copy(modelError = "import: ${e.message}") } }
            refreshModelList()
            reloadModels.set(true)
        }
    }

    fun deleteModel(spec: ModelSpec) {
        store.delete(spec)
        refreshModelList()
        reloadModels.set(true)
    }

    // ---- live view decode (display rate) ----------------------------------------------------

    private suspend fun decodeLoop() {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var count = 0
        var windowStart = SystemClock.elapsedRealtime()
        for (f in rawFrames) {
            val decoded = BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size, opts) ?: continue
            val rot = _ui.value.rotation
            // Vertical shooting: turn the frame upright so detectors see birds the right way up.
            val bmp = if (rot.degrees == 0) decoded else Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rot.degrees.toFloat()) }, true,
            )
            val now = SystemClock.elapsedRealtime()
            _frame.value = bmp.asImageBitmap()
            detectFrames.trySend(bmp to now)
            checkAfFollows(f.cameraFrames, now)
            count++
            if (now - windowStart >= 1000) {
                val fps = count * 1000f / (now - windowStart)
                count = 0; windowStart = now
                _ui.update {
                    it.copy(liveFps = fps, afResult = session.lastAfResult, capability = session.capability, hybridNote = session.hybridAreaResult)
                }
            }
            val frames = f.cameraFrames.map { cf ->
                val c = Pt(cf.cx, cf.cy).camToView(rot)
                val swap = rot.degrees % 180 != 0
                cf.copy(cx = c.x, cy = c.y, w = if (swap) cf.h else cf.w, h = if (swap) cf.w else cf.h)
            }
            _ui.update { it.copy(cameraFrames = frames, frameAspect = bmp.width.toFloat() / bmp.height) }
        }
    }

    /** The camera reports where its AF frame is; if it lands where we sent it, the link works. */
    private fun checkAfFollows(frames: List<CameraFocusFrame>, now: Long) {
        val sent = lastSentCam ?: return
        if (frames.isEmpty()) return
        val near = frames.any { Pt(it.cx, it.cy).dist(sent) < 0.08f }
        if (now - lastSentMs < 250) {
            // Remember whether the camera frame was already there when we sent the point.
            if (now - lastSentMs < 60) alreadyThere = near
            return
        }
        // Only a frame that *moved* onto our point counts as proof.
        val follows = near && !alreadyThere
        if (now - lastSentMs < 3000 && _ui.value.afFollows != follows && (follows || !near)) {
            _ui.update { it.copy(afFollows = follows) }
        }
    }

    // ---- detection → tracking → AF (inference rate) -----------------------------------------

    private suspend fun detectLoop() {
        for ((bmp, ts) in detectFrames) {
            if (reloadModels.getAndSet(false)) loadModels()
            val det = auto?.detector() ?: detector ?: continue
            val loc = parts ?: continue
            val st = _ui.value
            val t0 = SystemClock.elapsedRealtime()

            val zoom = if (st.roiZoom) tracker.primary?.box else null
            val classes = st.classes.filter { kindOf(it) in st.mode.kinds }.toSet()
            val raw = runCatching { det.detect(bmp, st.minScore, classes, zoom) }
            raw.exceptionOrNull()?.let { e ->
                Log.w(TAG, "detect", e)
                _ui.update { it.copy(modelError = "detect: ${e.message ?: e.javaClass.simpleName}") }
            }
            val all = raw.getOrDefault(emptyList())
            val found = all.filter { it.kind in st.mode.kinds }

            // Tracking first (gives us motion), then parts only for the subject that matters.
            tracker.update(found, st.mode, st.stickiness, ts)
            pendingTap.getAndSet(null)?.let(::applyTap)
            if (unlockRequested.getAndSet(false)) tracker.clearLock()
            val primary = tracker.primary
            if (primary != null && primary.misses == 0) {
                primary.det = runCatching { loc.locate(bmp, primary.det, primary.velocity) }.getOrDefault(primary.det)
            }

            val decision = focus.decide(primary, st.mode, st.eyePref, st.tuning, ts)
            drive(decision, st)
            val inferMs = (SystemClock.elapsedRealtime() - t0).toInt()
            // Auto model: choose next frame's detector from the tracked subject's size.
            val tier = auto?.update(
                width = primary?.takeIf { it.misses <= 2 }?.w,
                flight = st.mode.flight || primary?.inFlight == true,
                lastMs = inferMs,
            )

            _ui.update {
                it.copy(
                    decision = decision,
                    tracks = tracker.all.filter { t -> t.misses == 0 }.map { t ->
                        TrackBox(t.box, t.det.kind, t.det.label, t.id == tracker.primaryId, t.inFlight)
                    },
                    locked = tracker.isUserLocked,
                    inferMs = inferMs,
                    modelShort = tier?.let { t -> "Auto·${t.short}" } ?: det.modelTitle.substringBefore(" "),
                    detCount = found.size,
                    topDet = (found.ifEmpty { all }).maxByOrNull { d -> d.score }
                        ?.let { d -> "${d.label} ${(d.score * 100).toInt()}%" + if (d !in found) " (other mode)" else "" },
                )
            }
        }
    }

    private fun sendAf(viewPoint: Pt) {
        val cam = viewPoint.viewToCam(_ui.value.rotation)
        lastSentCam = cam
        lastSentMs = SystemClock.elapsedRealtime()
        session.setAfPoint(cam)
    }

    // ---- Hybrid tracking ------------------------------------------------------------------------
    // Seed once (AF point on the eye + half-press), then let the camera's Real-time Tracking
    // follow. Re-seed only when the subject changes or the camera's frame leaves our bird.

    private var seededTrackId: Int? = null
    private var offTargetFrames = 0
    private var lastSeedMs = 0L

    private fun driveHybrid(decision: FocusDecision?, st: UiState) {
        val now = SystemClock.elapsedRealtime()
        if (decision == null) {
            if (seededTrackId != null && st.drive == AfDrive.SUBJECT && s1Held) {
                s1Held = false
                session.submit { it.halfPress(false) }
            }
            if (st.drive == AfDrive.ALWAYS && !s1Held) {
                s1Held = true
                session.submit { it.halfPress(true) }
            }
            seededTrackId = null
            offTargetFrames = 0
            setHybridState("searching")
            return
        }
        // Is the camera's own AF frame still on the bird we chose?
        val box = tracker.primary?.box?.expand(0.3f)
        val frames = _ui.value.cameraFrames
        val onTarget: Boolean? = if (box == null || frames.isEmpty()) null else frames.any { box.contains(Pt(it.cx, it.cy)) }
        offTargetFrames = if (onTarget == false) offTargetFrames + 1 else 0

        val newSubject = seededTrackId != decision.trackId
        val drifted = offTargetFrames >= HYBRID_DRIFT_FRAMES
        val blindTooLong = onTarget == null && now - lastSeedMs > HYBRID_BLIND_RESEED_MS
        if ((newSubject || drifted || blindTooLong) && now - lastSeedMs > HYBRID_MIN_RESEED_MS) {
            // The a7C ignores AF moves under S1; setAfPoint releases and re-presses around the move.
            sendAf(decision.point)
            if (!s1Held) {
                s1Held = true
                session.submit { it.halfPress(true) }
            }
            setHybridState(if (seededTrackId == null) "seeding" else if (drifted) "re-seeding (drift)" else "re-seeding")
            seededTrackId = decision.trackId
            lastSeedMs = now
            offTargetFrames = 0
        } else {
            setHybridState(if (onTarget == true) "camera tracking" else "camera tracking (no frame info)")
        }
    }

    private fun setHybridState(s: String) {
        if (_ui.value.hybridState != s) _ui.update { it.copy(hybridState = s) }
    }

    fun setHybrid(on: Boolean) {
        prefs.edit().putBoolean("hybrid", on).apply()
        seededTrackId = null
        _ui.update { it.copy(hybrid = on, hybridState = "") }
        if (_ui.value.conn is ConnState.Live) session.setTrackingArea(on)
    }

    private fun drive(decision: FocusDecision?, st: UiState) {
        if (st.hybrid && st.drive != AfDrive.OFF) {
            driveHybrid(decision, st)
            return
        }
        // Every move while S1 is held costs a release/re-press, so move less often then.
        val tuning = if (s1Held) st.tuning.copy(
            maxRateHz = minOf(st.tuning.maxRateHz, 6), deadbandGrid = maxOf(st.tuning.deadbandGrid, 14),
        ) else st.tuning
        if (decision != null && focus.shouldSend(decision.point, tuning, SystemClock.elapsedRealtime())) {
            sendAf(decision.point)
        }
        val wantS1 = when (st.drive) {
            AfDrive.OFF -> false
            AfDrive.ALWAYS -> true
            AfDrive.SUBJECT -> decision != null
        }
        if (wantS1 != s1Held) {
            s1Held = wantS1
            session.submit { it.halfPress(wantS1) }
        }
    }

    // ---- UI actions ---------------------------------------------------------------------------

    fun connect() { session.connectFirst() }

    val savedWifiHost: String get() = prefs.getString("wifiHost", "")!!

    /** Wi-Fi PC Remote; blank host = use the Wi-Fi gateway (the camera in Wi-Fi Direct mode). */
    fun connectWifi(host: String) {
        prefs.edit().putString("wifiHost", host.trim()).apply()
        session.connectWifi(host.trim())
    }

    fun disconnect() = session.disconnect()

    fun setMode(m: AfMode) {
        prefs.edit().putString("mode", m.name).apply()
        _ui.update { it.copy(mode = m) }
        reloadModels.set(true) // also resets tracker/focus on the detection thread
    }

    fun setClasses(c: Set<String>) {
        prefs.edit().putStringSet("classes", c).apply()
        _ui.update { it.copy(classes = c) }
    }

    fun setRotation(r: CamRotation) {
        prefs.edit().putString("rotation", r.name).apply()
        _ui.update { it.copy(rotation = r) }
        reloadModels.set(true)
    }

    fun runAfTest() = session.runAfTest()
    fun forceAfPath(p: com.phoneaf.camera.SonyCamera.AfPath?) = session.forceAfPath(p)

    fun fixFocusSettings() {
        val r = _ui.value.readiness
        session.fixFocusSettings(area = r.fixArea, mode = r.fixMode)
    }

    fun setEyePref(e: EyePref) = _ui.update { it.copy(eyePref = e) }
    fun setDrive(d: AfDrive) = _ui.update { it.copy(drive = d) }
    fun setTuning(t: FocusTuning) = _ui.update { it.copy(tuning = t) }
    fun setStickiness(v: Float) = _ui.update { it.copy(stickiness = v) }
    fun setMinScore(v: Float) = _ui.update { it.copy(minScore = v) }
    fun setRoiZoom(v: Boolean) = _ui.update { it.copy(roiZoom = v) }
    fun setShowAll(v: Boolean) = _ui.update { it.copy(showAllSubjects = v) }
    fun setShowCameraFrames(v: Boolean) = _ui.update { it.copy(showCameraFrames = v) }

    /** Tap on the live view: lock onto that subject, or do a plain spot-AF there. */
    fun tapAt(p: Pt) {
        if (detector == null || _ui.value.conn !is ConnState.Live) sendAf(p)
        else pendingTap.set(p)
    }

    /** Runs on the detection coroutine so the tracker is only touched from one thread. */
    private fun applyTap(p: Pt) {
        if (!tracker.lockAt(p)) {
            tracker.clearLock()
            sendAf(p)
            if (_ui.value.drive != AfDrive.OFF) session.submit { it.halfPress(true) }
        }
    }

    fun releaseLock() = unlockRequested.set(true)

    fun shoot() {
        val hold = s1Held
        session.submit { it.shoot(holdAf = hold) }
    }

    override fun onCleared() {
        session.close()
        detector?.close()
        parts?.close()
    }

    companion object {
        private const val TAG = "PhoneAF"
        /** Camera frame off our bird this many detection frames in a row → re-seed. */
        private const val HYBRID_DRIFT_FRAMES = 5
        /** Never re-seed more often than this (each seed costs a half-press cycle). */
        private const val HYBRID_MIN_RESEED_MS = 400L
        /** Bodies that report no AF frame: refresh the seed this often. */
        private const val HYBRID_BLIND_RESEED_MS = 1500L
    }
}
