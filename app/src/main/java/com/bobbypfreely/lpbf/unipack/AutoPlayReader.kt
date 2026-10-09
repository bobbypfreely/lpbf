package com.bobbypfreely.lpbf.unipack

import com.bobbypfreely.lpbf.marking.ButtonRef
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Parses UniPad autoPlay files — behavior aligned with UniPackFolder.autoPlay()
 * in kimjisub/unipad-android (long and short tokens).
 *
 *   chain|c N     switch chain (1-based on disk), reset pad occurrence counters
 *   on|o X Y      press (queue slot = occurrence for this pad)
 *   off|f X Y     release (no new queue slot)
 *   touch|t X Y   on + off with no delay between
 *   delay|d MS    advance clock
 *
 * Coordinates and chain are 1-based on disk → 0-based [ButtonRef].
 */
object AutoPlayReader {

	fun read(file: File, buttonX: Int = 8, buttonY: Int = 8, chainCount: Int = 24): AutoPlayProgram {
		val raw = try {
			file.takeIf { it.exists() && it.length() > 0L }?.readText()
		} catch (_: Exception) {
			null
		}
		if (raw.isNullOrBlank()) return AutoPlayProgram(emptyList(), emptyList(), raw)
		return parse(raw, buttonX, buttonY, chainCount)
	}

	fun parse(
		text: String,
		buttonX: Int = 8,
		buttonY: Int = 8,
		chainCount: Int = 24,
	): AutoPlayProgram {
		val elements = ArrayList<AutoPlayElement>()
		// occurrence map: per pad index since last chain switch (UniPad map[x][y])
		val map = Array(buttonX.coerceAtLeast(1)) { IntArray(buttonY.coerceAtLeast(1)) }
		var currChain = 0
		var clockMs = 0

		for (rawLine in text.lineSequence()) {
			val s = rawLine.trim()
			if (s.isEmpty()) continue
			val split = s.split(Regex("\\s+"))
			if (split.isEmpty()) continue
			val option = split[0]
			try {
				when (option) {
					"on", "o" -> {
						val x = split[1].toInt() - 1
						val y = split[2].toInt() - 1
						if (x !in 0 until buttonX || y !in 0 until buttonY) continue
						val num = map[x][y]
						val button = ButtonRef(currChain, x, y)
						elements.add(AutoPlayElement.On(button, num))
						map[x][y] = num + 1
					}
					"off", "f" -> {
						val x = split[1].toInt() - 1
						val y = split[2].toInt() - 1
						if (x !in 0 until buttonX || y !in 0 until buttonY) continue
						elements.add(AutoPlayElement.Off(ButtonRef(currChain, x, y)))
					}
					"touch", "t" -> {
						val x = split[1].toInt() - 1
						val y = split[2].toInt() - 1
						if (x !in 0 until buttonX || y !in 0 until buttonY) continue
						val num = map[x][y]
						val button = ButtonRef(currChain, x, y)
						elements.add(AutoPlayElement.On(button, num))
						elements.add(AutoPlayElement.Off(button))
						map[x][y] = num + 1
					}
					"chain", "c" -> {
						val chain = split[1].toInt() - 1
						if (chain < 0 || chain >= chainCount) continue
						currChain = chain
						elements.add(AutoPlayElement.Chain(chain))
						map.forEach { row -> row.fill(0) }
					}
					"delay", "d" -> {
						val delay = split[1].toIntOrNull() ?: continue
						elements.add(AutoPlayElement.Delay(delay.coerceAtLeast(0)))
						clockMs += delay.coerceAtLeast(0)
					}
					else -> { /* skip unknown */ }
				}
			} catch (_: Exception) {
				// malformed line — skip like UniPad continues on error after addErr
			}
		}

		val presses = ArrayList<AutoPlayPress>()
		var t = 0
		for (el in elements) {
			when (el) {
				is AutoPlayElement.Delay -> t += el.delayMs
				is AutoPlayElement.On -> presses.add(
					AutoPlayPress(el.button, t, el.occurrenceIndex)
				)
				else -> {}
			}
		}
		return AutoPlayProgram(elements, presses, text)
	}

	/** Legacy helper used by older call sites expecting only presses. */
	fun readPresses(file: File): List<AutoPlayPress> = read(file).presses
}
