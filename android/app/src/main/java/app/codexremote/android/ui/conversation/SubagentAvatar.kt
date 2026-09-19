package app.codexremote.android.ui.conversation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private data class AvatarPalette(
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val center: Color,
    val subtle: Color,
)

private val AVATAR_PALETTES = listOf(
    // 0: Coral Blossom (warm)
    AvatarPalette(
        primary = Color(0xFFE64A19),
        secondary = Color(0xFFFF8A65).copy(alpha = 0.70f),
        accent = Color(0xFFFFD54F),
        center = Color(0xFFFFF8E1),
        subtle = Color(0xFFFFCCBC).copy(alpha = 0.40f)
    ),
    // 1: Emerald Meadow (cool botanical)
    AvatarPalette(
        primary = Color(0xFF00796B),
        secondary = Color(0xFF4DB6AC).copy(alpha = 0.70f),
        accent = Color(0xFFA7FFEB),
        center = Color(0xFFE0F2F1),
        subtle = Color(0xFFB2DFDB).copy(alpha = 0.40f)
    ),
    // 2: Indigo Iris (cool floral)
    AvatarPalette(
        primary = Color(0xFF3949AB),
        secondary = Color(0xFF7986CB).copy(alpha = 0.70f),
        accent = Color(0xFF8C9EFF),
        center = Color(0xFFEDE7F6),
        subtle = Color(0xFFC5CAE9).copy(alpha = 0.40f)
    ),
    // 3: Golden Sunflower (warm bright)
    AvatarPalette(
        primary = Color(0xFFF57C00),
        secondary = Color(0xFFFFB74D).copy(alpha = 0.75f),
        accent = Color(0xFFFFEE58),
        center = Color(0xFFFFFDE7),
        subtle = Color(0xFFFFE082).copy(alpha = 0.40f)
    ),
    // 4: Azure Sky (cool crystalline)
    AvatarPalette(
        primary = Color(0xFF0288D1),
        secondary = Color(0xFF4FC3F7).copy(alpha = 0.70f),
        accent = Color(0xFF80D8FF),
        center = Color(0xFFE1F5FE),
        subtle = Color(0xFFB3E5FC).copy(alpha = 0.40f)
    ),
    // 5: Crimson Dahlia (vivid warm)
    AvatarPalette(
        primary = Color(0xFFC2185B),
        secondary = Color(0xFFEC407A).copy(alpha = 0.70f),
        accent = Color(0xFFFF80AB),
        center = Color(0xFFFCE4EC),
        subtle = Color(0xFFF8BBD0).copy(alpha = 0.40f)
    ),
    // 6: Copper Ochre (warm earthy)
    AvatarPalette(
        primary = Color(0xFFD84315),
        secondary = Color(0xFFFF7043).copy(alpha = 0.70f),
        accent = Color(0xFFFFAB40),
        center = Color(0xFFFBE9E7),
        subtle = Color(0xFFFFCC80).copy(alpha = 0.40f)
    ),
    // 7: Royal Violet (deep rich jewel)
    AvatarPalette(
        primary = Color(0xFF7B1FA2),
        secondary = Color(0xFFBA68C8).copy(alpha = 0.70f),
        accent = Color(0xFFEA80FC),
        center = Color(0xFFF3E5F5),
        subtle = Color(0xFFE1BEE7).copy(alpha = 0.40f)
    )
)

/**
 * Procedural subagent avatar rendering 8 distinct radial geometric and floral shapes
 * across 8 harmonious palettes with layered translucent depth.
 *
 * Fully decorative with no duplicate accessibility labels (the enclosing container
 * provides full identity and status metadata).
 */
@Composable
internal fun SubagentAvatar(
    threadId: String,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    val spec = remember(threadId) { SubagentAvatarIdentity.forId(threadId) }
    val palette = AVATAR_PALETTES[spec.palette.coerceIn(0, 7)]

    Canvas(modifier = modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = min(this.size.width, this.size.height) / 2f * 0.90f
        if (radius <= 0f) return@Canvas

        when (spec.shape) {
            0 -> drawPetalFlower(center, radius, palette)
            1 -> drawPointedLeaves(center, radius, palette)
            2 -> drawSoftClover(center, radius, palette)
            3 -> drawRosette(center, radius, palette)
            4 -> drawDiamondCompass(center, radius, palette)
            5 -> drawOverlappingRings(center, radius, palette)
            6 -> drawSunflower(center, radius, palette)
            7 -> drawCompactGeometricStar(center, radius, palette)
            else -> drawPetalFlower(center, radius, palette)
        }
    }
}

