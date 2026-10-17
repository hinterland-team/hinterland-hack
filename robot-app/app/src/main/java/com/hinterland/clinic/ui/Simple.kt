package com.hinterland.clinic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sin

// big, calm pieces for the patient screens, many patients are older: one message, one picture,
// big buttons. patient text is never below 30 sp, and nothing says more than one short line.
object Big {
    val headline = 64.sp
    val line = 34.sp
    val label = 30.sp
    val button = 128.dp
}

@Composable
fun Headline(value: String, color: Color = Nova.Ink, maxLines: Int = 2) {
    Text(value, style = text(Big.headline, FontWeight.Bold, color, lineHeight = 72.sp), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

// one short line under the headline
@Composable
fun Say(value: String, color: Color = Nova.InkSoft, maxLines: Int = 2) {
    Text(value, style = text(Big.line, FontWeight.Medium, color, lineHeight = 42.sp), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

// one-message screens: a picture, the headline, one line, maybe one button at the bottom
@Composable
fun Message(
    title: String,
    line: String?,
    picture: @Composable () -> Unit,
    bottom: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        Spacer(Modifier.weight(1f))
        picture()
        Spacer(Modifier.height(Space.m))
        Headline(title)
        line?.let {
            Spacer(Modifier.height(Space.xs))
            Say(it)
        }
        Spacer(Modifier.weight(1f))
        bottom?.invoke()
    }
}

// a big round icon; pulse = soft rings going out, to draw the eye
@Composable
fun IconBadge(glyph: GlyphKind, fg: Color, bg: Color, size: Dp = 168.dp, pulse: Boolean = false) {
    val reduced = LocalReducedMotion.current
    // read while drawing only (v3.3): the rings redraw, the badge does not recompose every frame
    val ring = if (pulse && !reduced) {
        val loop = rememberInfiniteTransition(label = "badge")
        loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1_800, easing = LinearEasing)), label = "ring")
    } else {
        null
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (ring != null) {
            Canvas(Modifier.size(size)) {
                for (i in 0..1) {
                    val q = (ring.value + i * 0.5f) % 1f
                    drawCircle(fg.copy(alpha = (1f - q) * 0.25f), this.size.minDimension / 2f * (0.7f + 0.3f * q))
                }
            }
        }
        Box(Modifier.size(size * 0.78f).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Glyph(glyph, fg, size * 0.42f)
        }
    }
}

// a tall button with a big picture and one word, for choices side by side
@Composable
fun RowScope.PictureButton(label: String, glyph: GlyphKind, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Space.radius)
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(shape)
            .background(Nova.Card)
            .border(3.dp, Nova.Line, shape)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(112.dp).clip(CircleShape).background(Nova.SkySoft), contentAlignment = Alignment.Center) {
            Glyph(glyph, Nova.Blue, 72.dp)
        }
        Spacer(Modifier.height(Space.m))
        Text(label, style = text(36.sp, FontWeight.Bold, Nova.Ink), maxLines = 1)
    }
}

// "just talk to me": a mic with sound waves going out
@Composable
fun TalkHint(label: String = "Or just talk to me") {
    val reduced = LocalReducedMotion.current
    // read while drawing only (v3.3): the waves redraw, the row does not recompose every frame
    val wave = if (reduced) {
        null
    } else {
        val loop = rememberInfiniteTransition(label = "talkHint")
        loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "wave")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 112.dp, height = 72.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Nova.SkySoft), contentAlignment = Alignment.Center) {
                Glyph(GlyphKind.MIC, Nova.Blue, 40.dp)
            }
            Canvas(Modifier.size(width = 112.dp, height = 72.dp)) {
                val c = Offset(36.dp.toPx(), size.height / 2f)
                for (i in 0..2) {
                    val q = ((wave?.value ?: 0.5f) + i / 3f) % 1f
                    val r = 40.dp.toPx() + q * 28.dp.toPx()
                    drawArc(
                        Nova.Blue.copy(alpha = (1f - q) * 0.8f), -40f, 80f, false,
                        topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2f, r * 2f),
                        style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
        Text(label, style = text(Big.line, FontWeight.SemiBold, Nova.Blue), maxLines = 1)
    }
}

