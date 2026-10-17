package com.hinterland.clinic.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinterland.clinic.BrainStatus
import com.hinterland.clinic.Health
import com.hinterland.clinic.Pace
import com.hinterland.clinic.Place
import com.hinterland.clinic.RobotState
import com.hinterland.clinic.UiState
import kotlin.math.min

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
// so it looks the same at any density. left: nova's face, what was just said, status. right: the task.
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
                Row(
                    Modifier
                        .size(DESIGN_WIDTH, DESIGN_HEIGHT)
                        .padding(Space.page),
                    horizontalArrangement = Arrangement.spacedBy(Space.page),
                ) {
                    FaceColumn(state, actions, Modifier.width(Space.faceColumn).fillMaxHeight())
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
                        modifier = Modifier.weight(1f).fillMaxHeight(),
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

// ---- left: face, caption, status ----

@Composable
private fun FaceColumn(state: UiState, actions: ScreenActions, modifier: Modifier) {
    Column(modifier) {
        NovaLogo(onLongPress = actions.openStaff)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Mascot(state.mood(), Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(Space.s))
        Caption(state)
        Spacer(Modifier.height(Space.s))
        StatusLine(state)
    }
}

// what nova or the person just said, always the same height
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
                fromUser -> "I'm listening to you..."
                state.subtitle.isBlank() -> "I'm listening whenever you're ready."
                else -> state.subtitle
            },
            style = text(Type.body, FontWeight.Medium, if (fromUser || state.subtitle.isBlank()) Nova.InkSoft else Nova.Ink),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val CAPTION_HEIGHT = 188.dp

@Composable
private fun StatusLine(state: UiState) {
    val brain = state.brain
    val online = state.backend != Health.DOWN && state.robot != Health.DOWN && state.chassis == null
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (online) Nova.Done else Nova.Fail))
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                state.chassis != null -> "Driving off"
                state.robot == Health.DOWN -> "Driving not ready"
                state.backend == Health.DOWN -> "Practice offline"
                else -> "Online"
            },
            style = text(Type.caption, FontWeight.Medium, Nova.InkSoft),
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        BatteryGlyph(brain.battery, brain.charging)
        Spacer(Modifier.width(10.dp))
        Text(brain.battery?.let { "$it %" } ?: "–", style = text(Type.caption, FontWeight.Medium, Nova.InkSoft, tabular = true))
    }
}

@Composable
private fun BatteryGlyph(level: Int?, charging: Boolean) {
    val fill = when {
        charging -> Nova.Done
        level != null && level <= 20 -> Nova.Fail
        else -> Nova.InkSoft
    }
    Canvas(Modifier.size(width = 34.dp, height = 17.dp)) {
        val stroke = 2.dp.toPx()
        val tip = 3.5.dp.toPx()
        val bodyW = size.width - tip - stroke
        drawRoundRect(Nova.InkSoft, Offset(stroke / 2f, stroke / 2f), Size(bodyW, size.height - stroke), CornerRadius(4.dp.toPx()), style = Stroke(stroke))
        drawRoundRect(Nova.InkSoft, Offset(bodyW + stroke / 2f, size.height * 0.3f), Size(tip, size.height * 0.4f), CornerRadius(1.dp.toPx()))
        if (level != null) {
            val pad = stroke + 2.dp.toPx()
            val inner = bodyW - pad * 2f + stroke
            drawRoundRect(
                fill,
                Offset(pad, pad),
                Size((inner * level.coerceIn(0, 100) / 100f).coerceAtLeast(1.dp.toPx()), size.height - pad * 2f),
                CornerRadius(2.dp.toPx()),
            )
        }
    }
}

// ---- idle ----

