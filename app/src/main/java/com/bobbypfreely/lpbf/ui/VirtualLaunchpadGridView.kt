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

	/** Border-only outline, distinct from a filled lit pad -- Lightshow uses this to mark
	 * "has audio mapped, not currently selected" without implying the pad is actually on. */
	private val highlightedPads = HashMap<Pair<Int, Int>, Int>() // (x,y) -> ARGB stroke color

	private val cellPaintOff = Paint().apply { color = Color.parseColor("#2A2A3E"); isAntiAlias = true }
	private val cellPaintPressed = Paint().apply { color = Color.parseColor("#00ADB5"); isAntiAlias = true }
	private val labelPaint = Paint().apply {
		color = Color.parseColor("#0F0F1A")
		isAntiAlias = true
		textAlign = Paint.Align.CENTER
		isFakeBoldText = true
	}
	private val highlightPaint = Paint().apply {
		isAntiAlias = true
		style = Paint.Style.STROKE
		strokeWidth = 5f
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

	/** Outlines (x,y) in [color] without filling it -- used for "mapped but not selected" pads. */
	fun setPadHighlighted(x: Int, y: Int, color: Int) {
		highlightedPads[x to y] = color
		invalidate()
	}

	fun clearHighlight(x: Int, y: Int) {
		highlightedPads.remove(x to y)
		invalidate()
	}

	fun clearAllHighlights() {
		highlightedPads.clear()
		invalidate()
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val cellW = width.toFloat() / gridWidth
		val cellH = height.toFloat() / gridHeight
		val litPaint = Paint().apply { isAntiAlias = true }

		// Logical (x,y) follows the real hardware/file convention (confirmed against
		// the MIDI drivers and real Unipacks): x is the ROW, top(0) to bottom; y is
		// the COLUMN, left(0) to right. That's transposed from normal screen (col,row)
		// thinking, so this view swaps x/y when converting to/from screen position.
		// Do not "simplify" this away -- removing it is what previously made pads
		// render mirrored across the diagonal versus the real hardware and real packs.
		for (lx in 0 until gridHeight) {
			for (ly in 0 until gridWidth) {
				val screenCol = ly
				val screenRow = lx
				val left = screenCol * cellW + gapPx / 2
				val top = screenRow * cellH + gapPx / 2
				val right = (screenCol + 1) * cellW - gapPx / 2
				val bottom = (screenRow + 1) * cellH - gapPx / 2
				val rect = RectF(left, top, right, bottom)

				val paint = when {
					pressedCell == lx to ly -> cellPaintPressed
					litPads.containsKey(lx to ly) -> litPaint.apply { color = litPads[lx to ly]!! }
					else -> cellPaintOff
				}
				canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

				val highlightColor = highlightedPads[lx to ly]
				if (highlightColor != null) {
					highlightPaint.color = highlightColor
					val inset = highlightPaint.strokeWidth / 2
					val hRect = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
					canvas.drawRoundRect(hRect, cornerRadius, cornerRadius, highlightPaint)
				}

				val label = padLabels[lx to ly]
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
		val screenCol = (event.x / cellW).toInt().coerceIn(0, gridWidth - 1)
		val screenRow = (event.y / cellH).toInt().coerceIn(0, gridHeight - 1)
		// Swap back to logical (x=row, y=col) -- see onDraw's note above.
		val lx = screenRow
		val ly = screenCol

		when (event.action) {
			MotionEvent.ACTION_DOWN -> {
				pressedCell = lx to ly
				invalidate()
				listener?.onPadDown(lx, ly)
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
