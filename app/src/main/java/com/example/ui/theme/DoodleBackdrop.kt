package com.example.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * WhatsApp-inspired subtle tiled vector doodle wallpaper overlay
 * that adapts smoothly to Light/Dark mode and the active M3 ColorScheme.
 */
@Composable
fun DoodleBackdrop(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tintColor: Color? = null
) {
    if (!enabled) return
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.luminance() < 0.5f
    val baseTint = tintColor ?: colorScheme.primary
    val secondaryTint = colorScheme.onBackground
    val primaryLine = baseTint.copy(alpha = if (isDark) 0.11f else 0.085f)
    val secondaryLine = secondaryTint.copy(alpha = if (isDark) 0.065f else 0.055f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val tile = 132f
        val cols = (size.width / tile).toInt() + 2
        val rows = (size.height / tile).toInt() + 2
        val strokePrimary = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val strokeSecondary = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val xOffset = if (r % 2 == 1) tile * 0.45f else 0f
                val cx = c * tile + tile * 0.45f - xOffset
                val cy = r * tile + tile * 0.45f
                val glyph = (r * 7 + c * 3) % 8
                val angle = (((r * 13 + c * 19) % 5) - 2) * 11f
                val color = if ((r + c) % 2 == 0) primaryLine else secondaryLine
                val stroke = if ((r + c) % 2 == 0) strokePrimary else strokeSecondary

                rotate(degrees = angle, pivot = Offset(cx, cy)) {
                    when (glyph) {
                        0 -> drawPhoneDoodle(Offset(cx, cy), color, stroke)
                        1 -> drawChatBubbleDoodle(Offset(cx, cy), color, stroke)
                        2 -> drawWaveformDoodle(Offset(cx, cy), color, stroke)
                        3 -> drawMusicNoteDoodle(Offset(cx, cy), color, stroke)
                        4 -> drawSparkleStarDoodle(Offset(cx, cy), color, stroke)
                        5 -> drawShieldDoodle(Offset(cx, cy), color, stroke)
                        6 -> drawHeartDoodle(Offset(cx, cy), color, stroke)
                        else -> drawHeadsetDoodle(Offset(cx, cy), color, stroke)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawPhoneDoodle(center: Offset, color: Color, stroke: Stroke) {
    val path = Path().apply {
        moveTo(center.x - 14f, center.y - 14f)
        cubicTo(
            center.x - 18f, center.y - 4f,
            center.x - 4f, center.y + 12f,
            center.x + 14f, center.y + 14f
        )
        lineTo(center.x + 18f, center.y + 7f)
        lineTo(center.x + 9f, center.y + 4f)
        lineTo(center.x + 6f, center.y + 7f)
        cubicTo(
            center.x - 1f, center.y + 4f,
            center.x - 6f, center.y - 1f,
            center.x - 8f, center.y - 7f
        )
        lineTo(center.x - 5f, center.y - 10f)
        lineTo(center.x - 8f, center.y - 18f)
        close()
    }
    drawPath(path, color = color, style = stroke)
}

private fun DrawScope.drawChatBubbleDoodle(center: Offset, color: Color, stroke: Stroke) {
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - 18f, center.y - 13f),
        size = Size(36f, 24f),
        cornerRadius = CornerRadius(10f, 10f),
        style = stroke
    )
    drawCircle(color = color, radius = 2.2f, center = Offset(center.x - 7f, center.y - 1f))
    drawCircle(color = color, radius = 2.2f, center = Offset(center.x, center.y - 1f))
    drawCircle(color = color, radius = 2.2f, center = Offset(center.x + 7f, center.y - 1f))
}

private fun DrawScope.drawWaveformDoodle(center: Offset, color: Color, stroke: Stroke) {
    val heights = listOf(8f, 16f, 24f, 14f, 20f, 10f)
    heights.forEachIndexed { idx, h ->
        val x = center.x - 18f + idx * 7.2f
        drawLine(
            color = color,
            start = Offset(x, center.y - h / 2f),
            end = Offset(x, center.y + h / 2f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawMusicNoteDoodle(center: Offset, color: Color, stroke: Stroke) {
    drawCircle(color = color, radius = 5.5f, center = Offset(center.x - 8f, center.y + 10f), style = stroke)
    drawCircle(color = color, radius = 5.5f, center = Offset(center.x + 10f, center.y + 7f), style = stroke)
    val path = Path().apply {
        moveTo(center.x - 3f, center.y + 10f)
        lineTo(center.x - 3f, center.y - 13f)
        lineTo(center.x + 15f, center.y - 16f)
        lineTo(center.x + 15f, center.y + 7f)
    }
    drawPath(path, color = color, style = stroke)
}

private fun DrawScope.drawSparkleStarDoodle(center: Offset, color: Color, stroke: Stroke) {
    val outer = 16f
    val inner = 6.5f
    val path = Path()
    for (i in 0 until 8) {
        val rad = if (i % 2 == 0) outer else inner
        val angle = (i * PI / 4.0) - PI / 2.0
        val x = center.x + (rad * cos(angle)).toFloat()
        val y = center.y + (rad * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color = color, style = stroke)
}

private fun DrawScope.drawShieldDoodle(center: Offset, color: Color, stroke: Stroke) {
    val path = Path().apply {
        moveTo(center.x, center.y - 16f)
        lineTo(center.x + 14f, center.y - 10f)
        cubicTo(
            center.x + 14f, center.y + 4f,
            center.x + 6f, center.y + 13f,
            center.x, center.y + 17f
        )
        cubicTo(
            center.x - 6f, center.y + 13f,
            center.x - 14f, center.y + 4f,
            center.x - 14f, center.y - 10f
        )
        close()
    }
    drawPath(path, color = color, style = stroke)
}

private fun DrawScope.drawHeartDoodle(center: Offset, color: Color, stroke: Stroke) {
    val path = Path().apply {
        moveTo(center.x, center.y + 14f)
        cubicTo(
            center.x - 18f, center.y + 2f,
            center.x - 18f, center.y - 16f,
            center.x, center.y - 5f
        )
        cubicTo(
            center.x + 18f, center.y - 16f,
            center.x + 18f, center.y + 2f,
            center.x, center.y + 14f
        )
        close()
    }
    drawPath(path, color = color, style = stroke)
}

private fun DrawScope.drawHeadsetDoodle(center: Offset, color: Color, stroke: Stroke) {
    val arcPath = Path().apply {
        moveTo(center.x - 14f, center.y + 4f)
        cubicTo(
            center.x - 14f, center.y - 17f,
            center.x + 14f, center.y - 17f,
            center.x + 14f, center.y + 4f
        )
    }
    drawPath(arcPath, color = color, style = stroke)
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - 17f, center.y + 2f),
        size = Size(7f, 12f),
        cornerRadius = CornerRadius(3f, 3f),
        style = stroke
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x + 10f, center.y + 2f),
        size = Size(7f, 12f),
        cornerRadius = CornerRadius(3f, 3f),
        style = stroke
    )
}
