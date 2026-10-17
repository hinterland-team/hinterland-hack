package com.hinterland.clinic.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinterland.clinic.GuidePhase
import com.hinterland.clinic.GuideUi
import com.hinterland.clinic.Pace
import com.hinterland.clinic.TalkStage
import com.hinterland.clinic.TalkUi
import com.hinterland.clinic.UiState

// the walk on the screen, kept very simple: where we go (big), the way there as a picture,
// what to do now in one line, and one big button when a tap is needed.

private enum class GuideLayout { PICKUP, PACE, WALK, ARRIVED, END }

private fun GuidePhase.layout(): GuideLayout = when (this) {
    GuidePhase.TO_PICKUP, GuidePhase.PICKUP -> GuideLayout.PICKUP
    GuidePhase.PACE -> GuideLayout.PACE
    GuidePhase.WALKING, GuidePhase.CHECKING, GuidePhase.WAITING, GuidePhase.PAUSED, GuidePhase.LOOKING, GuidePhase.LOST,
    GuidePhase.BLOCKED -> GuideLayout.WALK
    GuidePhase.ARRIVED -> GuideLayout.ARRIVED
    GuidePhase.NOBODY_CAME, GuidePhase.FAILED, GuidePhase.CANCELLED -> GuideLayout.END
}

@Composable
fun GuideContent(state: UiState, actions: ScreenActions) {
    val g = state.guide ?: return
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = g.phase.layout(),
        transitionSpec = {
            if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            else (fadeIn(tween(400, delayMillis = 100)) + scaleIn(tween(400, delayMillis = 100), initialScale = 0.97f)) togetherWith fadeOut(tween(180))
        },
        label = "guideLayout",
        modifier = Modifier.fillMaxSize(),
    ) { layout ->
        // the layout lambda reads the latest guide state, the key only decides when to cross-fade
        val now = state.guide ?: g
        when (layout) {
            GuideLayout.PICKUP -> Pickup(now, actions)
            GuideLayout.PACE -> PaceQuestion(actions)
            GuideLayout.WALK -> Walk(now, actions)
            GuideLayout.ARRIVED -> Arrived(now, actions)
            GuideLayout.END -> End(now)
        }
    }
}

// ---- pickup: otto on his way to you, then your ticket, huge ----

@Composable
private fun Pickup(g: GuideUi, actions: ScreenActions) {
    if (g.phase == GuidePhase.TO_PICKUP) {
        Column(Modifier.fillMaxSize()) {
            Headline("I'm coming to you")
            Spacer(Modifier.height(Space.xs))
            Say(if (g.from.isBlank()) "Please wait where you are." else "Please wait at ${g.from}.")
            Spacer(Modifier.weight(1f))
            RouteMap(from = "Otto", progress = g.progress)
            Spacer(Modifier.weight(1f))
            if (g.ticket.isNotBlank()) TicketLine(g.ticket)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("YOUR TICKET", style = text(Big.label, FontWeight.Bold, Nova.Blue, spacing = 3.sp))
                Text(g.ticket.ifBlank { "–" }, style = text(Type.ticket, FontWeight.Black, Nova.Ink, lineHeight = Type.ticket, tabular = true), maxLines = 1)
            }
            g.walkUrl?.let { url ->
                Spacer(Modifier.width(Space.m))
                PhoneCard(url)
            }
        }
        Say("Is this you? Please come to me.")
        Spacer(Modifier.weight(1f))
        BigButton("That's me", GlyphKind.CHECK, onClick = actions.thatsMe, pulse = true, height = Big.button)
    }
}

@Composable
private fun TicketLine(ticket: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(GlyphKind.TICKET, Nova.Blue, 48.dp)
        Spacer(Modifier.width(Space.s))
        Text("Your ticket", style = text(Big.line, FontWeight.Medium, Nova.InkSoft))
        Spacer(Modifier.width(Space.s))
        Text(ticket, style = text(56.sp, FontWeight.Black, Nova.Ink, tabular = true), maxLines = 1)
    }
}

