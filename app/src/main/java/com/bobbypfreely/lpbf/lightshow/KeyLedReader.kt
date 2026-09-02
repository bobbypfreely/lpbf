package com.bobbypfreely.lpbf.lightshow

import java.io.File

/**
 * Reads a real keyLED file back into this app's own Pattern model. This is the missing
 * half of KeyLedWriter -- confirmed against a real Unipack ("Store_Sing_me_to_sleep")
 * and the official unipad.io docs:
 *   - Filenames: "<chain> <x> <y> <loop>[ <suffix>]", all 1-indexed on disk.
 *   - Two command dialects appear in real packs and both parse fine: abbreviated
 *     (o/f/d) and full-word (on/off/delay). Whichever a given file uses, every line in
 *     it uses consistently.
 *   - Color is always "a <velocity>" (auto/palette) in every real file seen so far --
 *     velocity is a direct index into LaunchpadColor.ARGB, not RGB.
 *   - The suffix letter is for multi-mapping: multiple keySound lines sharing the same
 *     (chain,x,y) each get their own lettered keyLED file, matched 1:1 in file order.
 */
object KeyLedReader {

	/** One on/off event at an absolute millisecond offset from the start of this
	 * particular keyLED file's own timeline (i.e. relative to whichever single button
	 * press triggers it -- not the whole track). */
	data class TimedEvent(val atMs: Int, val x: Int, val y: Int, val on: Boolean, val velocity: Int)

	/**
	 * Finds the Nth keyLED file mapped to (chain,x,y), where N is [occurrenceIndex] --
	 * the position of this button-mapping among every keySound line sharing that exact
	 * button, in the order they appear in keySound. For an unstacked button (only one
	 * sound mapped) occurrenceIndex is always 0, which also matches an un-suffixed file.
	 * Files are matched by their first 3 whitespace-separated tokens (chain, x, y) --
	 * not a raw string prefix -- so e.g. "3 2 2 ..." never accidentally matches "3 2 22 ...".
	 */
	fun findFile(keyLedDir: File, chain: Int, x: Int, y: Int, occurrenceIndex: Int): File? {
		val wantChain = (chain + 1).toString()
		val wantX = (x + 1).toString()
		val wantY = (y + 1).toString()
		val candidates = keyLedDir.listFiles { f ->
			if (!f.isFile) return@listFiles false
			val tokens = f.name.trim().split(Regex("\\s+"))
			tokens.size >= 3 && tokens[0] == wantChain && tokens[1] == wantX && tokens[2] == wantY
		}?.sortedBy { it.name } ?: return null
		return candidates.getOrNull(occurrenceIndex)
	}

	/** Parses one keyLED file's raw text into a flat, time-ordered event list. Malformed
	 * or unrecognized lines are skipped rather than throwing -- a partially-garbled file
	 * should still import whatever it can, same spirit as UnipackReader's own warnings. */
	fun parse(file: File): List<TimedEvent> {
		var t = 0
		val events = mutableListOf<TimedEvent>()
		file.readLines().forEach { raw ->
			val line = raw.trim()
			if (line.isEmpty()) return@forEach
			val tok = line.split(Regex("\\s+"))
			when (tok[0]) {
				"o", "on" -> {
					if (tok.size < 5) return@forEach
					val x = tok[1].toIntOrNull()?.minus(1) ?: return@forEach
					val y = tok[2].toIntOrNull()?.minus(1) ?: return@forEach
					// tok[3] is the color code -- every real file seen uses "a" (auto/
					// palette). A literal hex color could theoretically appear here per
					// the format spec, but none observed in practice does; treat it the
					// same as "a" and read tok[4] as the velocity either way.
					val velocity = tok[4].toIntOrNull() ?: return@forEach
					events.add(TimedEvent(t, x, y, on = true, velocity = velocity.coerceIn(0, 127)))
				}
				"f", "off" -> {
					if (tok.size < 3) return@forEach
					val x = tok[1].toIntOrNull()?.minus(1) ?: return@forEach
					val y = tok[2].toIntOrNull()?.minus(1) ?: return@forEach
					events.add(TimedEvent(t, x, y, on = false, velocity = 0))
				}
				"d", "delay" -> {
					val ms = tok.getOrNull(1)?.toIntOrNull() ?: return@forEach
					t += ms
				}
				// "chain" (autoPlay-only) and anything else: not part of a single
				// button's keyLED file, safe to ignore here.
			}
		}
		return events
	}

	/** Converts a parsed event list into a duration-agnostic Pattern, scaling each
	 * event's absolute ms against [durationMs] (the matched sound clip's real decoded
	 * duration, not the keyLED file's own delay total -- real packs run those two
	 * numbers slightly differently, see prior investigation). */
	fun toPattern(name: String, events: List<TimedEvent>, durationMs: Int): Pattern? {
		if (events.isEmpty() || durationMs <= 0) return null
		val keyframes = events.map { e ->
			val t = (e.atMs.toFloat() / durationMs).coerceIn(0f, 1f)
			Keyframe(t = t, x = e.x, y = e.y, on = e.on, velocity = if (e.on) e.velocity.coerceIn(1, 127) else 1)
		}
		return Pattern(name, keyframes)
	}
}
