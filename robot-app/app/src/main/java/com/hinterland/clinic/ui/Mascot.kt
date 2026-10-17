package com.hinterland.clinic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.hinterland.clinic.Mood
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// nova's face: two big round eyes that blink, look up while thinking and smile when done.
// with reduced motion the face stays still and only changes its expression.
@Composable
fun Mascot(mood: Mood, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val blink = remember { Animatable(1f) }
    LaunchedEffect(reduced) {
        while (!reduced) {
            delay(Random.nextLong(2_200, 5_200))
            blink.animateTo(0.08f, tween(70))
            blink.animateTo(1f, tween(120))
            // sometimes a double blink
            if (Random.nextInt(4) == 0) {
                delay(140)
                blink.animateTo(0.08f, tween(70))
                blink.animateTo(1f, tween(120))
            }
        }
    }

    val loop = rememberInfiniteTransition(label = "mascot")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "spin")
    val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "pulse")
    val talk by loop.animateFloat(0.15f, 1f, infiniteRepeatable(tween(170), RepeatMode.Reverse), label = "talk")
    val bob by loop.animateFloat(-1f, 1f, infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val sway by loop.animateFloat(-1f, 1f, infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sway")
    val breathe by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(3_200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    // 0 = no movement at all
    val m = if (reduced) 0f else 1f

    val lookX by animateFloatAsState(if (mood == Mood.THINKING) 0.20f else 0f, tween(450), label = "lookX")
    val lookY by animateFloatAsState(if (mood == Mood.THINKING) -0.20f else 0f, tween(450), label = "lookY")
    val open by animateFloatAsState(
        when (mood) {
            Mood.LISTENING -> 1.12f
            Mood.THINKING -> 0.86f
            Mood.SORRY -> 0.62f
            else -> 1f
        },
        tween(300), label = "open",
    )

    Canvas(modifier) {
        val s = size.minDimension
        val bounce = m * if (mood == Mood.WALKING) bob * s * 0.008f else (breathe - 0.5f) * s * 0.006f
        val c = Offset(size.width / 2f, size.height / 2f + bounce)
        val r = s * 0.40f

        // listening: soft rings going out
        if (mood == Mood.LISTENING) {
            for (i in 0..1) {
                val p = ((if (reduced) 0.3f else pulse) + i * 0.5f) % 1f
                drawCircle(
                    color = Nova.Sky.copy(alpha = (1f - p) * 0.5f),
                    radius = r * (1.04f + 0.18f * p),
                    center = c,
                    style = Stroke(width = s * 0.010f),
                )
            }
        }

        // calm blue aura, shadow, face
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Nova.Sky.copy(alpha = 0.30f), Nova.Sky.copy(alpha = 0f)),
                center = c,
                radius = r * 1.30f,
            ),
            radius = r * 1.30f,
            center = c,
        )
        drawCircle(Nova.BlueDeep.copy(alpha = 0.10f), radius = r * 1.01f, center = c + Offset(0f, s * 0.03f))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Nova.SkySoft),
                center = c + Offset(-r * 0.35f, -r * 0.45f),
                radius = r * 1.7f,
            ),
            radius = r,
            center = c,
        )
        drawCircle(Nova.Line, radius = r, center = c, style = Stroke(width = s * 0.006f))

        // eyes
        val eyeW = s * 0.105f
        val eyeH = s * 0.165f * open * blink.value
        val dx = r * 0.36f
        val lookOffset = Offset(
            (lookX + if (mood == Mood.WALKING) sway * 0.10f * m else 0f) * r * 0.5f,
            lookY * r * 0.5f,
        )
        for (side in listOf(-1f, 1f)) {
            val ec = Offset(c.x + side * dx, c.y - r * 0.10f) + lookOffset
            if (mood == Mood.HAPPY) {
                // closed happy eyes ^ ^
                drawArc(
                    color = Nova.Ink,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(ec.x - eyeW * 0.7f, ec.y - eyeW * 0.35f),
                    size = Size(eyeW * 1.4f, eyeW * 1.2f),
                    style = Stroke(width = eyeW * 0.32f, cap = StrokeCap.Round),
                )
            } else {
                val h = eyeH.coerceAtLeast(s * 0.012f)
                drawRoundRect(
                    color = Nova.Ink,
                    topLeft = Offset(ec.x - eyeW / 2f, ec.y - h / 2f),
                    size = Size(eyeW, h),
                    cornerRadius = CornerRadius(eyeW / 2f, eyeW / 2f),
                )
                if (blink.value > 0.5f) {
                    drawCircle(
                        Color.White.copy(alpha = 0.92f),
                        radius = eyeW * 0.17f,
                        center = Offset(ec.x + eyeW * 0.16f, ec.y - h * 0.22f),
                    )
                }
            }
        }

        // cheeks
        for (side in listOf(-1f, 1f)) {
            drawCircle(
                Nova.Blush.copy(alpha = 0.35f),
                radius = s * 0.045f,
                center = Offset(c.x + side * r * 0.62f, c.y + r * 0.20f),
            )
        }

        // mouth
        val mouthY = c.y + r * 0.30f
        when (mood) {
            Mood.SPEAKING -> {
                val w = s * 0.10f
                val h = s * (0.022f + 0.050f * (if (reduced) 0.5f else talk))
                drawRoundRect(
                    color = Nova.Ink,
                    topLeft = Offset(c.x - w / 2f, mouthY - h / 2f),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w / 2f, w / 2f),
                )
            }
            Mood.SORRY -> drawArc(
                color = Nova.Ink,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(c.x - s * 0.07f, mouthY - s * 0.005f),
                size = Size(s * 0.14f, s * 0.08f),
                style = Stroke(width = s * 0.018f, cap = StrokeCap.Round),
            )
            Mood.THINKING -> drawLine(
                color = Nova.Ink,
                start = Offset(c.x - s * 0.035f, mouthY + s * 0.01f),
                end = Offset(c.x + s * 0.045f, mouthY - s * 0.002f),
                strokeWidth = s * 0.018f,
                cap = StrokeCap.Round,
            )
            else -> {
                val w = if (mood == Mood.HAPPY) s * 0.18f else s * 0.14f
                drawArc(
                    color = Nova.Ink,
                    startAngle = 20f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(c.x - w / 2f, mouthY - w * 0.42f),
                    size = Size(w, w * 0.62f),
                    style = Stroke(width = s * 0.018f, cap = StrokeCap.Round),
                )
            }
        }

        // thinking: three dots orbiting above the head
        if (mood == Mood.THINKING) {
            for (i in 0 until 3) {
                val a = Math.toRadians((spin * m + i * 26f - 90f).toDouble())
                val orbit = r * 1.16f
                drawCircle(
                    color = Nova.Blue.copy(alpha = 0.35f + 0.25f * i),
                    radius = s * (0.015f + 0.005f * i),
                    center = Offset(c.x + (orbit * cos(a)).toFloat(), c.y + (orbit * sin(a)).toFloat()),
                )
            }
        }
    }
}
