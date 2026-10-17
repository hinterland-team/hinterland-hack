package com.hinterland.clinic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class GlyphKind { ROUTE, DOOR, PERSON, CHECK, LOCK, MIC, THUMB_UP, THUMB_DOWN, WHEELCHAIR, PACE_1, PACE_2, PACE_3, TICKET, BACK, PAUSE, PHONE, PLUG, CLOSE }

// simple line icons, all on one 24-unit grid with round caps
@Composable
fun Glyph(kind: GlyphKind, color: Color, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        when (kind) {
            GlyphKind.ROUTE -> {
                drawCircle(color, 2.6f * u, p(5f, 18f))
                drawCircle(color, 2.6f * u, p(19f, 6f), style = stroke)
                val path = Path().apply {
                    moveTo(5f * u, 14.5f * u)
                    cubicTo(5f * u, 8f * u, 19f * u, 16f * u, 19f * u, 9.5f * u)
                }
                drawPath(path, color, style = Stroke(2.2f * u, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.4f * u, 3f * u))))
            }
            GlyphKind.DOOR -> {
                drawRoundRect(color, p(4f, 3f), Size(11f * u, 18f * u), CornerRadius(1.5f * u), style = stroke)
                drawCircle(color, 1.2f * u, p(12f, 12.5f))
                drawLine(color, p(14.5f, 12f), p(21f, 12f), 2.2f * u, StrokeCap.Round)
                drawLine(color, p(18f, 9f), p(21f, 12f), 2.2f * u, StrokeCap.Round)
                drawLine(color, p(18f, 15f), p(21f, 12f), 2.2f * u, StrokeCap.Round)
            }
            GlyphKind.PERSON -> {
                drawCircle(color, 4f * u, p(12f, 8f), style = stroke)
                drawArc(color, 200f, 140f, false, p(4.5f, 14f), Size(15f * u, 13f * u), style = stroke)
            }
            GlyphKind.CHECK -> {
                val path = Path().apply {
                    moveTo(5f * u, 12.5f * u)
                    lineTo(10f * u, 17.5f * u)
                    lineTo(19.5f * u, 6.5f * u)
                }
                drawPath(path, color, style = Stroke(3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            GlyphKind.LOCK -> {
                drawArc(color, 180f, 180f, false, p(7.5f, 4f), Size(9f * u, 10f * u), style = stroke)
                drawLine(color, p(7.5f, 9f), p(7.5f, 11f), 2.2f * u, StrokeCap.Round)
                drawLine(color, p(16.5f, 9f), p(16.5f, 11f), 2.2f * u, StrokeCap.Round)
                drawRoundRect(color, p(5f, 11f), Size(14f * u, 10f * u), CornerRadius(2f * u))
            }
            GlyphKind.MIC -> {
                drawRoundRect(color, p(9f, 3f), Size(6f * u, 11f * u), CornerRadius(3f * u), style = stroke)
                drawArc(color, 20f, 140f, false, p(5.5f, 6f), Size(13f * u, 12f * u), style = stroke)
                drawLine(color, p(12f, 18f), p(12f, 21f), 2.2f * u, StrokeCap.Round)
            }
            GlyphKind.THUMB_UP -> thumb(color, u, stroke)
            GlyphKind.THUMB_DOWN -> rotate(180f) { thumb(color, u, stroke) }
            GlyphKind.WHEELCHAIR -> {
                drawCircle(color, 1.8f * u, p(9f, 3.5f))
                drawCircle(color, 6f * u, p(10f, 16f), style = stroke)
                val path = Path().apply {
                    moveTo(9f * u, 7f * u)
                    lineTo(9.5f * u, 12f * u)
                    lineTo(16f * u, 12f * u)
                    lineTo(18.5f * u, 19f * u)
                    lineTo(21f * u, 18f * u)
                }
                drawPath(path, color, style = stroke)
            }
            GlyphKind.PACE_1, GlyphKind.PACE_2, GlyphKind.PACE_3 -> {
                val n = when (kind) {
                    GlyphKind.PACE_1 -> 1
                    GlyphKind.PACE_2 -> 2
                    else -> 3
                }
                val total = 6f * (n - 1)
                for (i in 0 until n) {
                    val x = 12f - total / 2f + i * 6f
                    chevron(color, u, x)
                }
            }
            GlyphKind.TICKET -> {
                val path = Path().apply {
                    moveTo(3f * u, 6f * u)
                    lineTo(21f * u, 6f * u)
                    lineTo(21f * u, 10f * u)
                    arcTo(Rect(19f * u, 10f * u, 23f * u, 14f * u), 270f, -180f, false)
                    lineTo(21f * u, 18f * u)
                    lineTo(3f * u, 18f * u)
                    lineTo(3f * u, 14f * u)
                    arcTo(Rect(1f * u, 10f * u, 5f * u, 14f * u), 90f, -180f, false)
                    close()
                }
                drawPath(path, color, style = stroke)
                drawLine(color, p(14f, 8f), p(14f, 16f), 1.6f * u, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(1.6f * u, 2f * u)))
            }
            GlyphKind.PAUSE -> {
                drawRoundRect(color, p(6.5f, 5f), Size(3.6f * u, 14f * u), CornerRadius(1.4f * u))
                drawRoundRect(color, p(13.9f, 5f), Size(3.6f * u, 14f * u), CornerRadius(1.4f * u))
            }
            GlyphKind.PHONE -> {
                drawRoundRect(color, p(6.5f, 2.5f), Size(11f * u, 19f * u), CornerRadius(2.4f * u), style = stroke)
                drawLine(color, p(10.5f, 18.2f), p(13.5f, 18.2f), 2.2f * u, StrokeCap.Round)
            }
            GlyphKind.PLUG -> {
                // a plug with its cable: "driving is switched off"
                drawLine(color, p(9f, 3f), p(9f, 7f), 2.2f * u, StrokeCap.Round)
                drawLine(color, p(15f, 3f), p(15f, 7f), 2.2f * u, StrokeCap.Round)
                val body = Path().apply {
                    moveTo(5.5f * u, 7f * u)
                    lineTo(18.5f * u, 7f * u)
                    lineTo(18.5f * u, 10.5f * u)
                    cubicTo(18.5f * u, 14.5f * u, 15.5f * u, 16.5f * u, 12f * u, 16.5f * u)
                    cubicTo(8.5f * u, 16.5f * u, 5.5f * u, 14.5f * u, 5.5f * u, 10.5f * u)
                    close()
                }
                drawPath(body, color, style = stroke)
                drawLine(color, p(12f, 16.5f), p(12f, 21f), 2.2f * u, StrokeCap.Round)
            }
            GlyphKind.CLOSE -> {
                drawLine(color, p(6f, 6f), p(18f, 18f), 2.4f * u, StrokeCap.Round)
                drawLine(color, p(18f, 6f), p(6f, 18f), 2.4f * u, StrokeCap.Round)
            }
            GlyphKind.BACK -> {
                drawLine(color, p(19f, 12f), p(5f, 12f), 2.4f * u, StrokeCap.Round)
                drawLine(color, p(11f, 6f), p(5f, 12f), 2.4f * u, StrokeCap.Round)
                drawLine(color, p(11f, 18f), p(5f, 12f), 2.4f * u, StrokeCap.Round)
            }
        }
    }
}

