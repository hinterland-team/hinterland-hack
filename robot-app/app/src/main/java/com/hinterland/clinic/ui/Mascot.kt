package com.hinterland.clinic.ui

import android.content.res.Resources
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import com.hinterland.clinic.Mood
import com.hinterland.clinic.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.TimeSource

private typealias Anim = Animatable<Float, AnimationVector1D>

// he starts his little idle habits after this long with nothing going on
private const val IDLE_ACT_AFTER_MS = 15_000L

// otto's face: the doctor art as a rig (FaceParts.kt). every mood has a pose; on top he blinks,
// glances around, nods, hops when happy, droops when sorry, and his mouth talks in any mood.
// with reduced motion the face stays still and only changes its expression.
// show = show mode (RobotModes.kt): his little habits come often, with a silent "singing" now and then
@Composable
fun Mascot(mood: Mood, modifier: Modifier = Modifier, talking: Boolean = false, show: Boolean = false) {
    val reduced = LocalReducedMotion.current
    // the pictures are decoded once per process (FaceSprites), not again when the face comes back
    val res = LocalContext.current.resources
    val sprites = remember(res) { FaceSprites.get(res) }
    val body = sprites.body
    val eyesL = sprites.eyesL
    val eyesR = sprites.eyesR
    val browL = sprites.browL
    val browR = sprites.browR
    val mouths = sprites.mouths
    val smile = mouths[Mouth.SMILE.ordinal]!!
    // the lens openings the eyes are clipped to: made once, not two new paths every frame
    val lenses = remember { listOf(Rig.EyeL, Rig.EyeR).map { Path().apply { addOval(Rect(it, Rig.LENS_R)) } } }

    // one clock for breathing, bobbing and the effects. read only while drawing, so it redraws, not recomposes
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        val start = withFrameMillis { it } - clock.longValue
        while (true) withFrameMillis { clock.longValue = it - start }
    }
    val moodSince = remember { mutableLongStateOf(0L) }

    // waking up from a nap starts with a little surprise
    var surprise by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(mood) }
    LaunchedEffect(mood) {
        moodSince.longValue = clock.longValue
        val woke = last == Mood.SLEEPY && mood != Mood.SLEEPY
        last = mood
        if (woke && !reduced) {
            surprise = true
            try {
                delay(800)
            } finally {
                surprise = false
            }
        }
    }
    val shown = if (surprise) Mood.SURPRISED else mood

    var act by remember { mutableStateOf(Act.NONE) }
    val pose = poseFor(shown).with(act)
    // the mouth moves with syllables while he talks, and while he "sings" in show mode (no sound)
    val mouthMoves = talking || act == Act.SING

    val blink = remember { Animatable(1f) }
    val wink = remember { Animatable(1f) }
    val gaze = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val hop = remember { Animatable(0f) }
    val squash = remember { Animatable(1f) }
    val nod = remember { Animatable(0f) }
    val sway = remember { Animatable(0f) }
    val talkAmp = remember { Animatable(0f) }
    val browPop = remember { Animatable(0f) }
    // the syllable's mouth shape. only read in the effects below, never while composing: a new syllable
    // redraws the face instead of recomposing it (v3.3, ~10 times a second while he talks)
    val talkShape = remember { mutableStateOf(Mouth.O) }

    // blinking: a blink when the mood changes, like taking it in, then every few seconds
    LaunchedEffect(shown, reduced) {
        blink.snapTo(1f)
        if (reduced || shown == Mood.SLEEPY) return@LaunchedEffect
        val slow = shown == Mood.TIRED
        blinkOnce(blink, slow)
        while (true) {
            delay(if (slow) Random.nextLong(3_000, 6_000) else Random.nextLong(2_000, 5_000))
            blinkOnce(blink, slow)
            if (!slow && Random.nextInt(4) == 0) {
                delay(140)
                blinkOnce(blink, false)
            }
        }
    }

    // the moment a mood starts: a hop for the happy ones, a pop for a surprise, a slump for bad news
    LaunchedEffect(shown, reduced) {
        if (reduced) return@LaunchedEffect
        if (hop.value != 0f) hop.animateTo(0f, tween(150))
        when (shown) {
            Mood.HAPPY, Mood.GREETING, Mood.LOVE -> hopOnce(hop, squash)
            Mood.EXCITED -> while (true) {
                hopOnce(hop, squash, high = 34f)
                delay(Random.nextLong(120, 380))
            }
            Mood.SURPRISED -> {
                squash.animateTo(1.08f, tween(110))
                squash.animateTo(1f, spring(0.35f, Spring.StiffnessMedium))
            }
            Mood.SORRY, Mood.CONCERNED -> {
                squash.animateTo(0.97f, tween(220))
                squash.animateTo(1f, spring(0.5f, Spring.StiffnessLow))
            }
            else -> {
                squash.animateTo(0.985f, tween(90))
                squash.animateTo(1f, spring(0.45f, Spring.StiffnessMediumLow))
            }
        }
    }

    // where he looks, and his little habits when nobody needs him (in show mode all the time)
    LaunchedEffect(shown, talking, reduced, show) {
        act = Act.NONE
        launch { wink.animateTo(1f, tween(120)) }
        launch { sway.animateTo(0f, tween(250)) }
        if (reduced) {
            gaze.snapTo(Offset.Zero)
            return@LaunchedEffect
        }
        when (shown) {
            Mood.SLEEPY -> gaze.animateTo(Offset.Zero, tween(600))
            // looking for someone
            Mood.CONCERNED -> while (true) {
                gaze.animateTo(Offset(-15f, 2f), tween(520, easing = FastOutSlowInEasing))
                delay(Random.nextLong(500, 1_100))
                gaze.animateTo(Offset(15f, 2f), tween(620, easing = FastOutSlowInEasing))
                delay(Random.nextLong(500, 1_100))
            }
            Mood.CONFUSED -> while (true) {
                gaze.animateTo(Offset(-7f, -3f), tween(140))
                delay(Random.nextLong(900, 1_500))
                gaze.animateTo(Offset(9f, -3f), tween(140))
                delay(Random.nextLong(900, 1_500))
            }
            Mood.THINKING -> while (true) {
                gaze.animateTo(Offset(Random.nextFloat() * 8f - 4f, Random.nextFloat() * 6f - 3f), tween(220))
                delay(Random.nextLong(700, 1_600))
            }
            else -> {
                val since = TimeSource.Monotonic.markNow()
                while (true) {
                    delay(if (show) Random.nextLong(900, 2_400) else Random.nextLong(1_200, 3_400))
                    val bored = (show && !talking) ||
                        (shown == Mood.IDLE && !talking && since.elapsedNow().inWholeMilliseconds > IDLE_ACT_AFTER_MS)
                    if (bored && Random.nextInt(if (show) 2 else 3) == 0) {
                        val a = if (show) SHOW_ACTS.random() else IDLE_ACTS.random()
                        act = a
                        try {
                            perform(a, gaze, wink, sway, squash)
                        } finally {
                            act = Act.NONE
                        }
                    } else {
                        // a quick saccade, now and then back to the middle
                        val target = if (Random.nextInt(3) == 0) Offset.Zero else Offset(Random.nextFloat() * 18f - 9f, Random.nextFloat() * 9f - 5f)
                        gaze.animateTo(target, tween(90))
                    }
                }
            }
        }
    }

    // listening: a small nod now and then. tired: a yawn now and then
    LaunchedEffect(shown, reduced) {
        if (reduced) return@LaunchedEffect
        when (shown) {
            Mood.LISTENING -> while (true) {
                delay(Random.nextLong(2_200, 4_500))
                nod.animateTo(7f, tween(160))
                nod.animateTo(0f, spring(0.45f, Spring.StiffnessLow))
            }
            Mood.TIRED -> while (true) {
                delay(Random.nextLong(8_000, 15_000))
                act = Act.YAWN
                try {
                    delay(1_900)
                } finally {
                    act = Act.NONE
                }
            }
            else -> if (nod.value != 0f) nod.animateTo(0f, tween(200))
        }
    }

    // talking: syllables of different sizes, short pauses, the brows lift on the strong ones
    LaunchedEffect(mouthMoves, shown, reduced) {
        val p = poseFor(shown).with(if (act == Act.SING) Act.SING else Act.NONE)
        talkShape.value = p.talk.first()
        if (!mouthMoves) {
            talkAmp.animateTo(0f, tween(150))
            return@LaunchedEffect
        }
        if (reduced) {
            talkAmp.snapTo(0.5f)
            return@LaunchedEffect
        }
        while (true) {
            val a = Random.nextFloat()
            talkShape.value = p.talk.filter { it != talkShape.value }.ifEmpty { p.talk }.random()
            if (a > 0.82f) {
                launch {
                    browPop.animateTo(5f, tween(90))
                    browPop.animateTo(0f, spring(0.5f, Spring.StiffnessLow))
                }
            }
            talkAmp.animateTo(a, tween(70))
            delay(Random.nextLong(60, 140))
            if (Random.nextInt(7) == 0) {
                talkShape.value = p.mouth
                talkAmp.animateTo(0.1f, tween(90))
                delay(Random.nextLong(120, 260))
            }
        }
    }

    val spec: AnimationSpec<Float> = if (reduced) snap() else spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
    val brow = animateFloatAsState(pose.brow, spec, label = "brow")
    val browTilt = animateFloatAsState(pose.browTilt, spec, label = "browTilt")
    val browAsym = animateFloatAsState(pose.browAsym, spec, label = "browAsym")
    val eyeScale = animateFloatAsState(pose.eyeScale, spec, label = "eyeScale")
    val lid = animateFloatAsState(pose.lid, spec, label = "lid")
    val gazeX = animateFloatAsState(pose.gaze.x, spec, label = "gazeX")
    val gazeY = animateFloatAsState(pose.gaze.y, spec, label = "gazeY")
    val tilt = animateFloatAsState(pose.tilt, spec, label = "tilt")
    val sink = animateFloatAsState(pose.sink, spec, label = "sink")
    val lean = animateFloatAsState(pose.lean, spec, label = "lean")
    val mouthScale = animateFloatAsState(pose.mouthScale, spec, label = "mouthScale")
    val energy = animateFloatAsState(pose.energy, if (reduced) snap() else tween(800), label = "energy")
    val walk = animateFloatAsState(if (shown == Mood.WALKING) 1f else 0f, if (reduced) snap() else tween(500), label = "walk")

    // every shape fades in and out on its own, so changing shape is a short crossfade
    val eyes = pose.eyes
    val fade: AnimationSpec<Float> = if (reduced) snap() else tween(160)
    val eyeW = Eye.entries.map { animateFloatAsState(if (it == eyes) 1f else 0f, fade, label = "eye$it") }
    // the mouth: the same crossfade (70 ms between syllables, else the fade), driven from the syllables
    // themselves so they never recompose the face
    val mouthW = remember {
        val first = if (mouthMoves) talkShape.value else pose.mouth
        Mouth.entries.map { Animatable(if (it == first) 1f else 0f) }
    }
    val talkingNow by rememberUpdatedState(mouthMoves)
    val restMouth by rememberUpdatedState(pose.mouth)
    LaunchedEffect(reduced) {
        snapshotFlow { if (talkingNow) talkShape.value else restMouth }.collect { mouth ->
            val shapeSpec: AnimationSpec<Float> = if (talkingNow && !reduced) tween(70) else if (reduced) snap() else tween(160)
            Mouth.entries.forEachIndexed { i, mo -> launch { mouthW[i].animateTo(if (mo == mouth) 1f else 0f, shapeSpec) } }
        }
    }
    val fxW = Fx.entries.map {
        animateFloatAsState(if (it in pose.fx) 1f else 0f, if (reduced) snap() else tween(400), label = "fx$it")
    }
    val fx = { f: Fx -> if (reduced && f == Fx.CONFETTI) 0f else fxW[f.ordinal].value }

    // its own layer: the face redraws every frame without the rest of the screen redrawing with it
    Canvas(modifier.graphicsLayer()) {
        val now = clock.longValue
        val t = now / 1000f
        val since = (now - moodSince.longValue) / 1000f
        val k = min(size.width / Rig.WIDTH, size.height / Rig.HEIGHT)
        val ox = (size.width - Rig.WIDTH * k) / 2f
        val oy = (size.height - Rig.HEIGHT * k) / 2f
        val view = Rect(-ox / k, -oy / k, (size.width - ox) / k, (size.height - oy) / k)

        // breathing: slower and deeper when tired or sleepy
        val e = energy.value
        val breath = sin(t * 2f * PI.toFloat() / (3.4f / (0.5f + 0.5f * e)))
        val breathAmp = 0.006f + (1f - e) * 0.01f
        // the walk: a step bounce and a sway. excited: a wiggle. love: a slow sway
        val w = walk.value
        val stepBob = -abs(sin(t * PI.toFloat() / 0.65f)) * 9f * w
        val wiggle = sin(t * 12f) * 2.5f * fx(Fx.CONFETTI) + sin(t * 3f) * 2.5f * fx(Fx.HEARTS) + sin(t * PI.toFloat() / 1.3f) * 1.5f * w

        val dy = sink.value + hop.value + nod.value + talkAmp.value * 3f + stepBob + breath * 2f
        val sy = squash.value * (1f + breath * breathAmp)
        val sx = 1f + (1f - squash.value) * 0.7f
        val l = lean.value

        translate(ox, oy) {
            scale(k, k, Offset.Zero) {
                drawBackFx(fx, t)
                translate(sin(t * PI.toFloat() / 1.3f) * 8f * w, dy) {
                    rotate(tilt.value + sway.value + wiggle, Rig.Pivot) {
                        scale(sx * l, sy * l, Rig.Pivot) {
                            drawImage(body)

                            // eyes: shapes crossfade, the whole eye moves with the gaze and squashes to blink
                            var g = gaze.value + Offset(gazeX.value, gazeY.value)
                            if (g.getDistance() > Rig.GAZE_MAX) g *= Rig.GAZE_MAX / g.getDistance()
                            val open = lid.value * blink.value
                            for (side in 0..1) {
                                val lens = if (side == 0) Rig.EyeL else Rig.EyeR
                                val c = lens + g
                                val lidS = (open * if (side == 0) wink.value else 1f).coerceAtLeast(0.04f)
                                val sprites = if (side == 0) eyesL else eyesR
                                withTransform({
                                    // the eye stays inside its lens, however far he looks
                                    clipPath(lenses[side])
                                    translate(c.x, c.y)
                                    scale(eyeScale.value, eyeScale.value * lidS, Offset.Zero)
                                }) {
                                    Eye.entries.forEach { eye ->
                                        val a = eyeW[eye.ordinal].value
                                        if (a < 0.01f) return@forEach
                                        val img = sprites[eye.ordinal]
                                        if (img == null) drawnEye(eye, a, t) else drawImage(img, -Rig.EyeHalf, alpha = a, blendMode = BlendMode.Plus)
                                    }
                                }
                            }

                            // brows: up, tilted, one up one down; they dip a little with a blink
                            for (side in 0..1) {
                                val s = if (side == 0) 1f else -1f
                                val c = if (side == 0) Rig.BrowL else Rig.BrowR
                                val up = brow.value + browPop.value + s * browAsym.value
                                withTransform({
                                    translate(c.x + g.x * 0.25f, c.y - up + g.y * 0.35f + (1f - blink.value) * 3f)
                                    rotate(-s * browTilt.value, Offset.Zero)
                                }) {
                                    drawImage(if (side == 0) browL else browR, -Rig.BrowHalf, blendMode = BlendMode.Plus)
                                }
                            }

                            // mouth: opens with the syllables while talking (and singing in show mode)
                            val m = Rig.Mouth + Offset(g.x * 0.3f, 0f)
                            val talkOpen = if (mouthMoves) 0.72f + 0.5f * talkAmp.value else 1f
                            withTransform({
                                translate(m.x, m.y)
                                scale(mouthScale.value, mouthScale.value * talkOpen, Offset.Zero)
                            }) {
                                Mouth.entries.forEach { mo ->
                                    val a = mouthW[mo.ordinal].value
                                    if (a < 0.01f) return@forEach
                                    val img = mouths[mo.ordinal]
                                    when {
                                        mo == Mouth.FROWN -> scale(1f, -1f, Offset(0f, 3f)) {
                                            drawImage(smile, -Rig.MouthHalf, alpha = a, blendMode = BlendMode.Plus)
                                        }
                                        img == null -> drawnMouth(mo, a, t)
                                        else -> drawImage(img, -Rig.MouthHalf, alpha = a, blendMode = BlendMode.Plus)
                                    }
                                }
                            }

                            drawHeadFx(fx, t)
                        }
                    }
                }
                drawConfetti(fx(Fx.CONFETTI), since, t, view)
            }
        }
    }
}

