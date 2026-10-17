package com.hinterland.clinic.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinterland.clinic.GuidePhase
import com.hinterland.clinic.GuideUi
import com.hinterland.clinic.Pace
import com.hinterland.clinic.UiState

private enum class GuideLayout { PICKUP, PACE, WALK, ARRIVED, END }

private fun GuidePhase.layout(): GuideLayout = when (this) {
    GuidePhase.TO_PICKUP, GuidePhase.PICKUP -> GuideLayout.PICKUP
    GuidePhase.PACE -> GuideLayout.PACE
    GuidePhase.WALKING, GuidePhase.CHECKING, GuidePhase.WAITING, GuidePhase.PAUSED, GuidePhase.LOOKING, GuidePhase.LOST -> GuideLayout.WALK
    GuidePhase.ARRIVED -> GuideLayout.ARRIVED
    GuidePhase.NOBODY_CAME, GuidePhase.FAILED, GuidePhase.CANCELLED -> GuideLayout.END
}

const val PRIVACY_LINE = "Nova only knows your ticket and the room. Nothing is recorded."

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
            GuideLayout.PACE -> PaceQuestion(now, actions)
            GuideLayout.WALK -> Walk(now, actions)
            GuideLayout.ARRIVED -> Arrived(now, actions)
            GuideLayout.END -> End(now)
        }
    }
}

// ---- pickup: the ticket, huge ----

@Composable
private fun Pickup(g: GuideUi, actions: ScreenActions) {
    Column(Modifier.fillMaxSize()) {
        Text("YOUR TICKET", style = text(Type.label, FontWeight.Bold, Nova.Blue, spacing = 3.sp))
        Text(
            g.ticket.ifBlank { "–" },
            style = text(Type.ticket, FontWeight.Black, Nova.Ink, lineHeight = Type.ticket, tabular = true),
            maxLines = 1,
        )
        Spacer(Modifier.height(Space.s))
        Text(g.step(), style = text(Type.step, FontWeight.Bold, Nova.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Space.s))
        Destination(g)
        Spacer(Modifier.weight(1f))
        if (g.phase == GuidePhase.PICKUP) {
            BigButton("That's me", GlyphKind.CHECK, onClick = actions.thatsMe, pulse = true)
        } else {
            NoteCard(if (g.from.isBlank()) "I'm on my way. Please wait there." else "Pickup point: ${g.from}. Please wait there.")
        }
    }
}

@Composable
private fun Destination(g: GuideUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(if (g.exit) GlyphKind.DOOR else GlyphKind.ROUTE, Nova.InkSoft, 32.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            listOfNotNull("To ${g.destination}", g.destinationNote).joinToString("  ·  "),
            style = text(Type.body, FontWeight.Medium, Nova.InkSoft),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- pace question ----

@Composable
private fun PaceQuestion(g: GuideUi, actions: ScreenActions) {
    Column(Modifier.fillMaxSize()) {
        Chips(g)
        Spacer(Modifier.height(Space.m))
        Text("How shall I walk?", style = text(Type.title, FontWeight.Bold, Nova.Ink))
        Spacer(Modifier.height(Space.s))
        Text("Tap or just tell me.", style = text(Type.bodyLarge, FontWeight.Normal, Nova.InkSoft))
        Spacer(Modifier.height(Space.l))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            ChoiceButton("Normal pace", GlyphKind.PACE_3, onClick = { actions.pace(Pace.NORMAL) })
            ChoiceButton("A bit slower", GlyphKind.PACE_1, onClick = { actions.pace(Pace.SLOW) })
        }
    }
}

@Composable
private fun Chips(g: GuideUi, alert: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (g.ticket.isNotBlank()) {
            Pill(g.ticket, if (alert) Nova.Amber else Nova.BlueDeep, if (alert) Nova.AmberSoft else Nova.SkySoft, GlyphKind.TICKET)
            Spacer(Modifier.width(12.dp))
        }
        Pill(g.pace.label, Nova.Teal, Nova.TealSoft, paceGlyph(g.pace))
    }
}

private fun paceGlyph(p: Pace) = when (p) {
    Pace.NORMAL -> GlyphKind.PACE_3
    Pace.SLOW -> GlyphKind.PACE_2
    Pace.VERY_SLOW -> GlyphKind.PACE_1
}

