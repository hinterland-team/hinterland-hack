package com.hinterland.clinic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// calm blue palette for a waiting room
object Nova {
    val Ink = Color(0xFF0E2A47)
    val InkSoft = Color(0xFF4F6784)
    val Blue = Color(0xFF2563C9)
    val BlueDeep = Color(0xFF1B4A9E)
    val Sky = Color(0xFF7FB2F5)
    val SkySoft = Color(0xFFDCEAFD)
    val Mist = Color(0xFFE8F1FC)
    val Paper = Color(0xFFF6F9FE)
    val Card = Color(0xFFFFFFFF)
    val Line = Color(0xFFD5E2F3)
    val Done = Color(0xFF1E9A78)
    val Fail = Color(0xFFD2504B)
    val FailSoft = Color(0xFFFCEBEA)
    val Pending = Color(0xFFAFC1D6)
    val Blush = Color(0xFFF6B7C0)
}

@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Nova.Blue,
            onPrimary = Color.White,
            background = Nova.Paper,
            onBackground = Nova.Ink,
            surface = Nova.Card,
            onSurface = Nova.Ink,
            error = Nova.Fail,
        ),
        content = content,
    )
}
