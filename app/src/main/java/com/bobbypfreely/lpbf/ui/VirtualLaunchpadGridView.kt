package com.bobbypfreely.lpbf.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * On-screen Launchpad: 8 top function keys + 8x8 main grid + 8 side chain buttons.
 * Matches hardware layout (MK2 / X / Mini style): top row across columns, right column for chains.
 *
 * Logical pad coords stay Unipad convention: x = row (0 top), y = column (0 left).
 * Chain buttons call [PadInputListener.onChainTouch]; top row calls [onFunctionKeyTouch].
 *
 * Forces a square footprint in [onMeasure] so drawers can shrink the area without
 * stretching pads into rectangles.
 */
class VirtualLaunchpadGridView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : View(context, attrs) {

	var gridWidth: Int = 8
	var gridHeight: Int = 8
	var listener: PadInputListener? = null

	private val litPads = HashMap<Pair<Int, Int>, Int>()
	private val padLabels = HashMap<Pair<Int, Int>, String>()
	private val highlightedPads = HashMap<Pair<Int, Int>, Int>()

	private var activeChain: Int = 0
	private var pressedPad: Pair<Int, Int>? = null
	private var pressedChain: Int? = null
	private var pressedFunction: Int? = null

	private val bezelPaint = Paint().apply { color = Color.parseColor("#12121A"); isAntiAlias = true }
	private val cellPaintOff = Paint().apply { color = Color.parseColor("#2A2A3E"); isAntiAlias = true }
	private val cellPaintPressed = Paint().apply { color = Color.parseColor("#00ADB5"); isAntiAlias = true }
	private val chainPaintOff = Paint().apply { color = Color.parseColor("#1E1E30"); isAntiAlias = true }
	private val chainPaintOn = Paint().apply { color = Color.parseColor("#00ADB5"); isAntiAlias = true }
	private val topPaintOff = Paint().apply { color = Color.parseColor("#252538"); isAntiAlias = true }
	private val topPaintPressed = Paint().apply { color = Color.parseColor("#7B68EE"); isAntiAlias = true }
	private val labelPaint = Paint().apply {
		color = Color.parseColor("#0F0F1A")
		isAntiAlias = true
		textAlign = Paint.Align.CENTER
		isFakeBoldText = true
	}
	private val chainLabelPaint = Paint().apply {
		color = Color.parseColor("#CCCCDD")
		isAntiAlias = true
		textAlign = Paint.Align.CENTER
		isFakeBoldText = true
	}
	private val highlightPaint = Paint().apply {
		isAntiAlias = true
		style = Paint.Style.STROKE
		strokeWidth = 4f
	}

	private val gapPx = 5f
	private val cornerRadius = 8f

	private var originX = 0f
	private var originY = 0f
	private var cell = 0f
	private var topStrip = 0f
	private var sideStrip = 0f
	private var mainLeft = 0f
	private var mainTop = 0f

	fun setActiveChain(chain: Int) {
		activeChain = chain.coerceIn(0, 7)
		invalidate()
	}

	fun setPadLit(x: Int, y: Int, color: Int, label: String? = null) {
		litPads[x to y] = color
		if (label != null) padLabels[x to y] = label else padLabels.remove(x to y)
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

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val w = MeasureSpec.getSize(widthMeasureSpec)
		val h = MeasureSpec.getSize(heightMeasureSpec)
		val size = when {
			MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED -> h
			MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED -> w
			else -> min(w, h)
		}.coerceAtLeast(suggestedMinimumWidth)
		setMeasuredDimension(size, size)
	}

