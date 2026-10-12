package pt.tiago.bubblenight.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Visual feature of one orbit; each orbit name in the catalog maps to one of these. */
internal enum class OrbitFeature { RING, ASTEROIDS, AURORA, CONCENTRIC, BRIDGE, ECHOES, NEBULA, HORIZON, CROWN, STARFIELD, BIG_MOON }

/**
 * Vector illustration parameters for one orbit. Everything is drawn in Compose Canvas
 * from these values, so the art stays crisp at any size and needs no image assets.
 */
internal data class OrbitArt(
    val planetLight: Color,
    val planetDark: Color,
    val accent: Color,
    val feature: OrbitFeature,
    val tiltDegrees: Float,
    val moons: Int,
    val mountainSeed: Float,
)

private val Violet = Color(0xFFBB9AF7)
private val Orange = Color(0xFFFF9E64)
private val Cyan = Color(0xFF7DCFFF)
private val Green = Color(0xFF9ECE6A)
private val Pink = Color(0xFFF7768E)
private val Blue = Color(0xFF7AA2F7)
private val Gold = Color(0xFFE0AF68)
private val Teal = Color(0xFF73DACA)
private val Grey = Color(0xFFA9B1D6)

/** One entry per orbit, in catalog order (R.array.orbit_names). */
internal fun orbitArt(orbit: Int): OrbitArt = when (orbit.coerceIn(1, 12)) {
    1 -> OrbitArt(Blue, Color(0xFF2A3A66), Blue, OrbitFeature.RING, 12f, 1, 0.3f)                     // First Sky
    2 -> OrbitArt(Grey, Color(0xFF3B4158), Grey, OrbitFeature.BIG_MOON, 0f, 1, 1.1f)                 // Lunar Valley
    3 -> OrbitArt(Green, Color(0xFF2C4A34), Green, OrbitFeature.STARFIELD, 8f, 2, 2.0f)              // Star Garden
    4 -> OrbitArt(Orange, Color(0xFF5A3A2A), Gold, OrbitFeature.ASTEROIDS, 26f, 0, 2.7f)             // Asteroid Belt
    5 -> OrbitArt(Violet, Color(0xFF3A2A5C), Violet, OrbitFeature.AURORA, 4f, 2, 0.8f)               // Violet Aurora
    6 -> OrbitArt(Teal, Color(0xFF1F4A4A), Teal, OrbitFeature.CONCENTRIC, 18f, 3, 1.9f)              // Sea of Orbits
    7 -> OrbitArt(Cyan, Color(0xFF244A60), Cyan, OrbitFeature.BRIDGE, 10f, 1, 0.6f)                  // Celestial Bridge
    8 -> OrbitArt(Grey, Color(0xFF363A55), Violet, OrbitFeature.ECHOES, 16f, 2, 2.4f)                 // Echoes of the Moon
    9 -> OrbitArt(Blue, Color(0xFF1F2F5C), Cyan, OrbitFeature.NEBULA, 30f, 1, 1.5f)                  // Blue Nebula
    10 -> OrbitArt(Pink, Color(0xFF5C2A3C), Pink, OrbitFeature.HORIZON, 0f, 2, 3.1f)                  // Distant Horizon
    11 -> OrbitArt(Gold, Color(0xFF4A3D24), Gold, OrbitFeature.CROWN, 14f, 2, 0.1f)                   // Northern Crown
    else -> OrbitArt(Orange, Color(0xFF4E2C24), Gold, OrbitFeature.RING, 40f, 4, 2.2f)                // Last Constellation
}

