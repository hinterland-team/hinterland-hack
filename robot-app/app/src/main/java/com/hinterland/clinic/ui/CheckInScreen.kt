package com.hinterland.clinic.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinterland.clinic.CheckInStep
import com.hinterland.clinic.CheckInUi
import com.hinterland.clinic.Pace
import com.hinterland.clinic.Place
import com.hinterland.clinic.UiState

// self check-in, no typing: who -> how to walk -> yes, let's go. one question per screen.
@Composable
fun CheckInContent(state: UiState, actions: ScreenActions) {
    val c = state.checkIn ?: return
    val reduced = LocalReducedMotion.current
    Column(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = c.step,
            transitionSpec = {
                if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else (fadeIn(tween(320, delayMillis = 80)) + slideInHorizontally(tween(320, delayMillis = 80)) { it / 16 }) togetherWith fadeOut(tween(160))
            },
            label = "checkInStep",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { step ->
            when (step) {
                CheckInStep.WHO -> Rooms(c, actions)
                CheckInStep.ASK_DESK -> Message("Please go to the desk", "They will check you in.", { IconBadge(GlyphKind.PERSON, Nova.Blue, Nova.SkySoft) })
                CheckInStep.PACE -> PaceChoice(actions)
                CheckInStep.CONFIRM -> Confirm(c, state.places, actions)
                CheckInStep.SENDING -> Message("One moment", "I'm getting your ticket.", { Spinner(120.dp) })
                CheckInStep.FAILED -> Message(
                    "That didn't work", "Please ask at the desk.",
                    { IconBadge(GlyphKind.CLOSE, Nova.Fail, Nova.FailSoft) },
                ) { BigButton("Try again", null, onClick = actions.checkInConfirm, height = Big.button) }
            }
        }
        if (c.step != CheckInStep.SENDING) {
            Spacer(Modifier.height(Space.s))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (c.step != CheckInStep.WHO) {
                    BigButton("Back", GlyphKind.BACK, onClick = actions.checkInBack, modifier = Modifier.width(260.dp), primary = false, height = FOOTER_BUTTON)
                }
                Spacer(Modifier.weight(1f))
                BigButton("Stop", GlyphKind.CLOSE, onClick = actions.checkInClose, modifier = Modifier.width(260.dp), primary = false, height = FOOTER_BUTTON)
            }
        }
    }
}

private val FOOTER_BUTTON = 96.dp

@Composable
private fun Rooms(c: CheckInUi, actions: ScreenActions) {
    Column(Modifier.fillMaxSize()) {
        Headline("Who are you seeing?", maxLines = 1)
        Spacer(Modifier.height(Space.m))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(c.rooms, key = { it.id }) { room ->
                Tile(room.name, room.room?.let { "Room $it" }, primary = true) { actions.room(room) }
            }
            item(key = "not-sure") {
                Tile("Not sure?", "Ask the desk", primary = false) { actions.room(null) }
            }
        }
    }
}

@Composable
private fun Tile(title: String, note: String?, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Space.radius)
    Column(
        Modifier
            .fillMaxWidth()
            .height(124.dp)
            .clip(shape)
            .background(if (primary) Nova.Card else Nova.Paper)
            .border(3.dp, if (primary) Nova.Line else Nova.Pending, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = text(38.sp, FontWeight.Bold, if (primary) Nova.Ink else Nova.InkSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
        note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = text(Big.label, FontWeight.Medium, Nova.InkSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PaceChoice(actions: ScreenActions) {
    Column(Modifier.fillMaxSize()) {
        Headline("How shall we walk?", maxLines = 1)
        Spacer(Modifier.height(Space.xs))
        Say("Tap or just tell me.", maxLines = 1)
        Spacer(Modifier.height(Space.m))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PictureButton("Normal", GlyphKind.PACE_3) { actions.checkInPace(Pace.NORMAL, false) }
            PictureButton("Slowly", GlyphKind.PACE_1) { actions.checkInPace(Pace.SLOW, false) }
            PictureButton("Wheelchair", GlyphKind.WHEELCHAIR) { actions.checkInPace(Pace.SLOW, true) }
        }
    }
}

// the doctor, the way there as a picture, one big yes
@Composable
private fun Confirm(c: CheckInUi, places: List<Place>, actions: ScreenActions) {
    val room = c.room ?: return
    val waiting = room.waitingPlaceId?.let { id -> places.firstOrNull { it.id == id } }
    Column(Modifier.fillMaxSize()) {
        Headline(room.name, maxLines = 1)
        Spacer(Modifier.height(Space.xs))
        Say("I'll walk you to ${waiting?.name ?: room.label}.", maxLines = 1)
        Spacer(Modifier.weight(1f))
        RouteMap(from = "Here", progress = null)
        Spacer(Modifier.weight(1f))
        BigButton("Yes, let's go", GlyphKind.CHECK, onClick = actions.checkInConfirm, pulse = true, height = Big.button)
    }
}
