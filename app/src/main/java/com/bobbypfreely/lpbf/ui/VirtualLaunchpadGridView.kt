package com.bobbypfreely.lpbf.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * On-screen tappable pad grid -- the fallback input source when no physical Launchpad is
 * connected (or a convenient secondary even when one is). Fires the same PadInputListener
 * contract as MidiControllerBridge, so calling code doesn't need to know which was used.
 */
class VirtualLaunchpadGridView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : View(context, attrs) {

	var gridWidth: Int = 8
	var gridHeight: Int = 8
	var listener: PadInputListener? = null

	/** External callers (e.g. showing which button a segment is assigned to) can light specific pads. */
	private val litPads = HashMap<Pair<Int, Int>, Int>() // (x,y) -> ARGB color
	private val padLabels = HashMap<Pair<Int, Int>, String>() // (x,y) -> label text (e.g. placement order)

	private val cellPaintOff = Paint().apply { color = Color.parseColor("#2A2A3E"); isAntiAlias = true }
	private val cellPaintPressed = Paint().apply { color = Color.parseColor("#00ADB5"); isAntiAlias = true }
	private val labelPaint = Paint().apply {
		color = Color.parseColor("#0F0F1A")
		isAntiAlias = true
		textAlign = Paint.Align.CENTER
		isFakeBoldText = true
	}
	private val gapPx = 6f
	private val cornerRadius = 10f

	private var pressedCell: Pair<Int, Int>? = null

	/** [label] is optional text drawn centered on the pad -- Place uses this to show
	 * each pad's placement order (1, 2, 3...) so a sequence is easy to read at a glance. */
	fun setPadLit(x: Int, y: Int, color: Int, label: String? = null) {
		litPads[x to y] = color
		if (label != null) {
			padLabels[x to y] = label
		} else {
			padLabels.remove(x to y)
		}
		invalidate()
	}

	fun clearPad(x: Int, y: Int) {
		litPads.remove(x to y)
		padLabels.remove(x to y)
		invalidate()
	}

	fun clearAllPads() {
		litPads.clear()
		padLabels.clear()
		invalidate()
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val cellW = width.toFloat() / gridWidth
		val cellH = height.toFloat() / gridHeight
		val litPaint = Paint().apply { isAntiAlias = true }

		for (gx in 0 until gridWidth) {
			for (gy in 0 until gridHeight) {
				val left = gx * cellW + gapPx / 2
				val top = gy * cellH + gapPx / 2
				val right = (gx + 1) * cellW - gapPx / 2
				val bottom = (gy + 1) * cellH - gapPx / 2
				val rect = RectF(left, top, right, bottom)

				val paint = when {
					pressedCell == gx to gy -> cellPaintPressed
					litPads.containsKey(gx to gy) -> litPaint.apply { color = litPads[gx to gy]!! }
					else -> cellPaintOff
				}
				canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

				val label = padLabels[gx to gy]
				if (label != null) {
					labelPaint.textSize = cellH * 0.4f
					val textY = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2
					canvas.drawText(label, rect.centerX(), textY, labelPaint)
				}
			}
		}
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		val cellW = width.toFloat() / gridWidth
		val cellH = height.toFloat() / gridHeight
		val gx = (event.x / cellW).toInt().coerceIn(0, gridWidth - 1)
		val gy = (event.y / cellH).toInt().coerceIn(0, gridHeight - 1)

		when (event.action) {
			MotionEvent.ACTION_DOWN -> {
				pressedCell = gx to gy
				invalidate()
				listener?.onPadDown(gx, gy)
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				val cell = pressedCell
				pressedCell = null
				invalidate()
				if (cell != null) listener?.onPadUp(cell.first, cell.second)
			}
		}
		return true
	}
}