/** Draws the orbit's planet, feature, moons and mountains over the sky. */
internal fun DrawScope.drawOrbitArt(art: OrbitArt) {
    val w = size.width
    val h = size.height
    val horizon = art.feature == OrbitFeature.HORIZON
    val center = if (horizon) Offset(w * .5f, h * .8f) else Offset(w * .83f, h * .17f)
    val r = if (horizon) w * .32f else w * .105f

    // Halo, then the planet body with a soft light from the upper left.
    drawCircle(art.accent.copy(alpha = .12f), r * 1.8f, center)
    drawCircle(
        Brush.radialGradient(listOf(art.planetLight, art.planetDark), center = center - Offset(r * .35f, r * .35f), radius = r * 1.4f),
        r, center,
    )

    when (art.feature) {
        OrbitFeature.RING -> ring(art, center, r, 1.6f, .45f)
        OrbitFeature.ASTEROIDS -> {
            ring(art, center, r, 1.7f, .42f)
            for (i in 0 until 9) {
                val a = i * 0.7f + 0.3f
                val p = Offset(center.x + cos(a) * r * 1.7f, center.y + sin(a) * r * .42f)
                drawCircle(art.accent.copy(alpha = .7f), 1.6f + (i % 3) * .6f, p)
            }
        }
        OrbitFeature.AURORA -> for (k in 0 until 4) {
            drawOval(art.accent.copy(alpha = .08f + k * .02f), topLeft = Offset(0f, h * (.04f + k * .035f)), size = Size(w, h * .07f))
        }
        OrbitFeature.CONCENTRIC -> for (k in 1..3) {
            drawCircle(art.accent.copy(alpha = .28f), r * (1f + k * .32f), center, style = Stroke(width = 1.dp.toPx()))
        }
        OrbitFeature.BRIDGE -> {
            val arc = Path().apply {
                moveTo(w * .04f, h * .36f)
                quadraticBezierTo(w * .32f, h * .08f, center.x - r * 1.2f, center.y + r * .6f)
            }
            drawPath(arc, art.accent.copy(alpha = .45f), style = Stroke(width = 1.dp.toPx()))
        }
        OrbitFeature.ECHOES -> for (k in 1..3) {
            drawCircle(art.accent.copy(alpha = .16f), r * (1f + k * .22f), center, style = Stroke(width = 1.dp.toPx()))
        }
        OrbitFeature.NEBULA -> {
            val blobs = listOf(Offset(w * .62f, h * .3f), Offset(w * .7f, h * .36f), Offset(w * .55f, h * .38f))
            blobs.forEachIndexed { i, b -> drawCircle(art.accent.copy(alpha = .07f), w * (.09f + i * .02f), b) }
        }
        OrbitFeature.HORIZON -> drawCircle(art.accent.copy(alpha = .10f), r * 1.1f, center)
        OrbitFeature.CROWN -> drawArc(
            color = art.accent.copy(alpha = .7f), startAngle = 200f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(center.x - r * 1.25f, center.y - r * 1.25f), size = Size(r * 2.5f, r * 2.5f),
            style = Stroke(width = 2.dp.toPx()),
        )
        OrbitFeature.STARFIELD -> for (i in 0 until 26) {
            val x = ((i * .618f) % 1f) * w
            val y = ((i * .381f) % 1f) * h * .6f
            drawCircle(art.accent.copy(alpha = .5f), 1f + (i % 2) * .5f, Offset(x, y))
        }
        OrbitFeature.BIG_MOON -> {
            // Low over the skyline: the top quarter belongs to page headers, whose height varies
            // with the difficulty summary, so a moon there sat on top of the text.
            val moon = Offset(w * .17f, h * .74f)
            drawCircle(art.planetLight.copy(alpha = .6f), w * .07f, moon)
            drawCircle(art.planetDark.copy(alpha = .5f), w * .018f, moon + Offset(-w * .02f, -w * .01f))
            drawCircle(art.planetDark.copy(alpha = .4f), w * .011f, moon + Offset(w * .018f, w * .02f))
        }
    }

    // Moons travel a small elliptical path around the planet.
    for (i in 0 until art.moons) {
        val a = 0.6f + i * 1.9f
        val p = Offset(center.x + cos(a) * r * 1.7f, center.y + sin(a) * r * .65f)
        drawCircle(art.planetLight.copy(alpha = .9f), r * .14f, p)
    }

    // Mountains: three harmonics with an orbit-specific phase, so each landscape has its own skyline.
    val mountains = Path().apply {
        val s = art.mountainSeed
        moveTo(0f, h)
        for (step in 0..24) {
            val x = w * step / 24f
            val y = h * (.9f - .035f * sin(3.1f * x / w + s) - .025f * sin(7.3f * x / w + s * 1.7f) - .012f * sin(13f * x / w + s * 2.3f))
            if (step == 0) lineTo(0f, y) else lineTo(x, y)
        }
        lineTo(w, h)
        close()
    }
    drawPath(mountains, Color(0xFF171B2D).copy(alpha = .8f))
}

private fun DrawScope.ring(art: OrbitArt, center: Offset, r: Float, rx: Float, ry: Float) {
    rotate(art.tiltDegrees, center) {
        drawOval(
            art.accent.copy(alpha = .55f),
            topLeft = Offset(center.x - r * rx, center.y - r * ry),
            size = Size(r * rx * 2f, r * ry * 2f),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}