/**
 * Shape 0: Petal Flower - radial 6-petal floral blossom with dual translucent overlapping tiers.
 */
private fun DrawScope.drawPetalFlower(center: Offset, radius: Float, palette: AvatarPalette) {
    val petalCount = 6
    val angleStep = 360f / petalCount
    val outerPetalLen = radius * 0.88f
    val outerPetalWidth = radius * 0.40f

    // Outer soft petals
    for (i in 0 until petalCount) {
        rotate(degrees = i * angleStep, pivot = center) {
            val path = Path().apply {
                moveTo(center.x, center.y)
                cubicTo(
                    center.x - outerPetalWidth * 0.85f, center.y - outerPetalLen * 0.5f,
                    center.x - outerPetalWidth * 0.45f, center.y - outerPetalLen,
                    center.x, center.y - outerPetalLen
                )
                cubicTo(
                    center.x + outerPetalWidth * 0.45f, center.y - outerPetalLen,
                    center.x + outerPetalWidth * 0.85f, center.y - outerPetalLen * 0.5f,
                    center.x, center.y
                )
                close()
            }
            drawPath(path, color = palette.secondary)
        }
    }

    // Inner primary petals rotated by half-step
    val innerPetalLen = radius * 0.68f
    val innerPetalWidth = radius * 0.30f
    for (i in 0 until petalCount) {
        rotate(degrees = i * angleStep + (angleStep / 2f), pivot = center) {
            val path = Path().apply {
                moveTo(center.x, center.y)
                cubicTo(
                    center.x - innerPetalWidth * 0.85f, center.y - innerPetalLen * 0.5f,
                    center.x - innerPetalWidth * 0.45f, center.y - innerPetalLen,
                    center.x, center.y - innerPetalLen
                )
                cubicTo(
                    center.x + innerPetalWidth * 0.45f, center.y - innerPetalLen,
                    center.x + innerPetalWidth * 0.85f, center.y - innerPetalLen * 0.5f,
                    center.x, center.y
                )
                close()
            }
            drawPath(path, color = palette.primary)
        }
    }

    // Pistil core
    drawCircle(color = palette.accent, radius = radius * 0.26f, center = center)
    drawCircle(color = palette.center, radius = radius * 0.12f, center = center)
}

/**
 * Shape 1: Pointed Leaves - 6 lanceolate foliate leaves with delicate central ribs.
 */
private fun DrawScope.drawPointedLeaves(center: Offset, radius: Float, palette: AvatarPalette) {
    val leafCount = 6
    val angleStep = 360f / leafCount
    val leafLen = radius * 0.92f
    val leafWidth = radius * 0.36f

    // Outer lanceolate leaves
    for (i in 0 until leafCount) {
        rotate(degrees = i * angleStep, pivot = center) {
            val path = Path().apply {
                moveTo(center.x, center.y)
                quadraticBezierTo(
                    center.x - leafWidth, center.y - leafLen * 0.55f,
                    center.x, center.y - leafLen
                )
                quadraticBezierTo(
                    center.x + leafWidth, center.y - leafLen * 0.55f,
                    center.x, center.y
                )
                close()
            }
            drawPath(path, color = palette.secondary)
            // Leaf rib
            drawLine(
                color = palette.primary,
                start = center,
                end = Offset(center.x, center.y - leafLen * 0.82f),
                strokeWidth = (radius * 0.08f).coerceAtLeast(1.5f),
                cap = StrokeCap.Round
            )
        }
    }

    // Inner smaller sharp leaves
    val innerLeafLen = radius * 0.58f
    val innerLeafWidth = radius * 0.22f
    for (i in 0 until leafCount) {
        rotate(degrees = i * angleStep + (angleStep / 2f), pivot = center) {
            val path = Path().apply {
                moveTo(center.x, center.y)
                quadraticBezierTo(
                    center.x - innerLeafWidth, center.y - innerLeafLen * 0.5f,
                    center.x, center.y - innerLeafLen
                )
                quadraticBezierTo(
                    center.x + innerLeafWidth, center.y - innerLeafLen * 0.5f,
                    center.x, center.y
                )
                close()
            }
            drawPath(path, color = palette.primary)
        }
    }

    drawCircle(color = palette.accent, radius = radius * 0.22f, center = center)
    drawCircle(color = palette.center, radius = radius * 0.10f, center = center)
}