@Composable
private fun IdleContent(state: UiState, actions: ScreenActions) {
    Column(Modifier.fillMaxSize()) {
        Text("Hello, I'm Nova", style = text(Type.title, FontWeight.Bold, Nova.Ink))
        Spacer(Modifier.height(Space.s))
        Text(
            "I walk with you to your room, at your pace.",
            style = text(Type.bodyLarge, FontWeight.Normal, Nova.InkSoft),
        )
        Spacer(Modifier.height(Space.l))
        if (state.canSelfCheckIn) {
            BigButton("Check in", GlyphKind.TICKET, onClick = actions.checkIn, pulse = true)
        } else {
            NoteCard("The desk sends me to you. I'll call your ticket.")
        }
        Spacer(Modifier.weight(1f))
        Capability(GlyphKind.ROUTE, "Walk with you", "To your room, slowly if you like")
        Spacer(Modifier.height(Space.m))
        Capability(GlyphKind.DOOR, "Show the way", "To the exit or the reception")
        Spacer(Modifier.height(Space.m))
        Capability(GlyphKind.PERSON, "Get the team", "If you need a person")
        Spacer(Modifier.weight(1f))
        val busy = idleBusy(state.brain)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(if (busy != null) GlyphKind.ROUTE else GlyphKind.MIC, Nova.Blue, 30.dp)
            Spacer(Modifier.width(12.dp))
            Text(busy ?: "Just talk to me, in your language.", style = text(Type.body, FontWeight.Medium, Nova.Blue), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// what nova does right now when it is not the plain waiting, else null
private fun idleBusy(b: BrainStatus): String? = when (b.state) {
    RobotState.RETURNING, RobotState.CHARGING -> b.goal
    RobotState.ERROR -> b.detail ?: b.goal
    else -> null
}

@Composable
private fun Capability(glyph: GlyphKind, title: String, caption: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Nova.SkySoft), contentAlignment = Alignment.Center) {
            Glyph(glyph, Nova.Blue, 34.dp)
        }
        Spacer(Modifier.width(Space.m))
        Column {
            Text(title, style = text(Type.body, FontWeight.SemiBold, Nova.Ink))
            Spacer(Modifier.height(4.dp))
            Text(caption, style = text(Type.caption, FontWeight.Normal, Nova.InkSoft))
        }
    }
}

// ---- out of service: no chassis rights, emergency stop, the system took over ----

@Composable
private fun OutOfServiceContent(state: UiState) {
    val issue = state.chassis ?: return
    Column(Modifier.fillMaxSize()) {
        Pill("Out of service", Nova.Amber, Nova.AmberSoft, GlyphKind.PLUG)
        Spacer(Modifier.height(Space.m))
        Text(issue.title, style = text(Type.endTitle, FontWeight.Bold, Nova.Ink), maxLines = 2)
        Spacer(Modifier.height(Space.s))
        Text(issue.hint, style = text(Type.bodyLarge, FontWeight.Normal, Nova.InkSoft), maxLines = 3)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Space.radius))
                .background(Nova.Card)
                .border(1.5.dp, Nova.Line, RoundedCornerShape(Space.radius))
                .padding(horizontal = Space.l, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Nova.SkySoft), contentAlignment = Alignment.Center) {
                Glyph(GlyphKind.MIC, Nova.Blue, 34.dp)
            }
            Spacer(Modifier.width(Space.m))
            Column {
                Text("I can still talk with you", style = text(Type.body, FontWeight.SemiBold, Nova.Ink))
                Spacer(Modifier.height(4.dp))
                Text("For the way to your room, the reception desk will help.", style = text(Type.caption, FontWeight.Normal, Nova.InkSoft))
            }
        }
        Spacer(Modifier.height(Space.m))
        Text(issue.detail, style = text(Type.caption, FontWeight.Normal, Nova.InkFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- urgent help ----

@Composable
private fun HelpContent(state: UiState) {
    val sent = state.help?.sent
    Column(Modifier.fillMaxSize()) {
        Pill(if (sent == false) "Could not reach the team" else "Team informed", if (sent == false) Nova.Fail else Nova.Amber, if (sent == false) Nova.FailSoft else Nova.AmberSoft)
        Spacer(Modifier.height(Space.m))
        Text("Help is on the way", style = text(Type.title, FontWeight.Bold, Nova.Ink))
        Spacer(Modifier.height(Space.s))
        Text("I'll stay right here with you.", style = text(Type.bodyLarge, FontWeight.Normal, Nova.InkSoft))
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
                Text("In an emergency", style = text(Type.body, FontWeight.SemiBold, Nova.Fail))
                Spacer(Modifier.height(Space.xs))
                Text("Call the emergency number now", style = text(Type.bodyLarge, FontWeight.Medium, Nova.Ink))
            }
            Text("112", style = text(Type.title * 1.6f, FontWeight.Black, Nova.Fail, tabular = true))
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
fun RowScope.ChoiceButton(label: String, glyph: GlyphKind, onClick: () -> Unit, selected: Boolean = false) {
    val shape = RoundedCornerShape(Space.radius)
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) Nova.SkySoft else Nova.Card)
            .border(if (selected) 3.dp else 2.dp, if (selected) Nova.Blue else Nova.Line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Space.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Glyph(glyph, Nova.Blue, 64.dp)
        Spacer(Modifier.height(Space.s))
        Text(label, style = text(Type.body, FontWeight.SemiBold, Nova.Ink), maxLines = 1)
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

@Composable
fun NoteCard(textLine: String, fg: Color = Nova.InkSoft, bg: Color = Nova.Card) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Space.radius))
            .background(bg)
            .border(1.5.dp, Nova.Line, RoundedCornerShape(Space.radius))
            .padding(horizontal = Space.l, vertical = Space.m),
    ) {
        Text(textLine, style = text(Type.body, FontWeight.Medium, fg))
    }
}
