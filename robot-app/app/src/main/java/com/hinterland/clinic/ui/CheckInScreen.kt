package com.hinterland.clinic.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hinterland.clinic.CheckInStep
import com.hinterland.clinic.CheckInUi
import com.hinterland.clinic.Pace
import com.hinterland.clinic.Place
import com.hinterland.clinic.UiState

// self check-in, three big steps and no typing: who -> how to walk -> confirm
@Composable
fun CheckInContent(state: UiState, actions: ScreenActions) {
    val c = state.checkIn ?: return
    val reduced = LocalReducedMotion.current
    Column(Modifier.fillMaxSize()) {
        StepDots(c.stepNumber())
        Spacer(Modifier.height(Space.s))
        AnimatedContent(
            targetState = c.step,
            transitionSpec = {
                if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else (fadeIn(tween(320, delayMillis = 80)) + slideInHorizontally(tween(320, delayMillis = 80)) { it / 16 }) togetherWith fadeOut(tween(160))
            },
            label = "checkInStep",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { step ->
            Column(Modifier.fillMaxSize()) {
                Text(c.copy(step = step).stepLabel(), style = text(Type.step, FontWeight.Bold, Nova.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Space.m))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (step) {
                        CheckInStep.WHO -> Rooms(c, actions)
                        CheckInStep.ASK_DESK -> AskDesk()
                        CheckInStep.PACE -> PaceChoice(actions)
                        CheckInStep.CONFIRM -> Confirm(c, state.places, actions)
                        CheckInStep.SENDING -> Sending()
                        CheckInStep.FAILED -> Failed(c, actions)
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.m))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (c.step != CheckInStep.WHO && c.step != CheckInStep.SENDING) {
                BigButton("Back", GlyphKind.BACK, onClick = actions.checkInBack, modifier = Modifier.width(240.dp), primary = false, height = FOOTER_BUTTON)
            }
            Spacer(Modifier.weight(1f))
            if (c.step != CheckInStep.SENDING) {
                BigButton("Cancel", null, onClick = actions.checkInClose, modifier = Modifier.width(240.dp), primary = false, height = FOOTER_BUTTON)
            }
        }
    }
}

private val FOOTER_BUTTON = 96.dp

@Composable
private fun StepDots(current: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..3) {
            val done = i < current
            val now = i == current
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (now || done) Nova.Blue else Nova.Card)
                    .border(2.dp, if (now || done) Nova.Blue else Nova.Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Glyph(GlyphKind.CHECK, Color.White, 24.dp)
                else Text("$i", style = text(Type.caption, FontWeight.Bold, if (now) Color.White else Nova.InkFaint))
            }
            if (i < 3) Box(Modifier.width(36.dp).height(3.dp).background(if (done) Nova.Blue else Nova.Line))
        }
        Spacer(Modifier.width(Space.s))
        Text("Step $current of 3", style = text(Type.caption, FontWeight.Medium, Nova.InkSoft))
    }
}

@Composable
private fun Rooms(c: CheckInUi, actions: ScreenActions) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(c.rooms, key = { it.id }) { room ->
            Tile(room.name, room.room?.let { "Room $it" }, primary = true) { actions.room(room) }
        }
        item(key = "not-sure") {
            Tile("I'm not sure", "The desk will help", primary = false) { actions.room(null) }
        }
    }
}

