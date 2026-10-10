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

class GameRulesTest : GameFixture() {
    @Test
    fun `first launch plays the opener, and Let's go saves it and asks for an area`() {
        val launched = Game().after(Outcome.FlagsRead(AppFlags()))
        assertEquals(Screen.Opener(back = null), launched.game.ui.screen)
        assertEquals(listOf(Command.Relabel), launched.commands)

        val done = launched.game.after(GameEvent.OpenerDone)
        assertEquals(Screen.Region(back = null), done.game.ui.screen)
        assertEquals(listOf(Command.SaveFlags(AppFlags(openerSeen = true))), done.commands)
    }

    @Test
    fun `a later launch restores the area and reads the saved hunt`() {
        val flags = AppFlags(openerSeen = true, region = home, locationDenied = true)
        val step = Game().after(Outcome.FlagsRead(flags))

        assertEquals(Screen.Loading, step.game.ui.screen)
        assertEquals(home, step.game.ui.region)
        assertTrue(step.game.ui.locationFailed)
        assertEquals(listOf(Command.Relabel, Command.ReadHunt), step.commands)
    }

    @Test
    fun `a saved hunt resumes on its tutorial, its list, or its stars, and none means Start`() {
        val loading = Game(flags = flags()).after(Outcome.ModelsReady(rows)).game

        assertEquals(Screen.Start, loading.after(Outcome.SavedHunt(null)).game.ui.screen)
        assertEquals(Screen.Tutorial, loading.after(Outcome.SavedHunt(hunt(tutorial = true))).game.ui.screen)
        assertEquals(Screen.Complete, loading.after(Outcome.SavedHunt(hunt(found = setOf(0, 1, 2)))).game.ui.screen)

        val resumed = loading.after(Outcome.SavedHunt(hunt(found = setOf(1))))
        assertEquals(Screen.Hunt, resumed.game.ui.screen)
        assertEquals(listOf(Command.UseVerifier(setOf(0, 1, 2, 3, 4))), resumed.commands)
        assertEquals(
            listOf(
                Stop(0, "oak", null, found = false, description = "d0", canSkip = true),
                Stop(1, "maple", null, found = true, description = "d1", canSkip = false),
                Stop(2, "sweetgum", null, found = false, description = "d2", canSkip = true),
            ),
            resumed.game.ui.stops,
        )
    }

    @Test
    fun `a target's stop carries its species' hints`() {
        val hint = Hint("Look in woods.", Season.FALL)
        val hinted = rows.mapIndexed { row, species -> if (row == 0) species.copy(hints = listOf(hint)) else species }
        val loading = Game(flags = flags()).after(Outcome.ModelsReady(hinted)).game

        val stops = loading.after(Outcome.SavedHunt(hunt())).game.ui.stops

        assertEquals(listOf(hint), stops[0].hints)
        assertEquals(emptyList<Hint>(), stops[1].hints)
    }

    @Test
    fun `a plant without hints opens none, and Hint before the table loads is harmless`() {
        val bare = Game(GameState(screen = Screen.Camera(0)), flags(), hunt(), rows)
        assertEquals(mapOf(0 to 0), bare.after(GameEvent.RevealHint(0)).game.ui.hintsShown)

        val loading = Game(GameState(screen = Screen.Camera(0)), flags(), null, null)
        assertEquals(mapOf(0 to 0), loading.after(GameEvent.RevealHint(0)).game.ui.hintsShown)
    }

    @Test
    fun `Cache my area starts one run for the hunting area and reports its months`() {
        val grownUps = playing(Screen.GrownUps(from = Screen.Hunt))

        val started = grownUps.after(GameEvent.CacheArea)
        assertEquals(AreaCache.Running(0, 12), started.game.ui.areaCache)
        assertEquals(listOf(Command.CacheArea(home)), started.commands)

        val twice = started.game.after(GameEvent.CacheArea)
        assertEquals(emptyList<Command>(), twice.commands)

        assertEquals(
            AreaCache.Running(4, 12),
            started.game.after(Outcome.AreaReport(home, 4, finished = false)).game.ui.areaCache,
        )
        assertEquals(
            AreaCache.Done,
            started.game.after(Outcome.AreaReport(home, 12, finished = true)).game.ui.areaCache,
        )
        assertEquals(
            AreaCache.Stopped(5, 12),
            started.game.after(Outcome.AreaReport(home, 5, finished = true)).game.ui.areaCache,
        )
    }

