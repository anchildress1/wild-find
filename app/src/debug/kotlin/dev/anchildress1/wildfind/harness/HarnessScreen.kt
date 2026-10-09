package dev.anchildress1.wildfind.harness

import androidx.camera.core.Camera
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anchildress1.wildfind.camera.FrameSize
import dev.anchildress1.wildfind.camera.Viewfinder
import dev.anchildress1.wildfind.core.verify.Verdict
import dev.anchildress1.wildfind.core.verify.VerifyStreak
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor
import kotlin.math.roundToInt

private val NO_STATUS = MutableStateFlow(GateStatus())
private val NO_FRAME = MutableStateFlow<FrameSize?>(null)
private val SCRIM = Color.Black.copy(alpha = 0.7f)

/** The harness screen: live viewfinder, the reticle ring the models read, a readout, and the capture button. */
@Composable
fun HarnessScreen(runs: StateFlow<GateRun?>, executor: Executor, onCamera: (GateRun, Camera) -> Unit) {
    val run by runs.collectAsState()
    val status by (run?.status ?: NO_STATUS).collectAsState()
    val frame by (run?.verifier?.frameSize ?: NO_FRAME).collectAsState()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        run?.let { live ->
            Viewfinder(live.verifier, executor, Modifier.fillMaxSize(), onCamera = {
                onCamera(live, it)
            }) { center, r ->
                drawCircle(Color.Black, r, center, style = Stroke(width = 8.dp.toPx()))
                drawCircle(Color.White, r, center, style = Stroke(width = 4.dp.toPx()))
            }
        }
        Readout(status, frame, Modifier.align(Alignment.TopStart))
        Button(
            onClick = { run?.requestCapture() },
            enabled = run != null && !status.capturing,
            modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp).heightIn(min = 56.dp),
        ) { Text("Capture", fontSize = 22.sp) }
    }
}

@Composable
private fun Readout(status: GateStatus, frame: FrameSize?, modifier: Modifier) {
    val verdict = when (val v = status.verdict) {
        null -> "starting"
        is Verdict.Matching -> "matching ${v.frames}/${VerifyStreak.FRAMES}"
        Verdict.WalkCloser -> "get closer or zoom in"
        else -> v.toString()
    }
    Column(modifier.fillMaxWidth().background(SCRIM).padding(16.dp)) {
        Text("Find: ${status.target}", color = Color.White, fontSize = 26.sp)
        Text(
            "$verdict · ${status.frameMs.roundToInt()} ms · found ${status.found}",
            color = Color.White,
            fontSize = 22.sp,
        )
        Text(
            "${frame?.width ?: 0}x${frame?.height ?: 0} · PSS ${status.pssMb} MB · thermal ${status.thermalStatus}",
            color = Color.White,
            fontSize = 18.sp,
        )
        if (status.species.isNotEmpty()) Text("sees ${status.species}", color = Color.White, fontSize = 18.sp)
    }
}
