package com.phoneaf.focus

import com.phoneaf.camera.FocusSettings

enum class ReadyLevel { OK, WARN, BLOCKED, UNKNOWN }

/** Can phoneAF actually steer the a7C's focus with the camera's current settings? */
data class AfReadiness(
    val level: ReadyLevel,
    val title: String,
    val detail: String,
    val fixArea: Boolean = false,
    val fixMode: Boolean = false,
)

fun focusModeName(code: Int?) = when (code) {
    null -> "?"
    1 -> "MF"; 2 -> "AF-S"; 0x8004 -> "AF-C"; 0x8005 -> "AF-A"; 0x8006 -> "DMF"
    else -> "0x" + code.toString(16)
}

fun focusAreaName(code: Int?) = when (code) {
    null -> "?"
    0x0001 -> "Wide"; 0x0002 -> "Zone"; 0x0003 -> "Center"
    0x0101 -> "Flexible Spot S"; 0x0102 -> "Flexible Spot M"; 0x0103 -> "Flexible Spot L"
    0x0104 -> "Expand Flex Spot"; 0x0105 -> "Flexible Spot"
    0x0201 -> "Tracking: Wide"; 0x0202 -> "Tracking: Zone"; 0x0203 -> "Tracking: Center"
    0x0204 -> "Tracking: Spot S"; 0x0205 -> "Tracking: Spot M"; 0x0206 -> "Tracking: Spot L"
    0x0207 -> "Tracking: Expand"; 0x0208 -> "Tracking: Flex Spot"
    4 -> "Center"
    5 -> "Flexible Spot S"; 6 -> "Flexible Spot M"; 7 -> "Flexible Spot L"; 8 -> "Expand Flex Spot"
    9 -> "Tracking: Wide"; 10 -> "Tracking: Zone"; 11 -> "Tracking: Center"
    12 -> "Tracking: Spot S"; 13 -> "Tracking: Spot M"; 14 -> "Tracking: Spot L"; 15 -> "Tracking: Expand"
    else -> "code $code"
}

// Two encodings exist: legacy 1..15 and Camera Remote SDK style 0x0001..0x0208. A value is read
// in the SDK encoding when the camera reports anything >= 0x0100 or one of its low codes 1/2/3.
private val MOVABLE_AREAS = setOf(5, 6, 7, 8, 12, 13, 14, 15) + setOf(0x0101, 0x0102, 0x0103, 0x0104, 0x0105) + (0x0204..0x0208)
private val ZONE_AREAS = setOf(0x0002, 10, 0x0202)
private val FIXED_AREAS = setOf(0x0001, 0x0003, 4, 9, 11, 0x0201, 0x0203)

/** Flexible Spot M in whichever numbering this camera uses. */
fun spotMFor(current: Int?) = if (current != null && (current >= 0x0100 || current in 1..3)) 0x0102 else 6

fun evaluateReadiness(s: FocusSettings?): AfReadiness {
    if (s == null || (s.focusMode == null && s.focusArea == null)) {
        return AfReadiness(ReadyLevel.UNKNOWN, "AF settings unknown", "Couldn't read Focus Mode / Focus Area from the camera.")
    }
    val mode = focusModeName(s.focusMode)
    // Raw code shown too: Sony numbering differs between generations.
    val area = focusAreaName(s.focusArea) + (s.focusArea?.let { " [0x%04X]".format(it) } ?: "")
    val label = "$mode · $area"

    if (s.focusMode == 1) return AfReadiness(
        ReadyLevel.BLOCKED, label, "Manual focus: the camera ignores AF. Switch Focus Mode to AF-C.", fixMode = true,
    )
    if (s.focusArea in FIXED_AREAS) return AfReadiness(
        ReadyLevel.BLOCKED, label,
        "In $area the camera picks the AF point itself, so phoneAF can't move it. Use Flexible Spot.",
        fixArea = true, fixMode = s.focusMode != 0x8004,
    )
    val notes = mutableListOf<String>()
    var level = ReadyLevel.OK
    if (s.focusArea in ZONE_AREAS) {
        level = ReadyLevel.WARN; notes += "Zone only moves a large block; Flexible Spot is more precise."
    } else if (s.focusArea !in MOVABLE_AREAS) {
        level = ReadyLevel.WARN; notes += "Unrecognised Focus Area ($area)."
    }
    if (s.focusMode == 2) {
        level = ReadyLevel.WARN; notes += "AF-S focuses once per half-press, then locks. Use AF-C to follow moving subjects."
    }
    if (s.focusMode == 0x8005 || s.focusMode == 0x8006) {
        level = ReadyLevel.WARN; notes += "AF-C tracks most reliably."
    }
    return AfReadiness(
        level, label,
        notes.joinToString(" ").ifEmpty { "phoneAF moves the AF point and AF-C keeps the lens on it." },
        fixArea = level != ReadyLevel.OK && s.focusArea !in MOVABLE_AREAS,
        fixMode = s.focusMode != 0x8004,
    )
}
