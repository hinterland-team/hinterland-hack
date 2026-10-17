package com.hinterland.clinic.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinterland.clinic.BrainStatus
import com.hinterland.clinic.Clock
import com.hinterland.clinic.Health
import com.hinterland.clinic.Mood
import com.hinterland.clinic.RobotState
import com.hinterland.clinic.Step
import com.hinterland.clinic.StepKey
import com.hinterland.clinic.StepState
import com.hinterland.clinic.Timeline
import com.hinterland.clinic.UiState
import kotlinx.coroutines.delay
import kotlin.math.min

// 14" 1920x1080 landscape (549x308 dp at density 3.5). left: nova, what she is doing right now and
// what is being said. right: status, then the welcome card or the visit's steps.
// laid out for 1280x720 dp and scaled to the real screen, so it looks the same at any density.
@Composable
fun ClinicScreen(state: UiState) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Nova.Mist, Nova.Paper))),
    ) {
        val scale = min(maxWidth / DESIGN_WIDTH, maxHeight / DESIGN_HEIGHT)
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density * scale, 1f)) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 44.dp, vertical = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                FacePane(state, Modifier.weight(0.42f).fillMaxHeight())
                InfoPane(state, Modifier.weight(0.58f).fillMaxHeight())
            }
        }
    }
}

private val DESIGN_WIDTH = 1280.dp
private val DESIGN_HEIGHT = 720.dp

// two lines of the goal text, so the card never changes height
private val GOAL_HEIGHT = 68.dp

@Composable
private fun FacePane(state: UiState, modifier: Modifier) {
    val mood = state.mood()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Mascot(mood, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(12.dp))
        GoalCard(state, mood)
        Spacer(Modifier.height(16.dp))
        SubtitleCard(state)
    }
}

