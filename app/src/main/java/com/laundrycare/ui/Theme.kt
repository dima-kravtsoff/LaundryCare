package com.laundrycare.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun LaundryCareTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = Color(0xFF8FB4FF), secondary = Color(0xFF9CCBB5), tertiary = Color(0xFFF0C68A),
        )
        else -> lightColorScheme(
            primary = Color(0xFF2F6DF6), secondary = Color(0xFF12805C), tertiary = Color(0xFFB4530A),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Semantic colours for notes, independent of the wallpaper-derived palette. */
object Tone {
    @Composable fun warn(): Color = if (isSystemInDarkTheme()) Color(0xFFF0A35B) else Color(0xFF9A4508)
    @Composable fun warnSoft(): Color = if (isSystemInDarkTheme()) Color(0xFF2A2013) else Color(0xFFFDEEDE)
    @Composable fun good(): Color = if (isSystemInDarkTheme()) Color(0xFF4BD3A0) else Color(0xFF12805C)
    @Composable fun goodSoft(): Color = if (isSystemInDarkTheme()) Color(0xFF12271F) else Color(0xFFE3F5EC)
    @Composable fun bad(): Color = if (isSystemInDarkTheme()) Color(0xFFF07070) else Color(0xFFB42626)
    @Composable fun badSoft(): Color = if (isSystemInDarkTheme()) Color(0xFF2A1616) else Color(0xFFFDEAEA)
    @Composable fun info(): Color = if (isSystemInDarkTheme()) Color(0xFF8FB4FF) else Color(0xFF2558C8)
    @Composable fun infoSoft(): Color = if (isSystemInDarkTheme()) Color(0xFF1B2540) else Color(0xFFE8F0FF)
}