    @Test
    fun `a run for an area that is no longer the hunting area is ignored`() {
        val running = playing(Screen.GrownUps(from = Screen.Hunt)).after(GameEvent.CacheArea).game

        val stale = running.after(
            Outcome.AreaReport(away, 7, finished = false),
            Outcome.AreaReport(away, 12, finished = true),
        ).game

        assertEquals(AreaCache.Running(0, 12), stale.ui.areaCache)
    }

    @Test
    fun `changing the hunting area stops a run and clears the card, but leaves a finished report alone`() {
        val running = playing(Screen.GrownUps(from = Screen.Hunt)).after(GameEvent.CacheArea).game
        val moved = running.after(GameEvent.PickRegion(away))

        assertEquals(AreaCache.Idle, moved.game.ui.areaCache)
        assertTrue(Command.CancelCacheArea in moved.commands)

        val finished = running.after(Outcome.AreaReport(home, 12, finished = true)).game
        val after = finished.after(GameEvent.PickRegion(away))
        assertEquals(AreaCache.Idle, after.game.ui.areaCache)
        assertFalse(Command.CancelCacheArea in after.commands)
    }

    @Test
    fun `Cache my area needs a hunting area, and reopening the page clears an old report`() {
        val noArea = Game(GameState(screen = Screen.GrownUps(from = Screen.Hunt)), flags(region = null), null, rows)
        assertEquals(emptyList<Command>(), noArea.after(GameEvent.CacheArea).commands)

        val done = playing(Screen.Hunt).after(Outcome.AreaReport(home, 12, finished = true)).game
        assertEquals(AreaCache.Idle, done.after(GameEvent.OpenGrownUps).game.ui.areaCache)
    }

    @Test
    fun `the models flip the camera ready`() {
        val step = Game().after(Outcome.ModelsReady(rows))

        assertTrue(step.game.ui.camera.ready)
        assertEquals(rows, step.game.rows)
    }

    @Test
    fun `a saved hunt that arrives before the table is read again, never cleared`() {
        val step = Game(flags = flags()).after(Outcome.SavedHunt(hunt()))

        assertEquals(listOf(Command.ReadHunt), step.commands)
        assertNull(step.game.hunt)
    }

    @Test
    fun `invalid or differently located saved hunts are cleared before rows are indexed`() {
        val loading = Game(flags = flags()).after(Outcome.ModelsReady(rows)).game
        val saved = hunt()
        val invalid = listOf(
            saved.copy(region = away),
            saved.copy(eligible = saved.eligible + 99),
            saved.copy(
                progress = saved.progress.copy(
                    targets = targets.map {
                        it.copy(row = it.row + 99)
                    },
                    queue = emptyList(),
                ),
                eligible = listOf(99, 100, 101),
            ),
            saved.copy(progress = saved.progress.copy(queue = listOf(Eligible(99, "missing", 10)))),
            saved.copy(progress = saved.progress.copy(queue = listOf(queue[0], queue[0], queue[1]))),
        )
        invalid.forEach {
            val step = loading.after(Outcome.SavedHunt(it))
            assertEquals(Screen.Start, step.game.ui.screen)
            assertNull(step.game.hunt)
            assertEquals(listOf(Command.ClearHunt), step.commands)
            assertTrue(step.game.ui.stops.isEmpty())
        }
    }

    @Test
    fun `the area label names the place, and stays empty without an area`() {
        val file = File("../app/generated/assets/places.bin")
        check(file.isFile) { "$file missing; run make assets" }
        val places = Places.parse(file.readBytes())

        assertEquals(
            Places.label(home, places.nameAt(home)),
            playing(Screen.Hunt).after(Outcome.PlacesRead(places)).game.ui.regionLabel,
        )
        assertNull(Game().after(Outcome.PlacesRead(places)).game.ui.regionLabel)
    }

