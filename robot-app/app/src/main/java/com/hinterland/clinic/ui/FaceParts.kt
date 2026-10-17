package com.hinterland.clinic.ui

import androidx.annotation.DrawableRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.hinterland.clinic.Mood
import com.hinterland.clinic.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// nova's face rig. the doctor art is split into a clean bust (doctor_body) and glowing parts
// (doctor_eye_*, doctor_brow_*, doctor_mouth_*) added on top with BlendMode.Plus, so each part can move,
// squash and fade on its own. body + parts in their places = the original art.
// all numbers are pixels of doctor_body (818 x 712).
internal object Rig {
    const val WIDTH = 818f
    const val HEIGHT = 712f
    // lens centres; the eye glyphs sit about 8 px above them
    val EyeL = Offset(290f, 359f)
    val EyeR = Offset(525f, 359f)
    val BrowL = Offset(290f, 258f)
    val BrowR = Offset(528f, 258f)
    val Mouth = Offset(407f, 454f)
    // half sizes of the part images, they are drawn centred
    val EyeHalf = Offset(75f, 70f)
    val BrowHalf = Offset(50f, 25f)
    val MouthHalf = Offset(85f, 60f)
    // the bust leans, tilts and squashes around the bottom of the coat
    val Pivot = Offset(WIDTH / 2f, HEIGHT)
    // the light blue circle behind the doctor
    val Backdrop = Offset(408.5f, 412f)
    const val BACKDROP_R = 404f
    // how far the eyes may look inside the lenses, and the lens opening they are clipped to
    const val GAZE_MAX = 18f
    const val LENS_R = 70f
}

internal enum class Eye(@DrawableRes val left: Int = 0, @DrawableRes val right: Int = 0) {
    SMILE(R.drawable.doctor_eye_smile_l, R.drawable.doctor_eye_smile_r),
    LOOK(R.drawable.doctor_eye_look_l, R.drawable.doctor_eye_look_r),
    GLANCE(R.drawable.doctor_eye_glance_l, R.drawable.doctor_eye_glance_r),
    SQUINT(R.drawable.doctor_eye_squint_l, R.drawable.doctor_eye_squint_r),
    LAUGH(R.drawable.doctor_eye_laugh_l, R.drawable.doctor_eye_laugh_r),
    // drawn in the same glow: round open eyes (surprise, worry) and hearts
    OPEN,
    HEART,
    ;

    val drawn get() = left == 0
}

internal enum class Mouth(@DrawableRes val res: Int = 0) {
    SMILE(R.drawable.doctor_mouth_smile),
    O(R.drawable.doctor_mouth_o),
    LAUGH(R.drawable.doctor_mouth_laugh),
    // the smile upside down
    FROWN,
    // drawn
    FLAT,
    WAVY,
}

internal enum class Fx { RINGS, DOTS, SPARKLES, CONFETTI, HEARTS, ZZZ, QUESTION, SWEAT, NOTES }

// little things nova does by herself when nobody needs her for a while (and the tired yawn)
internal enum class Act { NONE, LOOK_AROUND, WINK, HUM, STRETCH, TILT, YAWN }

internal val IDLE_ACTS = listOf(Act.LOOK_AROUND, Act.WINK, Act.HUM, Act.STRETCH, Act.TILT)

private val HAPPY_TALK = listOf(Mouth.O, Mouth.LAUGH, Mouth.O, Mouth.SMILE)
private val SAD_TALK = listOf(Mouth.O, Mouth.FROWN)
private val CALM_TALK = listOf(Mouth.O, Mouth.FLAT)

// where every part goes for a mood. brow: px up. browTilt: degrees, + = inner ends up (worried),
// - = inner ends down (determined). browAsym: px, + = left brow up and right down. lid: 1 open, 0 shut.
// tilt: head degrees. sink: head down px. lean: head scale. energy: breathing speed and depth.
internal data class Pose(
    val eyes: Eye = Eye.SMILE,
    val mouth: Mouth = Mouth.SMILE,
    val mouthScale: Float = 1f,
    val brow: Float = 0f,
    val browTilt: Float = 0f,
    val browAsym: Float = 0f,
    val eyeScale: Float = 1f,
    val lid: Float = 1f,
    val gaze: Offset = Offset.Zero,
    val tilt: Float = 0f,
    val sink: Float = 0f,
    val lean: Float = 1f,
    val energy: Float = 1f,
    val fx: Set<Fx> = emptySet(),
    // mouth shapes she flaps through while talking in this mood
    val talk: List<Mouth> = HAPPY_TALK,
)

