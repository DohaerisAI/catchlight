@file:OptIn(ExperimentalTextApi::class)

package com.phoneaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.phoneaf.R

/**
 * "Instrument" design language: monochrome, hairline-ruled, type-led.
 * One accent, and it only ever means "focus is confirmed".
 * Canvas spec: claude.ai artifact "phoneAF Design Spec".
 */
object Ink {
    val Ground = Color(0xFF0A0A0A)
    val Raised = Color(0xFF141414)
    val Line = Color(0xFF262626)
    val LineStrong = Color(0xFF333333)
    val Text = Color(0xFFEDEDED)
    val Dim = Color(0xFF8C8C8C)
    val Faint = Color(0xFF6E6E6E)
    val Accent = Color(0xFFD7FF3A)      // focus confirmed
    val Warn = Color(0xFFFFB020)        // camera setting needs attention
    val Alert = Color(0xFFFF5A5F)       // phoneAF can't steer / error
    val OverlayWhite = Color(0xE6FFFFFF)

    private fun geist(w: Int) = Font(
        R.font.geist_variable, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)),
    )
    private fun mono(w: Int) = Font(
        R.font.geist_mono_variable, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)),
    )
    val Sans = FontFamily(geist(300), geist(400), geist(500), geist(600))
    val Mono = FontFamily(mono(400), mono(500))

    val colorScheme = darkColorScheme(
        primary = Accent, onPrimary = Ground,
        secondary = Text, onSecondary = Ground,
        background = Ground, onBackground = Text,
        surface = Raised, onSurface = Text,
        surfaceVariant = Raised, onSurfaceVariant = Dim,
        surfaceContainerLow = Raised, surfaceContainer = Raised, surfaceContainerHigh = Raised,
        outline = LineStrong, outlineVariant = Line,
        error = Alert,
    )

    /** The big mode word ("Bird"). */
    val Display = TextStyle(fontFamily = Sans, fontWeight = FontWeight(500), fontSize = 46.sp, letterSpacing = (-0.04).em, lineHeight = 46.sp)
    val Title = TextStyle(fontFamily = Sans, fontWeight = FontWeight(500), fontSize = 18.sp)
    val Body = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = 14.sp, lineHeight = 20.sp)
    val Value = TextStyle(fontFamily = Sans, fontWeight = FontWeight(500), fontSize = 15.sp)
    /** Telemetry and labels: mono, uppercase, tracked. */
    fun micro(size: TextUnit = 10.5.sp, color: Color = Dim) =
        TextStyle(fontFamily = Mono, fontWeight = FontWeight(400), fontSize = size, letterSpacing = 0.08.em, color = color)
}

/** Mono, uppercase label / telemetry. */
@Composable
fun Micro(text: String, color: Color = Ink.Dim, size: TextUnit = 10.5.sp, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = Ink.micro(size, color), maxLines = 1, modifier = modifier)

/** 1 px hairline rule. */
@Composable
fun Hairline(modifier: Modifier = Modifier, vertical: Boolean = false) =
    Box(if (vertical) modifier.width(1.dp).background(Ink.Line) else modifier.fillMaxWidth().height(1.dp).background(Ink.Line))

/** Labelled value cell for the telemetry grid. */
@Composable
fun Readout(label: String, value: String, valueColor: Color = Ink.Text, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Column(
        modifier.then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
    ) {
        Micro(label, Ink.Faint, 9.5.sp)
        Text(value, style = Ink.Value, color = valueColor, maxLines = 1)
    }
}

/** Text tab with an accent underline when selected. */
@Composable
fun Tab(text: String, selected: Boolean, onClick: () -> Unit, mono: Boolean = false) {
    Column(
        Modifier.clickable(role = Role.Tab, onClick = onClick).padding(top = 10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        if (mono) Micro(text, if (selected) Ink.Text else Ink.Faint)
        else Text(text, style = Ink.Body.copy(fontWeight = if (selected) FontWeight(500) else FontWeight(400)), color = if (selected) Ink.Text else Ink.Dim)
        Box(Modifier.padding(top = 8.dp).height(1.5.dp).width(if (selected) 28.dp else 0.dp).background(Ink.Accent))
    }
}

/** Segmented control: mono labels, selected segment inverted. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.border(1.dp, Ink.LineStrong, shape).height(44.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.then(if (i == selected) Modifier.background(Ink.Text, shape) else Modifier)
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .padding(horizontal = 11.dp)
                    .height(44.dp),
                contentAlignment = Alignment.Center,
            ) { Micro(o, if (i == selected) Ink.Ground else Ink.Dim) }
        }
    }
}

/** Shutter: hairline ring, solid core; accent inner ring only while AF is engaged. */
@Composable
fun InstrumentShutter(afEngaged: Boolean, onClick: () -> Unit, size: Dp = 76.dp) {
    Box(
        Modifier.size(size).clickable(role = Role.Button, onClick = onClick).drawBehind {
            val r = this.size.minDimension / 2
            drawCircle(Ink.Text, radius = r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            drawCircle(Ink.Text, radius = r - 7.dp.toPx())
            if (afEngaged) drawCircle(Ink.Accent, radius = r - 8.dp.toPx(), style = Stroke(2.dp.toPx()))
        },
    )
}

/** Square hairline icon button. */
@Composable
fun SquareButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.size(48.dp).border(1.dp, Ink.LineStrong, RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A list row with hairline below: label left, detail right. */
@Composable
fun ListRow(onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        Hairline()
    }
}

/** Small round status dot. */
@Composable
fun Dot(color: Color, size: Dp = 6.dp) = Box(Modifier.size(size).background(color, CircleShape))
