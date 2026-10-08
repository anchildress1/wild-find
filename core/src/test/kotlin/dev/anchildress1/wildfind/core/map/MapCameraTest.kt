package dev.anchildress1.wildfind.core.map

import dev.anchildress1.wildfind.core.region.RegionKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MapCameraTest {
    @Test
    fun `it opens on the whole world, and picking unlocks at 12 degrees across`() {
        val world = MapCamera()

        assertEquals(MapCamera.WORLD_SPAN, world.span)
        assertFalse(world.canPick)
        assertFalse(world.copy(span = 12.5).canPick)
        assertTrue(world.copy(span = 12.0).canPick)
    }

    @Test
    fun `zoom stays between the world and the closest view`() {
        assertEquals(MapCamera.WORLD_SPAN, MapCamera().zoom(0.5).span)
        assertEquals(90.0, MapCamera().zoom(4.0).span)
        assertEquals(MapCamera.MIN_SPAN, MapCamera().zoom(1_000.0).span)
    }

    @Test
    fun `the area detail draws once zoomed in`() {
        assertEquals(0, MapCamera(span = 60.0).level)
        assertEquals(1, MapCamera(span = MapCamera.DETAIL_SPAN).level)
    }

    @Test
    fun `panning wraps at the date line and stops at the poles`() {
        val east = MapCamera(lat = 10.0, lng = 179.0, span = 10.0).pan(3.0, 85.0)

        assertEquals(-178.0, east.lng, 1e-9)
        assertEquals(90.0, east.lat)
        assertEquals(-90.0, MapCamera().pan(-360.0, -200.0).lat)
        assertEquals(0.0, MapCamera().pan(-360.0, 0.0).lng, 1e-9)
    }

    @Test
    fun `the crosshairs snap to the nearest whole degree`() {
        assertEquals(RegionKey(38, -121), MapCamera(37.6, -121.4, 8.0).region)
        // Ties round toward positive infinity, as RegionKey.from does for device coordinates.
        assertEquals(RegionKey.from(33.5, -84.5), MapCamera(33.5, -84.5, 8.0).region)
        // 179.6 rounds to 180, the same meridian as -180.
        assertEquals(RegionKey(0, -180), MapCamera(0.0, 179.6, 8.0).region)
        assertEquals(MapCamera(38.0, -121.0, 8.0), MapCamera(37.6, -121.4, 8.0).let { it.at(it.region) })
    }

    @Test
    fun `arrows move one degree, wrapping east-west and clamping at the poles`() {
        val camera = MapCamera(38.2, -121.0, 8.0)

        assertEquals(RegionKey(39, -121), camera.step(Heading.NORTH).region)
        assertEquals(RegionKey(37, -121), camera.step(Heading.SOUTH).region)
        assertEquals(RegionKey(38, -120), camera.step(Heading.EAST).region)
        assertEquals(RegionKey(38, -122), camera.step(Heading.WEST).region)
        assertEquals(RegionKey(0, -180), MapCamera(0.0, 179.0, 8.0).step(Heading.EAST).region)
        assertEquals(RegionKey(0, 179), MapCamera(0.0, -180.0, 8.0).step(Heading.WEST).region)
        assertEquals(RegionKey(90, 0), MapCamera(90.0, 0.0, 8.0).step(Heading.NORTH).region)
    }

    @Test
    fun `the projection keeps one scale both ways and takes the short way around`() {
        val camera = MapCamera(10.0, 170.0, 20.0)

        assertEquals(50f, camera.x(170.0, 100f))
        assertEquals(100f, camera.x(-180.0, 100f))
        assertEquals(125f, camera.x(-175.0, 100f))
        assertEquals(100f, camera.y(10.0, 100f, 200f))
        assertEquals(75f, camera.y(15.0, 100f, 200f))
    }

    @Test
    fun `the chip shows whole degrees with compass letters`() {
        assertEquals("38°N, 121°W", MapCamera.degrees(RegionKey(38, -121)))
        assertEquals("12°S, 0°E", MapCamera.degrees(RegionKey(-12, 0)))
    }

    @Test
    fun `a camera off the globe or outside the zoom range is refused`() {
        assertThrows<IllegalArgumentException> { MapCamera(lat = 91.0) }
        assertThrows<IllegalArgumentException> { MapCamera(lng = 180.0) }
        assertThrows<IllegalArgumentException> { MapCamera(span = 1.0) }
    }
}
