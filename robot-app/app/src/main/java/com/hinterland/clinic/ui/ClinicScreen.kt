package com.hinterland.clinic.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.hinterland.clinic.BrainStatus
import com.hinterland.clinic.Health
import com.hinterland.clinic.Pace
import com.hinterland.clinic.Place
import com.hinterland.clinic.RobotState
import com.hinterland.clinic.UiState
import kotlin.math.min
import kotlinx.coroutines.delay

// what the screen buttons do (Clinic in the app, nothing in previews)
class ScreenActions(
    val thatsMe: () -> Unit = {},
    val pace: (Pace) -> Unit = {},
    val imHere: () -> Unit = {},
    val goOn: () -> Unit = {},
    val feedback: (Boolean) -> Unit = {},
    val checkIn: () -> Unit = {},
    val room: (Place?) -> Unit = {},
    val checkInPace: (Pace, Boolean) -> Unit = { _, _ -> },
    val checkInBack: () -> Unit = {},
    val checkInClose: () -> Unit = {},
    val checkInConfirm: () -> Unit = {},
    // the talk at a check-back
    val allGood: () -> Unit = {},
    val talkSlower: () -> Unit = {},
    val talkWait: () -> Unit = {},
    // staff menu: long-press the logo, close, any touch keeps it open, check the servers now
    val openStaff: () -> Unit = {},
    val closeStaff: () -> Unit = {},
    val staffTouched: () -> Unit = {},
    val recheckServers: () -> Unit = {},
)

private enum class View { IDLE, CHECK_IN, GUIDE, HELP, OUT_OF_SERVICE }

private fun UiState.view(): View = when {
    help != null -> View.HELP
    guide != null -> View.GUIDE
    chassis != null -> View.OUT_OF_SERVICE
    checkIn != null -> View.CHECK_IN
    else -> View.IDLE
}

// 14" 1920x1080 landscape (549x308 dp at density 3.5). laid out at 1280x720 design dp and scaled,
// so it looks the same at any density. most of the time nova's face fills the screen. when something
// happens it moves to the left column (face, what was just said, status) and the task shows on the right.
@Composable
fun ClinicScreen(state: UiState, actions: ScreenActions = ScreenActions()) {
    val reduced = rememberReducedMotion()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Nova.Paper, Nova.Mist))),
    ) {
        val scale = min(maxWidth / DESIGN_WIDTH, maxHeight / DESIGN_HEIGHT)
        val density = LocalDensity.current.density
        CompositionLocalProvider(
            LocalDensity provides Density(density * scale, 1f),
            LocalReducedMotion provides reduced,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Stage(state, actions, reduced)
                // staff only, over everything
                val shownStaff = rememberLast(state.staff)
                AnimatedVisibility(
                    visible = state.staff != null,
                    enter = if (reduced) fadeIn(tween(0)) else fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.985f),
                    exit = if (reduced) fadeOut(tween(0)) else fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.985f),
                ) {
                    // the last menu stays drawn while it fades out
                    shownStaff?.let { StaffMenu(it, actions, Modifier.size(DESIGN_WIDTH, DESIGN_HEIGHT)) }
                }
            }
        }
    }
}

private val DESIGN_WIDTH = 1280.dp
private val DESIGN_HEIGHT = 720.dp

// how long the info stays before the face comes back, counted from the last touch
private const val INFO_MS = 8_000L
// the welcome after someone tapped the face, a bit longer to read and choose
private const val WELCOME_MS = 12_000L
// nova's words stay under the big face this long after she stops talking
private const val CAPTION_LINGER_MS = 4_000L

// the Nova logo in the top left corner (long-press opens the staff menu), the face column starts under it
private val LOGO_HEIGHT = 60.dp
// the face alone, filling the screen (a bit of air on top for the head mirror)
private val HeroFace = DpRect(0.dp, 24.dp, DESIGN_WIDTH, DESIGN_HEIGHT)
// the face in the left column, under the logo and above the caption and the status line
private val SideFace = DpRect(Space.page, Space.page + LOGO_HEIGHT, Space.page + Space.faceColumn, 432.dp)
private val SideBelow = SideFace.bottom + Space.s
private val InfoLeft = SideFace.right + Space.page