    @Test
    fun `a replayed opener's Done returns where it came from and saves nothing`() {
        val step = playing(Screen.GrownUps(from = Screen.Hunt)).after(GameEvent.ReplayOpener, GameEvent.OpenerDone)

        assertEquals(Screen.GrownUps(from = Screen.Hunt), step.game.ui.screen)
        assertEquals(emptyList<Command>(), step.commands)
    }

    @Test
    fun `Back returns along each screen's way in, and does nothing where there is none`() {
        val grownUps = Screen.GrownUps(from = Screen.Hunt)
        listOf(
            Screen.Opener(back = grownUps) to grownUps,
            Screen.Opener(back = null) to Screen.Opener(back = null),
            Screen.Region(back = grownUps) to grownUps,
            Screen.Region(back = null) to Screen.Region(back = null),
            Screen.Map(back = Screen.Start) to Screen.Start,
            Screen.Map(back = null) to Screen.Map(back = null),
            grownUps to Screen.Hunt,
            Screen.Hunt to Screen.Hunt,
            Screen.Loading to Screen.Loading,
        ).forEach { (from, to) -> assertEquals(to, playing(from).after(GameEvent.Back).game.ui.screen, "$from") }
    }

    @Test
    fun `Back from the camera leaves for the list or the tutorial and drops the capture`() {
        val step = playing(Screen.Camera(0)).after(GameEvent.Back)
        assertEquals(Screen.Hunt, step.game.ui.screen)
        assertEquals(listOf(Command.CancelCapture), step.commands)
        assertEquals(1, step.game.session)

        val tutorial = playing(Screen.Camera(null), hunt(tutorial = true)).after(GameEvent.Back)
        assertEquals(Screen.Tutorial, tutorial.game.ui.screen)
    }

    @Test
    fun `Back from any find returns to the list, the last one included, and never skips ahead`() {
        assertEquals(
            Screen.Hunt,
            playing(Screen.Found(2), hunt(found = setOf(0, 1, 2))).after(GameEvent.Back).game.ui.screen,
        )
        assertEquals(Screen.Hunt, playing(Screen.Found(1), hunt(found = setOf(1))).after(GameEvent.Back).game.ui.screen)
    }

    @Test
    fun `Back from the stars goes home and forgets the hunt`() {
        val step = playing(Screen.Complete).after(GameEvent.Back)

        assertEquals(Screen.Start, step.game.ui.screen)
        assertNull(step.game.hunt)
        assertEquals(emptyList<Stop>(), step.game.ui.stops)
        assertEquals(listOf(Command.UseVerifier(null), Command.ClearHunt), step.commands)
    }

    @Test
    fun `confirming the area already set goes back where the kid came from, with no new pull`() {
        val grownUps = Screen.GrownUps(from = Screen.Hunt)
        listOf(
            Screen.Region(back = grownUps) to grownUps,
            Screen.Map(back = Screen.Region(back = grownUps)) to grownUps,
            Screen.Map(back = grownUps) to grownUps,
        ).forEach { (from, to) ->
            val step = playing(from).after(GameEvent.PickRegion(home))
            assertEquals(to, step.game.ui.screen, "$from")
            assertEquals(emptyList<Command>(), step.commands, "$from")
            assertNotNull(step.game.hunt)
        }
    }

    @Test
    fun `a new area, or the same one with nowhere to go back, ends the hunt and pulls`() {
        listOf(
            playing(Screen.Region(back = Screen.GrownUps(Screen.Hunt))) to away,
            playing(Screen.Map(back = Screen.Region(back = null))) to home,
        ).forEach { (game, region) ->
            val step = game.after(GameEvent.PickRegion(region))

            assertEquals(Screen.Loading, step.game.ui.screen)
            assertEquals(region, step.game.ui.region)
            assertEquals(region, step.game.flags.region)
            assertNull(step.game.hunt)
            assertEquals(
                listOf(
                    Command.SaveFlags(flags(region)),
                    Command.Relabel,
                    Command.UseVerifier(null),
                    Command.ClearHunt,
                    Command.Pull(region),
                ),
                step.commands,
            )
        }
    }

