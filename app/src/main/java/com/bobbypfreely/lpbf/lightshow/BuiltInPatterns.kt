package com.bobbypfreely.lpbf.lightshow

/**
 * Premade lightshow library for the composer.
 * Patterns are duration-agnostic (t in 0..1); PatternCompiler scales to the cut.
 *
 * Categories: basics, motion, bursts, glyphs (0–9, A–Z, suits, symbols).
 */
object BuiltInPatterns {

	fun all(gridWidth: Int = 8, gridHeight: Int = 8): List<Pattern> {
		val w = gridWidth.coerceAtLeast(1)
		val h = gridHeight.coerceAtLeast(1)
		val list = ArrayList<Pattern>(80)
		// Basics
		list += flash(w, h)
		list += solid(w, h)
		list += pulse(w, h)
		list += corners(w, h)
		list += cross(w, h)
		list += checker(w, h)
		list += ring(w, h)
		list += sparkle(w, h)
		// Motion
		list += cascade(w, h)
		list += waterfall(w, h)
		list += rowWipe(w, h)
		list += colWipe(w, h)
		list += lineSweep(w, h)
		list += fadingLine(w, h)
		list += shootingPad(w, h)
		list += flowers(w, h)
		// Bursts
		list += explosion(w, h)
		list += firework(w, h)
		// Glyphs: digits
		for (d in 0..9) list += glyphPattern(d.toString(), DIGITS[d])
		// Glyphs: letters
		for (c in 'A'..'Z') {
			val g = LETTERS[c] ?: continue
			list += glyphPattern(c.toString(), g)
		}
		// Suits + symbols
		list += glyphPattern("heart", SUIT_HEART)
		list += glyphPattern("diamond", SUIT_DIAMOND)
		list += glyphPattern("club", SUIT_CLUB)
		list += glyphPattern("spade", SUIT_SPADE)
		list += glyphPattern("money", SYMBOL_MONEY)
		list += glyphPattern("smiley", SYMBOL_SMILEY)
		list += glyphPattern("arrow_up", SYMBOL_ARROW_UP)
		list += glyphPattern("arrow_down", SYMBOL_ARROW_DOWN)
		list += glyphPattern("arrow_left", SYMBOL_ARROW_LEFT)
		list += glyphPattern("arrow_right", SYMBOL_ARROW_RIGHT)
		list += glyphPattern("star", SYMBOL_STAR)
		return list
	}

	fun byName(name: String, gridWidth: Int = 8, gridHeight: Int = 8): Pattern? =
		all(gridWidth, gridHeight).firstOrNull { it.name.equals(name, ignoreCase = true) }

	// ── Basics ──────────────────────────────────────────────────────────────

	fun flash(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 120): Pattern {
		val kf = ArrayList<Keyframe>()
		for (x in 0 until gridWidth) for (y in 0 until gridHeight) {
			kf.add(Keyframe(0.02f, x, y, true, velocity))
			kf.add(Keyframe(0.55f, x, y, false))
		}
		return Pattern("flash", kf)
	}

	fun solid(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 90): Pattern {
		val kf = ArrayList<Keyframe>()
		for (x in 0 until gridWidth) for (y in 0 until gridHeight) {
			kf.add(Keyframe(0.0f, x, y, true, velocity))
			kf.add(Keyframe(0.98f, x, y, false))
		}
		return Pattern("solid", kf)
	}

	fun pulse(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 110): Pattern {
		val kf = ArrayList<Keyframe>()
		for (x in 0 until gridWidth) for (y in 0 until gridHeight) {
			kf.add(Keyframe(0.05f, x, y, true, velocity))
			kf.add(Keyframe(0.28f, x, y, false))
			kf.add(Keyframe(0.45f, x, y, true, velocity))
			kf.add(Keyframe(0.70f, x, y, false))
		}
		return Pattern("pulse", kf)
	}