@Composable
private fun Stage(state: UiState, actions: ScreenActions, reduced: Boolean) {
    val focus = state.focus()
    var info by remember { mutableStateOf(false) }
    // every touch on the screen counts as reading, so the info stays while someone uses it
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(focus?.key) { info = focus != null }
    LaunchedEffect(info, focus?.key, focus?.holds, touches) {
        if (!info || focus?.holds == true) return@LaunchedEffect
        delay(if (focus == null) WELCOME_MS else INFO_MS)
        info = false
    }

    // 0 = the big face, 1 = face aside and the info on the right
    val t by animateFloatAsState(
        if (info) 1f else 0f,
        if (reduced) snap() else tween(800, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "stage",
    )
    // the info comes in once the face is mostly out of the way, and goes first on the way back
    val reveal = ((t - 0.5f) / 0.5f).coerceIn(0f, 1f)
    val face = lerp(HeroFace, SideFace, t)

    Box(
        Modifier
            .size(DESIGN_WIDTH, DESIGN_HEIGHT)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touches++
                }
            },
    ) {
        if (reveal > 0f) {
            Column(
                Modifier
                    .offset(Space.page, SideBelow)
                    .width(Space.faceColumn)
                    .graphicsLayer { alpha = reveal },
            ) {
                Caption(state)
            }
            Box(
                Modifier
                    .offset(InfoLeft, Space.page)
                    .size(DESIGN_WIDTH - InfoLeft - Space.page, DESIGN_HEIGHT - Space.page * 2)
                    .graphicsLayer {
                        alpha = reveal
                        translationX = (1f - reveal) * 64.dp.toPx()
                    },
            ) {
                InfoContent(state, actions, reduced)
            }
        }

        if (t < 1f) HeroCaption(state, Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = 1f - t })

        // tap the big face to see what nova can do, tap the small one to get the big face back
        Box(
            Modifier
                .offset(face.left, face.top)
                .size(face.right - face.left, face.bottom - face.top)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { info = !info },
        ) {
            Mascot(state.mood(), Modifier.fillMaxSize(), talking = state.speaking)
        }

        // on top of the face, so a long-press here never counts as a tap on the face
        NovaLogo(onLongPress = actions.openStaff, modifier = Modifier.offset(Space.page, Space.page))
    }
}

private fun lerp(a: DpRect, b: DpRect, t: Float) =
    DpRect(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))

@Composable
private fun InfoContent(state: UiState, actions: ScreenActions, reduced: Boolean) {
    AnimatedContent(
        targetState = state.view(),
        transitionSpec = {
            if (reduced) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            } else {
                (fadeIn(tween(420, delayMillis = 120)) + slideInVertically(tween(420, delayMillis = 120)) { it / 24 }) togetherWith
                    fadeOut(tween(200))
            }
        },
        label = "view",
        modifier = Modifier.fillMaxSize(),
    ) { view ->
        when (view) {
            View.HELP -> HelpContent(state)
            View.GUIDE -> GuideContent(state, actions)
            View.OUT_OF_SERVICE -> OutOfServiceContent(state)
            View.CHECK_IN -> CheckInContent(state, actions)
            View.IDLE -> IdleContent(state, actions)
        }
    }
}

// ---- under the big face: nova's words while she talks ----