    @Test
    fun `an area picked offline stays saved, the grown-ups page caches it, and Try again pulls it`() {
        val picked = playing(Screen.Map(back = Screen.GrownUps(Screen.Hunt))).after(
            GameEvent.PickRegion(away),
            Outcome.Pulled(away, LocalListResult.NeedsSignal, offline = true),
        )
        assertEquals(Screen.NeedsSignal, picked.game.ui.screen)
        assertEquals(away, picked.game.flags.region)
        assertTrue(Command.SaveFlags(flags(away)) in picked.commands)

        val cached = picked.game.after(GameEvent.OpenGrownUps, GameEvent.CacheArea)
        assertEquals(Screen.GrownUps(from = Screen.NeedsSignal), cached.game.ui.screen)
        assertEquals(listOf(Command.CacheArea(away)), cached.commands)

        val done = cached.game.after(Outcome.AreaReport(away, 12, finished = true), GameEvent.Back)
        assertEquals(Screen.NeedsSignal, done.game.ui.screen)
        val retried = done.game.after(GameEvent.LoadHunt)
        assertEquals(listOf(Command.Pull(away)), retried.commands)
        val local = local(targets + queue)
        assertEquals(Screen.Hunt, retried.game.after(Outcome.Pulled(away, local, offline = true)).game.ui.screen)
    }

    @Test
    fun `a denied location is saved and sends the area choice to the map`() {
        val region = Screen.Region(back = null)
        val step = playing(region, hunt = null).after(GameEvent.LocationAnswer(granted = false))

        assertEquals(Screen.Map(back = region), step.game.ui.screen)
        assertTrue(step.game.ui.locationFailed)
        assertEquals(listOf(Command.SaveFlags(flags().copy(locationDenied = true))), step.commands)

        val map = Screen.Map(back = null)
        assertEquals(map, playing(map).after(GameEvent.LocationAnswer(granted = false)).game.ui.screen)
    }

    @Test
    fun `an allowed location asks for a fix, and a fix on the area choice picks it`() {
        val region = Screen.Region(back = null)
        val asked = playing(region, hunt = null).after(GameEvent.LocationAnswer(granted = true))
        assertTrue(asked.game.ui.locating)
        assertEquals(listOf(Command.Locate(region)), asked.commands)

        val fixed = asked.game.after(Outcome.Located(region, away))
        assertFalse(fixed.game.ui.locating)
        assertEquals(Screen.Loading, fixed.game.ui.screen)
        assertEquals(Command.Pull(away), fixed.commands.last())
    }

    @Test
    fun `no fix opens the map from the area choice, and a fix on the map only recenters`() {
        val region = Screen.Region(back = null)
        assertEquals(
            Screen.Map(back = region),
            playing(region).after(Outcome.Located(region, null)).game.ui.screen,
        )

        val map = Screen.Map(back = null)
        val step = playing(map).after(Outcome.Located(map, away), Outcome.Located(map, away))
        assertEquals(MapFocus(away, 2), step.game.ui.mapFocus)
        assertEquals(map, step.game.ui.screen)
        assertEquals(emptyList<Command>(), step.commands)
    }

    @Test
    fun `a fix that lands after the kid moved on starts nothing`() {
        val step = playing(Screen.Hunt).after(Outcome.Located(Screen.Region(back = null), away))

        assertEquals(Screen.Hunt, step.game.ui.screen)
        assertEquals(emptyList<Command>(), step.commands)
        assertFalse(step.game.ui.locating)
    }

    @Test
    fun `the map opens on the rough location only while that same map is up`() {
        val opened = playing(Screen.Hunt).after(GameEvent.OpenGrownUps, GameEvent.EditRegion)
        val map = opened.game.ui.screen as Screen.Map
        assertEquals(Screen.GrownUps(from = Screen.Hunt), map.back)
        assertSame(map, (opened.commands.single() as Command.LocateMap).map)

        assertEquals(
            MapFocus(away, 1, opening = true),
            opened.game.after(Outcome.MapLocated(map, away)).game.ui.mapFocus,
        )

        // Left and reopened: an equal map, but not the one that asked.
        val reopened = opened.game.after(GameEvent.Back, GameEvent.OpenMap).game
        assertNull(reopened.after(Outcome.MapLocated(map, away)).game.ui.mapFocus)
    }