	fun corners(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 120): Pattern {
		val pts = listOf(
			0 to 0, gridWidth - 1 to 0,
			0 to gridHeight - 1, gridWidth - 1 to gridHeight - 1,
		)
		val kf = ArrayList<Keyframe>()
		for ((x, y) in pts) {
			kf.add(Keyframe(0.05f, x, y, true, velocity))
			kf.add(Keyframe(0.9f, x, y, false))
		}
		return Pattern("corners", kf)
	}

	fun cross(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 110): Pattern {
		val midX = gridWidth / 2
		val midY = gridHeight / 2
		val kf = ArrayList<Keyframe>()
		for (x in 0 until gridWidth) {
			kf.add(Keyframe(0.08f, x, midY, true, velocity))
			kf.add(Keyframe(0.85f, x, midY, false))
		}
		for (y in 0 until gridHeight) {
			if (y == midY) continue
			kf.add(Keyframe(0.08f, midX, y, true, velocity))
			kf.add(Keyframe(0.85f, midX, y, false))
		}
		return Pattern("cross", kf)
	}

	fun checker(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 100): Pattern {
		val kf = ArrayList<Keyframe>()
		for (x in 0 until gridWidth) for (y in 0 until gridHeight) {
			if ((x + y) % 2 == 0) {
				kf.add(Keyframe(0.05f, x, y, true, velocity))
				kf.add(Keyframe(0.45f, x, y, false))
			} else {
				kf.add(Keyframe(0.45f, x, y, true, velocity))
				kf.add(Keyframe(0.85f, x, y, false))
			}
		}
		return Pattern("checker", kf)
	}

	fun ring(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 100): Pattern {
		val cx = (gridWidth - 1) / 2f
		val cy = (gridHeight - 1) / 2f
		val maxD = kotlin.math.hypot(cx.toDouble(), cy.toDouble()).toFloat().coerceAtLeast(1f)
		val kf = ArrayList<Keyframe>()
		for (x in 0 until gridWidth) for (y in 0 until gridHeight) {
			val d = kotlin.math.hypot(x - cx.toDouble(), y - cy.toDouble()).toFloat()
			val onT = (d / maxD * 0.65f).coerceIn(0f, 0.9f)
			val offT = (onT + 0.2f).coerceAtMost(1f)
			kf.add(Keyframe(onT, x, y, true, velocity))
			kf.add(Keyframe(offT, x, y, false))
		}
		return Pattern("ring", kf)
	}

	fun sparkle(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 127): Pattern {
		val seeds = listOf(
			0 to 0, 2 to 1, 4 to 0, 6 to 2, 1 to 3, 3 to 4, 5 to 3, 7 to 5,
			0 to 6, 2 to 7, 4 to 5, 6 to 7, 1 to 5, 3 to 2, 5 to 6, 7 to 1,
		)
		val kf = ArrayList<Keyframe>()
		seeds.forEachIndexed { i, (x, y) ->
			if (x >= gridWidth || y >= gridHeight) return@forEachIndexed
			val onT = (i.toFloat() / seeds.size * 0.75f).coerceIn(0f, 0.9f)
			val offT = (onT + 0.12f).coerceAtMost(1f)
			kf.add(Keyframe(onT, x, y, true, velocity))
			kf.add(Keyframe(offT, x, y, false))
		}
		return Pattern("sparkle", kf)
	}

	// ── Motion ──────────────────────────────────────────────────────────────

	fun cascade(gridWidth: Int = 8, gridHeight: Int = 8): Pattern {
		val keyframes = ArrayList<Keyframe>()
		for (col in 0 until gridWidth) {
			val colStart = (col.toFloat() / gridWidth) * 0.3f
			for (row in 0 until gridHeight) {
				val onT = colStart + (row.toFloat() / gridHeight) * 0.6f
				val offT = (onT + 0.15f).coerceAtMost(1f)
				keyframes.add(Keyframe(onT, col, row, true, 100))
				keyframes.add(Keyframe(offT, col, row, false))
			}
		}
		return Pattern("cascade", keyframes)
	}