	private fun layoutGeometry() {
		val size = min(width, height).toFloat()
		originX = (width - size) / 2f
		originY = (height - size) / 2f
		cell = size / 9f
		topStrip = cell
		sideStrip = cell
		mainLeft = originX
		mainTop = originY + topStrip
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		if (width <= 0 || height <= 0) return
		layoutGeometry()

		val bezel = RectF(originX, originY, originX + cell * 9f, originY + cell * 9f)
		canvas.drawRoundRect(bezel, 12f, 12f, bezelPaint)

		for (f in 0 until 8) {
			val left = mainLeft + f * cell + gapPx / 2
			val top = originY + gapPx / 2
			val right = mainLeft + (f + 1) * cell - gapPx / 2
			val bottom = originY + topStrip - gapPx / 2
			val paint = if (pressedFunction == f) topPaintPressed else topPaintOff
			canvas.drawRoundRect(RectF(left, top, right, bottom), cornerRadius, cornerRadius, paint)
			chainLabelPaint.textSize = cell * 0.28f
			val ty = (top + bottom) / 2f - (chainLabelPaint.descent() + chainLabelPaint.ascent()) / 2
			canvas.drawText((f + 1).toString(), (left + right) / 2f, ty, chainLabelPaint)
		}

		val litPaint = Paint().apply { isAntiAlias = true }
		for (lx in 0 until gridHeight) {
			for (ly in 0 until gridWidth) {
				val screenCol = ly
				val screenRow = lx
				val left = mainLeft + screenCol * cell + gapPx / 2
				val top = mainTop + screenRow * cell + gapPx / 2
				val right = mainLeft + (screenCol + 1) * cell - gapPx / 2
				val bottom = mainTop + (screenRow + 1) * cell - gapPx / 2
				val rect = RectF(left, top, right, bottom)

				val paint = when {
					pressedPad == lx to ly -> cellPaintPressed
					litPads.containsKey(lx to ly) -> litPaint.apply { color = litPads[lx to ly]!! }
					else -> cellPaintOff
				}
				canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

				highlightedPads[lx to ly]?.let { hc ->
					highlightPaint.color = hc
					val inset = highlightPaint.strokeWidth / 2
					canvas.drawRoundRect(
						RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset),
						cornerRadius, cornerRadius, highlightPaint
					)
				}

				padLabels[lx to ly]?.let { label ->
					labelPaint.textSize = cell * 0.35f
					val textY = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2
					canvas.drawText(label, rect.centerX(), textY, labelPaint)
				}
			}
		}

		for (c in 0 until 8) {
			val left = mainLeft + 8 * cell + gapPx / 2
			val top = mainTop + c * cell + gapPx / 2
			val right = originX + 9 * cell - gapPx / 2
			val bottom = mainTop + (c + 1) * cell - gapPx / 2
			val on = c == activeChain || pressedChain == c
			val paint = if (on) chainPaintOn else chainPaintOff
			canvas.drawRoundRect(RectF(left, top, right, bottom), cornerRadius, cornerRadius, paint)
			chainLabelPaint.color = if (on) Color.parseColor("#0F0F1A") else Color.parseColor("#CCCCDD")
			chainLabelPaint.textSize = cell * 0.32f
			val ty = (top + bottom) / 2f - (chainLabelPaint.descent() + chainLabelPaint.ascent()) / 2
			canvas.drawText((c + 1).toString(), (left + right) / 2f, ty, chainLabelPaint)
		}
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		if (width <= 0 || height <= 0) return false
		layoutGeometry()
		val x = event.x
		val y = event.y

		when (event.action) {
			MotionEvent.ACTION_DOWN -> {
				if (y >= originY && y < originY + topStrip && x >= mainLeft && x < mainLeft + 8 * cell) {
					val f = ((x - mainLeft) / cell).toInt().coerceIn(0, 7)
					pressedFunction = f
					invalidate()
					listener?.onFunctionKeyTouch(f, true)
					return true
				}
				if (x >= mainLeft + 8 * cell && x < originX + 9 * cell && y >= mainTop && y < mainTop + 8 * cell) {
					val c = ((y - mainTop) / cell).toInt().coerceIn(0, 7)
					pressedChain = c
					activeChain = c
					invalidate()
					listener?.onChainTouch(c, true)
					return true
				}
				if (x >= mainLeft && x < mainLeft + 8 * cell && y >= mainTop && y < mainTop + 8 * cell) {
					val screenCol = ((x - mainLeft) / cell).toInt().coerceIn(0, 7)
					val screenRow = ((y - mainTop) / cell).toInt().coerceIn(0, 7)
					val lx = screenRow
					val ly = screenCol
					pressedPad = lx to ly
					invalidate()
					listener?.onPadDown(lx, ly)
					return true
				}
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				pressedFunction?.let { listener?.onFunctionKeyTouch(it, false); pressedFunction = null }
				pressedChain?.let { listener?.onChainTouch(it, false); pressedChain = null }
				pressedPad?.let { listener?.onPadUp(it.first, it.second); pressedPad = null }
				invalidate()
				return true
			}
		}
		return true
	}
}
