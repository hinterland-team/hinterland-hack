package com.hinterland.clinic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.hinterland.clinic.Mood
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private object Doctor {
    val Backdrop = Color(0xFFD4ECFA)
    val BackdropEdge = Color(0xFF9FCBF0)
    val Helmet = Color(0xFFFEFEFF)
    val HelmetShade = Color(0xFFE4EDF6)
    val Frame = Color(0xFF1A3552)
    val Eye = Color(0xFF2E9BFF)
    val EyeCore = Color(0xFF7FD4FF)
    val EyeHot = Color(0xFFF4FCFF)
    val Smile = Color(0xFF17324C)
    val Coat = Color(0xFFF8FBFE)
    val CoatShade = Color(0xFFDCE7F2)
    val Scrub = Color(0xFF3184F0)
    val ScrubDeep = Color(0xFF1C62C8)
    val Tube = Color(0xFF2B73DE)
    val Metal = Color(0xFFD5DEE8)
    val MetalMid = Color(0xFFB7C4D2)
    val MetalDark = Color(0xFF7F95AB)
    val Ear = Color(0xFFF7FBFE)
    val EarInner = Color(0xFF8FA4BA)
}

@Composable
fun Mascot(mood: Mood, modifier: Modifier = Modifier) {
    val blink = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2_200, 5_200))
            blink.animateTo(0.08f, tween(70))
            blink.animateTo(1f, tween(120))
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

    val lookX by animateFloatAsState(if (mood == Mood.THINKING) 0.18f else 0f, tween(450), label = "lookX")
    val lookY by animateFloatAsState(if (mood == Mood.THINKING) -0.22f else 0f, tween(450), label = "lookY")
    val open by animateFloatAsState(
        when (mood) {
            Mood.LISTENING -> 1.12f
            Mood.THINKING -> 0.82f
            Mood.SORRY -> 0.7f
            else -> 1f
        },
        tween(300),
        label = "open",
    )

    Canvas(modifier) {
        val s = size.minDimension
        val bounce = if (mood == Mood.WALKING) bob * s * 0.012f else (breathe - 0.5f) * s * 0.008f
        val swayX = if (mood == Mood.WALKING) sway * s * 0.01f else 0f
        val headR = s * 0.162f
        val head = Offset(size.width / 2f + swayX, size.height / 2f - s * 0.04f + bounce)
        val cheerful = mood == Mood.IDLE || mood == Mood.HAPPY || mood == Mood.WALKING
        val glow = 0.85f + 0.15f * breathe

        if (mood == Mood.LISTENING) {
            val ringC = head + Offset(0f, headR * 0.15f)
            for (i in 0..1) {
                val p = (pulse + i * 0.5f) % 1f
                drawCircle(
                    color = Doctor.Eye.copy(alpha = (1f - p) * 0.35f),
                    radius = headR * (2.05f + 0.45f * p),
                    center = ringC,
                    style = Stroke(width = s * 0.008f),
                )
            }
        }

        val backdrop = head + Offset(0f, headR * 0.12f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFF3FAFE), Doctor.Backdrop, Doctor.BackdropEdge),
                center = backdrop + Offset(-headR * 0.2f, -headR * 0.3f),
                radius = headR * 2.4f,
            ),
            radius = headR * 2.15f,
            center = backdrop,
        )

        drawCoat(head, headR)
        drawStethoscope(head, headR)
        drawEar(Offset(head.x - headR * 1.08f, head.y + headR * 0.02f), headR * 0.34f)
        drawEar(Offset(head.x + headR * 1.08f, head.y + headR * 0.02f), headR * 0.34f)
        drawHelmet(head, headR)
        drawMirror(Offset(head.x + headR * 0.02f, head.y - headR * 0.62f), headR * 0.26f)

        val look = Offset(lookX * headR * 0.45f, lookY * headR * 0.45f)
        val eyeY = head.y + headR * 0.02f
        val lensR = headR * 0.34f
        val left = Offset(head.x - headR * 0.36f, eyeY) + look
        val right = Offset(head.x + headR * 0.36f, eyeY) + look
        drawFace(left, right, lensR, head, headR, cheerful, open, blink.value, glow, mood, talk)

        if (mood == Mood.THINKING) {
            for (i in 0 until 3) {
                val a = Math.toRadians((spin + i * 22f - 100f).toDouble())
                drawCircle(
                    color = Doctor.Eye.copy(alpha = 0.45f + 0.18f * i),
                    radius = s * (0.012f + 0.004f * i),
                    center = Offset(
                        head.x + (headR * 1.55f * cos(a)).toFloat(),
                        head.y - headR * 1.15f + (headR * 0.28f * sin(a)).toFloat(),
                    ),
                )
            }
        }
    }
}

