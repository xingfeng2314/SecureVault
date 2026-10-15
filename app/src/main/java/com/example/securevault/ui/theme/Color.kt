package com.example.securevault.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val NavyBlue = Color(0xFF0A192F)
val DeepBlue = Color(0xFF112240)
val AccentGold = Color(0xFFD4AF37)
val AccentTeal = Color(0xFF64FFDA)
val SoftGray = Color(0xFF8892B0)
val CardBg = Color(0xFF1D2D50)

val DarkColorScheme = darkColorScheme(
    primary = AccentTeal,
    secondary = AccentGold,
    background = NavyBlue,
    surface = DeepBlue,
    onPrimary = NavyBlue,
    onSecondary = NavyBlue,
    onBackground = Color.White,
    onSurface = Color.White
)

val LightColorScheme = lightColorScheme(
    primary = DeepBlue,
    secondary = AccentGold,
    background = Color(0xFFF8F9FA),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = NavyBlue,
    onSurface = NavyBlue
)