    @Test
    fun `a pull needs an area, and ChangeRegion asks for one`() {
        assertEquals(
            Screen.Region(back = null),
            playing(Screen.NeedsSignal, flags = flags(null)).after(GameEvent.LoadHunt).game.ui.screen,
        )
        assertEquals(
            Screen.Region(back = null),
            playing(Screen.NotEnough).after(GameEvent.ChangeRegion).game.ui.screen,
        )

        val step = playing(Screen.NeedsSignal).after(GameEvent.LoadHunt)
        assertEquals(Screen.Loading, step.game.ui.screen)
        assertEquals(listOf(Command.Pull(home)), step.commands)
    }

    @Test
    fun `a pull with no signal or too few genera says so`() {
        val loading = playing(Screen.Loading, hunt = null)

        assertEquals(
            Screen.NeedsSignal,
            loading.after(Outcome.Pulled(home, LocalListResult.NeedsSignal, offline = true)).game.ui.screen,
        )
        assertEquals(
            Screen.NotEnough,
            loading.after(Outcome.Pulled(home, LocalListResult.NotEnough, offline = false)).game.ui.screen,
        )
        val twoGenera = local(listOf(Eligible(0, "oak", 10), Eligible(1, "maple", 10)))
        assertEquals(Screen.NotEnough, loading.after(Outcome.Pulled(home, twoGenera, offline = false)).game.ui.screen)
    }

    @Test
    fun `a full pull starts and saves a hunt for the area it was pulled for`() {
        val loading = playing(Screen.Loading, hunt = null, flags = flags().copy(tutorialDone = false))
        val step = loading.after(Outcome.Pulled(away, local(targets + queue), offline = true))
        val hunt = checkNotNull(step.game.hunt)

        assertEquals(Screen.Tutorial, step.game.ui.screen)
        assertEquals(away, hunt.region)
        assertTrue(hunt.progress.tutorialPending)
        assertTrue(step.game.ui.offline)
        assertEquals(3, step.game.ui.stops.size)
        assertEquals(listOf(Command.UseVerifier(setOf(0, 1, 2, 3, 4)), Command.SaveHunt(hunt)), step.commands)

        val played = playing(Screen.Loading, hunt = null).after(Outcome.Pulled(home, local(targets), offline = false))
        assertEquals(Screen.Hunt, played.game.ui.screen)
        assertFalse(played.game.ui.offline)
    }

    @Test
    fun `the next hunt leaves out plants found before while others fill it`() {
        val foundBefore = flags().copy(foundSpecies = setOf("Quercus s0", "Acer s1"))
        val loading = playing(Screen.Loading, hunt = null, flags = foundBefore)
        val pull = local(targets + queue)

        val rows = loading.after(Outcome.Pulled(home, pull, offline = false)).game.hunt!!.progress.targets.map {
            it.row
        }

        assertEquals(setOf(2, 3, 4), rows.toSet())
    }

    @Test
    fun `each Hint tap opens one more hint, capped at what the plant has, and a new hunt starts over`() {
        val hinted = rows.mapIndexed { row, species ->
            species.copy(hints = List(if (row == 0) 5 else 1) { Hint("Look $it.") })
        }
        val start = Game(GameState(screen = Screen.Camera(0)), flags(), hunt(), hinted)

        val tapped = start.after(
            *Array(4) {
                GameEvent.RevealHint(0)
            },
            GameEvent.RevealHint(1),
            GameEvent.RevealHint(1),
        )

        assertEquals(mapOf(0 to 3, 1 to 1), tapped.game.ui.hintsShown)
        val again = tapped.game.copy(ui = tapped.game.ui.copy(screen = Screen.Loading))
            .after(Outcome.Pulled(home, local(targets + queue), offline = false))
        assertEquals(emptyMap<Int, Int>(), again.game.ui.hintsShown)
    }

    @Test
    fun `a found tutorial passes it for good`() {
        val camera = Screen.Camera(null)
        val step = playing(camera, hunt(tutorial = true), flags().copy(tutorialDone = false))
            .after(frame(camera, 0, Verdict.Found))

        assertEquals(Screen.Found(null), step.game.ui.screen)
        assertFalse(step.game.hunt!!.progress.tutorialPending)
        assertTrue(step.game.flags.tutorialDone)
        assertTrue(Command.SaveFlags(step.game.flags) in step.commands)
    }