// the face's pictures (doctor_*.webp, small, in drawable-nodpi), decoded once per process. they are drawn
// scaled on the gpu, which costs nothing; bitmaps at screen size would only take more memory
internal class FaceSprites private constructor(res: Resources) {
    val body = ImageBitmap.imageResource(res, R.drawable.doctor_body)
    val eyesL = Eye.entries.map { if (it.drawn) null else ImageBitmap.imageResource(res, it.left) }
    val eyesR = Eye.entries.map { if (it.drawn) null else ImageBitmap.imageResource(res, it.right) }
    val browL = ImageBitmap.imageResource(res, R.drawable.doctor_brow_l)
    val browR = ImageBitmap.imageResource(res, R.drawable.doctor_brow_r)
    val mouths = Mouth.entries.map { if (it.res == 0) null else ImageBitmap.imageResource(res, it.res) }

    companion object {
        // drawable-nodpi: the same pictures for any screen or configuration, so one copy for the process
        @Volatile private var cached: FaceSprites? = null

        fun get(res: Resources): FaceSprites =
            cached ?: synchronized(this) { cached ?: FaceSprites(res).also { cached = it } }
    }
}

private suspend fun blinkOnce(blink: Anim, slow: Boolean) {
    blink.animateTo(0.06f, tween(if (slow) 260 else 70))
    blink.animateTo(1f, tween(if (slow) 380 else 120))
}

