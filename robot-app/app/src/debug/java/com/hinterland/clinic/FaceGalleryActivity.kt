package com.hinterland.clinic

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinterland.clinic.ui.Mascot
import com.hinterland.clinic.ui.Nova
import com.hinterland.clinic.ui.NovaTheme
import com.hinterland.clinic.ui.text
import kotlinx.coroutines.delay

// debug builds only: nova's face alone, in any mood, to check the animations without the robot.
//   adb shell am start -n com.hinterland.relay/com.hinterland.clinic.FaceGalleryActivity --es mood LOVE --ez talking true
//   adb shell am start -n com.hinterland.relay/com.hinterland.clinic.FaceGalleryActivity --ez cycle true
class FaceGalleryActivity : ComponentActivity() {

    private var mood by mutableStateOf(Mood.IDLE)
    private var talking by mutableStateOf(false)
    private var cycle by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        read(intent)
        setContent {
            NovaTheme {
                LaunchedEffect(cycle) {
                    while (cycle) {
                        delay(5_000)
                        mood = Mood.entries[(mood.ordinal + 1) % Mood.entries.size]
                    }
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Nova.Paper, Nova.Mist)))) {
                    Mascot(mood, Modifier.fillMaxSize().padding(top = 8.dp), talking = talking)
                    Text(
                        mood.name + if (talking) " + talking" else "",
                        style = text(14.sp, FontWeight.Bold, Nova.InkSoft),
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent) {
        intent.getStringExtra("mood")?.let { name -> Mood.entries.firstOrNull { it.name == name.uppercase() }?.let { mood = it } }
        talking = intent.getBooleanExtra("talking", false)
        cycle = intent.getBooleanExtra("cycle", false)
    }
}
