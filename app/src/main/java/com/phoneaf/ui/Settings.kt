package com.phoneaf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneaf.camera.SonyCamera
import com.phoneaf.detect.CamRotation
import com.phoneaf.detect.Engine
import com.phoneaf.detect.EyePref
import com.phoneaf.detect.ModelRole
import com.phoneaf.detect.ModelSpec
import com.phoneaf.detect.Origin
import com.phoneaf.detect.SubjectKind
import com.phoneaf.detect.kindOf

@Composable
fun SettingsSheet(ui: UiState, vm: PhoneAfViewModel, onImportModel: () -> Unit, onClasses: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = Ink.Title.copy(fontSize = 22.sp), color = Ink.Text)
        Micro(ui.modelInfo, Ink.Faint, 9.5.sp, Modifier.padding(top = 4.dp))

        Group("Subject detector")
        ui.models.filter { it.spec.role == ModelRole.SUBJECT }.forEach { m ->
            ModelRow(m, m.spec.id == ui.subjectModelId, { vm.selectSubjectModel(m.spec.id) }, { vm.downloadModel(m.spec) }, { vm.deleteModel(m.spec) })
        }

        Group("Eye & head model")
        ModelRow(
            ModelEntry(ModelSpec(UiState.NO_PARTS_MODEL, "Estimate from box", ModelRole.PARTS, Engine.POSE, "built-in",
                Origin.BUNDLED, "", blurb = "No model: head and eye placed from box shape and motion."), true),
            ui.partsModelId == UiState.NO_PARTS_MODEL, { vm.selectPartsModel(UiState.NO_PARTS_MODEL) }, {}, {},
        )
        ui.models.filter { it.spec.role == ModelRole.PARTS }.forEach { m ->
            ModelRow(m, m.spec.id == ui.partsModelId, { vm.selectPartsModel(m.spec.id) }, {}, { vm.deleteModel(m.spec) })
        }
        OutlinedButton(onClick = onImportModel, modifier = Modifier.padding(top = 10.dp)) { Text("Import .tflite model", style = Ink.Body) }
        Note("A YOLO detector (e.g. bird_head, bird_eye classes), or a keypoint model with \"pose\" in its file name. A .txt with the same name supplies class labels.")

        Group("Classes")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (ui.classes.isEmpty()) "All classes the mode allows" else ui.classes.sorted().joinToString(), style = Ink.Body, color = Ink.Text, modifier = Modifier.weight(1f))
            TextButton(onClick = onClasses) { Micro("Choose", Ink.Text) }
        }

        Group("Focus")
        Label("Eye")
        Segmented(EyePref.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, ui.eyePref.ordinal) { vm.setEyePref(EyePref.entries[it]) }
        Label("Drive")
        Segmented(listOf("Point", "Auto", "Hold"), ui.drive.ordinal) { vm.setDrive(AfDrive.entries[it]) }
        Note(ui.drive.label)
        val t = ui.tuning
        SliderRow("Latency lead", "${t.leadMs} ms", t.leadMs.toFloat(), 0f..300f) { vm.setTuning(t.copy(leadMs = it.toInt())) }
        SliderRow("Smoothing", "%.2f s".format(t.smoothingS), t.smoothingS, 0f..0.4f) { vm.setTuning(t.copy(smoothingS = it)) }
        SliderRow("Deadband", "${t.deadbandGrid} px", t.deadbandGrid.toFloat(), 0f..30f) { vm.setTuning(t.copy(deadbandGrid = it.toInt())) }
        SliderRow("Max AF updates", "${t.maxRateHz}/s", t.maxRateHz.toFloat(), 2f..30f) { vm.setTuning(t.copy(maxRateHz = it.toInt().coerceAtLeast(1))) }
        SliderRow("Lock-on hold", "${t.holdMs} ms", t.holdMs.toFloat(), 0f..2000f) { vm.setTuning(t.copy(holdMs = it.toInt())) }
        SliderRow("Tracking stickiness", "%.2f".format(ui.stickiness), ui.stickiness, 0f..1f, vm::setStickiness)
        SliderRow("Detection threshold", "%.2f".format(ui.minScore), ui.minScore, 0.1f..0.8f, vm::setMinScore)
        ToggleRow("Switch subject with the camera wheel", ui.dialSwitch, vm::setDialSwitch)
        Note("With several birds in frame, press the camera's wheel left/right/up/down: Catchlight sees its AF spot move and locks onto the next bird that way. Experimental; off during Hybrid tracking.")
        ToggleRow("Hybrid tracking", ui.hybrid, vm::setHybrid)
        Note(
            "Catchlight finds the eye and places the start point; the camera's own Real-time Tracking follows the subject. " +
                "Catchlight re-seeds when the camera's frame drifts off the bird. Sets Focus Area to Tracking: Flexible Spot M " +
                "and restores it when turned off. Needs drive Auto or Hold." +
                if (ui.hybridNote.isNotEmpty()) "\n" + ui.hybridNote else "",
        )
        ToggleRow("ROI zoom for small, distant subjects", ui.roiZoom, vm::setRoiZoom)
        ToggleRow("Show all detected subjects", ui.showAllSubjects, vm::setShowAll)
        ToggleRow("Show the camera's own AF frame", ui.showCameraFrames, vm::setShowCameraFrames)

        Group("Camera")
        Label("Orientation")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CamRotation.entries.forEach { r -> FilterChip(r == ui.rotation, { vm.setRotation(r) }, { Text(r.label, style = Ink.Body.copy(fontSize = 13.sp)) }) }
        }
        val rd = ui.readiness
        Text("${rd.title} — ${rd.detail}", style = Ink.Body.copy(fontSize = 13.sp), color = Ink.Text, modifier = Modifier.padding(top = 14.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rd.fixArea || rd.fixMode) OutlinedButton(onClick = vm::fixFocusSettings) { Text("Set camera for $APP_NAME", style = Ink.Body) }
            OutlinedButton(onClick = vm::disconnect) { Text("Disconnect", style = Ink.Body) }
        }

        Group("Diagnostics")
        SelectionContainer { Text(ui.capability, style = Ink.micro(10.5.sp, Ink.Dim).copy(letterSpacing = 0.sp, lineHeight = 15.sp)) }
        OutlinedButton(onClick = vm::runAfTest, modifier = Modifier.padding(top = 10.dp)) { Text("AF test · one method per corner", style = Ink.Body) }
        Note("Watch the camera's screen: top-left ctl, top-right prop, bottom-right ctl-S1, bottom-left prop-S1. ${ui.afResult}")
        Label("AF method")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(false, { vm.forceAfPath(null) }, { Text("Auto", style = Ink.Body.copy(fontSize = 13.sp)) })
            SonyCamera.AfPath.entries.forEach { p -> FilterChip(false, { vm.forceAfPath(p) }, { Text(p.short, style = Ink.Body.copy(fontSize = 13.sp)) }) }
        }
        Spacer(Modifier.height(24.dp))
        Note("$APP_NAME is free software under the GNU AGPL-3.0. Geist fonts under the SIL Open Font License.")
    }
}

