package app.codexremote.android

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View

/** The reference quote has a rounded leading rule, with no surrounding box. */
internal class QuoteRule(color: Int, private val width: Float, private val inset: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    override fun draw(canvas: Canvas) {
        val left = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right - width else bounds.left.toFloat()
        canvas.drawRoundRect(left, bounds.top + inset, left + width, bounds.bottom - inset,
            width / 2f, width / 2f, paint)
    }
    override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean { invalidateSelf(); return true }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