	/** Top → bottom rain / waterfall. */
	fun waterfall(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 105): Pattern {
		val kf = ArrayList<Keyframe>()
		for (col in 0 until gridWidth) {
			val stagger = (col % 3) * 0.04f
			for (row in 0 until gridHeight) {
				val onT = stagger + row.toFloat() / gridHeight * 0.7f
				val offT = (onT + 0.18f).coerceAtMost(1f)
				kf.add(Keyframe(onT.coerceIn(0f, 0.95f), col, row, true, velocity))
				kf.add(Keyframe(offT, col, row, false))
			}
		}
		return Pattern("waterfall", kf)
	}

	fun rowWipe(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 105): Pattern {
		val kf = ArrayList<Keyframe>()
		for (row in 0 until gridHeight) {
			val onT = row.toFloat() / gridHeight * 0.7f
			val offT = (onT + 0.2f).coerceAtMost(1f)
			for (col in 0 until gridWidth) {
				kf.add(Keyframe(onT, col, row, true, velocity))
				kf.add(Keyframe(offT, col, row, false))
			}
		}
		return Pattern("row_wipe", kf)
	}

	fun colWipe(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 105): Pattern {
		val kf = ArrayList<Keyframe>()
		for (col in 0 until gridWidth) {
			val onT = col.toFloat() / gridWidth * 0.7f
			val offT = (onT + 0.2f).coerceAtMost(1f)
			for (row in 0 until gridHeight) {
				kf.add(Keyframe(onT, col, row, true, velocity))
				kf.add(Keyframe(offT, col, row, false))
			}
		}
		return Pattern("col_wipe", kf)
	}

	/** Single horizontal line sweeping top → bottom. */
	fun lineSweep(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 120): Pattern {
		val kf = ArrayList<Keyframe>()
		for (row in 0 until gridHeight) {
			val onT = row.toFloat() / gridHeight * 0.85f
			val offT = (onT + 0.12f).coerceAtMost(1f)
			for (col in 0 until gridWidth) {
				kf.add(Keyframe(onT, col, row, true, velocity))
				kf.add(Keyframe(offT, col, row, false))
			}
		}
		return Pattern("line", kf)
	}

	/** Line that leaves a fading trail (longer hold on earlier rows). */
	fun fadingLine(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 115): Pattern {
		val kf = ArrayList<Keyframe>()
		for (row in 0 until gridHeight) {
			val onT = row.toFloat() / gridHeight * 0.55f
			val hold = 0.35f - row.toFloat() / gridHeight * 0.25f
			val offT = (onT + hold.coerceAtLeast(0.08f)).coerceAtMost(1f)
			val v = (velocity - row * 8).coerceIn(40, 127)
			for (col in 0 until gridWidth) {
				kf.add(Keyframe(onT, col, row, true, v))
				kf.add(Keyframe(offT, col, row, false))
			}
		}
		return Pattern("fading_line", kf)
	}

	/**
	 * Shooting pad: center flash, then each row lights in sequence with fade behind
	 * (press-point feel — full-grid version for presets).
	 */
	fun shootingPad(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 120): Pattern {
		val kf = ArrayList<Keyframe>()
		val cx = gridWidth / 2
		val cy = gridHeight / 2
		kf.add(Keyframe(0.0f, cx, cy, true, 127))
		kf.add(Keyframe(0.12f, cx, cy, false))
		for (row in 0 until gridHeight) {
			val onT = 0.12f + row.toFloat() / gridHeight * 0.55f
			val offT = (onT + 0.22f).coerceAtMost(1f)
			val v = (velocity - row * 10).coerceIn(35, 127)
			for (col in 0 until gridWidth) {
				kf.add(Keyframe(onT, col, row, true, v))
				kf.add(Keyframe(offT, col, row, false))
			}
		}
		return Pattern("shooting_pad", kf)
	}