@Composable
private fun ColumnScope.Group(title: String) {
    Micro(title, Ink.Faint, modifier = Modifier.padding(top = 28.dp, bottom = 6.dp))
    Hairline()
}

@Composable
private fun Label(text: String) = Micro(text, Ink.Dim, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))

@Composable
private fun Note(text: String) = Text(text, style = Ink.Body.copy(fontSize = 12.sp, lineHeight = 17.sp), color = Ink.Dim, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun ModelRow(m: ModelEntry, selected: Boolean, onSelect: () -> Unit, onDownload: () -> Unit, onDelete: () -> Unit) {
    val s = m.spec
    ListRow(if (m.available) onSelect else null) {
        RadioButton(selected = selected && m.available, onClick = onSelect, enabled = m.available)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.title, style = Ink.Value, color = if (m.available) Ink.Text else Ink.Dim)
                Micro(s.license, Ink.Faint, 9.sp)
            }
            if (s.blurb.isNotEmpty()) Text(s.blurb, style = Ink.Body.copy(fontSize = 12.sp, lineHeight = 16.sp), color = Ink.Dim)
        }
        when {
            m.downloading != null -> Micro("${(m.downloading * 100).toInt()}%", Ink.Text)
            !m.available && s.origin == Origin.DOWNLOAD -> TextButton(onClick = onDownload) { Micro("Get ${s.sizeMb} MB", Ink.Text) }
            s.origin == Origin.IMPORTED || (s.origin == Origin.DOWNLOAD && m.available) -> TextButton(onClick = onDelete) { Micro("Remove") }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Ink.Body, color = Ink.Text, modifier = Modifier.weight(1f))
        Micro(value, Ink.Text)
    }
    Slider(value = v, onValueChange = onChange, valueRange = range)
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Ink.Body, color = Ink.Text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun ClassPicker(ui: UiState, onDismiss: () -> Unit, onChange: (Set<String>) -> Unit) {
    var picked by remember { mutableStateOf(ui.classes) }
    val groups = ui.modelLabels.distinct().groupBy { kindOf(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Raised,
        confirmButton = { TextButton(onClick = { onChange(picked); onDismiss() }) { Micro("Done", Ink.Text) } },
        dismissButton = { TextButton(onClick = { picked = emptySet() }) { Micro("All") } },
        title = { Text("Classes", style = Ink.Title, color = Ink.Text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Note("Nothing ticked = every class the AF mode allows. Use Object mode for cars, planes, balls…")
                for (kind in listOf(SubjectKind.BIRD, SubjectKind.ANIMAL, SubjectKind.HUMAN, SubjectKind.OBJECT)) {
                    val labels = groups[kind].orEmpty()
                    if (labels.isEmpty()) continue
                    Micro(kind.name, Ink.Faint, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    labels.forEach { l ->
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { picked = if (l in picked) picked - l else picked + l },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = l in picked, onCheckedChange = { picked = if (it) picked + l else picked - l })
                            Text(l, style = Ink.Body, color = Ink.Text)
                        }
                    }
                }
            }
        },
    )
}
