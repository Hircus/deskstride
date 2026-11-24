package io.github.deskstride

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/** Small area chart of the speed during the session. */
class SpeedChart(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val values = ArrayList<Float>()
    private val maxPoints = 240
    private val accent = ContextCompat.getColor(ctx, R.color.accent)
    private val accent2 = ContextCompat.getColor(ctx, R.color.accent2)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    fun add(kmh: Double) {
        values += kmh.toFloat()
        if (values.size > maxPoints) values.removeAt(0)
        invalidate()
    }

    fun clear() { values.clear(); invalidate() }

    /** Loads a whole series, resampled (block averages) to [maxPoints] points. */
    fun setAll(series: List<Double>) {
        values.clear()
        if (series.size <= maxPoints) series.forEach { values += it.toFloat() }
        else {
            val bucket = series.size.toDouble() / maxPoints
            for (i in 0 until maxPoints) {
                val from = (i * bucket).toInt(); val to = ((i + 1) * bucket).toInt().coerceAtMost(series.size)
                values += series.subList(from, maxOf(to, from + 1)).average().toFloat()
            }
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        line.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, accent, accent2, Shader.TileMode.CLAMP)
        fill.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            (accent and 0x00FFFFFF) or 0x55000000, accent and 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(c: Canvas) {
        if (values.size < 2) return
        val w = width.toFloat(); val h = height.toFloat()
        val pad = line.strokeWidth
        val max = maxOf(values.max(), 1f) * 1.15f
        val step = w / (maxPoints - 1)
        val x0 = w - (values.size - 1) * step
        fun y(v: Float) = h - pad - (v / max) * (h - 2 * pad)

        path.reset()
        path.moveTo(x0, y(values[0]))
        for (i in 1 until values.size) {
            val px = x0 + (i - 1) * step; val x = x0 + i * step
            val mx = (px + x) / 2
            path.cubicTo(mx, y(values[i - 1]), mx, y(values[i]), x, y(values[i]))
        }
        val area = Path(path).apply { lineTo(w, h); lineTo(x0, h); close() }
        c.drawPath(area, fill)
        c.drawPath(path, line)
    }
}
