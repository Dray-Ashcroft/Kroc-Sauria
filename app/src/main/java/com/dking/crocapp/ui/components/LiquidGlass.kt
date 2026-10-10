package com.dking.crocapp.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

// ═══════════════════════════════════════════════════════════════
// Liquid Glass
//
// Why the old buttons showed octagons: they were translucent Surfaces with
// shadowElevation. Android's elevation shadow is a tessellated polygon that
// is drawn *underneath* the surface; on an opaque surface you never see its
// inside, but through a translucent fill you do — and a circle's shadow is
// tessellated as an octagon-ish polygon.
//
// The fix used here: never use shadowElevation on glass. Instead draw a
// soft shadow ourselves, and clip it so it exists only OUTSIDE the shape.
// The glass then has nothing ugly behind it, only the app backdrop.
//
// Layers, bottom → top:
//   1. outside-only soft shadow (API 28+, silently skipped below)
//   2. tinted fill, a little lighter at the top (light coming from above)
//   3. caustic glow at the bottom edge (light bending through the "liquid")
//   4. specular sheen over the top half
//   5. content
//   6. 1dp rim: bright top-left, faint middle, soft bottom-right
// ═══════════════════════════════════════════════════════════════

fun Modifier.liquidGlass(
    shape: Shape,
    tint: Color,
    fillAlpha: Float = 0.55f,
    shadowElevation: Dp = 0.dp
): Modifier = composed {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val density = LocalDensity.current
    val shadowPx = with(density) { shadowElevation.toPx() }
    val rimPx = with(density) { 1.dp.toPx() }

    drawWithCache {
        val w = size.width
        val h = size.height
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }

        val baseAlpha = (tint.alpha * fillAlpha).coerceIn(0f, 1f)
        val solid = tint.copy(alpha = 1f)
        val topColor = lerp(solid, Color.White, if (dark) 0.08f else 0.45f)
            .copy(alpha = (baseAlpha + 0.12f).coerceAtMost(1f))
        val fill = Brush.verticalGradient(listOf(topColor, solid.copy(alpha = baseAlpha)))

        val sheen = Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (dark) 0.12f else 0.40f),
            0.5f to Color.Transparent,
            startY = 0f,
            endY = h
        )
        val caustic = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = if (dark) 0.07f else 0.22f), Color.Transparent),
            center = Offset(w * 0.5f, h * 1.05f),
            radius = max(w, h) * 0.6f
        )
        val rim = Brush.linearGradient(
            0f to Color.White.copy(alpha = if (dark) 0.40f else 0.95f),
            0.45f to Color.White.copy(alpha = if (dark) 0.06f else 0.25f),
            1f to Color.White.copy(alpha = if (dark) 0.20f else 0.60f),
            start = Offset.Zero,
            end = Offset(w, h)
        )
        val rimStroke = Stroke(width = rimPx)

        val shadowPaint = if (shadowPx > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK // clipped away; only its shadow survives
                setShadowLayer(
                    shadowPx * 1.6f,
                    0f,
                    shadowPx * 0.55f,
                    Color.Black.copy(alpha = if (dark) 0.45f else 0.14f).toArgb()
                )
            }
        } else null
        val androidPath = path.asAndroidPath()

        onDrawWithContent {
            if (shadowPaint != null) {
                clipPath(path, ClipOp.Difference) {
                    drawIntoCanvas { it.nativeCanvas.drawPath(androidPath, shadowPaint) }
                }
            }
            drawPath(path, fill)
            drawPath(path, caustic)
            drawPath(path, sheen)
            drawContent()
            drawPath(path, rim, style = rimStroke)
        }
    }
}

/**
 * Drop-in replacement for Material3 `Card` that renders as liquid glass.
 * Same parameters as the Card calls in this app, so swapping `Card(` for
 * `GlassCard(` is the whole migration. The card's containerColor becomes the
 * glass tint; its contentColor is kept so text colours don't change.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    colors: CardColors = CardDefaults.cardColors(),
    content: @Composable ColumnScope.() -> Unit
) {
    // Blurred shadows are re-rendered whenever a card changes size (e.g. the
    // Send screen's mode panel morphs height on every frame of a switch).
    // Phones handle that easily. Large displays — classroom smart panels,
    // TVs, big tablets — push 4–8x the pixels through a much weaker GPU, and
    // re-blurring there drops frames. So cards keep their shadow on phones
    // and skip it on large screens (smallest width >= 600dp, Android's
    // standard tablet/large-display threshold). Fixed-size glass (round
    // buttons, nav bar, header) keeps its shadow everywhere: it is blurred
    // once and reused.
    val largeScreen = LocalConfiguration.current.smallestScreenWidthDp >= 600
    Card(
        modifier = modifier.liquidGlass(
            shape = shape,
            tint = colors.containerColor,
            shadowElevation = if (largeScreen) 0.dp else 4.dp
        ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
            contentColor = colors.contentColor
        ),
        content = content
    )
}

/**
 * Soft colour field painted once behind the whole app. Glass needs something
 * behind it to tint — over a flat background it just looks grey. Static on
 * purpose: a constantly animating backdrop would redraw every frame and cost
 * battery and smoothness elsewhere.
 */
@Composable
fun LiquidBackdrop(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val amoled = cs.background == Color.Black
    val a = when {
        amoled -> 0.28f
        dark -> 0.45f
        else -> 0.75f
    }
    val bg = cs.background
    val c1 = cs.primaryContainer.copy(alpha = a)
    val c2 = cs.tertiaryContainer.copy(alpha = a)
    val c3 = cs.secondaryContainer.copy(alpha = a * 0.9f)

    Box(
        modifier = modifier.drawBehind {
            val w = size.width
            val h = size.height
            drawRect(bg)
            fun blob(color: Color, cx: Float, cy: Float, r: Float) {
                val center = Offset(cx, cy)
                drawCircle(
                    brush = Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = r),
                    radius = r,
                    center = center
                )
            }
            blob(c1, w * 0.05f, h * 0.15f, w * 0.95f)
            blob(c2, w * 1.0f, h * 0.55f, w * 0.85f)
            blob(c3, w * 0.20f, h * 0.98f, w * 0.95f)
        }
    )
}
