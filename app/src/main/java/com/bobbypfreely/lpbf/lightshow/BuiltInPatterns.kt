package com.bobbypfreely.lpbf.lightshow

/**
 * A handful of starter patterns, authored against a gridWidth x gridHeight grid.
 * These are the "cascade" and "number 5" examples used to validate the compiler.
 * Real pattern library growth (more digits, letters, wipes, etc.) slots in here.
 */
object BuiltInPatterns {

	/** Matrix-style column cascade: columns light top-to-bottom in a staggered wave. */
	fun cascade(gridWidth: Int = 8, gridHeight: Int = 8): Pattern {
		val keyframes = ArrayList<Keyframe>()
		for (col in 0 until gridWidth) {
			val colStart = (col.toFloat() / gridWidth) * 0.3f
			for (row in 0 until gridHeight) {
				val onT = colStart + (row.toFloat() / gridHeight) * 0.6f
				val offT = (onT + 0.15f).coerceAtMost(1f)
				keyframes.add(Keyframe(t = onT, x = col, y = row, on = true, velocity = 100))
				keyframes.add(Keyframe(t = offT, x = col, y = row, on = false))
			}
		}
		return Pattern("cascade", keyframes)
	}

	/** Static "5" glyph, held for most of the clip then cleared near the end. */
	fun number5(): Pattern {
		val glyph = listOf(
			"111",
			"100",
			"111",
			"001",
			"111",
		)
		val keyframes = ArrayList<Keyframe>()
		for ((row, line) in glyph.withIndex()) {
			for ((col, ch) in line.withIndex()) {
				if (ch == '1') {
					keyframes.add(Keyframe(t = 0.02f, x = col, y = row, on = true, velocity = 110))
					keyframes.add(Keyframe(t = 0.95f, x = col, y = row, on = false))
				}
			}
		}
		return Pattern("number5", keyframes)
	}
}
