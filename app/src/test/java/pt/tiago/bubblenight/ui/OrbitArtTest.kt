package pt.tiago.bubblenight.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitArtTest {
    private val arts = (1..12).map { orbitArt(it) }

    @Test fun everyOrbitHasItsOwnPlanetPaletteAndSkyline() {
        assertEquals(12, arts.map { it.planetLight to it.planetDark to it.accent }.distinct().size)
        assertEquals(12, arts.map { it.mountainSeed }.distinct().size)
    }

    @Test fun orbitsUseAtLeastTenDifferentFeatures() {
        assertTrue(arts.map { it.feature }.distinct().size >= 10)
    }

    @Test fun outOfRangeOrbitsFallBackToTheNearestEnd() {
        assertEquals(orbitArt(1), orbitArt(0))
        assertEquals(orbitArt(12), orbitArt(99))
    }
}
