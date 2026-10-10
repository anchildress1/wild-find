package dev.anchildress1.wildfind.core.game

import dev.anchildress1.wildfind.core.frame.Pixels
import dev.anchildress1.wildfind.core.hunt.ActiveHunt
import dev.anchildress1.wildfind.core.hunt.AppFlags
import dev.anchildress1.wildfind.core.hunt.Eligible
import dev.anchildress1.wildfind.core.hunt.Hint
import dev.anchildress1.wildfind.core.hunt.HuntProgress
import dev.anchildress1.wildfind.core.hunt.LocalList
import dev.anchildress1.wildfind.core.hunt.LocalListResult
import dev.anchildress1.wildfind.core.hunt.Season
import dev.anchildress1.wildfind.core.hunt.SpeciesRow
import dev.anchildress1.wildfind.core.map.Places
import dev.anchildress1.wildfind.core.region.RegionKey
import dev.anchildress1.wildfind.core.verify.CaptureCue
import dev.anchildress1.wildfind.core.verify.FrameEvidence
import dev.anchildress1.wildfind.core.verify.FrameResult
import dev.anchildress1.wildfind.core.verify.StageTimes
import dev.anchildress1.wildfind.core.verify.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

/** The camera: opening it, capture sessions, frame verdicts, hazard cues, and finds. */
class CaptureRulesTest : GameFixture() {
    @Test
    fun `the camera opens fresh on an open target, never on a found one`() {
        val game = playing(Screen.Hunt, hunt(found = setOf(1))).copy(
            ui = GameState(screen = Screen.Hunt, camera = CameraState(cue = CaptureCue.GET_CLOSER)),
        )

        assertEquals(Screen.Hunt, game.after(GameEvent.OpenCamera(1)).game.ui.screen)
        val opened = game.after(GameEvent.OpenCamera(0)).game.ui
        assertEquals(Screen.Camera(0), opened.screen)
        assertEquals(CameraState(ready = true), opened.camera)
        assertEquals(
            CameraState(ready = false),
            game.copy(rows = null).after(GameEvent.OpenCamera(null)).game.ui.camera,
        )
    }

    @Test
    fun `Capture starts a new session only once the verifier takes it`() {
        val camera = Screen.Camera(0)
        val game = playing(camera)
        val tapped = game.after(GameEvent.Capture)

        assertEquals(listOf(Command.Capture(camera, 1, game.hunt!!)), tapped.commands)
        assertEquals(0, tapped.game.session)
        assertFalse(tapped.game.ui.camera.checking)

        val started = tapped.game.after(Outcome.CaptureStarted(1)).game
        assertEquals(1, started.session)
        assertTrue(started.ui.camera.checking)
    }

    @Test
    fun `Capture needs the camera, a hunt, and the models`() {
        listOf(
            playing(Screen.Hunt),
            playing(Screen.Camera(0), hunt = null),
            playing(Screen.Camera(0)).copy(rows = null),
        ).forEach { assertEquals(emptyList<Command>(), it.after(GameEvent.Capture).commands) }
    }

    @Test
    fun `a frame from an older session or a camera that left changes nothing`() {
        val camera = Screen.Camera(0)
        val game = playing(camera).copy(session = 2)

        assertEquals(game, game.after(frame(camera, 1, Verdict.Found)).game)
        val left = game.copy(ui = game.ui.copy(screen = Screen.Camera(1)))
        assertEquals(left, left.after(frame(camera, 2, Verdict.Found)).game)
    }

    @Test
    fun `matching frames fill the ring and a final verdict sets the cue`() {
        val camera = Screen.Camera(0)
        val game = playing(camera).copy(session = 1).after(Outcome.CaptureStarted(1)).game

        assertEquals(2, game.after(frame(camera, 1, Verdict.Matching(2))).game.ui.camera.matched)
        val missed = game.after(frame(camera, 1, Verdict.NotPlant, fullShare = 0.9))
        assertEquals(CameraState(checking = false, matched = 0, cue = CaptureCue.PUT_IN_CIRCLE), missed.game.ui.camera)
        assertEquals(emptyList<Command>(), missed.commands)
        assertEquals(CaptureCue.POINT_AT_PLANT, game.after(frame(camera, 1, Verdict.NotPlant)).game.ui.camera.cue)
    }

    @Test
    fun `a hazard capture buzzes once and stays on the camera`() {
        val camera = Screen.Camera(0)
        val step = playing(camera).after(frame(camera, 0, Verdict.Hazard(4)))

        assertEquals(camera, step.game.ui.screen)
        assertEquals(CameraState(cue = CaptureCue.HAZARD, hazardLine = null), step.game.ui.camera)
        assertEquals(listOf(Command.Haptic(GameEffect.Reject)), step.commands)
    }

    @Test
    fun `the hazard card's button clears the warning and its line, and touches no other cue`() {
        val camera = Screen.Camera(0)
        val lined = rows.dropLast(1) + rows.last().copy(hazard = true, hazardLine = "Its sap can blister skin.")
        val warned = playing(camera).copy(rows = lined).after(frame(camera, 0, Verdict.Hazard(4)))

        val dismissed = warned.game.after(GameEvent.DismissHazard)
        assertEquals(CameraState(), dismissed.game.ui.camera)
        assertEquals(camera, dismissed.game.ui.screen)
        assertEquals(emptyList<Command>(), dismissed.commands)

        val missed = playing(camera).after(frame(camera, 0, Verdict.TapToFocus)).game
        assertEquals(missed, missed.after(GameEvent.DismissHazard).game)
    }

    @Test
    fun `a hazard with its own line shows it until the next capture's cue`() {
        val camera = Screen.Camera(0)
        val line = "Its sap can make skin blister in sunlight."
        val lined = rows.dropLast(1) + rows.last().copy(hazard = true, hazardLine = line)
        val warned = playing(camera).copy(rows = lined).after(frame(camera, 0, Verdict.Hazard(4)))

        assertEquals(line, warned.game.ui.camera.hazardLine)
        assertNull(warned.game.after(frame(camera, 0, Verdict.NotPlant)).game.ui.camera.hazardLine)
    }

    @Test
    fun `a found target is saved, starred, and shown with its crop`() {
        val camera = Screen.Camera(1)
        val step = playing(camera).after(frame(camera, 0, Verdict.Found))
        val hunt = step.game.hunt!!

        assertEquals(Screen.Found(1), step.game.ui.screen)
        assertEquals(setOf(1), hunt.progress.found)
        assertEquals(1, step.game.ui.stars)
        assertSame(crop, step.game.ui.crop)
        assertEquals(setOf("Acer s1"), step.game.flags.foundSpecies)
        assertEquals(
            listOf(
                Command.SaveHunt(hunt),
                Command.SaveFlags(step.game.flags),
                Command.Haptic(GameEffect.Confirm),
                Command.CancelCapture,
            ),
            step.commands,
        )
    }
}