	/** Bloom from center — flower petals. */
	fun flowers(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 110): Pattern {
		val cx = (gridWidth - 1) / 2f
		val cy = (gridHeight - 1) / 2f
		val kf = ArrayList<Keyframe>()
		// center
		kf.add(Keyframe(0.02f, cx.toInt(), cy.toInt(), true, 127))
		kf.add(Keyframe(0.9f, cx.toInt(), cy.toInt(), false))
		// rings of petals
		for (ring in 1..3) {
			val onT = 0.08f + ring * 0.12f
			val offT = (onT + 0.35f).coerceAtMost(1f)
			for (x in 0 until gridWidth) for (y in 0 until gridHeight) {
				val d = kotlin.math.hypot(x - cx.toDouble(), y - cy.toDouble())
				if (d >= ring - 0.6 && d <= ring + 0.6) {
					kf.add(Keyframe(onT, x, y, true, velocity - ring * 10))
					kf.add(Keyframe(offT, x, y, false))
				}
			}
		}
		return Pattern("flowers", kf)
	}

	// ── Bursts ──────────────────────────────────────────────────────────────

	fun explosion(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 127): Pattern {
		val cx = (gridWidth - 1) / 2f
		val cy = (gridHeight - 1) / 2f
		val maxD = kotlin.math.hypot(cx.toDouble(), cy.toDouble()).toFloat().coerceAtLeast(1f)
		val kf = ArrayList<Keyframe>()
		for (x in 0 until gridWidth) for (y in 0 until gridHeight) {
			val d = kotlin.math.hypot(x - cx.toDouble(), y - cy.toDouble()).toFloat()
			val onT = (d / maxD * 0.4f).coerceIn(0f, 0.5f)
			val offT = (onT + 0.25f).coerceAtMost(1f)
			kf.add(Keyframe(onT, x, y, true, velocity))
			kf.add(Keyframe(offT, x, y, false))
		}
		return Pattern("explosion", kf)
	}

	/** Multiple explosion centers (firework). */
	fun firework(gridWidth: Int = 8, gridHeight: Int = 8, velocity: Int = 120): Pattern {
		val centers = listOf(
			2f to 2f,
			5f to 2f,
			3.5f to 5f,
			1f to 6f,
			6f to 6f,
		)
		val kf = ArrayList<Keyframe>()
		centers.forEachIndexed { i, (cx, cy) ->
			val base = i * 0.12f
			for (x in 0 until gridWidth) for (y in 0 until gridHeight) {
				val d = kotlin.math.hypot(x - cx.toDouble(), y - cy.toDouble()).toFloat()
				if (d > 2.8f) continue
				val onT = (base + d * 0.08f).coerceIn(0f, 0.9f)
				val offT = (onT + 0.18f).coerceAtMost(1f)
				kf.add(Keyframe(onT, x, y, true, velocity))
				kf.add(Keyframe(offT, x, y, false))
			}
		}
		return Pattern("firework", kf)
	}

	// ── Glyphs ──────────────────────────────────────────────────────────────

	/** Hold a 5-row bitmap glyph centered-ish on an 8x8 (rows are glyph lines, cols are chars). */
	fun glyphPattern(
		name: String,
		rows: List<String>,
		velocity: Int = 110,
		onT: Float = 0.05f,
		offT: Float = 0.92f,
	): Pattern {
		val kf = ArrayList<Keyframe>()
		for ((row, line) in rows.withIndex()) {
			for ((col, ch) in line.withIndex()) {
				if (ch == '1' || ch == '#') {
					kf.add(Keyframe(onT, col, row, true, velocity))
					kf.add(Keyframe(offT, col, row, false))
				}
			}
		}
		return Pattern(name, kf)
	}

	// 5×5-ish bitmaps (width varies). Row 0 = top.
	private val DIGITS: Array<List<String>> = arrayOf(
		listOf("01110", "10001", "10001", "10001", "01110"), // 0
		listOf("00100", "01100", "00100", "00100", "01110"), // 1
		listOf("01110", "10001", "00010", "00100", "11111"), // 2
		listOf("11110", "00001", "01110", "00001", "11110"), // 3
		listOf("10010", "10010", "11111", "00010", "00010"), // 4
		listOf("11111", "10000", "11110", "00001", "11110"), // 5
		listOf("01110", "10000", "11110", "10001", "01110"), // 6
		listOf("11111", "00001", "00010", "00100", "00100"), // 7
		listOf("01110", "10001", "01110", "10001", "01110"), // 8
		listOf("01110", "10001", "01111", "00001", "01110"), // 9
	)

