package dev.transferflow.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF086B60),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFBDF0E5),
        onPrimaryContainer = Color(0xFF00382F),
        secondary = Color(0xFF52606D),
        secondaryContainer = Color(0xFFE4EBF0),
        background = Color(0xFFF5F7F9),
        onBackground = Color(0xFF142438),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF142438),
        surfaceVariant = Color(0xFFEAF0F3),
        onSurfaceVariant = Color(0xFF52606D),
        outline = Color(0xFF71818D),
        error = Color(0xFFB3261E),
    )
private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF70D9C7),
        onPrimary = Color(0xFF00382F),
        primaryContainer = Color(0xFF075046),
        onPrimaryContainer = Color(0xFFBDF0E5),
        secondary = Color(0xFFBBC8D3),
        secondaryContainer = Color(0xFF314151),
        background = Color(0xFF101923),
        onBackground = Color(0xFFE3EAF0),
        surface = Color(0xFF182430),
        onSurface = Color(0xFFE3EAF0),
        surfaceVariant = Color(0xFF243442),
        onSurfaceVariant = Color(0xFFBBC8D3),
        outline = Color(0xFF899BAA),
    )
private val TransferTypography =
    Typography(
        headlineLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 39.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 27.sp,
                lineHeight = 34.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 21.sp,
                lineHeight = 28.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 25.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 1.sp,
            ),
    )

@Composable
fun TransferFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = TransferTypography,
        content = content,
    )
}
