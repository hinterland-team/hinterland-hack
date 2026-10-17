package com.hinterland.clinic.ui

import androidx.annotation.DrawableRes
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.imageResource
import com.hinterland.clinic.Mood
import com.hinterland.clinic.R
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// nova's face is cut from the doctor artwork (res/drawable-nodpi/doctor_*): the bust from the idle frame,
// and the eyes and mouth of the other frames as patches laid over it. all numbers below are in sprite pixels.
// with reduced motion the face stays still and only changes its expression.
private object Sprite {
    const val WIDTH = 818f
    const val HEIGHT = 712f
    val EyesAt = Offset(180f, 240f)
    val MouthAt = Offset(326f, 402f)
    // the light blue circle behind the doctor
    val Backdrop = Offset(408.5f, 412f)
    const val BACKDROP_R = 404f
    val Glow = Color(0xFF2E9BFF)
}

private enum class Eyes(@DrawableRes val res: Int) {
    SMILE(R.drawable.doctor_eyes_smile),
    GLANCE(R.drawable.doctor_eyes_glance),
    LOOK(R.drawable.doctor_eyes_look),
    SQUINT(R.drawable.doctor_eyes_squint),
    LAUGH(R.drawable.doctor_eyes_laugh),
}

private enum class Mouth(@DrawableRes val res: Int) {
    SMILE(R.drawable.doctor_mouth_smile),
    O(R.drawable.doctor_mouth_o),
    LAUGH(R.drawable.doctor_mouth_laugh),
    FROWN(R.drawable.doctor_mouth_frown),
}

// mouth shapes nova flaps through while talking, the open ones more often
private val TALK = listOf(Mouth.O, Mouth.LAUGH, Mouth.O, Mouth.SMILE, Mouth.LAUGH, Mouth.O)

