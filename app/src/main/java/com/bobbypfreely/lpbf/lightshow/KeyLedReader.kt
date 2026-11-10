package com.bobbypfreely.lpbf.lightshow

import java.io.File

/** Reads a real keyLED file back into this app's own Pattern model. This is the missing */
object KeyLedReader {

	/** /** One on/off event at an absolute millisecond offset from the start of this */
	data class TimedEvent(val atMs: Int, val x: Int, val y: Int, val on: Boolean, val velocity: Int, val color: Int? = null)

	/** Finds the Nth keyLED file mapped to (chain,x,y), where N is occurrenceIndex -- */
	fun findFile(keyLedDir: File, chain: Int, x: Int, y: Int, occurrenceIndex: Int): File? {
		val wantChain = (chain + 1).toString()
		val wantX = (x + 1).toString()
		val wantY = (y + 1).toString()
		val candidates = keyLedDir.listFiles { f ->
			if (!f.isFile) return@listFiles false
			val tokens = f.name.trim().split(Regex("\\s+"))
			tokens.size >= 3 && tokens[0] == wantChain && tokens[1] == wantX && tokens[2] == wantY
		}?.sortedBy { it.name.lowercase() } ?: return null
		return candidates.getOrNull(occurrenceIndex)
	}

	/** /** Parses one keyLED file's raw text into a flat, time-ordered event list. Malformed */
	fun parse(file: File): List<TimedEvent> {
		var t = 0
		val events = mutableListOf<TimedEvent>()
		file.readLines().forEach { raw ->
			val line = raw.trim()
			if (line.isEmpty()) return@forEach
			val tok = line.split(Regex("\\s+"))
			when (tok[0]) {
				"o", "on" -> {
					if (tok.size < 2) return@forEach
					val (x, y, colorTokenIndex) = when (tok[1]) {
						"l" -> {
							// No column of its own -- see doc comment above.
							if (tok.size < 3) return@forEach
							Triple(-1, -1, 2)
						}
						"mc", "*" -> {
							if (tok.size < 3) return@forEach
							val y = tok[2].toIntOrNull()?.minus(1) ?: return@forEach
							Triple(-1, y, 3)
						}
						else -> {
							if (tok.size < 3) return@forEach
							val x = tok[1].toIntOrNull()?.minus(1) ?: return@forEach
							val y = tok[2].toIntOrNull()?.minus(1) ?: return@forEach
							Triple(x, y, 3)
						}
					}
					// From here, colorTokenIndex points at where the color/auto token
					// would start; how many tokens remain after it decides the format.
					val remaining = tok.size - colorTokenIndex
					when {
						remaining <= 0 -> return@forEach
						remaining == 1 -> {
							// "o <target> <hexColor>" -- explicit color, default velocity.
							val color = tok[colorTokenIndex].toIntOrNull(16) ?: return@forEach
							events.add(TimedEvent(t, x, y, on = true, velocity = LedAnimation.DEFAULT_VELOCITY, color = color or -0x1000000))
						}
						tok[colorTokenIndex] == "a" || tok[colorTokenIndex] == "auto" -> {
							// "o <target> a <velocity>" -- palette color, the common case.
							val velocity = tok.getOrNull(colorTokenIndex + 1)?.toIntOrNull() ?: return@forEach
							events.add(TimedEvent(t, x, y, on = true, velocity = velocity.coerceIn(0, 127), color = null))
						}
						else -> {
							// "o <target> <hexColor> <velocity>" -- both explicit.
							val color = tok[colorTokenIndex].toIntOrNull(16) ?: return@forEach
							val velocity = tok.getOrNull(colorTokenIndex + 1)?.toIntOrNull() ?: return@forEach
							events.add(TimedEvent(t, x, y, on = true, velocity = velocity.coerceIn(0, 127), color = color or -0x1000000))
						}
					}
				}
				"f", "off" -> {
					if (tok.size < 2) return@forEach
					when (tok[1]) {
						"l" -> events.add(TimedEvent(t, -1, -1, on = false, velocity = 0))
						"mc", "*" -> {
							if (tok.size < 3) return@forEach
							val y = tok[2].toIntOrNull()?.minus(1) ?: return@forEach
							events.add(TimedEvent(t, -1, y, on = false, velocity = 0))
						}
						else -> {
							if (tok.size < 3) return@forEach
							val x = tok[1].toIntOrNull()?.minus(1) ?: return@forEach
							val y = tok[2].toIntOrNull()?.minus(1) ?: return@forEach
							events.add(TimedEvent(t, x, y, on = false, velocity = 0))
						}
					}
				}
				"d", "delay" -> {
					val ms = tok.getOrNull(1)?.toIntOrNull() ?: return@forEach
					t += ms
				}
				// "chain"/"c" mid-file, and anything else: not modeled yet -- see doc
				// comment above. Falling through here is a safe no-op.
			}
		}
		return events
	}

	/** /** Converts a parsed event list into a normalized-time Pattern, scaling each */
	fun toPattern(name: String, events: List<TimedEvent>, durationMs: Int): Pattern? {
		if (events.isEmpty() || durationMs <= 0) return null
		val keyframes = events.map { e ->
			val t = (e.atMs.toFloat() / durationMs).coerceIn(0f, 1f)
			Keyframe(t = t, x = e.x, y = e.y, on = e.on, velocity = if (e.on) e.velocity.coerceIn(1, 127) else 1, color = e.color)
		}
		return Pattern(name, keyframes)
	}
}
