package com.hinterland.clinic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// calm blue and teal for a waiting room. text colors keep at least 7:1 on white, buttons 4.5:1.
object Nova {
    val Ink = Color(0xFF0B2540)
    val InkSoft = Color(0xFF3D5672)
    val InkFaint = Color(0xFF6B819A)
    val Blue = Color(0xFF1D5BBF)
    val BlueDeep = Color(0xFF15468F)
    val Sky = Color(0xFF7FB2F5)
    val SkySoft = Color(0xFFE1EDFC)
    val Teal = Color(0xFF0B7F73)
    val TealSoft = Color(0xFFDDF3EF)
    val Mist = Color(0xFFE9F1F9)
    val Paper = Color(0xFFF6F9FC)
    val Card = Color(0xFFFFFFFF)
    val Line = Color(0xFFD3E0EC)
    val Done = Color(0xFF0B7F73)
    val Amber = Color(0xFF9A4A06)
    val AmberSoft = Color(0xFFFFF1DE)
    val Fail = Color(0xFFB83A34)
    val FailSoft = Color(0xFFFCEBEA)
    val Pending = Color(0xFFAFC1D6)
    val Blush = Color(0xFFF6B7C0)
}

// spacing on an 8 dp grid, in design units (the screen is laid out at 1280 x 720 and scaled)
object Space {
    val xs = 8.dp
    val s = 16.dp
    val m = 24.dp
    val l = 32.dp
    val xl = 48.dp
    val page = 40.dp
    val faceColumn = 352.dp
    val button = 112.dp
    val radius = 28.dp
}

// one text style helper: no font padding, so big numbers sit exactly where the grid says
fun text(
    size: TextUnit,
    weight: FontWeight,
    color: Color,
    lineHeight: TextUnit = TextUnit.Unspecified,
    spacing: TextUnit = TextUnit.Unspecified,
    tabular: Boolean = false,
) = TextStyle(
    fontSize = size,
    fontWeight = weight,
    color = color,
    lineHeight = if (lineHeight == TextUnit.Unspecified) 1.18.em else lineHeight,
    letterSpacing = spacing,
    fontFeatureSettings = if (tabular) "tnum" else null,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

// the type scale, readable from 2 m on the robot's 14" screen
object Type {
    val label = 22.sp
    val caption = 22.sp
    val body = 28.sp
    val bodyLarge = 34.sp
    val button = 34.sp
    val step = 50.sp
    val title = 72.sp
    // long one-line endings ("Goodbye and get well soon")
    val endTitle = 62.sp
    val ticket = 210.sp
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
