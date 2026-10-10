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
import java.io.File
import kotlin.random.Random

/** The game, rows, hunt, and input helpers every GameRules test class shares. */
abstract class GameFixture {
    protected val rules = GameRules(Random(7))
    protected val home = RegionKey(34, -85)
    protected val away = RegionKey(40, -74)
    protected val rows = listOf("Quercus", "Acer", "Liquidambar", "Magnolia", "Fagus")
        .mapIndexed { row, genus ->
            SpeciesRow("$genus s$row", genus, hazard = false, toxic = false, description = "d$row")
        }
    protected val targets = listOf(Eligible(0, "oak", 10), Eligible(1, "maple", 10), Eligible(2, "sweetgum", 10))
    protected val queue = listOf(Eligible(3, "magnolia", 5), Eligible(4, "beech", 5))
    protected val crop = Pixels(1, 1, IntArray(1))

    protected fun hunt(tutorial: Boolean = false, found: Set<Int> = emptySet(), queue: List<Eligible> = this.queue) =
        ActiveHunt(HuntProgress(tutorial, targets, found, queue), home, (targets + queue).map { it.row }, emptyList())

    protected fun playing(screen: Screen, hunt: ActiveHunt? = hunt(), flags: AppFlags = flags()) =
        Game(GameState(screen = screen, region = flags.region), flags, hunt, rows)

    protected fun flags(region: RegionKey? = home) = AppFlags(openerSeen = true, tutorialDone = true, region = region)

    // Every input in order, with all their commands.
    protected fun Game.after(vararg inputs: GameInput): Step = inputs.fold(Step(this, emptyList())) { step, input ->
        rules.reduce(step.game, input).let { Step(it.game, step.commands + it.commands) }
    }

    protected fun frame(camera: Screen.Camera, session: Int, verdict: Verdict, fullShare: Double = 0.0) = Outcome.Frame(
        camera,
        session,
        FrameResult(
            FrameEvidence(hazardRow = null, reticlePlant = true, focus = null, goalMet = false),
            crop,
            reticleShare = 0.9,
            fullShare = fullShare,
            reticleRanking = null,
            fullRanking = null,
            goal = null,
            times = StageTimes(0, 0, 0, 0, 0, 0, 0),
        ),
        verdict,
    )

    protected fun local(eligible: List<Eligible>) =
        LocalListResult.Ready(LocalList(eligible, intArrayOf(), needsWiden = false))
}
