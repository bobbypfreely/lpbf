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
 * Visual:
 *  - Top function keys: full cell size, circular
 *  - Side chain keys: smaller circular pills
 *  - Main pads: rounded corners so black bezel forms a diamond where four pads meet
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

	private val bezelPaint = Paint().apply { color = Color.parseColor("#0A0A10"); isAntiAlias = true }
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

	/** Gap between main pads — black bezel shows through as diamonds at 4-corners. */
	private val gapPx = 7f
	/** Pad corner radius as fraction of cell (higher = more rounded → clearer diamond). */
	private val padCornerFraction = 0.28f
	/** Chain button diameter as fraction of cell (smaller than top). */
	private val chainSizeFraction = 0.55f
	/** Top function diameter as fraction of cell (near full, no shrink). */
	private val topSizeFraction = 0.88f

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
		canvas.drawRoundRect(bezel, 14f, 14f, bezelPaint)

		// Top function keys — circular, full-ish size (no shrink)
		val topDiameter = cell * topSizeFraction
		val topRadius = topDiameter / 2f
		for (f in 0 until 8) {
			val cx = mainLeft + f * cell + cell / 2f
			val cy = originY + topStrip / 2f
			val paint = if (pressedFunction == f) topPaintPressed else topPaintOff
			canvas.drawCircle(cx, cy, topRadius, paint)
			chainLabelPaint.color = if (pressedFunction == f) Color.parseColor("#0F0F1A") else Color.parseColor("#CCCCDD")
			chainLabelPaint.textSize = cell * 0.28f
			val ty = cy - (chainLabelPaint.descent() + chainLabelPaint.ascent()) / 2
			canvas.drawText((f + 1).toString(), cx, ty, chainLabelPaint)
		}

		// Main 8x8 — rounded pads; gaps + radius leave black diamonds between four pads
		val padCorner = cell * padCornerFraction
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
				canvas.drawRoundRect(rect, padCorner, padCorner, paint)

				highlightedPads[lx to ly]?.let { hc ->
					highlightPaint.color = hc
					val inset = highlightPaint.strokeWidth / 2
					canvas.drawRoundRect(
						RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset),
						padCorner, padCorner, highlightPaint
					)
				}

				padLabels[lx to ly]?.let { label ->
					labelPaint.textSize = cell * 0.32f
					val textY = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2
					canvas.drawText(label, rect.centerX(), textY, labelPaint)
				}
			}
		}

		// Side chain buttons — smaller circles, centered in side cells
		val chainDiameter = cell * chainSizeFraction
		val chainRadius = chainDiameter / 2f
		for (c in 0 until 8) {
			val cx = mainLeft + 8 * cell + cell / 2f
			val cy = mainTop + c * cell + cell / 2f
			val on = c == activeChain || pressedChain == c
			val paint = if (on) chainPaintOn else chainPaintOff
			canvas.drawCircle(cx, cy, chainRadius, paint)
			chainLabelPaint.color = if (on) Color.parseColor("#0F0F1A") else Color.parseColor("#CCCCDD")
			chainLabelPaint.textSize = cell * 0.26f
			val ty = cy - (chainLabelPaint.descent() + chainLabelPaint.ascent()) / 2
			canvas.drawText((c + 1).toString(), cx, ty, chainLabelPaint)
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
