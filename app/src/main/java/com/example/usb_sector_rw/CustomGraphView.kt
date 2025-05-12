package com.example.usb_sector_rw

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.appcompat.app.AppCompatDelegate
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

class CustomGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val points = mutableListOf<Pair<Long, Float>>() // X = timeMillis, Y = value

    private val paint = Paint().apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val axisPaint = Paint().apply {
        strokeWidth = 2f
        textSize = 32f
        isAntiAlias = true
    }

    private var minX = Long.MAX_VALUE
    private var maxX = Long.MIN_VALUE
    private var minY = Float.MAX_VALUE
    private var maxY = Float.MIN_VALUE

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        updateColorsBasedOnTheme()
    }

    private fun updateColorsBasedOnTheme() {
        val isDarkTheme = when (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) {
            android.content.res.Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }

        val lineColor = if (isDarkTheme) Color.CYAN else Color.BLUE
        val axisColor = if (isDarkTheme) Color.WHITE else Color.BLACK

        paint.color = lineColor
        axisPaint.color = axisColor
    }

    fun addPoint(value: Float) {
        val time = System.currentTimeMillis()
        points.add(Pair(time, value))

        minX = minOf(minX, time)
        maxX = maxOf(maxX, time)
        minY = minOf(minY, value)
        maxY = maxOf(maxY, value)

        if (minX == maxX) maxX += 1000
        if (minY == maxY) maxY += 1f

        invalidate()
    }

    fun clearPoints() {
        points.clear()
        minX = Long.MAX_VALUE
        maxX = Long.MIN_VALUE
        minY = Float.MAX_VALUE
        maxY = Float.MIN_VALUE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size < 2) return

        val internalPaddingX = width * 0.07f
        val internalPaddingY = height * 0.07f
        val graphWidth = width - 2 * internalPaddingX
        val graphHeight = height - 2 * internalPaddingY

        val scaleX = graphWidth / (maxX - minX).toFloat()
        val scaleY = graphHeight / (maxY - minY)

        val step = max(1, points.size / (width / 5))
        for (i in step until points.size step step) {
            val (prevX, prevY) = points[i - step]
            val (currX, currY) = points[i]

            val x1 = internalPaddingX + (prevX - minX) * scaleX
            val y1 = height - internalPaddingY - (prevY - minY) * scaleY
            val x2 = internalPaddingX + (currX - minX) * scaleX
            val y2 = height - internalPaddingY - (currY - minY) * scaleY

            canvas.drawLine(x1, y1, x2, y2, paint)
        }

        // Подпись осей
        val axisLabel = "Время"
        val axisLabelWidth = axisPaint.measureText(axisLabel)
        canvas.drawText(
            axisLabel,
            width - internalPaddingX - axisLabelWidth,
            height.toFloat() - 10f,
            axisPaint
        )

        canvas.save()
        canvas.rotate(-90f, 20f, height / 2f)
        canvas.drawText("Значение", 20f, height / 2f, axisPaint)
        canvas.restore()

        // Подписи времени
        val labelStep = max(1, points.size / 5)
        for (i in points.indices step labelStep) {
            val (time, _) = points[i]
            val x = internalPaddingX + (time - minX) * scaleX
            val label = timeFormat.format(Date(time))

            val textWidth = axisPaint.measureText(label)
            canvas.save()
            canvas.rotate(-75f, x, height - internalPaddingY + 10f)
            canvas.drawText(label, x - textWidth, height - internalPaddingY + 10f, axisPaint)
            canvas.restore()
        }

        // Подписи по оси Y
        val yStep = (maxY - minY) / 4
        for (i in 0..4) {
            val value = minY + i * yStep
            val y = height - internalPaddingY - (value - minY) * scaleY
            canvas.drawText(String.format("%.2f", value), 10f, y + 10f, axisPaint)
        }
    }
}