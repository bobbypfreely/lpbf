package com.bobbypfreely.lpbf.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * On-screen tappable Launchpad.
 *
 * This is intentionally only a visual/input layer. UniPack data, pad coordinates and the
 * PadInputListener contract remain untouched so the virtual pad behaves exactly like the
 * physical Launchpad and existing placement/lightshow code.
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

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#080812")
        isAntiAlias = true
    }
    private val cellPaintOff = Paint().apply {
        color = Color.parseColor("#191A2A")
        isAntiAlias = true
    }
    private val litPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val highlightPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val gapPx = 7f
    private val cornerRadius = 12f
    private var pressedCell: Pair<Int, Int>? = null
    private var animationStartMs = 0L

    /** [label] is optional text drawn centered on the pad. */
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

    /** Outlines (x,y) without filling it. */
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

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val cellW = width.toFloat() / gridWidth
        val cellH = height.toFloat() / gridHeight
        val pulse = if (animationStartMs == 0L) {
            1f
        } else {
            val elapsed = SystemClock.uptimeMillis() - animationStartMs
            0.5f + 0.5f * ((elapsed % 1100L) / 1100f)
        }

        // Logical (x,y) follows the real hardware/file convention: x is ROW and y is
        // COLUMN. Screen coordinates are therefore deliberately transposed here.
        for (lx in 0 until gridHeight) {
            for (ly in 0 until gridWidth) {
                val screenCol = ly
                val screenRow = lx

                val left = screenCol * cellW + gapPx / 2
                val top = screenRow * cellH + gapPx / 2
                val right = (screenCol + 1) * cellW - gapPx / 2
                val bottom = (screenRow + 1) * cellH - gapPx / 2
                val baseRect = RectF(left, top, right, bottom)
                val key = lx to ly

                val isPressed = pressedCell == key
                val isLit = litPads.containsKey(key)
                val highlightColor = highlightedPads[key]

                val scale = if (isPressed) 0.91f else 1f
                val rect = scaleRect(baseRect, scale)

                // Soft halo behind active pads. It is deliberately drawn outside the
                // pad so LEDs feel emissive without changing the stored UniPack data.
                if (isLit) {
                    val color = litPads[key] ?: Color.WHITE
                    val alpha = (45 + 45 * pulse).toInt().coerceIn(0, 100)
                    glowPaint.shader = RadialGradient(
                        rect.centerX(),
                        rect.centerY(),
                        max(rect.width(), rect.height()) * 0.78f,
                        withAlpha(color, alpha),
                        Color.TRANSPARENT,
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(
                        rect.centerX(),
                        rect.centerY(),
                        max(rect.width(), rect.height()) * 0.78f,
                        glowPaint
                    )
                    glowPaint.shader = null
                }

                if (isPressed) {
                    litPaint.color = Color.WHITE
                    litPaint.alpha = 235
                    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, litPaint)

                    // Bright inner core gives the press a physical LED "snap".
                    litPaint.color = Color.parseColor("#DDFDFF")
                    litPaint.alpha = 255
                    canvas.drawRoundRect(
                        inset(rect, rect.width() * 0.055f),
                        cornerRadius * 0.78f,
                        cornerRadius * 0.78f,
                        litPaint
                    )
                } else if (isLit) {
                    val color = litPads[key] ?: Color.WHITE
                    litPaint.shader = RadialGradient(
                        rect.centerX(),
                        rect.centerY(),
                        max(rect.width(), rect.height()) * 0.72f,
                        Color.WHITE,
                        color,
                        Shader.TileMode.CLAMP
                    )
                    litPaint.alpha = 255
                    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, litPaint)
                    litPaint.shader = null
                } else {
                    cellPaintOff.alpha = 255
                    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, cellPaintOff)
                }

                if (highlightColor != null) {
                    highlightPaint.color = withAlpha(highlightColor, 220)
                    highlightPaint.strokeWidth = max(2f, min(cellW, cellH) * 0.035f)
                    val inset = highlightPaint.strokeWidth / 2
                    canvas.drawRoundRect(
                        inset(rect, inset),
                        cornerRadius,
                        cornerRadius,
                        highlightPaint
                    )
                }

                val label = padLabels[key]
                if (label != null) {
                    labelPaint.textSize = min(cellW, cellH) * 0.30f
                    labelPaint.setShadowLayer(5f, 0f, 1f, Color.BLACK)
                    val textY = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2
                    canvas.drawText(label, rect.centerX(), textY, labelPaint)
                    labelPaint.clearShadowLayer()
                }
            }
        }

        // Keep the LED atmosphere alive while pads are lit, without creating a timer or
        // changing any application state.
        if (litPads.isNotEmpty() || pressedCell != null) {
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gridWidth <= 0 || gridHeight <= 0 || width <= 0 || height <= 0) return true

        val cellW = width.toFloat() / gridWidth
        val cellH = height.toFloat() / gridHeight
        val screenCol = (event.x / cellW).toInt().coerceIn(0, gridWidth - 1)
        val screenRow = (event.y / cellH).toInt().coerceIn(0, gridHeight - 1)

        // Swap back to logical (x=row, y=col). Do not simplify this mapping:
        // it matches the physical Launchpad and UniPack convention.
        val lx = screenRow
        val ly = screenCol

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedCell = lx to ly
                animationStartMs = SystemClock.uptimeMillis()
                invalidate()
                listener?.onPadDown(lx, ly)
                performClick()
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

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun scaleRect(rect: RectF, scale: Float): RectF {
        if (scale == 1f) return RectF(rect)
        val dx = rect.width() * (1f - scale) / 2f
        val dy = rect.height() * (1f - scale) / 2f
        return RectF(rect.left + dx, rect.top + dy, rect.right - dx, rect.bottom - dy)
    }

    private fun inset(rect: RectF, amount: Float): RectF =
        RectF(rect.left + amount, rect.top + amount, rect.right - amount, rect.bottom - amount)

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