// the phone companion: scan the code to walk with your phone
@Composable
private fun PhoneCard(url: String) {
    val shape = RoundedCornerShape(Space.radius)
    Column(
        Modifier
            .clip(shape)
            .background(Nova.Card)
            .border(1.5.dp, Nova.Line, shape)
            .padding(Space.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        QrCode(url, 168.dp)
        Spacer(Modifier.height(Space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(GlyphKind.PHONE, Nova.Teal, 30.dp)
            Spacer(Modifier.width(6.dp))
            Text("Your phone", style = text(Big.label, FontWeight.SemiBold, Nova.Ink), maxLines = 1)
        }
    }
}

// ---- pace ----

@Composable
private fun PaceQuestion(actions: ScreenActions) {
    Column(Modifier.fillMaxSize()) {
        Headline("How shall we walk?")
        Spacer(Modifier.height(Space.xs))
        Say("Tap or just tell me.")
        Spacer(Modifier.height(Space.l))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PictureButton("Normal", GlyphKind.PACE_3) { actions.pace(Pace.NORMAL) }
            PictureButton("Slowly", GlyphKind.PACE_1) { actions.pace(Pace.SLOW) }
        }
    }
}

// ---- walking, check-back, waiting, lost ----

@Composable
private fun Walk(g: GuideUi, actions: ScreenActions) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = g.talk != null,
        transitionSpec = {
            if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            else (fadeIn(tween(380, delayMillis = 80)) + scaleIn(tween(380, delayMillis = 80), initialScale = 0.98f)) togetherWith fadeOut(tween(160))
        },
        label = "talk",
        modifier = Modifier.fillMaxSize(),
    ) { talking ->
        val talk = g.talk
        if (talking && talk != null) Talk(talk, actions) else WalkNow(g, actions)
    }
}

@Composable
private fun WalkNow(g: GuideUi, actions: ScreenActions) {
    val imHere = @Composable { BigButton("I'm here", GlyphKind.CHECK, onClick = actions.imHere, height = Big.button) }
    when (g.phase) {
        GuidePhase.WAITING -> Message("I'll wait for you", "Take your time.", { IconBadge(GlyphKind.PAUSE, Nova.Teal, Nova.TealSoft) }, imHere)
        GuidePhase.PAUSED -> Message(
            "Let's take a break", "Tap when you're ready.",
            { IconBadge(GlyphKind.PAUSE, Nova.Teal, Nova.TealSoft) },
        ) { BigButton("Let's go on", GlyphKind.ROUTE, onClick = actions.goOn, height = Big.button) }
        // someone is missing: the ticket is what they recognise from afar
        GuidePhase.LOOKING -> Message(
            if (g.ticket.isBlank()) "Where are you?" else "Ticket ${g.ticket}?", "I'm over here!",
            { IconBadge(GlyphKind.PERSON, Nova.Amber, Nova.AmberSoft, pulse = true) }, imHere,
        )
        GuidePhase.LOST -> Message(
            "Help is coming", "A team member is on the way.",
            { IconBadge(GlyphKind.PERSON, Nova.Amber, Nova.AmberSoft, pulse = true) }, imHere,
        )
        // the way stays blocked: otto waits here with the patient until the team comes (6.7)
        GuidePhase.BLOCKED -> Message(
            "The way is blocked", "Help is on the way. Please wait with me.",
            { IconBadge(GlyphKind.ROUTE, Nova.Amber, Nova.AmberSoft, pulse = true) },
        )
        else -> Column(Modifier.fillMaxSize()) {
            Headline(g.destination, maxLines = 1)
            g.destinationNote?.let { Say(it, maxLines = 1) }
            Spacer(Modifier.weight(1f))
            RouteMap(from = g.from.ifBlank { "Start" }, progress = g.progress)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(GlyphKind.ROUTE, Nova.Teal, 48.dp)
                Spacer(Modifier.width(Space.s))
                Text(
                    when {
                        g.phase == GuidePhase.CHECKING -> "Are you still with me?"
                        g.detour -> "The way is blocked. Stay close to me."
                        // walk beside (6.7): where otto's radar sees the patient
                        else -> "Please walk on my ${g.side.wire}."
                    },
                    style = text(Big.line, FontWeight.SemiBold, Nova.Teal),
                    maxLines = 1,
                )
            }
            if (g.needsPresence) {
                Spacer(Modifier.height(Space.m))
                imHere()
            }
        }
    }
}

