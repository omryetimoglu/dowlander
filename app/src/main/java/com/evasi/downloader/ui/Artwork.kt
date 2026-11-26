package com.evasi.downloader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

internal val Ink = Color(0xFF1E1B30)
internal val Muted = Color(0xFF625F71)
internal val Accent = Color(0xFF5943BD)
internal val Paper = Color(0xFFF8F8FC)
internal val Line = Color(0xFFDFDDE9)
internal val Lilac = Color(0xFFEDE9FF)
private val Lime = Color(0xFFC6F382)

internal enum class Mark { Logo, Settings, Back, Chevron, Clipboard, Sliders, Download, Gallery, Close, Repeat, Globe, Bell, Link, Share }

private fun line(vararg coordinates: Float, closed: Boolean = false): Path = Path().apply {
    moveTo(coordinates[0], coordinates[1])
    for (i in 2 until coordinates.size step 2) lineTo(coordinates[i], coordinates[i + 1])
    if (closed) close()
}

@Composable
internal fun Glyph(mark: Mark, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val pen = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun path(vararg points: Float) = drawPath(line(*points), color, style = pen)
        fun box(x: Float, y: Float, w: Float, h: Float, r: Float) = drawRoundRect(
            color, Offset(x, y), Size(w, h), CornerRadius(r), style = pen,
        )
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            when (mark) {
                Mark.Logo -> {
                    drawPath(line(2f, 2f, 22f, 2f, 19f, 6f, 5f, 6f, closed = true), color)
                    drawPath(line(6f, 9f, 18f, 9f, 12f, 17f, closed = true), color)
                    path(2f, 16f, 2f, 22f, 22f, 22f, 22f, 16f)
                }
                Mark.Download -> {
                    path(12f, 3f, 12f, 14f)
                    path(7.5f, 10f, 12f, 14.5f, 16.5f, 10f)
                    path(4f, 15.5f, 4f, 19.5f, 20f, 19.5f, 20f, 15.5f)
                }
                Mark.Settings -> {
                    drawCircle(color, 3.1f, Offset(12f, 12f), style = pen)
                    val gear = Path()
                    for (tooth in 0..7) {
                        for ((delta, radius) in listOf(-22.5 to 7.5, -13.0 to 7.5, -10.0 to 9.3, 10.0 to 9.3, 13.0 to 7.5)) {
                            val radians = Math.toRadians(tooth * 45.0 + delta)
                            val x = 12f + (radius * kotlin.math.cos(radians)).toFloat()
                            val y = 12f + (radius * kotlin.math.sin(radians)).toFloat()
                            if (tooth == 0 && delta == -22.5) gear.moveTo(x, y) else gear.lineTo(x, y)
                        }
                    }
                    gear.close()
                    drawPath(gear, color, style = pen)
                }
                Mark.Back -> { path(11f, 5f, 4f, 12f, 11f, 19f); path(4f, 12f, 21f, 12f) }
                Mark.Chevron -> path(9f, 5.5f, 15.5f, 12f, 9f, 18.5f)
                Mark.Clipboard -> {
                    box(5f, 4.5f, 14f, 16f, 2.2f)
                    drawRoundRect(color, Offset(9f, 2.5f), Size(6f, 4.5f), CornerRadius(1.5f))
                    path(9f, 12f, 15f, 12f); path(9f, 16f, 13f, 16f)
                }
                Mark.Sliders -> {
                    path(3f, 7f, 8f, 7f); path(13f, 7f, 21f, 7f)
                    path(3f, 17f, 12f, 17f); path(17f, 17f, 21f, 17f)
                    drawCircle(color, 2.4f, Offset(10.5f, 7f), style = pen)
                    drawCircle(color, 2.4f, Offset(14.5f, 17f), style = pen)
                }
                Mark.Gallery -> {
                    box(3f, 3.5f, 18f, 17f, 3f)
                    drawCircle(color, 1.75f, Offset(8f, 8.7f))
                    path(4f, 17.5f, 8.5f, 13f, 12f, 16.5f, 16.2f, 11.5f, 21f, 16.1f)
                }
                Mark.Close -> { path(6f, 6f, 18f, 18f); path(18f, 6f, 6f, 18f) }
                Mark.Repeat -> {
                    drawArc(color, 216f, 285f, false, Offset(4f, 4f), Size(16f, 16f), style = pen)
                    path(4f, 3.5f, 4f, 8.5f, 9f, 8.5f)
                }
                Mark.Globe -> {
                    drawCircle(color, 9f, Offset(12f, 12f), style = pen)
                    drawOval(color, Offset(8f, 3f), Size(8f, 18f), style = pen)
                    path(3f, 12f, 21f, 12f)
                }
                Mark.Bell -> {
                    val bell = Path().apply {
                        moveTo(4f, 17f); quadraticTo(6f, 15f, 6f, 11.5f)
                        cubicTo(6f, 2f, 18f, 2f, 18f, 11.5f)
                        quadraticTo(18f, 15f, 20f, 17f); close()
                    }
                    drawPath(bell, color, style = pen)
                    drawArc(color, 0f, 180f, false, Offset(9.6f, 17f), Size(4.8f, 4f), style = pen)
                }
                Mark.Link -> {
                    rotate(-45f, Offset(12f, 12f)) {
                        box(2f, 8.5f, 9f, 7f, 3.5f); box(13f, 8.5f, 9f, 7f, 3.5f)
                        path(8f, 12f, 16f, 12f)
                    }
                }
                Mark.Share -> {
                    path(6f, 12f, 17f, 5.5f); path(6f, 12f, 17f, 18.5f)
                    for (point in listOf(Offset(6f, 12f), Offset(17f, 5.5f), Offset(17f, 18.5f))) {
                        drawCircle(color, 2.8f, point)
                    }
                }
            }
        }
    }
}

