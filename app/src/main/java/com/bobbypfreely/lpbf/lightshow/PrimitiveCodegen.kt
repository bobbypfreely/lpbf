package com.bobbypfreely.lpbf.lightshow

import kotlin.math.hypot

/**
 * Turns a [PhraseParser.PrimitiveStep] into a duration-agnostic Pattern (t in 0..1).
 */
object PrimitiveCodegen {

	fun generate(
		step: PhraseParser.PrimitiveStep,
		gridWidth: Int = 8,
		gridHeight: Int = 8,
	): Pattern {
		val w = gridWidth.coerceAtLeast(1)
		val h = gridHeight.coerceAtLeast(1)
		return when (step.name) {
			"bloom" -> bloom(w, h, step.params)
			"cascade" -> cascade(w, h)
			"row_wipe" -> rowWipe(w, h)
			"col_wipe" -> colWipe(w, h)
			"fill" -> fill(w, h)
			"flash" -> flash(w, h)
			"whip_off" -> whipOff(w, h)
			"wheel" -> wheel(w, h)
			"sparkle" -> sparkle(w, h)
			else -> flash(w, h)
		}
	}

	// ── Primitives ──────────────────────────────────────────────────────────

	private fun bloom(w: Int, h: Int, params: Map<String, String>): Pattern {
		val cx = (w - 1) / 2f
		val cy = (h - 1) / 2f
		val maxR = hypot(cx.toDouble(), cy.toDouble()).toFloat().coerceAtLeast(1f)
		val rings = 4
		val kf = ArrayList<Keyframe>()
		for (ring in 0..rings) {
			val tOn = ring.toFloat() / (rings + 1) * 0.7f
			val tOff = (tOn + 0.22f).coerceAtMost(0.95f)
			val v = (120 - ring * 12).coerceIn(40, 127)
			for (x in 0 until w) for (y in 0 until h) {
				val d = hypot(x - cx.toDouble(), y - cy.toDouble()).toFloat()
				val target = ring * maxR / rings
				if (d >= target - 0.7f && d <= target + 0.7f) {
					kf += Keyframe(tOn, x, y, true, v)
					kf += Keyframe(tOff, x, y, false)
				}
			}
		}
		return Pattern("bloom", kf)
	}

	private fun cascade(w: Int, h: Int): Pattern {
		val kf = ArrayList<Keyframe>()
		for (row in 0 until h) {
			val tOn = row.toFloat() / h * 0.75f
			val tOff = (tOn + 0.18f).coerceAtMost(0.95f)
			val v = (115 - row * 6).coerceIn(40, 127)
			for (col in 0 until w) {
				kf += Keyframe(tOn, col, row, true, v)
				kf += Keyframe(tOff, col, row, false)
			}
		}
		return Pattern("cascade", kf)
	}

	private fun rowWipe(w: Int, h: Int): Pattern {
		val kf = ArrayList<Keyframe>()
		for (col in 0 until w) {
			val tOn = col.toFloat() / w * 0.7f
			val tOff = (tOn + 0.2f).coerceAtMost(0.95f)
			for (row in 0 until h) {
				kf += Keyframe(tOn, col, row, true, 110)
				kf += Keyframe(tOff, col, row, false)
			}
		}
		return Pattern("row_wipe", kf)
	}

	private fun colWipe(w: Int, h: Int): Pattern {
		val kf = ArrayList<Keyframe>()
		for (row in 0 until h) {
			val tOn = row.toFloat() / h * 0.7f
			val tOff = (tOn + 0.2f).coerceAtMost(0.95f)
			for (col in 0 until w) {
				kf += Keyframe(tOn, col, row, true, 110)
				kf += Keyframe(tOff, col, row, false)
			}
		}
		return Pattern("col_wipe", kf)
	}

	private fun fill(w: Int, h: Int): Pattern {
		val kf = ArrayList<Keyframe>()
		for (x in 0 until w) for (y in 0 until h) {
			kf += Keyframe(0.05f, x, y, true, 100)
			kf += Keyframe(0.9f, x, y, false)
		}
		return Pattern("fill", kf)
	}

	private fun flash(w: Int, h: Int): Pattern {
		val kf = ArrayList<Keyframe>()
		for (x in 0 until w) for (y in 0 until h) {
			kf += Keyframe(0.02f, x, y, true, 120)
			kf += Keyframe(0.45f, x, y, false)
		}
		return Pattern("flash", kf)
	}

	/** Short hold then hard all-off — the “whip” ending. */
	private fun whipOff(w: Int, h: Int): Pattern {
		val kf = ArrayList<Keyframe>()
		for (x in 0 until w) for (y in 0 until h) {
			kf += Keyframe(0.0f, x, y, true, 127)
		}
		for (x in 0 until w) for (y in 0 until h) {
			kf += Keyframe(0.12f, x, y, false)
		}
		return Pattern("whip_off", kf)
	}

	/** Simple 4-step rotating ring (wheel). */
	private fun wheel(w: Int, h: Int): Pattern {
		val cx = (w - 1) / 2
		val cy = (h - 1) / 2
		val kf = ArrayList<Keyframe>()
		val spokes = listOf(
			listOf(cx to 0, cx to 1),
			listOf(w - 1 to cy, w - 2 to cy),
			listOf(cx to h - 1, cx to h - 2),
			listOf(0 to cy, 1 to cy),
		)
		spokes.forEachIndexed { i, pts ->
			val tOn = i * 0.2f
			val tOff = tOn + 0.18f
			for ((x, y) in pts) {
				if (x in 0 until w && y in 0 until h) {
					kf += Keyframe(tOn, x, y, true, 115)
					kf += Keyframe(tOff.coerceAtMost(0.95f), x, y, false)
				}
			}
		}
		return Pattern("wheel", kf)
	}

	private fun sparkle(w: Int, h: Int): Pattern {
		val kf = ArrayList<Keyframe>()
		var seed = 17
		for (i in 0 until 24) {
			seed = (seed * 1103515245 + 12345) and 0x7fffffff
			val x = seed % w
			seed = (seed * 1103515245 + 12345) and 0x7fffffff
			val y = seed % h
			val tOn = (i / 24f) * 0.8f
			kf += Keyframe(tOn, x, y, true, 100)
			kf += Keyframe((tOn + 0.08f).coerceAtMost(0.95f), x, y, false)
		}
		return Pattern("sparkle", kf)
	}
}