private fun DrawScope.drawCoat(head: Offset, headR: Float) {
    val neck = head.y + headR * 0.58f
    val shoulder = head.y + headR * 1.05f
    val bottom = head.y + headR * 2.35f
    val side = headR * 1.42f
    drawCircle(Doctor.CoatShade.copy(alpha = 0.45f), headR * 1.2f, Offset(head.x, shoulder + headR * 0.35f))
    val coat = Path().apply {
        moveTo(head.x - headR * 0.42f, neck)
        cubicTo(
            head.x - headR * 0.95f, neck + headR * 0.02f,
            head.x - side, shoulder - headR * 0.35f,
            head.x - side, shoulder + headR * 0.15f,
        )
        cubicTo(
            head.x - side * 1.02f, bottom - headR * 0.55f,
            head.x - side * 0.72f, bottom,
            head.x, bottom,
        )
        cubicTo(
            head.x + side * 0.72f, bottom,
            head.x + side * 1.02f, bottom - headR * 0.55f,
            head.x + side, shoulder + headR * 0.15f,
        )
        cubicTo(
            head.x + side, shoulder - headR * 0.35f,
            head.x + headR * 0.95f, neck + headR * 0.02f,
            head.x + headR * 0.42f, neck,
        )
        close()
    }
    drawPath(
        coat,
        Brush.verticalGradient(
            colors = listOf(Doctor.Coat, Color(0xFFF3F8FC), Doctor.CoatShade),
            startY = neck,
            endY = bottom,
        ),
    )
    drawPath(coat, Doctor.CoatShade, style = Stroke(width = headR * 0.03f, join = StrokeJoin.Round))
    val scrub = Path().apply {
        moveTo(head.x - headR * 0.36f, neck - headR * 0.04f)
        quadraticTo(head.x - headR * 0.12f, neck + headR * 0.42f, head.x, neck + headR * 0.62f)
        quadraticTo(head.x + headR * 0.12f, neck + headR * 0.42f, head.x + headR * 0.36f, neck - headR * 0.04f)
        close()
    }
    drawPath(
        scrub,
        Brush.verticalGradient(
            colors = listOf(Doctor.Scrub, Doctor.ScrubDeep),
            startY = neck,
            endY = neck + headR * 0.7f,
        ),
    )
}

private fun DrawScope.drawStethoscope(head: Offset, headR: Float) {
    val top = head.y + headR * 0.7f
    val piece = Offset(head.x - headR * 0.62f, top + headR * 1.15f)
    val tube = Path().apply {
        moveTo(head.x + headR * 0.38f, top)
        cubicTo(
            head.x + headR * 0.05f, top - headR * 0.22f,
            head.x - headR * 0.35f, top + headR * 0.05f,
            head.x - headR * 0.72f, top + headR * 0.48f,
        )
        cubicTo(
            head.x - headR * 1.15f, top + headR * 0.95f,
            head.x - headR * 1.05f, piece.y,
            piece.x,
            piece.y - headR * 0.08f,
        )
    }
    drawPath(
        tube,
        Doctor.Tube,
        style = Stroke(width = headR * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawCircle(Doctor.Metal, headR * 0.2f, piece)
    drawCircle(Doctor.MetalDark, headR * 0.2f, piece, style = Stroke(width = headR * 0.03f))
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White, Doctor.Metal, Doctor.MetalMid),
            center = piece + Offset(-headR * 0.04f, -headR * 0.05f),
            radius = headR * 0.2f,
        ),
        radius = headR * 0.12f,
        center = piece,
    )
}

private fun DrawScope.drawEar(center: Offset, radius: Float) {
    drawCircle(Doctor.Ear, radius, center)
    drawCircle(Doctor.MetalMid, radius, center, style = Stroke(width = radius * 0.14f))
    drawCircle(Doctor.EarInner, radius * 0.52f, center)
    drawCircle(Color.White.copy(alpha = 0.85f), radius * 0.16f, center + Offset(-radius * 0.16f, -radius * 0.18f))
}

private fun DrawScope.drawHelmet(head: Offset, headR: Float) {
    drawCircle(Doctor.CoatShade.copy(alpha = 0.7f), headR * 1.02f, head + Offset(0f, headR * 0.08f))
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Doctor.Helmet, Color(0xFFF5F9FC), Doctor.HelmetShade),
            center = head + Offset(-headR * 0.28f, -headR * 0.38f),
            radius = headR * 1.8f,
        ),
        radius = headR,
        center = head,
    )
    drawCircle(Doctor.CoatShade, headR, head, style = Stroke(width = headR * 0.035f))
    drawCircle(
        Color.White.copy(alpha = 0.7f),
        headR * 0.16f,
        head + Offset(-headR * 0.38f, -headR * 0.42f),
    )
}

private fun DrawScope.drawMirror(center: Offset, radius: Float) {
    drawRoundRect(
        color = Doctor.MetalMid,
        topLeft = Offset(center.x - radius * 0.22f, center.y - radius * 1.15f),
        size = Size(radius * 0.44f, radius * 0.7f),
        cornerRadius = CornerRadius(radius * 0.22f, radius * 0.22f),
    )
    drawCircle(Doctor.MetalDark.copy(alpha = 0.25f), radius * 1.05f, center + Offset(0f, radius * 0.08f))
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White, Doctor.Metal, Doctor.MetalDark),
            center = center + Offset(-radius * 0.28f, -radius * 0.32f),
            radius = radius * 1.5f,
        ),
        radius = radius,
        center = center,
    )
    drawCircle(Doctor.Frame.copy(alpha = 0.55f), radius, center, style = Stroke(width = radius * 0.1f))
    drawCircle(Doctor.EyeHot, radius * 0.42f, center)
    drawCircle(Color.White, radius * 0.16f, center + Offset(-radius * 0.18f, -radius * 0.2f))
}

