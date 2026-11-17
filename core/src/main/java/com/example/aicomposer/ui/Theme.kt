package com.example.aicomposer.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Palette shared by every app ("field instrument"): a neutral grey surround so the eye can judge the photo's
 * colors, high-luminance text that stays readable in sunlight, and one signal color (yellow, the most legible
 * hue outdoors) reserved for "do this": primary buttons, the current selection and the direction arrows.
 * Status colors are only used for status (severity bars, scores), never for decoration.
 */
object CoachColors {
    // Surfaces (neutral, no tint, so they never bias the photo's white balance)
    val PanelBg = Color(0xFF17181A)
    val StripBg = Color(0xFF1F2023)
    val Card = Color(0xFF232528)
    val Line = Color(0xFF34373B)
    val Chip = Color(0xFF2E3034)

    // Text
    val Paper = Color(0xFFF4F6F2)          // main text, ~16:1 on PanelBg
    val Muted = Color(0xFFB4BCBF)          // secondary text, ~9:1 on PanelBg (replaces Color.Gray)
    val Ink = Color(0xFF141414)            // text on Signal

    // Signal: the one accent
    val Signal = Color(0xFFFFD400)

    // Status
    val Good = Color(0xFF46D39A)
    val Warn = Color(0xFFFF9F43)
    val Bad = Color(0xFFFF5C5C)
    val Tip = Color(0xFF8CCBFF)
    val Ai = Color(0xFFE77BFF)             // the AI framing is described as "purple" in the copy

    /** Kept for existing callers: the accent is the signal color. */
    val Accent = Signal
}

/**
 * Type scale (sp). Larger than Android's defaults because the app is used outdoors at arm's length; nothing
 * the user needs to read is smaller than [Caption].
 */
object CoachType {
    val Caption = 13.sp
    val Body = 15.sp
    val Hint = 17.sp
    val Title = 16.sp
    val Big = 20.sp
    val Score = 30.sp
}

private val Scheme: ColorScheme = darkColorScheme(
    primary = CoachColors.Signal,
    onPrimary = CoachColors.Ink,
    primaryContainer = CoachColors.Signal,
    onPrimaryContainer = CoachColors.Ink,
    secondary = CoachColors.Paper,
    onSecondary = CoachColors.Ink,
    tertiary = CoachColors.Ai,
    background = CoachColors.PanelBg,
    onBackground = CoachColors.Paper,
    surface = CoachColors.PanelBg,
    onSurface = CoachColors.Paper,
    surfaceVariant = CoachColors.Card,
    onSurfaceVariant = CoachColors.Muted,
    surfaceContainerHighest = CoachColors.Chip,
    outline = CoachColors.Muted,
    outlineVariant = CoachColors.Line,
    error = CoachColors.Bad,
    onError = CoachColors.Ink,
)

// No color here: text takes LocalContentColor, so a button's dark-on-yellow content color is not overridden
private val Base = TextStyle()

private val Type = Typography(
    bodyLarge = Base.copy(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = Base.copy(fontSize = CoachType.Body, lineHeight = 21.sp),
    bodySmall = Base.copy(fontSize = CoachType.Caption, lineHeight = 18.sp),
    // Button text
    labelLarge = Base.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = Base.copy(fontSize = CoachType.Caption, fontWeight = FontWeight.Medium),
    titleMedium = Base.copy(fontSize = CoachType.Title, fontWeight = FontWeight.SemiBold),
    titleLarge = Base.copy(fontSize = CoachType.Big, fontWeight = FontWeight.Bold),
)

/** Theme for every app: wrap the root content in this instead of a bare MaterialTheme. */
@Composable
fun AiComposerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}

/**
 * Colors for secondary (outlined) buttons: paper-white text, so signal yellow stays reserved for the main
 * action on each screen ("Shoot", "Pick photos", ...).
 */
@Composable
fun secondaryButtonColors(): androidx.compose.material3.ButtonColors =
    androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = CoachColors.Paper)
