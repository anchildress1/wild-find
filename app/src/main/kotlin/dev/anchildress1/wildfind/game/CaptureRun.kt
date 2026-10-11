package dev.anchildress1.wildfind.game

import dev.anchildress1.wildfind.camera.CaptureVerifier
import dev.anchildress1.wildfind.core.game.Outcome
import dev.anchildress1.wildfind.core.game.Screen
import dev.anchildress1.wildfind.core.verify.Goal
import dev.anchildress1.wildfind.core.verify.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Runs one camera capture with a deadline, reporting session-scoped outcomes on the UI scope. */
internal class CaptureRun(
    private val scope: CoroutineScope,
    private val report: (Outcome) -> Unit,
    private val timeoutMs: Long = 10_000L,
) {
    private var deadline: Job? = null
    private var activeSession: Int? = null

    /** Starts [goal] on [camera] if [verifier] has a current frame stream. */
    fun start(verifier: CaptureVerifier, goal: Goal, camera: Screen.Camera, session: Int) {
        val started = verifier.capture(goal, onFrame = { frame ->
            scope.launch {
                if (activeSession == session && frame.verdict !is Verdict.Matching) cancel()
                report(Outcome.Frame(camera, session, frame.result, frame.verdict))
            }
        }, onFailure = {
            scope.launch { report(Outcome.CaptureFailed(session)) }
        })
        if (!started) return
        cancel()
        activeSession = session
        report(Outcome.CaptureStarted(session))
        deadline = scope.launch {
            delay(timeoutMs)
            if (activeSession != session) return@launch
            verifier.cancel()
            report(Outcome.CaptureFailed(session))
        }
    }

    /** Cancels the pending deadline when the camera leaves or its capture is abandoned. */
    fun cancel() {
        activeSession = null
        deadline?.cancel()
    }
}
