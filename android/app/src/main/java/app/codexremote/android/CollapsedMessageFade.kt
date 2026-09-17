package app.codexremote.android

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Visual truncation cue; the TextView retains its full text for selection/accessibility. */
internal class CollapsedMessageFade(private val surface: Int, private val fadeHeight: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        val top = (bounds.bottom - fadeHeight).coerceAtLeast(bounds.top).toFloat()
        paint.shader = LinearGradient(
            0f, top, 0f, bounds.bottom.toFloat(),
            surface and 0x00ffffff, surface, Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds.left.toFloat(), (bounds.bottom - fadeHeight).coerceAtLeast(bounds.top).toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