/**
 * Shape 2: Soft Clover - 4-lobed rounded quadrifoil clover with diagonal underlay.
 */
private fun DrawScope.drawSoftClover(center: Offset, radius: Float, palette: AvatarPalette) {
    val leafCount = 4
    val angleStep = 90f

    // Diagonal soft translucent underlay lobes
    val underDist = radius * 0.52f
    for (i in 0 until leafCount) {
        rotate(degrees = i * angleStep + 45f, pivot = center) {
            drawCircle(
                color = palette.subtle,
                radius = radius * 0.32f,
                center = Offset(center.x, center.y - underDist)
            )
        }
    }

    // 4 cardinal rounded heart clover leaves
    val leafDist = radius * 0.50f
    val lobeR = radius * 0.30f
    for (i in 0 until leafCount) {
        rotate(degrees = i * angleStep, pivot = center) {
            drawCircle(
                color = palette.primary,
                radius = lobeR,
                center = Offset(center.x - lobeR * 0.45f, center.y - leafDist)
            )
            drawCircle(
                color = palette.secondary,
                radius = lobeR,
                center = Offset(center.x + lobeR * 0.45f, center.y - leafDist)
            )
            val basePath = Path().apply {
                moveTo(center.x - lobeR * 0.9f, center.y - leafDist + lobeR * 0.2f)
                lineTo(center.x + lobeR * 0.9f, center.y - leafDist + lobeR * 0.2f)
                lineTo(center.x, center.y)
                close()
            }
            drawPath(basePath, color = palette.primary)
        }
    }

    drawCircle(color = palette.accent, radius = radius * 0.24f, center = center)
    drawCircle(color = palette.center, radius = radius * 0.12f, center = center)
}

/**
 * Shape 3: Rosette - layered concentric circular petals forming a classic multi-tier rosette.
 */
private fun DrawScope.drawRosette(center: Offset, radius: Float, palette: AvatarPalette) {
    val outerCount = 8
    val outerStep = 360f / outerCount
    val outerR = radius * 0.34f
    val outerDist = radius * 0.56f

    // Outer 8 petals
    for (i in 0 until outerCount) {
        rotate(degrees = i * outerStep, pivot = center) {
            drawCircle(
                color = palette.secondary,
                radius = outerR,
                center = Offset(center.x, center.y - outerDist)
            )
        }
    }

    // Mid 8 petals offset by 22.5 deg
    val midCount = 8
    val midR = radius * 0.26f
    val midDist = radius * 0.38f
    for (i in 0 until midCount) {
        rotate(degrees = i * outerStep + (outerStep / 2f), pivot = center) {
            drawCircle(
                color = palette.primary,
                radius = midR,
                center = Offset(center.x, center.y - midDist)
            )
        }
    }

    // Center nested concentric core
    drawCircle(color = palette.accent, radius = radius * 0.28f, center = center)
    drawCircle(
        color = palette.primary,
        radius = radius * 0.20f,
        center = center,
        style = Stroke(width = (radius * 0.08f).coerceAtLeast(1.5f))
    )
    drawCircle(color = palette.center, radius = radius * 0.12f, center = center)
}

/**
 * Shape 4: Diamond Compass - 8-point geometric compass rose with faceted 3D shading.
 */
private fun DrawScope.drawDiamondCompass(center: Offset, radius: Float, palette: AvatarPalette) {
    val majorLen = radius * 0.94f
    val majorWidth = radius * 0.26f
    val minorLen = radius * 0.64f
    val minorWidth = radius * 0.18f

    // 4 Ordinal (diagonal) diamond points
    for (i in 0 until 4) {
        rotate(degrees = i * 90f + 45f, pivot = center) {
            val path = Path().apply {
                moveTo(center.x, center.y - minorLen)
                lineTo(center.x + minorWidth, center.y - minorLen * 0.45f)
                lineTo(center.x, center.y)
                lineTo(center.x - minorWidth, center.y - minorLen * 0.45f)
                close()
            }
            drawPath(path, color = palette.secondary)
        }
    }

    // 4 Cardinal points with faceted left/right tones
    for (i in 0 until 4) {
        rotate(degrees = i * 90f, pivot = center) {
            // Left facet
            val pathLeft = Path().apply {
                moveTo(center.x, center.y - majorLen)
                lineTo(center.x - majorWidth, center.y - majorLen * 0.45f)
                lineTo(center.x, center.y)
                close()
            }
            drawPath(pathLeft, color = palette.primary)

            // Right facet
            val pathRight = Path().apply {
                moveTo(center.x, center.y - majorLen)
                lineTo(center.x + majorWidth, center.y - majorLen * 0.45f)
                lineTo(center.x, center.y)
                close()
            }
            drawPath(pathRight, color = palette.accent)
        }
    }

    drawCircle(color = palette.primary, radius = radius * 0.22f, center = center)
    drawCircle(color = palette.center, radius = radius * 0.11f, center = center)
}