@Composable
private fun HeroCaption(state: UiState, modifier: Modifier) {
    var recent by remember { mutableStateOf(false) }
    LaunchedEffect(state.speaking) {
        if (state.speaking) {
            recent = true
        } else {
            delay(CAPTION_LINGER_MS)
            recent = false
        }
    }
    // the patient's words stay off the screen; nova's own lines are shown
    val line = state.subtitle.takeIf { recent && it.isNotBlank() && !state.subtitleFromUser }
    var last by remember { mutableStateOf("") }
    LaunchedEffect(line) { if (line != null) last = line }
    AnimatedVisibility(
        visible = line != null,
        enter = fadeIn(tween(250)),
        exit = fadeOut(tween(400)),
        modifier = modifier.padding(bottom = Space.m),
    ) {
        Box(
            Modifier
                .width(880.dp)
                .clip(RoundedCornerShape(Space.radius))
                .background(Nova.Card.copy(alpha = 0.92f))
                .border(1.5.dp, Nova.Line, RoundedCornerShape(Space.radius))
                .padding(horizontal = Space.l, vertical = Space.m),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                line ?: last,
                style = text(Type.bodyLarge, FontWeight.Medium, Nova.Ink),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- left column under the small face: what nova says ----

// what nova just said, big, always the same height. connection and battery are in the staff menu.
@Composable
private fun Caption(state: UiState) {
    // the patient's words stay off the screen; nova's own lines are shown
    val fromUser = state.subtitleFromUser
    Column(
        Modifier
            .fillMaxWidth()
            .height(CAPTION_HEIGHT)
            .clip(RoundedCornerShape(Space.radius))
            .background(Nova.Card)
            .border(1.5.dp, Nova.Line, RoundedCornerShape(Space.radius))
            .padding(horizontal = Space.m, vertical = 18.dp),
    ) {
        Text("NOVA", style = text(Type.label, FontWeight.Bold, Nova.Blue, spacing = 2.5.sp))
        Spacer(Modifier.height(Space.xs))
        Text(
            when {
                fromUser -> "I'm listening..."
                state.subtitle.isBlank() -> "Just talk to me."
                else -> state.subtitle
            },
            style = text(Big.label, FontWeight.Medium, if (fromUser || state.subtitle.isBlank()) Nova.InkSoft else Nova.Ink, lineHeight = 38.sp),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val CAPTION_HEIGHT = DESIGN_HEIGHT - Space.page - SideBelow

// ---- idle: the welcome, or what nova is busy with ----

@Composable
private fun IdleContent(state: UiState, actions: ScreenActions) {
    when (state.brain.state) {
        RobotState.RETURNING -> Column(Modifier.fillMaxSize()) {
            Headline("I'm going back")
            Spacer(Modifier.height(Space.xs))
            Say("See you soon!")
            Spacer(Modifier.weight(1f))
            RouteMap(from = "Here", progress = null)
            Spacer(Modifier.weight(1f))
        }
        RobotState.CHARGING -> Message("I'm charging", "Just talk to me if you need me.", { IconBadge(GlyphKind.PLUG, Nova.Teal, Nova.TealSoft, pulse = true) })
        RobotState.ERROR -> Message("One moment, please", "The desk will help you.", { IconBadge(GlyphKind.PERSON, Nova.Amber, Nova.AmberSoft) })
        else -> Column(Modifier.fillMaxSize()) {
            Headline("Hello! I'm Nova")
            Spacer(Modifier.height(Space.xs))
            Say("I walk with you to your room.")
            Spacer(Modifier.weight(1f))
            if (state.canSelfCheckIn) {
                BigButton("Check in", GlyphKind.TICKET, onClick = actions.checkIn, pulse = true, height = Big.button)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(GlyphKind.TICKET, Nova.Blue, Nova.SkySoft, size = 104.dp)
                    Spacer(Modifier.width(Space.s))
                    Say("The desk will call your ticket.", color = Nova.Ink)
                }
            }
            Spacer(Modifier.height(Space.l))
            TalkHint()
            Spacer(Modifier.weight(1f))
        }
    }
}

// ---- out of service: nova can't drive (no chassis rights, emergency stop, the system took over) ----

@Composable
private fun OutOfServiceContent(state: UiState) {
    val issue = state.chassis ?: return
    Message(
        issue.title, "The desk will help you.",
        { IconBadge(GlyphKind.PLUG, Nova.Amber, Nova.AmberSoft) },
    ) {
        // for the staff: how to get nova going again
        Text(issue.hint, style = text(Type.caption, FontWeight.Normal, Nova.InkFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- urgent help ----

@Composable
private fun HelpContent(state: UiState) {
    val failed = state.help?.sent == false
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(GlyphKind.PERSON, Nova.Amber, Nova.AmberSoft, size = 132.dp, pulse = !failed)
            Spacer(Modifier.width(Space.m))
            Column {
                Headline(if (failed) "Please call 112" else "Help is coming", maxLines = 1)
                Say(if (failed) "I couldn't reach the team." else "I'm staying with you.", maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Space.radius))
                .background(Nova.FailSoft)
                .padding(horizontal = Space.l, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Emergency?", style = text(40.sp, FontWeight.Bold, Nova.Fail))
                Spacer(Modifier.height(Space.xs))
                Say("Call this number now.", color = Nova.Ink, maxLines = 1)
            }
            Text("112", style = text(Type.title * 1.8f, FontWeight.Black, Nova.Fail, tabular = true))
        }
        Spacer(Modifier.weight(1f))
    }
}

// ---- shared pieces ----

@Composable
fun BigButton(
    label: String,
    glyph: GlyphKind? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    primary: Boolean = true,
    pulse: Boolean = false,
    height: Dp = Space.button,
) {
    val reduced = LocalReducedMotion.current
    val halo = if (pulse && !reduced) {
        val loop = rememberInfiniteTransition(label = "pulse")
        val v by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "halo")
        v
    } else {
        0f
    }
    val bg = if (primary) Nova.Blue else Nova.Card
    val fg = if (primary) Color.White else Nova.Blue
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(bg)
            .then(if (primary) Modifier else Modifier.border(2.5.dp, Nova.Blue, shape))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (halo > 0f) {
            // a soft light that drifts across the button now and then
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width * (halo * 1.6f - 0.3f)
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0f)),
                        startX = x - size.width * 0.25f,
                        endX = x + size.width * 0.25f,
                    ),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            glyph?.let {
                Glyph(it, fg, 40.dp)
                Spacer(Modifier.width(Space.s))
            }
            Text(label, style = text(Type.button, FontWeight.SemiBold, fg), maxLines = 1)
        }
    }
}

@Composable
fun Pill(label: String, fg: Color, bg: Color, glyph: GlyphKind? = null) {
    Row(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        glyph?.let {
            Glyph(it, fg, 26.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = text(Type.caption, FontWeight.SemiBold, fg), maxLines = 1)
    }
}