private fun DrawScope.drawFace(
    left: Offset,
    right: Offset,
    lensR: Float,
    head: Offset,
    headR: Float,
    cheerful: Boolean,
    open: Float,
    blink: Float,
    glow: Float,
    mood: Mood,
    talk: Float,
) {
    val frame = headR * 0.045f
    for (eye in listOf(left, right)) {
        drawCircle(Color.White.copy(alpha = 0.72f), lensR * 0.92f, eye)
        if (cheerful) drawSmileEye(eye, lensR, blink, glow) else drawOpenEye(eye, lensR, open, blink, glow)
    }
    drawCircle(Doctor.Frame, lensR, left, style = Stroke(width = frame))
    drawCircle(Doctor.Frame, lensR, right, style = Stroke(width = frame))
    drawLine(
        Doctor.Frame,
        Offset(left.x + lensR * 0.82f, left.y - lensR * 0.04f),
        Offset(right.x - lensR * 0.82f, right.y - lensR * 0.04f),
        frame,
        StrokeCap.Round,
    )
    drawLine(
        Doctor.Frame,
        Offset(left.x - lensR * 0.92f, left.y),
        Offset(left.x - lensR * 1.35f, left.y + headR * 0.06f),
        frame,
        StrokeCap.Round,
    )
    drawLine(
        Doctor.Frame,
        Offset(right.x + lensR * 0.92f, right.y),
        Offset(right.x + lensR * 1.35f, right.y + headR * 0.06f),
        frame,
        StrokeCap.Round,
    )
    drawMouth(head, headR, mood, talk)
}

private fun DrawScope.drawSmileEye(center: Offset, lensR: Float, blink: Float, glow: Float) {
    val band = (lensR * 0.5f * blink).coerceAtLeast(lensR * 0.07f)
    val box = Size(lensR * 1.35f, lensR * 0.95f)
    val top = Offset(center.x - box.width / 2f, center.y - box.height * 0.15f)
    drawArc(
        color = Doctor.Eye.copy(alpha = 0.28f * glow),
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = top,
        size = box,
        style = Stroke(width = band * 1.8f, cap = StrokeCap.Round),
    )
    drawArc(
        color = Doctor.Eye.copy(alpha = glow),
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = top,
        size = box,
        style = Stroke(width = band, cap = StrokeCap.Round),
    )
    drawArc(
        color = Doctor.EyeHot,
        startAngle = 210f,
        sweepAngle = 70f,
        useCenter = false,
        topLeft = top + Offset(box.width * 0.08f, box.height * 0.02f),
        size = Size(box.width * 0.7f, box.height * 0.7f),
        style = Stroke(width = band * 0.35f, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawOpenEye(center: Offset, lensR: Float, open: Float, blink: Float, glow: Float) {
    val h = (lensR * 0.72f * open * blink).coerceAtLeast(lensR * 0.08f)
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(Doctor.EyeHot, Doctor.EyeCore, Doctor.Eye),
            center = center + Offset(-lensR * 0.08f, -h * 0.1f),
            radius = lensR * 0.7f,
        ),
        topLeft = Offset(center.x - lensR * 0.42f, center.y - h / 2f),
        size = Size(lensR * 0.84f, h),
    )
    if (blink > 0.45f) {
        drawCircle(Color.White.copy(alpha = 0.95f * glow), lensR * 0.12f, center + Offset(lensR * 0.12f, -h * 0.18f))
    }
}

private fun DrawScope.drawMouth(head: Offset, headR: Float, mood: Mood, talk: Float) {
    val y = head.y + headR * 0.46f
    val stroke = headR * 0.07f
    when (mood) {
        Mood.SPEAKING -> {
            val w = headR * 0.34f
            val h = headR * (0.08f + 0.16f * talk)
            drawRoundRect(
                color = Doctor.Smile,
                topLeft = Offset(head.x - w / 2f, y - h / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
        }
        Mood.SORRY -> drawArc(
            color = Doctor.Smile,
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(head.x - headR * 0.28f, y - headR * 0.02f),
            size = Size(headR * 0.56f, headR * 0.36f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        Mood.THINKING -> drawLine(
            color = Doctor.Smile,
            start = Offset(head.x - headR * 0.12f, y + headR * 0.02f),
            end = Offset(head.x + headR * 0.16f, y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        else -> {
            val w = if (mood == Mood.HAPPY) headR * 0.62f else headR * 0.5f
            drawArc(
                color = Doctor.Smile,
                startAngle = 18f,
                sweepAngle = 144f,
                useCenter = false,
                topLeft = Offset(head.x - w / 2f, y - w * 0.28f),
                size = Size(w, w * 0.62f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