// ---- walking, check-back, waiting, lost ----

@Composable
private fun Walk(g: GuideUi, actions: ScreenActions) {
    val alert = g.phase == GuidePhase.LOOKING || g.phase == GuidePhase.LOST
    val accent = when (g.phase) {
        GuidePhase.LOOKING, GuidePhase.LOST -> Nova.Amber
        GuidePhase.CHECKING -> Nova.Teal
        else -> Nova.Blue
    }
    val reduced = LocalReducedMotion.current
    Column(Modifier.fillMaxSize()) {
        Chips(g, alert)
        Spacer(Modifier.height(Space.m))
        if (alert && g.ticket.isNotBlank()) {
            // someone is missing: the ticket is what they recognise from afar
            Text("Ticket ${g.ticket}", style = text(Type.title, FontWeight.Bold, Nova.Ink), maxLines = 1)
            Spacer(Modifier.height(Space.xs))
            Destination(g)
        } else {
            Text(g.destination, style = text(Type.title, FontWeight.Bold, Nova.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            g.destinationNote?.let {
                Spacer(Modifier.height(Space.xs))
                Text(it, style = text(Type.bodyLarge, FontWeight.Medium, Nova.InkSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(Space.l))
        ProgressLine(g, accent)
        Spacer(Modifier.height(Space.l))
        AnimatedContent(
            targetState = g.phase,
            transitionSpec = {
                if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else fadeIn(tween(300, delayMillis = 60)) togetherWith fadeOut(tween(150))
            },
            label = "walkStep",
        ) { phase ->
            val shown = g.copy(phase = phase)
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(Space.s))
                    Text(shown.step(), style = text(Type.step, FontWeight.Bold, accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(Space.xs))
                Text(
                    support(shown),
                    style = text(Type.body, FontWeight.Normal, Nova.InkSoft),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 34.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        when {
            g.needsPresence -> BigButton("I'm here", GlyphKind.CHECK, onClick = actions.imHere)
            g.phase == GuidePhase.PAUSED -> BigButton("Let's go on", GlyphKind.ROUTE, onClick = actions.goOn)
        }
        Spacer(Modifier.height(Space.m))
        Privacy()
    }
}

private fun support(g: GuideUi): String = when (g.phase) {
    GuidePhase.WALKING -> "Walk behind me, I'll check on you now and then."
    GuidePhase.CHECKING -> "All good? Shall I slow down?"
    GuidePhase.WAITING -> "Take your time, I'll wait right here."
    GuidePhase.PAUSED -> "Tell me or tap when you're ready."
    GuidePhase.LOOKING -> "Ticket ${g.ticket}, I'm over here."
    GuidePhase.LOST -> "I've asked a team member to come and help."
    else -> ""
}

// from where nova started to the destination, with nova's dot on the way
@Composable
private fun ProgressLine(g: GuideUi, accent: Color) {
    val reduced = LocalReducedMotion.current
    val known = g.progress != null
    val p by animateFloatAsState(g.progress ?: 0f, calm(900, reduced), label = "progress")
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val r = size.height / 2f
            val y = size.height / 2f
            val track = 10.dp.toPx()
            val x0 = r
            val x1 = size.width - r
            if (known) {
                drawLine(Nova.Line, Offset(x0, y), Offset(x1, y), track, StrokeCap.Round)
                drawLine(Nova.Teal, Offset(x0, y), Offset(x0 + (x1 - x0) * p, y), track, StrokeCap.Round)
            } else {
                // not known how far: a calm dotted way
                drawLine(Nova.Line, Offset(x0, y), Offset(x1, y), track * 0.6f, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 14.dp.toPx())))
            }
            drawCircle(Nova.Teal, r * 0.55f, Offset(x0, y))
            drawCircle(Color.White, r * 0.95f, Offset(x1, y))
            drawCircle(if (p >= 0.999f) Nova.Teal else Nova.Blue, r * 0.95f, Offset(x1, y), style = Stroke(4.dp.toPx()))
            val nx = x0 + (x1 - x0) * p
            drawCircle(Color.White, r, Offset(nx, y))
            drawCircle(accent, r * 0.72f, Offset(nx, y))
        }
        Spacer(Modifier.height(Space.xs))
        Row(Modifier.fillMaxWidth()) {
            Text(g.from.ifBlank { "Start" }, style = text(Type.caption, FontWeight.Medium, Nova.InkSoft), maxLines = 1, modifier = Modifier.weight(1f))
            Text(
                if (known) "${(p * 100).toInt()} %" else "On our way",
                style = text(Type.caption, FontWeight.SemiBold, Nova.Teal, tabular = true),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(g.destination, style = text(Type.caption, FontWeight.Medium, Nova.InkSoft), maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Privacy() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(GlyphKind.LOCK, Nova.InkFaint, 24.dp)
        Spacer(Modifier.width(12.dp))
        Text(PRIVACY_LINE, style = text(Type.caption, FontWeight.Normal, Nova.InkSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- arrived ----

@Composable
private fun Arrived(g: GuideUi, actions: ScreenActions) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(Nova.Teal), contentAlignment = Alignment.Center) {
            Glyph(GlyphKind.CHECK, Color.White, 56.dp)
        }
        Spacer(Modifier.height(Space.m))
        Text(
            if (g.exit) "Goodbye and get well soon" else "This is ${g.destination}",
            style = text(Type.endTitle, FontWeight.Bold, Nova.Ink),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Space.s))
        Text(
            if (g.exit) "Thank you for visiting. Take care!" else "Please take a seat. The team knows you're here.",
            style = text(Type.bodyLarge, FontWeight.Normal, Nova.InkSoft),
        )
        Spacer(Modifier.weight(1f))
        Feedback(g, actions)
    }
}

@Composable
private fun Feedback(g: GuideUi, actions: ScreenActions) {
    val shape = RoundedCornerShape(Space.radius)
    Row(
        Modifier
            .fillMaxWidth()
            .height(144.dp)
            .clip(shape)
            .background(Nova.Card)
            .border(1.5.dp, Nova.Line, shape)
            .padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (g.feedback == null) {
            Column(Modifier.weight(1f)) {
                Text("How was the walk?", style = text(Type.bodyLarge, FontWeight.SemiBold, Nova.Ink))
                Spacer(Modifier.height(6.dp))
                Text("One tap, anonymous", style = text(Type.caption, FontWeight.Normal, Nova.InkSoft))
            }
            Thumb(GlyphKind.THUMB_UP, Nova.Teal) { actions.feedback(true) }
            Spacer(Modifier.width(Space.s))
            Thumb(GlyphKind.THUMB_DOWN, Nova.InkSoft) { actions.feedback(false) }
        } else {
            Glyph(GlyphKind.CHECK, Nova.Teal, 40.dp)
            Spacer(Modifier.width(Space.s))
            Text("Thank you!", style = text(Type.bodyLarge, FontWeight.SemiBold, Nova.Ink))
        }
    }
}

@Composable
private fun Thumb(kind: GlyphKind, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(104.dp)
            .clip(CircleShape)
            .background(Nova.Paper)
            .border(2.dp, Nova.Line, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(kind, color, 52.dp)
    }
}

// ---- nobody came, failed, cancelled ----

@Composable
private fun End(g: GuideUi) {
    val failed = g.phase == GuidePhase.FAILED
    Column(Modifier.fillMaxSize()) {
        if (g.ticket.isNotBlank()) {
            Pill(g.ticket, if (failed) Nova.Fail else Nova.Amber, if (failed) Nova.FailSoft else Nova.AmberSoft, GlyphKind.TICKET)
            Spacer(Modifier.height(Space.m))
        }
        Text(g.step(), style = text(Type.endTitle, FontWeight.Bold, Nova.Ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Space.s))
        Text(
            when (g.phase) {
                GuidePhase.NOBODY_CAME -> "I'm going back. The team can send me again any time."
                GuidePhase.FAILED -> listOfNotNull(g.note, "A team member will help you.").joinToString(". ")
                else -> "A team member will help you."
            },
            style = text(Type.bodyLarge, FontWeight.Normal, Nova.InkSoft),
        )
    }
}
