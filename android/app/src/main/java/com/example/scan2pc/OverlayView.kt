package com.example.scan2pc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors

/** Найденный на фото код. Рамка в долях картинки (0..1). */
data class Det(
    val photo: Int,
    val value: String,
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
)

/** Показывает фото с рамками вокруг кодов; нажатие на рамку выбирает код. */
class OverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var orderOf: (Det) -> Int = { 0 }
    var onTap: ((Det) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var items: List<Det> = emptyList()
    private val dst = RectF()
    private val tmp = RectF()
    private val d = resources.displayMetrics.density

    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
    private val onPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = primary }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onPrimary
        textAlign = Paint.Align.CENTER
        textSize = 15 * d
        isFakeBoldText = true
    }

    fun setContent(bmp: Bitmap?, list: List<Det>) {
        bitmap = bmp
        items = list
        invalidate()
    }

    private fun layoutImage() {
        val bmp = bitmap ?: return
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        dst.set(left, top, left + w, top + h)
    }

    private fun rectOf(det: Det, out: RectF) {
        out.set(
            dst.left + det.l * dst.width(),
            dst.top + det.t * dst.height(),
            dst.left + det.r * dst.width(),
            dst.top + det.b * dst.height(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        layoutImage()
        canvas.drawBitmap(bmp, null, dst, imagePaint)

        for (det in items) {
            rectOf(det, tmp)
            val order = orderOf(det)
            if (order > 0) {
                fill.color = (primary and 0x00FFFFFF) or (0x66 shl 24)
                canvas.drawRoundRect(tmp, 6 * d, 6 * d, fill)
                stroke.color = primary
                stroke.strokeWidth = 4 * d
                canvas.drawRoundRect(tmp, 6 * d, 6 * d, stroke)

                val r = 14 * d
                val cx = tmp.left.coerceIn(dst.left + r, dst.right - r)
                val cy = tmp.top.coerceIn(dst.top + r, dst.bottom - r)
                canvas.drawCircle(cx, cy, r, badge)
                canvas.drawText(order.toString(), cx, cy - (number.ascent() + number.descent()) / 2f, number)
            } else {
                fill.color = 0x33FFFFFF
                canvas.drawRoundRect(tmp, 6 * d, 6 * d, fill)
                stroke.color = 0xAA000000.toInt()
                stroke.strokeWidth = 5 * d
                canvas.drawRoundRect(tmp, 6 * d, 6 * d, stroke)
                stroke.color = Color.WHITE
                stroke.strokeWidth = 3 * d
                canvas.drawRoundRect(tmp, 6 * d, 6 * d, stroke)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                performClick()
                val pad = 12 * d
                var best: Det? = null
                var bestArea = Float.MAX_VALUE
                for (det in items) {
                    rectOf(det, tmp)
                    tmp.inset(-pad, -pad)
                    if (tmp.contains(e.x, e.y)) {
                        val area = tmp.width() * tmp.height()
                        if (area < bestArea) { best = det; bestArea = area }
                    }
                }
                best?.let { onTap?.invoke(it) }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