// ---- the way from A to B ----

// the road has to stand out on the light page
private val Road = Color(0xFFCADCF2)
private val ROUTE_HEIGHT = 210.dp

// a wide road from A (where we are) to B (the pin), arrows flowing towards B all the time,
// otto's little face on the way. progress null = otto can't tell how far: only the arrows move.
@Composable
fun RouteMap(
    from: String,
    progress: Float?,
    modifier: Modifier = Modifier,
    arrived: Boolean = false,
    accent: Color = Nova.Blue,
) {
    val reduced = LocalReducedMotion.current
    val loop = rememberInfiniteTransition(label = "route")
    val flow by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1_000, easing = LinearEasing)), label = "flow")
    val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1_800, easing = LinearEasing)), label = "pulse")
    val bob by loop.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(900, easing = LinearEasing)), label = "bob")
    val p by animateFloatAsState(if (arrived) 1f else progress ?: 0f, calm(900, reduced), label = "progress")
    val showOtto = arrived || progress != null
    val pin = if (arrived) Nova.Teal else accent

    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(ROUTE_HEIGHT)) {
            val road = 36.dp.toPx()
            val pinR = 30.dp.toPx()
            val a = Offset(road * 0.9f, size.height * 0.78f)
            val b = Offset(size.width - pinR * 1.4f, size.height * 0.66f)
            val path = Path().apply {
                moveTo(a.x, a.y)
                cubicTo(a.x + size.width * 0.32f, a.y + 6.dp.toPx(), b.x - size.width * 0.32f, b.y - 40.dp.toPx(), b.x, b.y)
            }
            val m = PathMeasure().apply { setPath(path, false) }
            val len = m.length

            drawPath(path, Road, style = Stroke(road, cap = StrokeCap.Round))
            if (p > 0f) {
                val done = Path()
                m.getSegment(0f, len * p, done, true)
                drawPath(done, Nova.Teal.copy(alpha = 0.25f), style = Stroke(road, cap = StrokeCap.Round))
            }

            // arrows along the road, always flowing towards B
            val gap = 54.dp.toPx()
            var d = (if (reduced) 0.5f else flow) * gap + road * 0.6f
            while (d < len - pinR * 0.9f) {
                val pos = m.getPosition(d)
                val tan = m.getTangent(d)
                val angle = atan2(tan.y, tan.x) * 180f / PI.toFloat()
                // arrows fade in at the start, so they seem to come out of A
                val fadeIn = ((d - road * 0.6f) / gap).coerceIn(0f, 1f)
                rotate(angle, pos) { chevron(pos, 11.dp.toPx(), if (d <= len * p) Nova.Teal else accent, fadeIn) }
                d += gap
            }

            // A: where we start
            drawCircle(Color.White, 20.dp.toPx(), a)
            drawCircle(Nova.Teal, 20.dp.toPx(), a, style = Stroke(5.dp.toPx()))
            drawCircle(Nova.Teal, 8.dp.toPx(), a)

            // B: soft rings on the floor, then the pin
            if (!reduced && !arrived) {
                for (i in 0..1) {
                    val q = (pulse + i * 0.5f) % 1f
                    val w = pinR * (1.2f + 2.4f * q)
                    drawOval(pin.copy(alpha = (1f - q) * 0.35f), Offset(b.x - w, b.y - w * 0.32f), Size(w * 2f, w * 0.64f), style = Stroke(3.dp.toPx()))
                }
            }
            mapPin(b, pinR, pin, arrived)

            if (showOtto) {
                val pos = m.getPosition((len * p).coerceIn(0f, len))
                val hop = if (reduced || arrived) 0f else sin(bob) * 3.dp.toPx()
                ottoDot(pos + Offset(0f, -road * 0.15f + hop), 24.dp.toPx())
            }
        }
        Text(from, style = text(Big.label, FontWeight.Medium, Nova.InkSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun DrawScope.chevron(c: Offset, s: Float, color: Color, alpha: Float) {
    val path = Path().apply {
        moveTo(c.x - s * 0.55f, c.y - s)
        lineTo(c.x + s * 0.45f, c.y)
        lineTo(c.x - s * 0.55f, c.y + s)
    }
    drawPath(path, color, alpha = alpha, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// a map pin standing on its tip; arrived = a check inside
private fun DrawScope.mapPin(tip: Offset, r: Float, color: Color, arrived: Boolean) {
    val head = tip - Offset(0f, r * 1.7f)
    val body = Path().apply {
        moveTo(head.x - r * 0.72f, head.y + r * 0.7f)
        lineTo(tip.x, tip.y)
        lineTo(head.x + r * 0.72f, head.y + r * 0.7f)
        close()
    }
    drawPath(body, color)
    drawCircle(color, r, head)
    if (arrived) {
        val check = Path().apply {
            moveTo(head.x - r * 0.42f, head.y + r * 0.02f)
            lineTo(head.x - r * 0.1f, head.y + r * 0.32f)
            lineTo(head.x + r * 0.45f, head.y - r * 0.28f)
        }
        drawPath(check, Color.White, style = Stroke(r * 0.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    } else {
        drawCircle(Color.White, r * 0.42f, head)
    }
}

// otto's little face, like the logo: a blue circle with two eyes and a smile
private fun DrawScope.ottoDot(c: Offset, r: Float) {
    drawCircle(Color.White, r * 1.18f, c)
    drawCircle(Nova.Blue, r, c)
    for (side in listOf(-1f, 1f)) {
        drawRoundRect(
            Color.White,
            topLeft = Offset(c.x + side * r * 0.36f - r * 0.1f, c.y - r * 0.42f),
            size = Size(r * 0.2f, r * 0.42f),
            cornerRadius = CornerRadius(r * 0.1f),
        )
    }
    drawArc(Color.White, 20f, 140f, false, Offset(c.x - r * 0.4f, c.y - r * 0.15f), Size(r * 0.8f, r * 0.6f), style = Stroke(r * 0.12f, cap = StrokeCap.Round))
}

// ---- arrived: a check that draws itself ----

@Composable
fun ArrivedCheck(size: Dp = 132.dp) {
    val reduced = LocalReducedMotion.current
    val grow = remember { Animatable(if (reduced) 1f else 0f) }
    val draw = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        grow.animateTo(1f, spring(0.5f, Spring.StiffnessMediumLow))
        draw.animateTo(1f, tween(380))
    }
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        drawCircle(Nova.TealSoft, w / 2f * grow.value)
        drawCircle(Nova.Teal, w / 2f * 0.8f * grow.value)
        val check = Path().apply {
            moveTo(w * 0.3f, w * 0.52f)
            lineTo(w * 0.45f, w * 0.66f)
            lineTo(w * 0.71f, w * 0.38f)
        }
        val m = PathMeasure().apply { setPath(check, false) }
        val seg = Path()
        m.getSegment(0f, m.length * draw.value, seg, true)
        drawPath(seg, Color.White, style = Stroke(w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// a calm turning ring for "one moment"
@Composable
fun Spinner(size: Dp = 96.dp) {
    val reduced = LocalReducedMotion.current
    // read while drawing only (v3.3)
    val spin = if (reduced) {
        null
    } else {
        val loop = rememberInfiniteTransition(label = "spinner")
        loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1_200, easing = LinearEasing)), label = "spin")
    }
    Canvas(Modifier.size(size)) {
        val w = 9.dp.toPx()
        drawCircle(Nova.SkySoft, this.size.minDimension / 2f - w / 2f, style = Stroke(w))
        drawArc(Nova.Blue, spin?.value ?: 0f, 100f, false, Offset(w / 2f, w / 2f), Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

// answers next to each other, full width, big
@Composable
fun Buttons(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(Big.button), horizontalArrangement = Arrangement.spacedBy(Space.s), content = content)
}