/** A compact media-to-gallery diagram. It scales without cropping on narrow phones. */
@Composable
internal fun TransferArtwork(saved: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(24.dp))) {
        drawRect(Brush.linearGradient(listOf(Color(0xFF211B37), Color(0xFF332B4B))))
        val unit = minOf(size.width / 340f, size.height / 168f)
        val spacing = 13f * unit
        var x = size.width / 2 % spacing
        while (x < size.width) {
            var y = size.height / 2 % spacing
            while (y < size.height) {
                drawCircle(Color.White.copy(alpha = 0.17f), unit * 0.7f, Offset(x, y))
                y += spacing
            }
            x += spacing
        }
        withTransform({
            translate((size.width - 340f * unit) / 2f, (size.height - 168f * unit) / 2f)
            scale(unit, unit, Offset.Zero)
        }) {
            val lavender = Color(0xFFD2C8F5)
            val deep = Color(0xFF383045)
            val route = Path().apply {
                moveTo(100f, 92f); lineTo(149f, 92f)
                cubicTo(163f, 92f, 162f, 74f, 177f, 74f); lineTo(235f, 74f)
            }
            drawPath(route, Lime, style = Stroke(width = 1.7f, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f))))

            rotate(-10f, Offset(85f, 88f)) {
                drawRoundRect(Color.Black.copy(alpha = 0.2f), Offset(48f, 54f), Size(80f, 76f), CornerRadius(9f))
                drawRoundRect(lavender, Offset(45f, 49f), Size(80f, 76f), CornerRadius(9f))
                drawRoundRect(Color(0xFF7F6AA7), Offset(52f, 56f), Size(66f, 44f), CornerRadius(4f))
                drawPath(line(80f, 69f, 80f, 87f, 95f, 78f, closed = true), Color(0xFFF8F3FF))
                drawRoundRect(deep, Offset(53f, 107f), Size(40f, 3f), CornerRadius(1.5f))
                drawRoundRect(Color(0xFF9E8EBC), Offset(53f, 114f), Size(28f, 2.5f), CornerRadius(1.25f))
            }
            rotate(13f, Offset(252f, 83f)) {
                drawRoundRect(Color(0xFF9480B4), Offset(205f, 50f), Size(91f, 70f), CornerRadius(7f))
            }
            rotate(-6f, Offset(245f, 82f)) {
                drawRoundRect(Color(0xFFF0EBFF), Offset(201f, 43f), Size(91f, 74f), CornerRadius(7f))
                drawRoundRect(Color(0xFFD4C6F1), Offset(207f, 49f), Size(79f, 61f), CornerRadius(4f))
                drawCircle(Color(0xFFF8F3FF), 6f, Offset(268f, 63f))
                drawPath(line(211f, 104f, 231f, 76f, 252f, 103f, 264f, 86f, 281f, 104f, closed = true), Color(0xFF796591))
            }
            drawCircle(deep, 16.5f, Offset(288f, 111f))
            drawCircle(Lime, 14.5f, Offset(288f, 111f))
            val badge = Stroke(width = 2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (saved) {
                drawPath(line(281f, 111f, 286f, 116f, 295f, 106f), deep, style = badge)
            } else {
                drawLine(deep, Offset(288f, 104f), Offset(288f, 116f), 2.3f, StrokeCap.Round)
                drawPath(line(282.5f, 111f, 288f, 116.5f, 293.5f, 111f), deep, style = badge)
            }
        }
    }
}