private fun DrawScope.chevron(color: Color, u: Float, x: Float) {
    val path = Path().apply {
        moveTo((x - 2.5f) * u, 6f * u)
        lineTo((x + 2.5f) * u, 12f * u)
        lineTo((x - 2.5f) * u, 18f * u)
    }
    drawPath(path, color, style = Stroke(2.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.thumb(color: Color, u: Float, stroke: Stroke) {
    // a fist with the thumb up: palm block, sleeve, thumb
    drawRoundRect(color, Offset(2.5f * u, 10.5f * u), Size(4f * u, 10.5f * u), CornerRadius(1.2f * u), style = stroke)
    val path = Path().apply {
        moveTo(6.5f * u, 11f * u)
        lineTo(10.5f * u, 4f * u)
        cubicTo(12.5f * u, 2.6f * u, 14.6f * u, 4f * u, 13.8f * u, 6.6f * u)
        lineTo(13f * u, 9.6f * u)
        lineTo(19f * u, 9.6f * u)
        cubicTo(20.8f * u, 9.6f * u, 21.8f * u, 11.2f * u, 21.2f * u, 12.8f * u)
        lineTo(19f * u, 19.4f * u)
        cubicTo(18.6f * u, 20.4f * u, 17.8f * u, 21f * u, 16.8f * u, 21f * u)
        lineTo(6.5f * u, 21f * u)
        close()
    }
    drawPath(path, color, style = stroke)
}
