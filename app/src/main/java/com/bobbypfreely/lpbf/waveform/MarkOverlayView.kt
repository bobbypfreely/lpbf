package com.bobbypfreely.lpbf.waveform

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay drawn on top of WaveformView, showing every mark as a thin
 * vertical line. WaveformView itself only ever tracked Ringdroid's original 2-handle
 * selection; this is the piece that visualizes however many cuts you've actually made.
 */
class MarkOverlayView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : View(context, attrs) {

	var waveformView: WaveformView? = null

	var markPositionsMs: List<Int> = emptyList()
		set(value) {
			field = value
			invalidate()
		}

	private val linePaint = Paint().apply {
		color = Color.parseColor("#F5F5F5")
		strokeWidth = 3f
		isAntiAlias = true
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val wf = waveformView ?: return
		if (!wf.hasSoundFile()) return
		val offset = wf.offset

		for (ms in markPositionsMs) {
			val px = wf.millisecsToPixels(ms) - offset
			if (px in 0..width) {
				canvas.drawLine(px.toFloat(), 0f, px.toFloat(), height.toFloat(), linePaint)
			}
		}
	}
}
