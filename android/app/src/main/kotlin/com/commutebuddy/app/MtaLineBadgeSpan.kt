package com.commutebuddy.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt

/**
 * A [ReplacementSpan] that draws a filled circle badge with the line letter centered on top,
 * using official MTA trunk-line colors. Yellow-background lines use black text; all others white.
 *
 * @param line      the route ID (e.g. "N", "4")
 * @param sizePx    badge diameter in pixels
 */
class MtaLineBadgeSpan(
    private val line: String,
    private val sizePx: Float
) : ReplacementSpan() {

    private val bgColor = MtaLineColors.lineColor(line)
    private val textColor = if (MtaLineColors.isLightBackground(line)) Color.BLACK else Color.WHITE

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (fm != null) {
            // Make the span occupy exactly sizePx vertically, centred on the text baseline
            val half = (sizePx / 2).roundToInt()
            fm.ascent  = -half
            fm.descent =  half
            fm.top     = fm.ascent
            fm.bottom  = fm.descent
        }
        return sizePx.roundToInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val radius = sizePx / 2f
        val cx = x + radius
        val cy = (top + bottom) / 2f

        // Draw background circle
        paint.isAntiAlias = true
        paint.color = bgColor
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius, paint)

        // Draw the line letter, centered
        paint.color = textColor
        paint.textSize = sizePx * 0.55f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER

        // Vertically center the text within the circle
        val textBounds = android.graphics.Rect()
        paint.getTextBounds(line, 0, line.length, textBounds)
        val textY = cy + textBounds.height() / 2f - textBounds.bottom

        canvas.drawText(line, cx, textY, paint)

        // Restore paint state modified here
        paint.textAlign = Paint.Align.LEFT
    }
}
