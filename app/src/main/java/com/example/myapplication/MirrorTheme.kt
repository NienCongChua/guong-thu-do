package com.example.myapplication

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val Wine = Color(0xFF6D3D50)
internal val Cream = Color(0xFFFAF7F3)
internal val Ink = Color(0xFF30272C)
internal val Muted = Color(0xFF776C72)
internal val Sage = Color(0xFF496653)
internal val Blush = Color(0xFFF0E3E6)

@Composable
internal fun MirrorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Wine, onPrimary = Color.White,
            primaryContainer = Blush, onPrimaryContainer = Color(0xFF472635),
            secondary = Sage, onSecondary = Color.White,
            secondaryContainer = Color(0xFFE6EDE4), onSecondaryContainer = Color(0xFF284932),
            background = Cream, onBackground = Ink,
            surface = Cream, onSurface = Ink, surfaceVariant = Color(0xFFF0EAE6),
            onSurfaceVariant = Muted, outline = Color(0xFFB9AEB2),
            outlineVariant = Color(0xFFE5DBDE),
            surfaceContainer = Color(0xFFF2ECE8), surfaceContainerLow = Color.White,
            surfaceContainerHigh = Blush
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 38.sp, lineHeight = 44.sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp),
            headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 25.sp, lineHeight = 31.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp)
        ),
        content = content
    )
}
