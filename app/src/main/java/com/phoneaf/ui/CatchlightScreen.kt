package com.phoneaf.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneaf.camera.ConnState
import com.phoneaf.detect.AfMode
import com.phoneaf.detect.Box as NBox
import com.phoneaf.detect.FocusTarget
import com.phoneaf.detect.Pt
import com.phoneaf.focus.ReadyLevel

const val APP_NAME = "Catchlight"

/** AF modes grouped the way the UI presents them: a family word, then a target. */
enum class Family(val label: String, val targets: List<Pair<String, AfMode>>) {
    BIRD("Bird", listOf("Eye" to AfMode.BIRD_EYE, "Head" to AfMode.BIRD_HEAD, "Body" to AfMode.BIRD_BODY, "In flight" to AfMode.BIRD_FLIGHT)),
    ANIMAL("Animal", listOf("Eye" to AfMode.ANIMAL_EYE, "Head" to AfMode.ANIMAL_HEAD, "Body" to AfMode.ANIMAL_BODY, "In motion" to AfMode.ANIMAL_RUN)),
    PERSON("Person", listOf("Eye" to AfMode.HUMAN_EYE, "Face" to AfMode.HUMAN_FACE)),
    OBJECT("Object", listOf("Any" to AfMode.OBJECT, "Moving" to AfMode.OBJECT_MOVING)),
    AUTO("Auto", listOf("Any eye" to AfMode.AUTO));