    @Test
    fun `Next after a find opens the next target, the list after the tutorial, or the stars`() {
        assertEquals(Screen.Hunt, playing(Screen.Found(null)).after(GameEvent.Next).game.ui.screen)
        assertEquals(
            Screen.Camera(0),
            playing(Screen.Found(1), hunt(found = setOf(1))).after(GameEvent.Next).game.ui.screen,
        )
        assertEquals(
            Screen.Complete,
            playing(Screen.Found(2), hunt(found = setOf(0, 1, 2))).after(GameEvent.Next).game.ui.screen,
        )
        assertEquals(Screen.Hunt, playing(Screen.Hunt).after(GameEvent.Next).game.ui.screen)
        assertEquals(Screen.Found(0), playing(Screen.Found(0), hunt = null).after(GameEvent.Next).game.ui.screen)
    }

    @Test
    fun `a skip swaps the target in its own slot and reopens the camera there`() {
        val step = playing(Screen.Camera(1)).after(GameEvent.Skip)
        val hunt = step.game.hunt!!

        assertEquals(listOf(0, 3, 2), hunt.progress.targets.map { it.row })
        assertEquals(Screen.Camera(3), step.game.ui.screen)
        assertEquals(listOf(0, 3, 2), step.game.ui.stops.map { it.row })
        assertEquals(2, step.game.session)
        assertEquals(
            listOf(Command.CancelCapture, Command.SaveHunt(hunt), Command.CancelCapture),
            step.commands,
        )
    }

    @Test
    fun `a skip with nothing to swap in keeps the camera on the same target`() {
        val step = playing(Screen.Camera(1), hunt(queue = emptyList())).after(GameEvent.Skip)

        assertEquals(Screen.Camera(1), step.game.ui.screen)
        assertEquals(1, step.game.session)
    }

    @Test
    fun `skipping the grass tutorial passes it and opens the list`() {
        val step = playing(Screen.Camera(null), hunt(tutorial = true), flags().copy(tutorialDone = false))
            .after(GameEvent.Skip)

        assertEquals(Screen.Hunt, step.game.ui.screen)
        assertFalse(step.game.hunt!!.progress.tutorialPending)
        assertTrue(step.game.flags.tutorialDone)
    }

    @Test
    fun `a skip needs the camera, a hunt, and the models`() {
        listOf(
            playing(Screen.Hunt),
            playing(Screen.Camera(0), hunt = null),
            playing(Screen.Camera(0)).copy(rows = null),
        ).forEach { assertEquals(it, it.after(GameEvent.Skip).game) }
    }

    @Test
    fun `ending early clears the saved hunt but keeps the stops for the stars`() {
        val step = Game(flags = flags()).after(Outcome.ModelsReady(rows), Outcome.SavedHunt(hunt())).game
            .after(GameEvent.FinishHunt)

        assertEquals(Screen.Complete, step.game.ui.screen)
        assertNotNull(step.game.hunt)
        assertEquals(3, step.game.ui.stops.size)
        assertEquals(listOf(Command.ClearHunt), step.commands)
    }

    @Test
    fun `Hunt Again forgets the hunt and pulls the same area`() {
        val game = playing(Screen.Complete).copy(ui = playing(Screen.Complete).ui.copy(offline = true, crop = crop))
        val step = game.after(GameEvent.HuntAgain)

        assertEquals(Screen.Loading, step.game.ui.screen)
        assertNull(step.game.hunt)
        assertNull(step.game.ui.crop)
        assertFalse(step.game.ui.offline)
        assertEquals(listOf(Command.UseVerifier(null), Command.ClearHunt, Command.Pull(home)), step.commands)
    }

    @Test
    fun `ToHunt and the grown-ups page leave the camera and drop its capture`() {
        assertEquals(listOf(Command.CancelCapture), playing(Screen.Camera(0)).after(GameEvent.ToHunt).commands)
        val step = playing(Screen.Camera(0)).after(GameEvent.OpenGrownUps)
        assertEquals(Screen.GrownUps(from = Screen.Camera(0)), step.game.ui.screen)
        assertEquals(listOf(Command.CancelCapture), step.commands)
    }
}