@Composable
fun Mascot(mood: Mood, modifier: Modifier = Modifier) {
    val base = ImageBitmap.imageResource(R.drawable.doctor_base)
    val eyeImages = Eyes.entries.map { ImageBitmap.imageResource(it.res) }
    val mouthImages = Mouth.entries.map { ImageBitmap.imageResource(it.res) }
    val reduced = LocalReducedMotion.current

    var blinking by remember { mutableStateOf(false) }
    LaunchedEffect(reduced) {
        blinking = false
        while (!reduced) {
            delay(Random.nextLong(2_200, 5_200))
            blinking = true
            delay(130)
            blinking = false
            if (Random.nextInt(4) == 0) {
                delay(160)
                blinking = true
                delay(130)
                blinking = false
            }
        }
    }

    // while idle she looks around now and then
    var glance by remember { mutableStateOf<Eyes?>(null) }
    LaunchedEffect(mood, reduced) {
        glance = null
        if (mood != Mood.IDLE || reduced) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(6_000, 12_000))
            glance = if (Random.nextBoolean()) Eyes.GLANCE else Eyes.LOOK
            delay(Random.nextLong(900, 1_600))
            glance = null
        }
    }

    var talkMouth by remember { mutableStateOf(Mouth.SMILE) }
    LaunchedEffect(mood, reduced) {
        talkMouth = Mouth.SMILE
        if (mood != Mood.SPEAKING) return@LaunchedEffect
        // reduced motion: one still, half-open "talking" mouth
        if (reduced) {
            talkMouth = Mouth.O
            return@LaunchedEffect
        }
        var last = Mouth.SMILE
        while (true) {
            last = TALK.filter { it != last }.random()
            talkMouth = last
            delay(Random.nextLong(110, 190))
        }
    }

    val moodEyes = when (mood) {
        Mood.LISTENING -> Eyes.GLANCE
        Mood.THINKING -> Eyes.LOOK
        Mood.HAPPY -> Eyes.LAUGH
        Mood.SORRY -> Eyes.SQUINT
        else -> glance ?: Eyes.SMILE
    }
    val eyes = if (blinking && mood != Mood.SORRY) Eyes.SQUINT else moodEyes
    val mouth = when (mood) {
        Mood.SPEAKING -> talkMouth
        Mood.HAPPY -> Mouth.LAUGH
        Mood.SORRY -> Mouth.FROWN
        else -> Mouth.SMILE
    }

    // every patch fades in and out on its own, so switching between them is a short crossfade
    val eyeAlpha = Eyes.entries.map {
        animateFloatAsState(if (it == eyes) 1f else 0f, calm(if (blinking) 60 else 180, reduced), label = "eyes$it").value
    }
    val mouthAlpha = Mouth.entries.map {
        animateFloatAsState(if (it == mouth) 1f else 0f, calm(if (mood == Mood.SPEAKING) 60 else 200, reduced), label = "mouth$it").value
    }

    val loop = rememberInfiniteTransition(label = "mascot")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "spin")
    val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "pulse")
    val bob by loop.animateFloat(-1f, 1f, infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val sway by loop.animateFloat(-1f, 1f, infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sway")
    val breathe by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(3_200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    // 0 = no movement at all
    val m = if (reduced) 0f else 1f

    val tilt by animateFloatAsState(
        m * when (mood) {
            Mood.THINKING -> 4f
            Mood.SORRY -> -3f
            Mood.LISTENING -> -2f
            else -> 0f
        },
        calm(500, reduced),
        label = "tilt",
    )
    val sink by animateFloatAsState(m * if (mood == Mood.SORRY) 10f else 0f, calm(500, reduced), label = "sink")

    Canvas(modifier) {
        val k = min(size.width / Sprite.WIDTH, size.height / Sprite.HEIGHT)
        val walking = mood == Mood.WALKING
        val dx = m * if (walking) sway * 8f else 0f
        val dy = sink + m * if (walking) bob * 9f else (breathe - 0.5f) * 6f

        translate((size.width - Sprite.WIDTH * k) / 2f, (size.height - Sprite.HEIGHT * k) / 2f) {
            scale(k, k, pivot = Offset.Zero) {
                if (mood == Mood.LISTENING) {
                    for (i in 0..1) {
                        val p = ((if (reduced) 0.3f else pulse) + i * 0.5f) % 1f
                        drawCircle(
                            color = Sprite.Glow.copy(alpha = (1f - p) * 0.35f),
                            radius = Sprite.BACKDROP_R * (1.0f + 0.14f * p),
                            center = Sprite.Backdrop,
                            style = Stroke(width = 6f),
                        )
                    }
                }

                translate(dx, dy) {
                    rotate(tilt, pivot = Offset(Sprite.WIDTH / 2f, Sprite.HEIGHT)) {
                        drawImage(base)
                        Eyes.entries.forEach { e ->
                            if (eyeAlpha[e.ordinal] > 0.01f) drawImage(eyeImages[e.ordinal], Sprite.EyesAt, alpha = eyeAlpha[e.ordinal])
                        }
                        Mouth.entries.forEach { m ->
                            if (mouthAlpha[m.ordinal] > 0.01f) drawImage(mouthImages[m.ordinal], Sprite.MouthAt, alpha = mouthAlpha[m.ordinal])
                        }
                    }
                }

                if (mood == Mood.THINKING) {
                    val c = Sprite.Backdrop + Offset(Sprite.BACKDROP_R * 0.78f, -Sprite.BACKDROP_R * 0.82f)
                    for (i in 0 until 3) {
                        val a = Math.toRadians((spin * m + i * 22f - 100f).toDouble())
                        drawCircle(
                            color = Sprite.Glow.copy(alpha = 0.45f + 0.18f * i),
                            radius = 10f + 3.5f * i,
                            center = Offset(
                                c.x + (Sprite.BACKDROP_R * 0.22f * cos(a)).toFloat(),
                                c.y + (Sprite.BACKDROP_R * 0.07f * sin(a)).toFloat(),
                            ),
                        )
                    }
                }
            }
        }
    }
}