    companion object {
        fun of(m: AfMode) = entries.first { f -> f.targets.any { it.second == m } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatchlightApp(vm: PhoneAfViewModel, onImportModel: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val frame by vm.frame.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showClasses by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val view = LocalView.current

    // Transient problems surface as a snackbar instead of permanent red text.
    LaunchedEffect(ui.modelError) { ui.modelError?.let { snackbar.showSnackbar(it) } }
    // A soft tick whenever focus locks onto a new subject.
    LaunchedEffect(ui.decision?.trackId) {
        if (ui.decision != null) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    Box(Modifier.fillMaxSize().background(Ink.Ground).windowInsetsPadding(WindowInsets.safeDrawing)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val portrait = maxHeight > maxWidth
            val live = ui.conn as? ConnState.Live
            if (live == null || frame == null) {
                ConnectScreen(ui, vm, onSettings = { showSettings = true })
            } else if (portrait) {
                Column(Modifier.fillMaxSize()) {
                    StatusLine(ui, live, vm, Modifier.padding(horizontal = 20.dp))
                    LiveView(ui, frame!!, vm, Modifier.fillMaxWidth())
                    ReadinessStrip(ui, vm, Modifier.padding(horizontal = 20.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                        ModeReadout(ui)
                        Telemetry(ui, vm)
                        ModeTabs(ui, vm, onClasses = { showClasses = true })
                    }
                    ControlBar(ui, vm, onSettings = { showSettings = true }, Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        LiveView(ui, frame!!, vm, Modifier)
                    }
                    Column(Modifier.width(340.dp).fillMaxHeight().padding(horizontal = 20.dp)) {
                        StatusLine(ui, live, vm, Modifier)
                        ReadinessStrip(ui, vm, Modifier)
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            ModeReadout(ui)
                            Telemetry(ui, vm)
                            ModeTabs(ui, vm, onClasses = { showClasses = true })
                        }
                        ControlBar(ui, vm, onSettings = { showSettings = true }, Modifier.padding(vertical = 14.dp))
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = Ink.Raised) {
            SettingsSheet(ui, vm, onImportModel, onClasses = { showClasses = true })
        }
    }
    if (showClasses) ClassPicker(ui, onDismiss = { showClasses = false }, onChange = vm::setClasses)
    ui.crashReport?.let { report -> CrashDialog(report, onDismiss = vm::dismissCrashReport) }
}

// ------------------------------------------------------------------------------------------------
// Live view

@Composable
private fun StatusLine(ui: UiState, live: ConnState.Live, vm: PhoneAfViewModel, modifier: Modifier) {
    // Lit only when the camera itself reports focus; fall back to "command accepted" on bodies
    // that don't expose 0xD213.
    val cam = ui.camFocus
    val afLocked = if (cam != com.phoneaf.camera.FocusStatus.UNKNOWN) cam == com.phoneaf.camera.FocusStatus.FOCUSED
        else ui.decision != null && ui.afResult.startsWith("OK")
    val afLabel = when (cam) {
        com.phoneaf.camera.FocusStatus.FAILED -> "AF ✕"
        com.phoneaf.camera.FocusStatus.FOCUSING -> "AF …"
        com.phoneaf.camera.FocusStatus.IDLE -> "AF off"
        else -> "AF"
    }
    Row(
        modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Micro(live.model, Ink.Text)
            Micro(live.via)
            Micro("%.0f fps".format(ui.liveFps))
            Micro("${ui.inferMs} ms")
        }
        if (ui.dialNote.isNotEmpty()) Micro(ui.dialNote, Ink.Text, modifier = Modifier.padding(start = 12.dp))
        if (ui.hybrid) Micro("Hybrid", Ink.Text, modifier = Modifier.padding(start = 12.dp))
        if (ui.locked) {
            Micro("Locked ×", Ink.Text, modifier = Modifier.clickable { vm.releaseLock() }.padding(start = 12.dp))
        }
        Row(
            Modifier.padding(start = 12.dp).semantics { contentDescription = if (afLocked) "Focus locked" else "Focus idle" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Dot(if (afLocked) Ink.Accent else Ink.Faint)
            Micro(afLabel, if (afLocked) Ink.Accent else if (cam == com.phoneaf.camera.FocusStatus.FAILED) Ink.Warn else Ink.Faint)
        }
    }
}

@Composable
private fun LiveView(ui: UiState, frame: ImageBitmap, vm: PhoneAfViewModel, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        Box(
            Modifier
                .aspectRatio(ui.frameAspect, matchHeightConstraintsFirst = maxWidth / maxHeight > ui.frameAspect)
                .semantics { contentDescription = "Camera live view. Tap a subject to lock on." }
                .pointerInput(Unit) { detectTapGestures { o -> vm.tapAt(Pt(o.x / size.width, o.y / size.height)) } },
        ) {
            Image(frame, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) { drawOverlays(ui, measurer) }
        }
    }
}

/** One line under the image when the camera's settings stop Catchlight steering focus. */
@Composable
private fun ReadinessStrip(ui: UiState, vm: PhoneAfViewModel, modifier: Modifier) {
    val r = ui.readiness
    if (r.level == ReadyLevel.OK || r.level == ReadyLevel.UNKNOWN) return
    val color = if (r.level == ReadyLevel.BLOCKED) Ink.Alert else Ink.Warn
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(color)
            Text(r.detail, style = Ink.Body.copy(fontSize = 13.sp), color = Ink.Text, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            if (r.fixArea || r.fixMode) TextButton(onClick = vm::fixFocusSettings) { Micro("Fix", Ink.Text) }
        }
        Hairline()
    }
}

@Composable
private fun ModeReadout(ui: UiState) {
    val family = Family.of(ui.mode)
    val target = family.targets.first { it.second == ui.mode }.first
    val s = ui.readiness.title.split(" · ")
    Column(Modifier.padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(family.label, style = Ink.Display, color = Ink.Text, modifier = Modifier.weight(1f))
            Micro(ui.modelShort.ifEmpty { "—" }, modifier = Modifier.padding(bottom = 6.dp))
        }
        val state = when {
            ui.hybrid && ui.drive != AfDrive.OFF && ui.hybridState.isNotEmpty() -> ui.hybridState
            ui.decision != null -> "tracking"
            ui.detCount > 0 -> "subject found"
            else -> "searching"
        }
        Micro(listOfNotNull(target, s.getOrNull(0), s.getOrNull(1)?.substringBefore(" ["), state).joinToString(" · "), modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Telemetry(ui: UiState, vm: PhoneAfViewModel) {
    val subject = ui.topDet?.substringBefore(" (") ?: "searching"
    val target = ui.decision?.let { d ->
        d.part.name.lowercase().replaceFirstChar { it.uppercase() } + if (d.estimated) " ~" else ""
    } ?: "—"
    val (camText, camColor) = when {
        ui.afFollows == true -> "Follows" to Ink.Accent
        ui.readiness.level == ReadyLevel.BLOCKED -> "Can't steer" to Ink.Alert
        ui.readiness.level == ReadyLevel.WARN -> "Check" to Ink.Warn
        ui.afResult.startsWith("OK") -> "Ready" to Ink.Text
        else -> ui.afResult.take(12) to Ink.Dim
    }
    Column(Modifier.padding(top = 20.dp)) {
        Hairline()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Readout("Subject", subject, modifier = Modifier.weight(1f))
            Hairline(Modifier.fillMaxHeight(), vertical = true)
            Readout("Target", target, modifier = Modifier.weight(1f).padding(start = 14.dp))
            Hairline(Modifier.fillMaxHeight(), vertical = true)
            Readout("Camera", camText, camColor, Modifier.weight(1f).padding(start = 14.dp))
        }
        Hairline()
    }
}

@Composable
private fun ModeTabs(ui: UiState, vm: PhoneAfViewModel, onClasses: () -> Unit) {
    val family = Family.of(ui.mode)
    Column(Modifier.padding(top = 10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            family.targets.forEach { (label, mode) -> Tab(label, mode == ui.mode, { vm.setMode(mode) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Family.entries.forEach { f ->
                    Tab(f.label, f == family, { vm.setMode(f.targets.first().second) }, mono = true)
                }
            }
            Micro(
                if (ui.classes.isEmpty()) "Classes" else "Classes ${ui.classes.size}",
                if (ui.classes.isEmpty()) Ink.Faint else Ink.Text,
                modifier = Modifier.clickable(onClick = onClasses).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun ControlBar(ui: UiState, vm: PhoneAfViewModel, onSettings: () -> Unit, modifier: Modifier) {
    val view = LocalView.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Segmented(listOf("Point", "Auto", "Hold"), ui.drive.ordinal) { i ->
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            vm.setDrive(AfDrive.entries[i])
        }
        Box(Modifier.semantics { contentDescription = "Shutter" }) {
            InstrumentShutter(afEngaged = ui.drive != AfDrive.OFF && ui.decision != null, onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                vm.shoot()
            })
        }
        SquareButton(onSettings, Modifier.semantics { contentDescription = "Settings" }) {
            Canvas(Modifier.size(18.dp)) {
                val s = size.width
                val w = 1.5.dp.toPx()
                drawLine(Ink.Text, Offset(0f, s * 0.3f), Offset(s, s * 0.3f), w)
                drawLine(Ink.Text, Offset(0f, s * 0.7f), Offset(s, s * 0.7f), w)
                drawCircle(Ink.Ground, s * 0.13f, Offset(s * 0.66f, s * 0.3f))
                drawCircle(Ink.Text, s * 0.13f, Offset(s * 0.66f, s * 0.3f), style = Stroke(w))
                drawCircle(Ink.Ground, s * 0.13f, Offset(s * 0.36f, s * 0.7f))
                drawCircle(Ink.Text, s * 0.13f, Offset(s * 0.36f, s * 0.7f), style = Stroke(w))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Overlays: white hairline ticks for subjects, accent reticle only for the confirmed target.

private fun DrawScope.drawOverlays(ui: UiState, measurer: TextMeasurer) {
    fun rect(b: NBox) = Offset(b.l * size.width, b.t * size.height) to Size(b.w * size.width, b.h * size.height)
    val hair = 1.dp.toPx()

    if (ui.showCameraFrames) for (f in ui.cameraFrames) {
        val (o, s) = rect(NBox.around(Pt(f.cx, f.cy), f.w, f.h))
        drawRect(Color.White.copy(alpha = 0.45f), o, s, style = Stroke(hair, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))))
    }
    for (t in ui.tracks) {
        if (t.primary) cornerTicks(t.box, Ink.OverlayWhite, hair, 14.dp.toPx())
        else if (ui.showAllSubjects) {
            val (o, s) = rect(t.box)
            drawRect(Color.White.copy(alpha = 0.35f), o, s, style = Stroke(hair))
        }
    }
    ui.decision?.let { d ->
        val (o, s) = rect(d.box)
        val side = maxOf(minOf(s.width, s.height), 14.dp.toPx())
        val c = Offset(d.point.x * size.width, d.point.y * size.height)
        val tl = c - Offset(side / 2, side / 2)
        val stroke = 1.5.dp.toPx()
        drawRect(
            Ink.Accent, tl, Size(side, side),
            style = Stroke(stroke, pathEffect = if (d.estimated) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null),
        )
        val tick = 7.dp.toPx(); val gap = 3.dp.toPx()
        drawLine(Ink.Accent, Offset(c.x, tl.y - gap), Offset(c.x, tl.y - gap - tick), stroke)
        drawLine(Ink.Accent, Offset(c.x, tl.y + side + gap), Offset(c.x, tl.y + side + gap + tick), stroke)
        drawLine(Ink.Accent, Offset(tl.x - gap, c.y), Offset(tl.x - gap - tick, c.y), stroke)
        drawLine(Ink.Accent, Offset(tl.x + side + gap, c.y), Offset(tl.x + side + gap + tick, c.y), stroke)
        val label = d.part.name + (ui.topDet?.substringAfterLast(' ')?.removeSuffix("%")?.toIntOrNull()?.let { " %.2f".format(it / 100f) } ?: "")
        // Label to the right of the reticle, flipped to the left near the edge; drawText throws
        // if its top-left lands outside the canvas, so clamp it fully inside.
        val layout = measurer.measure(label, Ink.micro(9.5.sp, Ink.Accent))
        val pad = gap + tick + 4.dp.toPx()
        val w = layout.size.width.toFloat(); val h = layout.size.height.toFloat()
        val right = tl.x + side + pad
        val x = (if (right + w <= size.width) right else tl.x - pad - w).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
        val y = (c.y - h / 2).coerceIn(0f, (size.height - h).coerceAtLeast(0f))
        drawText(layout, topLeft = Offset(x, y))
    }
}

private fun DrawScope.cornerTicks(b: NBox, color: Color, width: Float, len: Float) {
    val l = b.l * size.width; val t = b.t * size.height; val r = b.r * size.width; val bt = b.b * size.height
    val k = minOf(len, (r - l) / 3, (bt - t) / 3)
    listOf(
        Offset(l, t) to Offset(l + k, t), Offset(l, t) to Offset(l, t + k),
        Offset(r, t) to Offset(r - k, t), Offset(r, t) to Offset(r, t + k),
        Offset(l, bt) to Offset(l + k, bt), Offset(l, bt) to Offset(l, bt - k),
        Offset(r, bt) to Offset(r - k, bt), Offset(r, bt) to Offset(r, bt - k),
    ).forEach { (a, z) -> drawLine(color, a, z, width) }
}

// ------------------------------------------------------------------------------------------------
// Connect

@Composable
private fun ConnectScreen(ui: UiState, vm: PhoneAfViewModel, onSettings: () -> Unit) {
    var wifiHost by remember { mutableStateOf(vm.savedWifiHost) }
    val (status, statusColor) = when (val c = ui.conn) {
        ConnState.NoCamera -> "No camera connected" to Ink.Faint
        ConnState.AwaitingPermission -> "Allow USB access in the dialog" to Ink.Warn
        ConnState.Connecting -> "Connecting…" to Ink.Warn
        is ConnState.Live -> "Waiting for live view · ${c.model}" to Ink.Accent
        is ConnState.Failed -> c.reason to Ink.Alert
        is ConnState.Reconnecting -> "Reconnecting · try ${c.attempt} in ${c.nextInSec}s · ${c.reason}" to Ink.Warn
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
    ) {
        Column(Modifier.widthIn(max = 560.dp)) {
            Text(APP_NAME, style = Ink.Display, color = Ink.Text)
            Micro("AI eye autofocus for Sony Alpha", modifier = Modifier.padding(top = 8.dp))

            Row(Modifier.padding(top = 28.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(statusColor)
                Text(status, style = Ink.Body, color = Ink.Text, modifier = Modifier.padding(start = 10.dp))
            }

            SectionLabel("Connect")
            ConnectRow("USB-C", "Lowest latency · ~25 fps live view", "01") { vm.connect() }
            ConnectRow("Wi-Fi", "Cable-free · join the camera's DIRECT-… network first", "02") { vm.connectWifi(wifiHost) }
            OutlinedTextField(
                value = wifiHost, onValueChange = { wifiHost = it }, singleLine = true,
                label = { Text("Camera IP for Wi-Fi", style = Ink.Body) },
                placeholder = { Text("auto", style = Ink.micro(13.sp)) },
                textStyle = Ink.micro(13.sp, Ink.Text),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            SectionLabel("Camera setup · a7C")
            SetupLine("01", "MENU → Network → Ctrl w/ Smartphone → Off")
            SetupLine("02", "Network → PC Remote Function → PC Remote → On")
            SetupLine("03", "PC Remote Cnct Method → USB, or Wi-Fi Direct")
            SetupLine("04", "Still Img. Save Dest. → Camera Only")
            SetupLine("05", "Focus Mode AF-C · Focus Area Flexible Spot S or M")

            TextButton(onClick = onSettings, modifier = Modifier.padding(top = 20.dp)) { Micro("Models & settings", Ink.Text) }
            Micro(ui.modelInfo, Ink.Faint, 9.5.sp, Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun ColumnScope.SectionLabel(text: String) {
    Micro(text, Ink.Faint, modifier = Modifier.padding(top = 28.dp, bottom = 4.dp))
    Hairline()
}

@Composable
private fun ConnectRow(title: String, detail: String, index: String, onClick: () -> Unit) {
    ListRow(onClick) {
        Micro(index, Ink.Faint, modifier = Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Ink.Title, color = Ink.Text)
            Text(detail, style = Ink.Body.copy(fontSize = 13.sp), color = Ink.Dim)
        }
        Micro("Connect →", Ink.Text)
    }
}

@Composable
private fun SetupLine(index: String, text: String) {
    Row(Modifier.padding(vertical = 8.dp)) {
        Micro(index, Ink.Faint, modifier = Modifier.width(32.dp))
        Text(text, style = Ink.Body.copy(fontSize = 13.sp), color = Ink.Text)
    }
}

@Composable
private fun CrashDialog(report: String, onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Raised,
        title = { Text("$APP_NAME stopped last time", style = Ink.Title, color = Ink.Text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Copy this and send it to the developer. Nothing was uploaded.", style = Ink.Body, color = Ink.Dim)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(report, style = Ink.micro(10.sp, Ink.Text).copy(letterSpacing = 0.sp, lineHeight = 14.sp), modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(report))
                copied = true
            }) { Micro(if (copied) "Copied" else "Copy", Ink.Text) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Micro("Close") } },
    )
}
