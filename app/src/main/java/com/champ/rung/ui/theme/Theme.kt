package com.champ.rung.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Felt = Color(0xFF0E3B2E)
val FeltDark = Color(0xFF0A2E24)
val FeltLight = Color(0xFF155741)
val Gold = Color(0xFFD9B45B)
val GoldBright = Color(0xFFEACA77)
val Cream = Color(0xFFF5EFE0)
val Ink = Color(0xFF1A1A1A)
val RedSuit = Color(0xFFD6555A)
val BlackSuit = Color(0xFF1A1A1A)
val DangerRed = Color(0xFFE07856)

private val RungColors = darkColorScheme(
    primary = Gold,
    onPrimary = Ink,
    secondary = GoldBright,
    onSecondary = Ink,
    background = FeltDark,
    onBackground = Cream,
    surface = Felt,
    onSurface = Cream,
    surfaceVariant = FeltLight,
    onSurfaceVariant = Cream,
    error = DangerRed,
    onError = Ink
)

val DisplayFont = FontFamily.Serif

private val RungTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp
    )
)

@Composable
fun RungTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RungColors,
        typography = RungTypography,
        content = content
    )
}