// what nova is doing right now, decided by her brain. the "she is thinking and acting" spot.
@Composable
private fun GoalCard(state: UiState, mood: Mood) {
    val brain = state.brain
    val accent by animateColorAsState(stateColor(brain.state), tween(400), label = "accent")
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(26.dp), ambientColor = Nova.Blue.copy(alpha = 0.12f), spotColor = Nova.Blue.copy(alpha = 0.12f))
            .clip(RoundedCornerShape(26.dp))
            .background(Nova.Card)
            .drawBehind { drawRect(accent, size = Size(7.dp.toPx(), size.height)) }
            .padding(start = 30.dp, end = 26.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(accent, pulsing = brain.state != RobotState.IDLE || mood != Mood.IDLE)
            Spacer(Modifier.width(12.dp))
            Text("RIGHT NOW", style = text(15.sp, FontWeight.Bold, accent, spacing = 3.sp))
            Text(
                "  ·  ${moodLine(state, mood)}",
                style = text(15.sp, FontWeight.SemiBold, Nova.InkSoft, spacing = 0.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(GOAL_HEIGHT), contentAlignment = Alignment.CenterStart) {
            AnimatedContent(
                targetState = brain.goal,
                transitionSpec = {
                    (slideInVertically(tween(320)) { it / 2 } + fadeIn(tween(320))) togetherWith
                        (slideOutVertically(tween(220)) { -it / 2 } + fadeOut(tween(220)))
                },
                label = "goal",
            ) { goal ->
                Text(
                    goal,
                    style = text(28.sp, FontWeight.Bold, Nova.Ink, lineHeight = 34.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun moodLine(state: UiState, mood: Mood): String = when (mood) {
    Mood.IDLE -> if (state.brain.state == RobotState.IDLE) "Ready - just start talking" else state.brain.state.label
    Mood.LISTENING -> "Listening"
    Mood.THINKING -> listOfNotNull("Thinking", state.task ?: state.agentNote).joinToString("  ·  ")
    Mood.SPEAKING -> "Speaking"
    Mood.WALKING -> listOfNotNull("Moving", state.task).joinToString("  ·  ")
    Mood.HAPPY -> "Done"
    Mood.SORRY -> "Something went wrong"
}

private fun stateColor(state: RobotState): Color = when (state) {
    RobotState.CHARGING -> Nova.Done
    RobotState.ERROR -> Nova.Fail
    RobotState.WALKING, RobotState.FETCHING, RobotState.RETURNING -> Nova.BlueDeep
    else -> Nova.Blue
}

@Composable
private fun PulseDot(color: Color, pulsing: Boolean) {
    val loop = rememberInfiniteTransition(label = "pulse")
    val halo by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "halo")
    Canvas(Modifier.size(16.dp)) {
        val r = size.minDimension / 2f
        if (pulsing) drawCircle(color.copy(alpha = (1f - halo) * 0.35f), r * (0.6f + 0.4f * halo), center)
        drawCircle(color, r * 0.5f, center)
    }
}

@Composable
private fun SubtitleCard(state: UiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 128.dp)
            .shadow(10.dp, RoundedCornerShape(28.dp), ambientColor = Nova.Blue.copy(alpha = 0.12f), spotColor = Nova.Blue.copy(alpha = 0.12f))
            .clip(RoundedCornerShape(28.dp))
            .background(Nova.Card)
            .padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        val who = when {
            state.subtitle.isBlank() -> "NOVA"
            state.subtitleFromUser -> "YOU"
            else -> "NOVA"
        }
        Text(
            who,
            style = text(18.sp, FontWeight.Bold, if (who == "YOU") Nova.InkSoft else Nova.Blue, spacing = 3.sp),
        )
        Spacer(Modifier.height(8.dp))
        Crossfade(targetState = state.subtitle, label = "subtitle") { line ->
            Text(
                line.ifBlank { "Hello! I'm listening whenever you're ready." },
                style = text(27.sp, FontWeight.Medium, if (line.isBlank()) Nova.InkSoft else Nova.Ink, lineHeight = 36.sp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InfoPane(state: UiState, modifier: Modifier) {
    Column(modifier) {
        Header(state)
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .shadow(18.dp, RoundedCornerShape(36.dp), ambientColor = Nova.Blue.copy(alpha = 0.14f), spotColor = Nova.Blue.copy(alpha = 0.14f))
                .clip(RoundedCornerShape(36.dp))
                .background(Nova.Card),
        ) {
            Crossfade(targetState = state.steps.isEmpty(), label = "card") { idle ->
                if (idle) Welcome() else Steps(state)
            }
        }
    }
}

@Composable
private fun Header(state: UiState) {
    var now by remember { mutableStateOf(Clock.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = Clock.now()
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Nova Clinic", style = text(36.sp, FontWeight.Bold, Nova.Ink), modifier = Modifier.weight(1f))
            StatusChip("Practice", state.backend)
            Spacer(Modifier.width(10.dp))
            StatusChip("Navigation", state.robot)
        }
        StatusLine(state, now)
    }
}

// battery, robot state, and the time - or an error, which replaces only the time so nothing jumps
@Composable
private fun StatusLine(state: UiState, now: String) {
    val brain = state.brain
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BatteryGlyph(brain.battery, brain.charging)
        Spacer(Modifier.width(9.dp))
        Text(batteryText(brain), style = text(19.sp, FontWeight.Medium, Nova.InkSoft, tabular = true))
        Separator()
        Text(brain.state.label, style = text(19.sp, FontWeight.SemiBold, stateColor(brain.state)))
        Separator()
        val banner = state.banner
        if (banner == null) {
            Text(now, style = text(19.sp, FontWeight.Medium, Nova.InkSoft, tabular = true))
        } else {
            Box(Modifier.size(9.dp).clip(CircleShape).background(Nova.Fail))
            Spacer(Modifier.width(8.dp))
            Text(banner, style = text(19.sp, FontWeight.Medium, Nova.Fail), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// "charging" only once: the state next to it may already say so
private fun batteryText(b: BrainStatus): String {
    val level = b.battery?.let { "Battery $it %" } ?: "Battery –"
    return if (b.charging && b.state != RobotState.CHARGING) "$level, charging" else level
}

@Composable
private fun Separator() {
    Text("  ·  ", style = text(19.sp, FontWeight.Medium, Nova.Pending))
}

@Composable
private fun BatteryGlyph(level: Int?, charging: Boolean) {
    val fill = when {
        charging -> Nova.Done
        level != null && level <= 20 -> Nova.Fail
        else -> Nova.InkSoft
    }
    Canvas(Modifier.size(width = 30.dp, height = 15.dp)) {
        val stroke = 1.6.dp.toPx()
        val tip = 3.dp.toPx()
        val bodyW = size.width - tip - stroke
        drawRoundRect(
            Nova.InkSoft,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(bodyW, size.height - stroke),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(stroke),
        )
        drawRoundRect(
            Nova.InkSoft,
            topLeft = Offset(bodyW + stroke / 2f, size.height * 0.3f),
            size = Size(tip, size.height * 0.4f),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        if (level != null) {
            val pad = stroke + 1.5.dp.toPx()
            val inner = bodyW + stroke / 2f - pad - pad / 2f
            drawRoundRect(
                fill,
                topLeft = Offset(pad, pad),
                size = Size((inner * level.coerceIn(0, 100) / 100f).coerceAtLeast(1.dp.toPx()), size.height - pad * 2f),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
        }
    }
}

@Composable
private fun StatusChip(label: String, health: Health) {
    val (dot, word) = when (health) {
        Health.OK -> Nova.Done to "online"
        Health.DOWN -> Nova.Fail to "offline"
        Health.UNKNOWN -> Nova.Pending to "connecting"
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Nova.Card)
            .border(1.5.dp, Nova.Line, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text("$label $word", style = text(18.sp, FontWeight.Medium, Nova.InkSoft))
    }
}

@Composable
private fun Welcome() {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp)) {
        Text("WELCOME", style = text(18.sp, FontWeight.Bold, Nova.Blue, spacing = 4.sp))
        Spacer(Modifier.height(8.dp))
        Text("Hi, I'm Nova.", style = text(54.sp, FontWeight.Bold, Nova.Ink))
        Spacer(Modifier.height(10.dp))
        Text(
            "Tell me your name and your doctor. I'll check you in and let your doctor know you're here.",
            style = text(25.sp, FontWeight.Normal, Nova.InkSoft, lineHeight = 34.sp),
        )
        Spacer(Modifier.weight(1f))
        Capability(1, "Check in", "Name, doctor and time. Nothing about your health.")
        Spacer(Modifier.height(14.dp))
        Capability(2, "Waiting time", "Ask me how long it will be.")
        Spacer(Modifier.height(14.dp))
        Capability(3, "Walk with you", "I'll take you to your doctor's room.")
        Spacer(Modifier.weight(1f))
        Text("Just start talking - no buttons needed.", style = text(20.sp, FontWeight.Medium, Nova.Blue))
    }
}

@Composable
private fun Capability(number: Int, title: String, caption: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Nova.SkySoft),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = text(22.sp, FontWeight.Bold, Nova.Blue))
        }
        Spacer(Modifier.width(20.dp))
        Column {
            Text(title, style = text(24.sp, FontWeight.SemiBold, Nova.Ink, lineHeight = 30.sp))
            Text(caption, style = text(19.sp, FontWeight.Normal, Nova.InkSoft, lineHeight = 24.sp))
        }
    }
}

@Composable
private fun Steps(state: UiState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
        Text(
            if (state.header.isNullOrBlank()) "NEW CONVERSATION" else "CURRENT VISIT",
            style = text(18.sp, FontWeight.Bold, Nova.Blue, spacing = 4.sp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            state.header?.takeIf { it.isNotBlank() } ?: "Nice to meet you",
            style = text(34.sp, FontWeight.Bold, Nova.Ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(Nova.Line))
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f)) {
            val steps = state.steps
            // a connector is green when the step above is done and the one below has started
            fun linked(upper: Step?, lower: Step?) =
                upper?.state == StepState.DONE && lower != null &&
                    lower.state != StepState.PENDING && lower.state != StepState.SKIPPED
            steps.forEachIndexed { i, step ->
                StepRow(
                    step = step,
                    first = i == 0,
                    last = i == steps.lastIndex,
                    lineIn = linked(steps.getOrNull(i - 1), step),
                    lineOut = linked(step, steps.getOrNull(i + 1)),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StepRow(step: Step, first: Boolean, last: Boolean, lineIn: Boolean, lineOut: Boolean, modifier: Modifier) {
    val reached = step.state != StepState.PENDING && step.state != StepState.SKIPPED
    val labelColor by animateColorAsState(
        when (step.state) {
            StepState.PENDING, StepState.SKIPPED -> Nova.Pending
            StepState.ACTIVE -> Nova.BlueDeep
            StepState.DONE -> Nova.Ink
            StepState.FAILED -> Nova.Fail
        },
        tween(400), label = "label",
    )
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(48.dp)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2f
                    val w = 3.dp.toPx()
                    if (!first) drawLine(if (lineIn) Nova.Done else Nova.Line, Offset(x, 0f), Offset(x, size.height / 2f), w)
                    if (!last) drawLine(if (lineOut) Nova.Done else Nova.Line, Offset(x, size.height / 2f), Offset(x, size.height), w)
                },
            contentAlignment = Alignment.Center,
        ) {
            StepDot(step.state)
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                step.label,
                style = text(
                    25.sp,
                    if (step.state == StepState.ACTIVE || step.state == StepState.FAILED) FontWeight.Bold else FontWeight.Medium,
                    labelColor,
                    lineHeight = 30.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            step.detail?.let {
                Text(
                    it,
                    style = text(
                        17.sp, FontWeight.Normal,
                        if (step.state == StepState.FAILED) Nova.Fail else Nova.InkSoft,
                        lineHeight = 21.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            if (reached) step.time.orEmpty() else "",
            style = text(21.sp, FontWeight.Medium, Nova.InkSoft, tabular = true),
        )
    }
}

@Composable
private fun StepDot(state: StepState) {
    val loop = rememberInfiniteTransition(label = "dot")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1_100, easing = LinearEasing)), label = "spin")
    val halo by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "halo")
    Canvas(Modifier.size(34.dp)) {
        val r = size.minDimension / 2f
        val c = center
        val stroke = r * 0.16f
        when (state) {
            StepState.DONE -> {
                drawCircle(Nova.Done, r, c)
                val check = Path().apply {
                    moveTo(c.x - r * 0.42f, c.y + r * 0.02f)
                    lineTo(c.x - r * 0.10f, c.y + r * 0.34f)
                    lineTo(c.x + r * 0.46f, c.y - r * 0.32f)
                }
                drawPath(check, Color.White, style = Stroke(width = stroke * 1.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            StepState.ACTIVE -> {
                drawCircle(Nova.Sky.copy(alpha = (1f - halo) * 0.45f), r * (1f + 0.45f * halo), c)
                drawCircle(Color.White, r, c)
                drawCircle(Nova.SkySoft, r - stroke / 2f, c, style = Stroke(stroke))
                drawArc(
                    color = Nova.Blue,
                    startAngle = spin,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(c.x - r + stroke / 2f, c.y - r + stroke / 2f),
                    size = Size((r - stroke / 2f) * 2f, (r - stroke / 2f) * 2f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawCircle(Nova.Blue, r * 0.22f, c)
            }
            StepState.FAILED -> {
                drawCircle(Nova.Fail, r, c)
                drawLine(Color.White, Offset(c.x, c.y - r * 0.45f), Offset(c.x, c.y + r * 0.10f), stroke * 1.1f, StrokeCap.Round)
                drawCircle(Color.White, stroke * 0.7f, Offset(c.x, c.y + r * 0.42f))
            }
            StepState.PENDING -> {
                drawCircle(Color.White, r, c)
                drawCircle(Nova.Pending, r - stroke / 2f, c, style = Stroke(stroke * 0.8f))
            }
            StepState.SKIPPED -> {
                drawCircle(Color.White, r, c)
                drawCircle(Nova.Pending, r - stroke / 2f, c, style = Stroke(stroke * 0.8f))
                drawLine(Nova.Pending, Offset(c.x - r * 0.38f, c.y), Offset(c.x + r * 0.38f, c.y), stroke, StrokeCap.Round)
            }
        }
    }
}

private fun text(
    size: TextUnit,
    weight: FontWeight,
    color: Color,
    lineHeight: TextUnit = TextUnit.Unspecified,
    spacing: TextUnit = TextUnit.Unspecified,
    tabular: Boolean = false,
) = TextStyle(
    fontSize = size,
    fontWeight = weight,
    color = color,
    lineHeight = lineHeight,
    letterSpacing = spacing,
    fontFeatureSettings = if (tabular) "tnum" else null,
)

// ---- previews (sample data, not used at runtime). 549x308 dp = the robot's 1920x1080 at density 3.5 ----

@Preview(widthDp = 549, heightDp = 308, showBackground = true)
@Composable
private fun IdlePreview() {
    NovaTheme {
        ClinicScreen(
            UiState(
                backend = Health.OK,
                robot = Health.OK,
                brain = BrainStatus(goal = "Waiting for patients", battery = 82, position = "Reception Point"),
            ),
        )
    }
}

@Preview(widthDp = 549, heightDp = 308, showBackground = true)
@Composable
private fun VisitPreview() {
    var steps = Timeline.named(Timeline.fresh("09:58"), "Sam Sample", "Dr. Example", "10:00")
    steps = Timeline.gotDetails(steps, "Sam Sample", "Dr. Example", "10:00", "09:59")
    steps = Timeline.set(steps, StepKey.NOTIFIED, StepState.DONE, "09:59", label = Timeline.notifiedLabel("Dr. Example", true))
    steps = Timeline.set(steps, StepKey.WAITING, StepState.DONE, "10:04", label = Timeline.waitingLabel("Dr. Example", true))
    steps = Timeline.set(steps, StepKey.WALKING, StepState.ACTIVE, "10:05")
    NovaTheme {
        ClinicScreen(
            UiState(
                walking = true,
                subtitle = "Sam Sample, Dr. Example is ready now. Please follow me.",
                header = Timeline.header("Sam Sample", "Dr. Example", "10:00"),
                steps = steps,
                backend = Health.OK,
                robot = Health.OK,
                brain = BrainStatus(goal = "Walking Sam Sample to Dr. Example", state = RobotState.WALKING, battery = 64),
            ),
        )
    }
}

@Preview(widthDp = 549, heightDp = 308, showBackground = true)
@Composable
private fun ChargingPreview() {
    NovaTheme {
        ClinicScreen(
            UiState(
                backend = Health.OK,
                robot = Health.OK,
                brain = BrainStatus(goal = "Charging (24 %)", state = RobotState.CHARGING, battery = 24, charging = true),
            ),
        )
    }
}