// face to face at a check-back: the question, otto listening, three big answers
@Composable
private fun Talk(talk: TalkUi, actions: ScreenActions) {
    Column(Modifier.fillMaxSize()) {
        Headline(talk.question.substringBefore('?').trim() + "?")
        talk.question.substringAfter('?', "").trim().takeIf { it.isNotEmpty() }?.let {
            Spacer(Modifier.height(Space.xs))
            Say(it, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        Listening(talk)
        Spacer(Modifier.weight(1f))
        Buttons {
            BigButton("All good", GlyphKind.CHECK, onClick = actions.allGood, modifier = Modifier.weight(1.3f), height = Big.button)
            BigButton("Slower", GlyphKind.PACE_1, onClick = actions.talkSlower, modifier = Modifier.weight(1f), primary = false, height = Big.button)
            BigButton("Wait", GlyphKind.PAUSE, onClick = actions.talkWait, modifier = Modifier.weight(1f), primary = false, height = Big.button)
        }
    }
}

// a mic in a soft circle; while otto listens a ring runs down over the listening time
@Composable
private fun Listening(talk: TalkUi) {
    val reduced = LocalReducedMotion.current
    val ring = remember(talk.id) { Animatable(talk.listened ?: 0f) }
    LaunchedEffect(talk.id, talk.stage) {
        if (talk.stage == TalkStage.LISTENING && talk.listened == null && !reduced) {
            ring.animateTo(1f, tween(talk.listenMs.toInt(), easing = LinearEasing))
        }
    }
    // read while drawing only (v3.3): the loop redraws the ring, nothing recomposes
    val breathe = if (reduced || talk.stage == TalkStage.LISTENING) {
        null
    } else {
        val loop = rememberInfiniteTransition(label = "talkWait")
        loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1_100), RepeatMode.Reverse), label = "breathe")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 8.dp.toPx()
                drawCircle(Nova.TealSoft, size.minDimension / 2f)
                if (talk.stage == TalkStage.LISTENING) {
                    drawArc(
                        Nova.Teal, -90f, 360f * (1f - ring.value), false,
                        topLeft = Offset(w / 2f, w / 2f), size = Size(size.width - w, size.height - w),
                        style = Stroke(w, cap = StrokeCap.Round),
                    )
                } else {
                    drawCircle(Nova.Teal.copy(alpha = 0.25f + 0.25f * (breathe?.value ?: 0f)), size.minDimension / 2f - w / 2f, style = Stroke(w))
                }
            }
            Glyph(GlyphKind.MIC, Nova.Teal, 52.dp)
        }
        Spacer(Modifier.width(Space.m))
        Column {
            Text(
                when (talk.stage) {
                    TalkStage.ASKING -> "Let me ask you"
                    TalkStage.LISTENING -> "I'm listening"
                    TalkStage.REPLYING -> "One moment"
                },
                style = text(40.sp, FontWeight.Bold, Nova.Teal),
            )
            Spacer(Modifier.height(Space.xs))
            Say("Just say it, or tap below.", maxLines = 1)
        }
    }
}

// ---- arrived ----

@Composable
private fun Arrived(g: GuideUi, actions: ScreenActions) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArrivedCheck(124.dp)
            Spacer(Modifier.width(Space.m))
            Column {
                Headline(
                    when {
                        g.exit -> "Goodbye!"
                        g.waiting -> "Please take a seat"
                        else -> "We're here!"
                    },
                    maxLines = 1,
                )
                Say(
                    when {
                        g.exit -> "Get well soon."
                        g.waiting -> "I'll come back for you."
                        else -> listOfNotNull(g.destination, g.destinationNote).joinToString("  ·  ")
                    },
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        RouteMap(from = g.from.ifBlank { "Start" }, progress = 1f, arrived = true)
        Spacer(Modifier.weight(1f))
        Feedback(g, actions)
    }
}

@Composable
private fun Feedback(g: GuideUi, actions: ScreenActions) {
    Row(Modifier.fillMaxWidth().height(Big.button), verticalAlignment = Alignment.CenterVertically) {
        if (g.feedback == null) {
            Text("How was it?", style = text(44.sp, FontWeight.Bold, Nova.Ink), modifier = Modifier.weight(1f))
            Thumb(GlyphKind.THUMB_UP, Nova.Teal) { actions.feedback(true) }
            Spacer(Modifier.width(Space.m))
            Thumb(GlyphKind.THUMB_DOWN, Nova.InkSoft) { actions.feedback(false) }
        } else {
            Glyph(GlyphKind.CHECK, Nova.Teal, 56.dp)
            Spacer(Modifier.width(Space.s))
            Text("Thank you!", style = text(44.sp, FontWeight.Bold, Nova.Ink))
        }
    }
}

@Composable
private fun Thumb(kind: GlyphKind, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Big.button)
            .clip(CircleShape)
            .background(Nova.Card)
            .border(3.dp, Nova.Line, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(kind, color, 64.dp)
    }
}

// ---- nobody came, failed, cancelled ----

@Composable
private fun End(g: GuideUi) {
    Message(
        g.step(),
        if (g.phase == GuidePhase.NOBODY_CAME) "The desk can send me again." else "A team member will help you.",
        { IconBadge(GlyphKind.PERSON, Nova.Amber, Nova.AmberSoft) },
    )
}
