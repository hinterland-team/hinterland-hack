package com.hinterland.clinic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinterland.clinic.QrMatrix
import kotlinx.coroutines.delay

// the Nova Clinic logo: a small round face with the teal plus of the app icon, and the word mark.
// holding it for 3 s opens the staff menu; a thin ring fills up while the finger stays on it.
@Composable
fun NovaLogo(onLongPress: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    var holding by remember { mutableStateOf(false) }
    val fill = remember { Animatable(0f) }
    val open by rememberUpdatedState(onLongPress)
    LaunchedEffect(holding) {
        if (holding) {
            // nothing shows for a normal tap; the ring only appears for a real hold
            delay(RING_DELAY_MS)
            fill.animateTo(1f, tween((HOLD_MS - RING_DELAY_MS).toInt(), easing = LinearEasing))
        } else {
            fill.snapTo(0f)
        }
    }
    val press = if (onLongPress == null) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                holding = true
                val up = withTimeoutOrNull(HOLD_MS) { waitForUpOrCancellation() }
                holding = false
                if (up == null) {
                    open?.invoke()
                    // swallow the rest of this touch
                    waitForUpOrCancellation()
                }
            }
        }
    }
    Row(modifier.then(press).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            LogoMark(Modifier.size(40.dp))
            val f = fill.value
            if (f > 0f) {
                Canvas(Modifier.size(52.dp)) {
                    val w = 3.dp.toPx()
                    drawArc(
                        Nova.Teal, -90f, 360f * f, false,
                        topLeft = Offset(w / 2f, w / 2f), size = Size(size.width - w, size.height - w),
                        style = Stroke(w, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Text("Nova", style = text(26.sp, FontWeight.Bold, Nova.Ink))
        Spacer(Modifier.width(6.dp))
        Text("Clinic", style = text(26.sp, FontWeight.Normal, Nova.InkSoft))
    }
}

private const val HOLD_MS = 3_000L
private const val RING_DELAY_MS = 450L

@Composable
private fun LogoMark(modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        drawCircle(Nova.Blue, s / 2f)
        // eyes
        for (side in listOf(-1f, 1f)) {
            drawRoundRect(
                Color.White,
                topLeft = Offset(s / 2f + side * s * 0.16f - s * 0.05f, s * 0.34f),
                size = Size(s * 0.10f, s * 0.20f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.05f),
            )
        }
        // smile
        drawArc(
            Color.White, 20f, 140f, false,
            topLeft = Offset(s * 0.34f, s * 0.48f), size = Size(s * 0.32f, s * 0.18f),
            style = Stroke(s * 0.06f, cap = StrokeCap.Round),
        )
        // the teal plus badge
        val c = Offset(s * 0.84f, s * 0.18f)
        drawCircle(Color.White, s * 0.19f, c)
        drawCircle(Nova.Teal, s * 0.15f, c)
        val arm = s * 0.08f
        drawLine(Color.White, c - Offset(arm, 0f), c + Offset(arm, 0f), s * 0.045f, StrokeCap.Round)
        drawLine(Color.White, c - Offset(0f, arm), c + Offset(0f, arm), s * 0.045f, StrokeCap.Round)
    }
}

// a QR code drawn from its modules (made on the robot, Qr.kt). quiet zone = the white around it.
@Composable
fun QrCode(url: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(url) { QrMatrix.encode(url) } ?: return
    Box(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(size / matrix.size * QUIET_MODULES),
    ) {
        Canvas(Modifier.size(size)) {
            val n = matrix.size
            val cell = this.size.minDimension / n
            for (y in 0 until n) {
                var x = 0
                while (x < n) {
                    if (!matrix[x, y]) {
                        x++
                        continue
                    }
                    // one rect per dark run: crisp edges, fewer draw calls
                    var end = x
                    while (end + 1 < n && matrix[end + 1, y]) end++
                    drawRect(Nova.Ink, Offset(x * cell, y * cell), Size((end - x + 1) * cell + 0.5f, cell + 0.5f))
                    x = end + 1
                }
            }
        }
    }
}

private const val QUIET_MODULES = 3

// a small round status dot
@Composable
fun Dot(color: Color, size: Dp = 12.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
