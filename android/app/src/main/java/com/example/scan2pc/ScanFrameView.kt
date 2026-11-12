package com.example.scan2pc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * Рамка области сканирования поверх превью камеры. Размер задаётся долями (0.2..1)
 * от кадра; всё вне рамки затемняется, коды вне рамки не отправляются.
 */
class ScanFrameView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var fracW = 0.7f
        set(v) { field = v.coerceIn(0.2f, 1f); invalidate() }
    var fracH = 0.4f
        set(v) { field = v.coerceIn(0.2f, 1f); invalidate() }

    /** Соотношение сторон кадра (ширина/высота, уже с учётом поворота). */
    var imageAspect = 0.75f
        set(v) { if (v > 0f && v != field) { field = v; invalidate() } }

    private val img = RectF()
    private val frame = RectF()
    private val d = resources.displayMetrics.density
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.WHITE)

    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2 * d
    }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accent
        strokeWidth = 5 * d
        strokeCap = Paint.Cap.ROUND
    }

    /** Рамка в долях кадра (0..1), по центру. */
    fun normalizedFrame() = RectF((1 - fracW) / 2, (1 - fracH) / 2, (1 + fracW) / 2, (1 + fracH) / 2)

    override fun onDraw(canvas: Canvas) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        var w = vw
        var h = w / imageAspect
        if (h > vh) { h = vh; w = h * imageAspect }
        img.set((vw - w) / 2, (vh - h) / 2, (vw + w) / 2, (vh + h) / 2)

        val fw = img.width() * fracW
        val fh = img.height() * fracH
        frame.set(img.centerX() - fw / 2, img.centerY() - fh / 2, img.centerX() + fw / 2, img.centerY() + fh / 2)

        canvas.drawRect(img.left, img.top, img.right, frame.top, dim)
        canvas.drawRect(img.left, frame.bottom, img.right, img.bottom, dim)
        canvas.drawRect(img.left, frame.top, frame.left, frame.bottom, dim)
        canvas.drawRect(frame.right, frame.top, img.right, frame.bottom, dim)

        canvas.drawRoundRect(frame, 8 * d, 8 * d, line)

        val len = minOf(24 * d, fw / 3, fh / 3)
        val l = frame.left; val t = frame.top; val r = frame.right; val b = frame.bottom
        canvas.drawLine(l, t, l + len, t, corner); canvas.drawLine(l, t, l, t + len, corner)
        canvas.drawLine(r, t, r - len, t, corner); canvas.drawLine(r, t, r, t + len, corner)
        canvas.drawLine(l, b, l + len, b, corner); canvas.drawLine(l, b, l, b - len, corner)
        canvas.drawLine(r, b, r - len, b, corner); canvas.drawLine(r, b, r, b - len, corner)
    }
}
