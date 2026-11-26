package com.evasi.downloader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale

internal val Ink = Color(0xFF152D29)
internal val Muted = Color(0xFF53655F)
internal val Accent = Color(0xFF176752)
internal val Paper = Color(0xFFF8F9F5)
internal val Line = Color(0xFFD4DFD7)
internal val Mint = Color(0xFFE2F2E8)

internal enum class Mark { Logo, Check, Settings, Back, Chevron, Clipboard, Sliders, Download, Gallery, Close, Repeat, Globe, Bell, Link, Share }

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
                Mark.Check -> path(4.5f, 12f, 10f, 17.5f, 20f, 7f)
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
