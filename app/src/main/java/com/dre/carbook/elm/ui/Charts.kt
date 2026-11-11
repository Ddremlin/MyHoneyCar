package com.dre.carbook.elm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.dre.carbook.elm.Sample
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Разрыв линии, если между точками больше этого интервала (опрос был остановлен). */
private const val GAP_MS = 5_000L

/** Мини-график в карточке значения. */
@Composable
fun Sparkline(samples: List<Sample>, windowMs: Long, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(28.dp)) {
        val now = System.currentTimeMillis()
        val visible = samples.filter { it.t >= now - windowMs }
        if (visible.size < 2) return@Canvas
        val (lo, hi) = range(visible)
        drawSeries(visible, now - windowMs, now, lo, hi, 0f, size.width, 0f, size.height, color, 1.5.dp.toPx())
    }
}

/** Полноразмерный график с сеткой и подписями осей. */
@Composable
fun LineChart(samples: List<Sample>, windowMs: Long, decimals: Int, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val style = remember(labelStyle) { TextStyle(fontSize = labelStyle.fontSize, color = labelStyle.color) }

    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        val now = System.currentTimeMillis()
        val start = now - windowMs
        val visible = samples.filter { it.t >= start }
        val (rawLo, rawHi) = if (visible.isEmpty()) 0.0 to 1.0 else range(visible)
        val ticks = niceTicks(rawLo, rawHi)
        val lo = ticks.first()
        val hi = ticks.last()

        val labelW = ticks.maxOf { measurer.measure(fmt(it, decimals), style).size.width }.toFloat()
        val left = labelW + 6.dp.toPx()
        val bottomLabelH = measurer.measure("0", style).size.height.toFloat()
        val bottom = size.height - bottomLabelH - 4.dp.toPx()
        val top = bottomLabelH / 2

        for (t in ticks) {
            val y = yOf(t, lo, hi, top, bottom)
            drawLine(grid, Offset(left, y), Offset(size.width, y), 1f)
            val layout = measurer.measure(fmt(t, decimals), style)
            drawText(layout, topLeft = Offset(left - 6.dp.toPx() - layout.size.width, y - layout.size.height / 2))
        }
        // подписи времени: начало окна, середина, «сейчас»
        listOf(0f to "-${windowMs / 60_000} мин", 0.5f to "-${fmtMinutes(windowMs / 2)}", 1f to "сейчас").forEach { (f, text) ->
            val layout = measurer.measure(text, style)
            val x = (left + f * (size.width - left) - f * layout.size.width).coerceAtLeast(left)
            drawText(layout, topLeft = Offset(x, bottom + 4.dp.toPx()))
        }

        if (visible.size >= 2) {
            drawSeries(visible, start, now, lo, hi, left, size.width, top, bottom, line, 2.dp.toPx())
        }
        visible.lastOrNull()?.let {
            drawCircle(line, 3.5.dp.toPx(), Offset(size.width, yOf(it.v, lo, hi, top, bottom)))
        }
    }
}

private fun DrawScope.drawSeries(
    samples: List<Sample>,
    t0: Long,
    t1: Long,
    lo: Double,
    hi: Double,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    color: Color,
    width: Float,
) {
    val path = Path()
    var prev: Sample? = null
    for (s in samples) {
        val x = left + (right - left) * ((s.t - t0).toFloat() / (t1 - t0))
        val y = yOf(s.v, lo, hi, top, bottom)
        if (prev == null || s.t - prev.t > GAP_MS) path.moveTo(x, y) else path.lineTo(x, y)
        prev = s
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun yOf(v: Double, lo: Double, hi: Double, top: Float, bottom: Float): Float =
    if (hi == lo) (top + bottom) / 2 else bottom - ((v - lo) / (hi - lo)).toFloat() * (bottom - top)

private fun range(samples: List<Sample>): Pair<Double, Double> {
    val lo = samples.minOf { it.v }
    val hi = samples.maxOf { it.v }
    return if (hi - lo < 1e-9) (lo - 1) to (hi + 1) else lo to hi
}

/** «Красивые» деления шкалы: шаг 1/2/5 × 10^n, 3–6 линий. */
private fun niceTicks(lo: Double, hi: Double): List<Double> {
    val rough = (hi - lo) / 4
    val mag = 10.0.pow(floor(log10(rough)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= rough }
    val start = floor(lo / step) * step
    val end = ceil(hi / step) * step
    val n = ((end - start) / step).toInt()
    return (0..n).map { start + it * step }
}

private fun fmt(v: Double, decimals: Int): String {
    val d = if (abs(v) < 10 && decimals == 0 && v != floor(v)) 1 else decimals
    return "%.${d}f".format(v)
}

private fun fmtMinutes(ms: Long): String =
    if (ms % 60_000 == 0L) "${ms / 60_000} мин" else "${ms / 1000} с"