internal fun poseFor(mood: Mood): Pose = when (mood) {
    Mood.IDLE -> Pose()
    Mood.LISTENING -> Pose(eyeScale = 1.06f, brow = 6f, tilt = -2.5f, lean = 1.025f, fx = setOf(Fx.RINGS))
    Mood.THINKING -> Pose(
        eyes = Eye.LOOK, mouth = Mouth.FLAT, browAsym = 9f, gaze = Offset(8f, -8f), tilt = 4f,
        fx = setOf(Fx.DOTS), talk = CALM_TALK,
    )
    Mood.WALKING -> Pose(brow = -4f, browTilt = -8f)
    Mood.HAPPY -> Pose(eyes = Eye.LAUGH, mouth = Mouth.LAUGH, brow = 8f, fx = setOf(Fx.SPARKLES))
    Mood.SORRY -> Pose(
        eyes = Eye.SQUINT, mouth = Mouth.FROWN, brow = -2f, browTilt = 18f, sink = 12f, tilt = -3f,
        energy = 0.6f, talk = SAD_TALK,
    )
    Mood.EXCITED -> Pose(eyes = Eye.LAUGH, mouth = Mouth.LAUGH, brow = 13f, eyeScale = 1.1f, fx = setOf(Fx.SPARKLES, Fx.CONFETTI))
    Mood.LOVE -> Pose(eyes = Eye.HEART, mouth = Mouth.LAUGH, brow = 8f, tilt = 3f, fx = setOf(Fx.HEARTS))
    Mood.SURPRISED -> Pose(eyes = Eye.OPEN, mouth = Mouth.O, brow = 20f, eyeScale = 1.12f, lean = 1.03f)
    Mood.CONCERNED -> Pose(
        eyes = Eye.OPEN, mouth = Mouth.FLAT, brow = 4f, browTilt = 18f, eyeScale = 0.92f, lean = 1.02f,
        fx = setOf(Fx.SWEAT), talk = SAD_TALK,
    )
    Mood.CONFUSED -> Pose(eyes = Eye.GLANCE, mouth = Mouth.WAVY, browAsym = 11f, tilt = 7f, fx = setOf(Fx.QUESTION), talk = CALM_TALK)
    Mood.SLEEPY -> Pose(
        eyes = Eye.SQUINT, mouth = Mouth.O, mouthScale = 0.45f, lid = 0.4f, brow = -3f, sink = 16f, tilt = -5f,
        energy = 0.4f, fx = setOf(Fx.ZZZ), talk = CALM_TALK,
    )
    Mood.TIRED -> Pose(
        eyes = Eye.SQUINT, mouth = Mouth.FLAT, lid = 0.72f, brow = -4f, browTilt = 9f, sink = 8f, energy = 0.6f,
        talk = CALM_TALK,
    )
    Mood.RELIEVED -> Pose(eyes = Eye.SQUINT, brow = 4f, browTilt = 10f, fx = setOf(Fx.SWEAT))
    Mood.GREETING -> Pose(eyes = Eye.LAUGH, brow = 12f, tilt = -3f, fx = setOf(Fx.SPARKLES))
}

internal fun Pose.with(act: Act): Pose = when (act) {
    Act.YAWN -> copy(eyes = Eye.SQUINT, lid = 0.35f, mouth = Mouth.O, mouthScale = 1.35f, brow = -1f, browTilt = 4f)
    Act.HUM -> copy(eyes = Eye.SQUINT, lid = 0.85f, mouth = Mouth.O, mouthScale = 0.55f, fx = fx + Fx.NOTES)
    Act.STRETCH -> copy(eyes = Eye.LAUGH, brow = 10f, mouth = Mouth.O, mouthScale = 0.8f)
    Act.WINK -> copy(browAsym = -5f, mouth = Mouth.LAUGH)
    Act.TILT -> copy(eyes = Eye.GLANCE, browAsym = 6f)
    Act.LOOK_AROUND, Act.NONE -> this
}

