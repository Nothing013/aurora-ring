package dev.aurora.ring.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Aurora {
    val Bg = Color(0xFF05060B)
    val BgElevated = Color(0xFF0A0D16)
    val Surface = Color(0xFF10141F)
    val SurfaceHigh = Color(0xFF171C2B)
    val Border = Color(0x1FFFFFFF)
    val Text = Color(0xFFF2F4FF)
    val TextDim = Color(0xFF9AA3BD)
    val Primary = Color(0xFF8B7CFF)
    val Mint = Color(0xFF00E5C0)
    val Pink = Color(0xFFFF4D8D)
    val Amber = Color(0xFFFFC46B)
    val Danger = Color(0xFFFF5A6E)
    val Gradient = listOf(Color(0xFF8B7CFF), Color(0xFF00E5C0))
    val GradientWarm = listOf(Color(0xFFFF4D8D), Color(0xFFFFC46B))
}

private val scheme = darkColorScheme(
    primary = Aurora.Primary,
    onPrimary = Color(0xFF0A0714),
    secondary = Aurora.Mint,
    onSecondary = Color(0xFF00140F),
    tertiary = Aurora.Pink,
    background = Aurora.Bg,
    onBackground = Aurora.Text,
    surface = Aurora.Surface,
    onSurface = Aurora.Text,
    surfaceVariant = Aurora.SurfaceHigh,
    onSurfaceVariant = Aurora.TextDim,
    outline = Aurora.Border,
    error = Aurora.Danger
)

private val typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun AuroraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(26.dp),
            extraLarge = RoundedCornerShape(34.dp)
        ),
        content = content
    )
}
