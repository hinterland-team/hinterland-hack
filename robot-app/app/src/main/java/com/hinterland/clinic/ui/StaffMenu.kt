package com.hinterland.clinic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinterland.clinic.LogLevel
import com.hinterland.clinic.LogLine
import com.hinterland.clinic.ServerState
import com.hinterland.clinic.StaffUi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// the staff menu (long-press the Nova Clinic logo 3 s): servers, chassis, battery, radar, version and the
// live log. laid out on the same 1280 x 720 design grid as the patient screens.
@Composable
fun StaffMenu(staff: StaffUi, actions: ScreenActions, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(0.dp))
            .background(Nova.Paper)
            // any touch keeps the menu open (it closes by itself after 2 min)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    actions.staffTouched()
                }
            }
            .padding(Space.page),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NovaLogo()
            Spacer(Modifier.width(Space.s))
            Pill("Staff", Nova.BlueDeep, Nova.SkySoft)
            Spacer(Modifier.width(Space.s))
            Text(
                "${staff.robotName} · app ${staff.version}" + (staff.system?.let { " · robot $it" } ?: ""),
                style = text(Type.caption, FontWeight.Medium, Nova.InkSoft),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            BigButton("Close", GlyphKind.CLOSE, onClick = actions.closeStaff, modifier = Modifier.width(200.dp), primary = false, height = 72.dp)
        }
        Spacer(Modifier.height(Space.m))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Column(Modifier.width(430.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                ServersCard(staff, actions)
                StatusCard("Robot") {
                    StatusRow(if (staff.chassisOk) Nova.Done else Nova.Fail, staff.chassis)
                    Spacer(Modifier.height(10.dp))
                    StatusRow(
                        when {
                            staff.charging -> Nova.Done
                            (staff.battery ?: 100) <= 20 -> Nova.Fail
                            else -> Nova.InkFaint
                        },
                        "Battery " + (staff.battery?.let { "$it %" } ?: "unknown") + if (staff.charging) ", charging" else "",
                    )
                    if (staff.emergency == true) {
                        Spacer(Modifier.height(10.dp))
                        StatusRow(Nova.Amber, "Emergency status reports pressed")
                    }
                    Spacer(Modifier.height(10.dp))
                    StatusRow(Nova.Blue, staff.brain)
                }
                StatusCard("Radar") {
                    Text(staff.radar, style = text(SMALL, FontWeight.Normal, Nova.Ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            LogCard(staff, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

private val SMALL = 20.sp

@Composable
private fun ServersCard(staff: StaffUi, actions: ScreenActions) {
    StatusCard("Server") {
        if (staff.servers.isEmpty()) {
            Text("No server set (teamInbox.url)", style = text(SMALL, FontWeight.Medium, Nova.Fail))
        }
        staff.servers.forEach { s ->
            ServerRow(s, s.url == staff.current)
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Check now",
            style = text(SMALL, FontWeight.SemiBold, Nova.Blue),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = actions.recheckServers)
                .padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun ServerRow(s: ServerState, inUse: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(
            when (s.up) {
                true -> Nova.Done
                false -> Nova.Fail
                null -> Nova.Pending
            },
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.host,
                style = text(SMALL, if (inUse) FontWeight.Bold else FontWeight.Medium, Nova.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val note = when (s.up) {
                true -> "${s.ms ?: "?"} ms" + (s.note?.let { " · $it" } ?: "")
                false -> s.note ?: "no answer"
                null -> "not checked yet"
            }
            Text(note, style = text(16.sp, FontWeight.Normal, Nova.InkSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (inUse) {
            Spacer(Modifier.width(8.dp))
            Text("IN USE", style = text(14.sp, FontWeight.Bold, Nova.Teal, spacing = 1.5.sp))
        }
    }
}

@Composable
private fun StatusCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Nova.Card)
            .border(1.5.dp, Nova.Line, shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(title.uppercase(), style = text(15.sp, FontWeight.Bold, Nova.Blue, spacing = 2.sp))
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun StatusRow(color: Color, line: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(10.dp))
        Text(line, style = text(SMALL, FontWeight.Medium, Nova.Ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LogCard(staff: StaffUi, modifier: Modifier) {
    var onlyProblems by rememberSaveable { mutableStateOf(false) }
    val lines = remember(staff.logs, onlyProblems) {
        staff.logs.asReversed().filter { !onlyProblems || it.level.ordinal >= LogLevel.WARN.ordinal }
    }
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(Nova.Card)
            .border(1.5.dp, Nova.Line, shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LOG", style = text(15.sp, FontWeight.Bold, Nova.Blue, spacing = 2.sp))
            Spacer(Modifier.width(Space.s))
            Chip("All", !onlyProblems) { onlyProblems = false }
            Spacer(Modifier.width(8.dp))
            Chip("Warnings", onlyProblems) { onlyProblems = true }
            Spacer(Modifier.weight(1f))
            Text(
                if (staff.logsPending > 0) "${staff.logsPending} waiting to send" else "All sent",
                style = text(16.sp, FontWeight.Medium, if (staff.logsPending > 0) Nova.Amber else Nova.InkSoft),
            )
        }
        Spacer(Modifier.height(10.dp))
        if (lines.isEmpty()) {
            Text("Nothing here yet.", style = text(SMALL, FontWeight.Normal, Nova.InkSoft))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(lines) { l -> LogRow(l) }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .height(36.dp)
            .clip(shape)
            .background(if (selected) Nova.SkySoft else Nova.Card)
            .border(1.5.dp, if (selected) Nova.Blue else Nova.Line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = text(16.sp, FontWeight.SemiBold, if (selected) Nova.BlueDeep else Nova.InkSoft))
    }
}

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
private fun LogRow(l: LogLine) {
    val (fg, bg) = when (l.level) {
        LogLevel.ERROR -> Nova.Fail to Nova.FailSoft
        LogLevel.WARN -> Nova.Amber to Nova.AmberSoft
        LogLevel.INFO -> Nova.BlueDeep to Nova.SkySoft
        LogLevel.DEBUG -> Nova.InkSoft to Nova.Mist
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(
            clock.format(Instant.ofEpochMilli(l.at).atZone(ZoneId.systemDefault())),
            style = text(15.sp, FontWeight.Normal, Nova.InkFaint, tabular = true).copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(top = 3.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .width(78.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .padding(vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(l.source.wire.uppercase(), style = text(13.sp, FontWeight.Bold, fg, spacing = 1.sp), maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            l.text,
            style = text(17.sp, if (l.level.ordinal >= LogLevel.WARN.ordinal) FontWeight.Medium else FontWeight.Normal, if (l.level == LogLevel.DEBUG) Nova.InkSoft else Nova.Ink),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// keeps the last non-null value, so content can fade out after its state is gone.
// a plain holder: the value is returned right away, nothing needs to recompose for it
@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    @Suppress("UNCHECKED_CAST")
    return last[0] as T?
}