/**
 * Shape 5: Overlapping Rings - 6 interlocking geometric circles forming floral lens intersections.
 */
private fun DrawScope.drawOverlappingRings(center: Offset, radius: Float, palette: AvatarPalette) {
    val ringCount = 6
    val ringStep = 360f / ringCount
    val ringR = radius * 0.46f
    val ringDist = radius * 0.46f
    val strokeW = (radius * 0.10f).coerceAtLeast(1.8f)

    // Translucent fills
    for (i in 0 until ringCount) {
        rotate(degrees = i * ringStep, pivot = center) {
            drawCircle(
                color = palette.secondary.copy(alpha = 0.45f),
                radius = ringR,
                center = Offset(center.x, center.y - ringDist)
            )
        }
    }

    // Interlocking outlines
    for (i in 0 until ringCount) {
        rotate(degrees = i * ringStep, pivot = center) {
            drawCircle(
                color = palette.primary,
                radius = ringR,
                center = Offset(center.x, center.y - ringDist),
                style = Stroke(width = strokeW)
            )
        }
    }

    drawCircle(color = palette.accent, radius = radius * 0.24f, center = center)
    drawCircle(color = palette.center, radius = radius * 0.12f, center = center)
}

/**
 * Shape 6: Sunflower - dense 12-petal solar ray array with distinct textured core disc.
 */
private fun DrawScope.drawSunflower(center: Offset, radius: Float, palette: AvatarPalette) {
    val petalCount = 12
    val angleStep = 360f / petalCount
    val petalLen = radius * 0.92f
    val petalWidth = radius * 0.16f

    // 12 outer radial petals alternating shades
    for (i in 0 until petalCount) {
        rotate(degrees = i * angleStep, pivot = center) {
            val path = Path().apply {
                moveTo(center.x, center.y - radius * 0.35f)
                lineTo(center.x - petalWidth, center.y - petalLen * 0.65f)
                lineTo(center.x, center.y - petalLen)
                lineTo(center.x + petalWidth, center.y - petalLen * 0.65f)
                close()
            }
            drawPath(path, color = if (i % 2 == 0) palette.primary else palette.secondary)
        }
    }

    // Disc floret
    drawCircle(color = palette.accent, radius = radius * 0.42f, center = center)
    drawCircle(color = palette.primary, radius = radius * 0.30f, center = center)
    drawCircle(color = palette.center, radius = radius * 0.14f, center = center)
}

/**
 * Shape 7: Compact Geometric Star - 8-pointed octagram with rotated layered facets.
 */
private fun DrawScope.drawCompactGeometricStar(center: Offset, radius: Float, palette: AvatarPalette) {
    val pointCount = 8
    val angleStep = 360f / pointCount
    val outerR = radius * 0.94f
    val innerR = radius * 0.48f

    // Outer 8-point star
    val starPath = Path()
    for (i in 0 until pointCount * 2) {
        val r = if (i % 2 == 0) outerR else innerR
        val angleRad = (i * (angleStep / 2f) - 90f) * (PI / 180f).toFloat()
        val x = center.x + r * cos(angleRad)
        val y = center.y + r * sin(angleRad)
        if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
    }
    starPath.close()
    drawPath(starPath, color = palette.primary)

    // Inner rotated star
    val midOuterR = radius * 0.66f
    val midInnerR = radius * 0.34f
    val midStarPath = Path()
    for (i in 0 until pointCount * 2) {
        val r = if (i % 2 == 0) midOuterR else midInnerR
        val angleRad = (i * (angleStep / 2f) - 90f + (angleStep / 2f)) * (PI / 180f).toFloat()
        val x = center.x + r * cos(angleRad)
        val y = center.y + r * sin(angleRad)
        if (i == 0) midStarPath.moveTo(x, y) else midStarPath.lineTo(x, y)
    }
    midStarPath.close()
    drawPath(midStarPath, color = palette.secondary)

    // Central pip
    drawCircle(color = palette.accent, radius = radius * 0.24f, center = center)
    drawCircle(color = palette.center, radius = radius * 0.12f, center = center)
}