// crouch, jump, land with a squash
private suspend fun hopOnce(hop: Anim, squash: Anim, high: Float = 26f) {
    squash.animateTo(0.94f, tween(90))
    coroutineScope {
        launch { squash.animateTo(1.04f, tween(160)) }
        hop.animateTo(-high, tween(170, easing = FastOutSlowInEasing))
    }
    coroutineScope {
        launch { squash.animateTo(1f, tween(150)) }
        hop.animateTo(0f, tween(190, easing = FastOutLinearInEasing))
    }
    squash.animateTo(0.93f, tween(70))
    squash.animateTo(1f, spring(0.4f, Spring.StiffnessMedium))
}

private suspend fun perform(a: Act, gaze: Animatable<Offset, AnimationVector2D>, wink: Anim, sway: Anim, squash: Anim) {
    when (a) {
        Act.LOOK_AROUND -> {
            gaze.animateTo(Offset(-16f, -3f), tween(260))
            delay(800)
            gaze.animateTo(Offset(16f, -3f), tween(360))
            delay(800)
            gaze.animateTo(Offset.Zero, tween(260))
        }
        Act.WINK -> {
            delay(150)
            wink.animateTo(0.05f, tween(90))
            delay(320)
            wink.animateTo(1f, tween(160))
            delay(300)
        }
        Act.HUM -> {
            repeat(4) {
                sway.animateTo(3f, tween(400, easing = FastOutSlowInEasing))
                sway.animateTo(-3f, tween(400, easing = FastOutSlowInEasing))
            }
            sway.animateTo(0f, tween(300))
        }
        // show mode: swaying to the beat while the mouth sings (the talk effect moves it, act == SING)
        Act.SING -> {
            repeat(6) {
                sway.animateTo(4f, tween(380, easing = FastOutSlowInEasing))
                sway.animateTo(-4f, tween(380, easing = FastOutSlowInEasing))
            }
            sway.animateTo(0f, tween(300))
        }
        Act.STRETCH -> {
            squash.animateTo(1.07f, tween(450))
            delay(350)
            squash.animateTo(0.95f, tween(220))
            squash.animateTo(1f, spring(0.4f, Spring.StiffnessMedium))
            delay(300)
        }
        Act.TILT -> {
            sway.animateTo(7f, spring(0.6f, Spring.StiffnessLow))
            delay(1_500)
            sway.animateTo(0f, spring(0.6f, Spring.StiffnessLow))
        }
        Act.YAWN, Act.NONE -> Unit
    }
}