	private val LETTERS: Map<Char, List<String>> = mapOf(
		'A' to listOf("01110", "10001", "11111", "10001", "10001"),
		'B' to listOf("11110", "10001", "11110", "10001", "11110"),
		'C' to listOf("01111", "10000", "10000", "10000", "01111"),
		'D' to listOf("11110", "10001", "10001", "10001", "11110"),
		'E' to listOf("11111", "10000", "11110", "10000", "11111"),
		'F' to listOf("11111", "10000", "11110", "10000", "10000"),
		'G' to listOf("01111", "10000", "10111", "10001", "01110"),
		'H' to listOf("10001", "10001", "11111", "10001", "10001"),
		'I' to listOf("01110", "00100", "00100", "00100", "01110"),
		'J' to listOf("00111", "00010", "00010", "10010", "01100"),
		'K' to listOf("10001", "10010", "11100", "10010", "10001"),
		'L' to listOf("10000", "10000", "10000", "10000", "11111"),
		'M' to listOf("10001", "11011", "10101", "10001", "10001"),
		'N' to listOf("10001", "11001", "10101", "10011", "10001"),
		'O' to listOf("01110", "10001", "10001", "10001", "01110"),
		'P' to listOf("11110", "10001", "11110", "10000", "10000"),
		'Q' to listOf("01110", "10001", "10001", "10010", "01101"),
		'R' to listOf("11110", "10001", "11110", "10010", "10001"),
		'S' to listOf("01111", "10000", "01110", "00001", "11110"),
		'T' to listOf("11111", "00100", "00100", "00100", "00100"),
		'U' to listOf("10001", "10001", "10001", "10001", "01110"),
		'V' to listOf("10001", "10001", "10001", "01010", "00100"),
		'W' to listOf("10001", "10001", "10101", "11011", "10001"),
		'X' to listOf("10001", "01010", "00100", "01010", "10001"),
		'Y' to listOf("10001", "01010", "00100", "00100", "00100"),
		'Z' to listOf("11111", "00010", "00100", "01000", "11111"),
	)

	private val SUIT_HEART = listOf(
		"01010",
		"11111",
		"11111",
		"01110",
		"00100",
	)
	private val SUIT_DIAMOND = listOf(
		"00100",
		"01110",
		"11111",
		"01110",
		"00100",
	)
	private val SUIT_CLUB = listOf(
		"01110",
		"01110",
		"11111",
		"00100",
		"01110",
	)
	private val SUIT_SPADE = listOf(
		"00100",
		"01110",
		"11111",
		"00100",
		"01110",
	)
	private val SYMBOL_MONEY = listOf(
		"00100",
		"01111",
		"10100",
		"01110",
		"00101",
		"11110",
		"00100",
	)
	private val SYMBOL_SMILEY = listOf(
		"01110",
		"10001",
		"10101",
		"10001",
		"10101",
		"10001",
		"01110",
	)
	private val SYMBOL_ARROW_UP = listOf(
		"00100",
		"01110",
		"11111",
		"00100",
		"00100",
		"00100",
	)
	private val SYMBOL_ARROW_DOWN = listOf(
		"00100",
		"00100",
		"00100",
		"11111",
		"01110",
		"00100",
	)
	private val SYMBOL_ARROW_LEFT = listOf(
		"00100",
		"01100",
		"11111",
		"01100",
		"00100",
	)
	private val SYMBOL_ARROW_RIGHT = listOf(
		"00100",
		"00110",
		"11111",
		"00110",
		"00100",
	)
	private val SYMBOL_STAR = listOf(
		"00100",
		"10101",
		"01110",
		"11111",
		"01110",
		"10101",
	)
}