// ---- the glow: light added to the navy visor (visor + Core = the art's cyan) ----

private val Core = Color(0xFF25B8BF)
private val Halo = Color(0xFF002A82)
private val Hot = Color(0xFF66C4C0)
private val PinkCore = Color(0xFFED5A78)
private val PinkHalo = Color(0xFF6E1238)

internal fun DrawScope.glowPath(path: Path, alpha: Float, width: Float, pink: Boolean = false, fill: Boolean = false) {
    val core = if (pink) PinkCore else Core
    val halo = if (pink) PinkHalo else Halo
    val round = { w: Float -> Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    drawPath(path, halo, alpha = alpha * 0.45f, style = round(width * 2.8f), blendMode = BlendMode.Plus)
    drawPath(path, halo, alpha = alpha * 0.7f, style = round(width * 1.7f), blendMode = BlendMode.Plus)
    if (fill) drawPath(path, core, alpha = alpha, blendMode = BlendMode.Plus)
    drawPath(path, core, alpha = alpha, style = round(width), blendMode = BlendMode.Plus)
}

private val OpenEye = Path().apply { addOval(Rect(-21f, -38f, 21f, 22f)) }
private val HeartEye = heart(Offset(0f, -6f), 30f)
private val FlatMouth = Path().apply {
    moveTo(-30f, 7f)
    quadraticTo(0f, 10f, 30f, 7f)
}

// drawn eyes, around the lens centre
internal fun DrawScope.drawnEye(eye: Eye, alpha: Float, t: Float) {
    when (eye) {
        Eye.OPEN -> {
            glowPath(OpenEye, alpha, 9f, fill = true)
            drawCircle(Hot, 7f, Offset(8f, -22f), alpha = alpha, blendMode = BlendMode.Plus)
        }
        Eye.HEART -> {
            val beat = 1f + 0.08f * sin(t * 7f)
            scale(beat, beat, Offset(0f, -6f)) { glowPath(HeartEye, alpha, 8f, pink = true, fill = true) }
        }
        else -> Unit
    }
}

// drawn mouths, around the mouth centre
internal fun DrawScope.drawnMouth(mouth: Mouth, alpha: Float, t: Float) {
    when (mouth) {
        Mouth.FLAT -> glowPath(FlatMouth, alpha, 12f)
        Mouth.WAVY -> {
            val wave = Path()
            var x = -38f
            while (x <= 38f) {
                val y = 8f + 6f * sin(x / 38f * 1.5f * PI.toFloat() + t * 2f)
                if (x == -38f) wave.moveTo(x, y) else wave.lineTo(x, y)
                x += 4f
            }
            glowPath(wave, alpha, 11f)
        }
        else -> Unit
    }
}

internal fun heart(c: Offset, s: Float) = Path().apply {
    moveTo(c.x, c.y + s * 0.9f)
    cubicTo(c.x - s * 1.2f, c.y + s * 0.1f, c.x - s * 0.9f, c.y - s, c.x, c.y - s * 0.35f)
    cubicTo(c.x + s * 0.9f, c.y - s, c.x + s * 1.2f, c.y + s * 0.1f, c.x, c.y + s * 0.9f)
    close()
}

// ---- effects around the head (normal colours, they sit on the light page) ----

private val FxBlue = Color(0xFF2E9BFF)
private val FxPink = Color(0xFFFF5C8A)
private val FxGold = Color(0xFFFFC94D)
private val FxDrop = Color(0xFF8FD8FF)
private val Confetti = listOf(FxBlue, Color(0xFF2EC4B6), Color(0xFFFFB627), FxPink, Color(0xFF7FD4FF))

private fun frac(x: Float) = x - floor(x)
private fun rnd(i: Int, salt: Float) = frac(sin(i * 12.9898f + salt * 78.233f) * 43758.547f)

// behind the bust
internal fun DrawScope.drawBackFx(weight: (Fx) -> Float, t: Float) {
    val w = weight(Fx.RINGS)
    if (w > 0.01f) {
        for (i in 0..1) {
            val p = (t / 1.6f + i * 0.5f) % 1f
            drawCircle(FxBlue.copy(alpha = (1f - p) * 0.35f * w), Rig.BACKDROP_R * (1f + 0.14f * p), Rig.Backdrop, style = Stroke(6f))
        }
    }
}

// in front, moving with the head
internal fun DrawScope.drawHeadFx(weight: (Fx) -> Float, t: Float) {
    weight(Fx.DOTS).takeIf { it > 0.01f }?.let { w ->
        val c = Rig.Backdrop + Offset(Rig.BACKDROP_R * 0.78f, -Rig.BACKDROP_R * 0.82f)
        for (i in 0 until 3) {
            val a = (t * 360f / 1.4f + i * 22f - 100f) * PI.toFloat() / 180f
            drawCircle(
                FxBlue.copy(alpha = (0.45f + 0.18f * i) * w),
                10f + 3.5f * i,
                Offset(c.x + Rig.BACKDROP_R * 0.22f * cos(a), c.y + Rig.BACKDROP_R * 0.07f * sin(a)),
            )
        }
    }
    weight(Fx.SPARKLES).takeIf { it > 0.01f }?.let { w ->
        SPARKLES.forEachIndexed { i, s ->
            val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 5f + i * 1.7f))
            run {
                rotate(t * 40f + i * 20f, Offset(s.x, s.y)) {
                    drawPath(star(Offset(s.x, s.y), s.size * twinkle), FxGold, alpha = w * twinkle)
                }
                drawCircle(Color.White, s.size * 0.18f * twinkle, Offset(s.x, s.y), alpha = w * twinkle)
            }
        }
    }
    weight(Fx.HEARTS).takeIf { it > 0.01f }?.let { w ->
        for (i in 0 until 6) {
            val p = (t / 2.8f + i / 6f) % 1f
            val side = if (i % 2 == 0) -1f else 1f
            val x = Rig.Backdrop.x + side * (250f + 30f * sin(i * 1.7f)) + sin(p * 6.3f + i) * 14f
            val y = 420f - p * 360f
            val a = w * sin(p * PI.toFloat())
            val s = 16f + 14f * p
            drawPath(heart(Offset(x, y), s), FxPink, alpha = a)
            drawCircle(Color.White, s * 0.18f, Offset(x - s * 0.4f, y - s * 0.35f), alpha = a * 0.8f)
        }
    }
    weight(Fx.ZZZ).takeIf { it > 0.01f }?.let { w ->
        for (i in 0 until 3) {
            val p = (t / 3.2f + i / 3f) % 1f
            val s = 14f + 26f * p
            val c = Offset(600f + p * 120f, 180f - p * 170f)
            val z = Path().apply {
                moveTo(c.x - s / 2f, c.y - s / 2f)
                lineTo(c.x + s / 2f, c.y - s / 2f)
                lineTo(c.x - s / 2f, c.y + s / 2f)
                lineTo(c.x + s / 2f, c.y + s / 2f)
            }
            drawPath(z, FxBlue, alpha = w * sin(p * PI.toFloat()), style = Stroke(5f + 3f * p, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    weight(Fx.QUESTION).takeIf { it > 0.01f }?.let { w ->
        val c = Offset(690f, 95f + sin(t * 2.5f) * 8f)
        rotate(sin(t * 1.7f) * 12f, c) {
            translate(c.x, c.y) {
                drawPath(Question, FxBlue.copy(alpha = 0.22f * w), style = Stroke(20f, cap = StrokeCap.Round))
                drawPath(Question, FxBlue, alpha = w, style = Stroke(9f, cap = StrokeCap.Round))
                drawCircle(FxBlue, 6f, Offset(0f, 24f), alpha = w)
            }
        }
    }
    weight(Fx.SWEAT).takeIf { it > 0.01f }?.let { w ->
        val p = (t / 2.4f) % 1f
        val a = w * when {
            p < 0.15f -> p / 0.15f
            p > 0.8f -> (1f - p) / 0.2f
            else -> 1f
        }
        val c = Offset(672f, 212f + p * 55f)
        drawPath(drop(c, 26f), FxDrop, alpha = a)
        drawPath(drop(c, 26f), FxBlue, alpha = a, style = Stroke(4f))
        drawCircle(Color.White, 5f, c + Offset(-6f, 4f), alpha = a)
    }
    weight(Fx.NOTES).takeIf { it > 0.01f }?.let { w ->
        for (i in 0 until 3) {
            val p = (t / 2.6f + i / 3f) % 1f
            val c = Offset(200f - p * 100f + sin(p * 9f) * 10f, 300f - p * 230f)
            note(c, 1f + 0.4f * p, w * sin(p * PI.toFloat()))
        }
    }
}

// over the whole canvas (view = the visible area in sprite pixels)
internal fun DrawScope.drawConfetti(w: Float, since: Float, t: Float, view: Rect) {
    if (w <= 0.01f) return
    val fall = view.height + 80f
    for (i in 0 until 46) {
        val speed = 260f + 200f * rnd(i, 1f)
        val y = view.top - 40f + ((since - rnd(i, 2f) * 0.6f).coerceAtLeast(0f) * speed) % fall
        val x = view.left + rnd(i, 3f) * view.width + sin(t * 2f + i) * 20f
        rotate(t * (120f + 240f * rnd(i, 4f)) + i * 37f, Offset(x, y)) {
            drawRect(Confetti[i % Confetti.size], Offset(x - 5f, y - 8f), Size(10f, 16f), alpha = w)
        }
    }
}

private data class Sparkle(val x: Float, val y: Float, val size: Float)

private val SPARKLES = listOf(
    Sparkle(130f, 140f, 36f), Sparkle(690f, 110f, 30f), Sparkle(745f, 330f, 26f), Sparkle(85f, 370f, 24f),
    Sparkle(610f, 50f, 20f), Sparkle(205f, 55f, 22f), Sparkle(730f, 500f, 20f), Sparkle(95f, 540f, 18f),
)

private fun star(c: Offset, r: Float) = Path().apply {
    val k = r * 0.25f
    moveTo(c.x, c.y - r)
    lineTo(c.x + k, c.y - k)
    lineTo(c.x + r, c.y)
    lineTo(c.x + k, c.y + k)
    lineTo(c.x, c.y + r)
    lineTo(c.x - k, c.y + k)
    lineTo(c.x - r, c.y)
    lineTo(c.x - k, c.y - k)
    close()
}

private fun drop(c: Offset, s: Float) = Path().apply {
    moveTo(c.x, c.y - s)
    cubicTo(c.x + s * 0.2f, c.y - s * 0.5f, c.x + s * 0.7f, c.y, c.x + s * 0.7f, c.y + s * 0.35f)
    cubicTo(c.x + s * 0.7f, c.y + s * 0.8f, c.x - s * 0.7f, c.y + s * 0.8f, c.x - s * 0.7f, c.y + s * 0.35f)
    cubicTo(c.x - s * 0.7f, c.y, c.x - s * 0.2f, c.y - s * 0.5f, c.x, c.y - s)
    close()
}

private val Question = Path().apply {
    moveTo(-16f, -20f)
    cubicTo(-16f, -42f, 16f, -42f, 16f, -20f)
    cubicTo(16f, -6f, 0f, -6f, 0f, 8f)
}

private fun DrawScope.note(c: Offset, s: Float, alpha: Float) {
    drawOval(FxBlue, Offset(c.x - 10f * s, c.y - 7f * s), Size(20f * s, 14f * s), alpha = alpha)
    drawLine(FxBlue, c + Offset(8f * s, -2f * s), c + Offset(8f * s, -38f * s), 4f * s, StrokeCap.Round, alpha = alpha)
    val flag = Path().apply {
        moveTo(c.x + 8f * s, c.y - 38f * s)
        quadraticTo(c.x + 22f * s, c.y - 30f * s, c.x + 18f * s, c.y - 18f * s)
    }
    drawPath(flag, FxBlue, alpha = alpha, style = Stroke(4f * s, cap = StrokeCap.Round))
}