@Composable
private fun Tile(title: String, note: String?, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Space.radius)
    Column(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(shape)
            .background(if (primary) Nova.Card else Nova.Paper)
            .border(2.dp, if (primary) Nova.Line else Nova.Pending, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = text(Type.body, FontWeight.Bold, if (primary) Nova.Ink else Nova.InkSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
        note?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = text(Type.caption, FontWeight.Medium, Nova.InkSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AskDesk() {
    Column {
        Text("Please go to the reception desk. They'll check you in and I'll walk you over.", style = text(Type.bodyLarge, FontWeight.Normal, Nova.InkSoft))
    }
}

@Composable
private fun PaceChoice(actions: ScreenActions) {
    Column(Modifier.fillMaxSize()) {
        Text("You can also just tell me.", style = text(Type.body, FontWeight.Normal, Nova.InkSoft))
        Spacer(Modifier.height(Space.m))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            ChoiceButton("Normal", GlyphKind.PACE_3, onClick = { actions.checkInPace(Pace.NORMAL, false) })
            ChoiceButton("Slower", GlyphKind.PACE_1, onClick = { actions.checkInPace(Pace.SLOW, false) })
            ChoiceButton("Wheelchair", GlyphKind.WHEELCHAIR, onClick = { actions.checkInPace(Pace.SLOW, true) })
        }
    }
}

@Composable
private fun Confirm(c: CheckInUi, places: List<Place>, actions: ScreenActions) {
    val room = c.room ?: return
    val waiting = room.waitingPlaceId?.let { id -> places.firstOrNull { it.id == id } }
    val walkTo = waiting?.name ?: room.label
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Space.radius))
                .background(Nova.Card)
                .border(1.5.dp, Nova.Line, RoundedCornerShape(Space.radius))
                .padding(horizontal = Space.l, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(room.name, style = text(Type.bodyLarge, FontWeight.Bold, Nova.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                room.room?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("Room $it", style = text(Type.body, FontWeight.Medium, Nova.InkSoft))
                }
            }
            Pill(
                if (c.wheelchair) "Wheelchair" else c.pace.label,
                Nova.Blue,
                Nova.SkySoft,
                if (c.wheelchair) GlyphKind.WHEELCHAIR else if (c.pace == Pace.NORMAL) GlyphKind.PACE_3 else GlyphKind.PACE_1,
            )
        }
        Spacer(Modifier.height(Space.m))
        Text("You get a ticket and the team knows you're here.", style = text(Type.body, FontWeight.Normal, Nova.InkSoft), maxLines = 1)
        Spacer(Modifier.height(Space.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(GlyphKind.ROUTE, Nova.InkSoft, 32.dp)
            Spacer(Modifier.width(12.dp))
            Text("I walk you to:  ", style = text(Type.body, FontWeight.Normal, Nova.InkSoft), maxLines = 1)
            Text(walkTo, style = text(Type.body, FontWeight.Bold, Nova.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        BigButton("Yes, let's go", GlyphKind.CHECK, onClick = actions.checkInConfirm)
    }
}

@Composable
private fun Sending() {
    val reduced = LocalReducedMotion.current
    val spin = if (reduced) {
        0f
    } else {
        val loop = rememberInfiniteTransition(label = "sending")
        val v by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1_200, easing = LinearEasing)), label = "spin")
        v
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(56.dp)) {
            val w = 6.dp.toPx()
            drawCircle(Nova.SkySoft, size.minDimension / 2f - w / 2f, style = Stroke(w))
            drawArc(Nova.Blue, spin, 100f, false, style = Stroke(w, cap = StrokeCap.Round), topLeft = Offset(w / 2f, w / 2f), size = Size(size.width - w, size.height - w))
        }
        Spacer(Modifier.width(Space.m))
        Text("One moment, I'm telling the team.", style = text(Type.bodyLarge, FontWeight.Normal, Nova.InkSoft))
    }
}

@Composable
private fun Failed(c: CheckInUi, actions: ScreenActions) {
    Column(Modifier.fillMaxSize()) {
        Text("I couldn't reach the practice system. The team at the desk can help you.", style = text(Type.bodyLarge, FontWeight.Normal, Nova.InkSoft))
        c.error?.let {
            Spacer(Modifier.height(Space.s))
            Text(it, style = text(Type.caption, FontWeight.Normal, Nova.InkFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        BigButton("Try again", null, onClick = actions.checkInConfirm)
    }
}
