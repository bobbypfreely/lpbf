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
	 * press triggers it -- not the whole track). [color] is the literal ARGB value when
	 * the file specified one explicitly; null means "use the palette color for
	 * [velocity]" (the "a"/"auto" form, which is what almost every real file uses). */
	data class TimedEvent(val atMs: Int, val x: Int, val y: Int, val on: Boolean, val velocity: Int, val color: Int? = null)

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
		}?.sortedBy { it.name.lowercase() } ?: return null
		return candidates.getOrNull(occurrenceIndex)
	}

	/** Parses one keyLED file's raw text into a flat, time-ordered event list. Malformed
	 * or unrecognized lines are skipped rather than throwing -- a partially-garbled file
	 * should still import whatever it can, same spirit as UnipackReader's own warnings.
	 *
	 * Rebuilt directly against the real parser (kimjisub/unipad-android,
	 * UniPackFolder.kt's keyLed() function) rather than inference, confirmed
	 * against Bobby's own copy of that source on 2026-09-10. Real formats:
	 *   o <x|mc|*> <y> <hexColor>              -- explicit color, default velocity (4)
	 *   o <x|mc|*> <y> a <velocity>             -- palette color (the common case)
	 *   o <x|mc|*> <y> <hexColor> <velocity>    -- explicit color AND velocity together
	 *   f <x|mc|*> <y>                          -- off
	 *   d <ms>                                  -- delay
	 * "mc" and "*" are both real synonyms for the round/chain-wide LED (x=-1, y=column).
	 * "l" (the single fixed scene-launch button) is NOT supported by the real app either
	 * -- its own parser hits this case and skips it, same as ours used to. LPBF still
	 * captures it as x=-1,y=-1 rather than dropping it -- that's purely our own
	 * extension since it's harmless to keep, not a confirmed real behavior.
	 * "chain"/"c" mid-file (a real, supported format for embedding a chain-jump inside
	 * a single button's own animation) is intentionally NOT preserved yet -- LPBF's
	 * Pattern/Keyframe model has no slot for an embedded chain-jump event, only on/off.
	 * Safe to skip: falls through the `when` as a no-op, same as any other unknown line.
	 */
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

	/** Converts a parsed event list into a duration-agnostic Pattern, scaling each
	 * event's absolute ms against [durationMs] (the matched sound clip's real decoded
	 * duration, not the keyLED file's own delay total -- real packs run those two
	 * numbers slightly differently, see prior investigation). */
	fun toPattern(name: String, events: List<TimedEvent>, durationMs: Int): Pattern? {
		if (events.isEmpty() || durationMs <= 0) return null
		val keyframes = events.map { e ->
			val t = (e.atMs.toFloat() / durationMs).coerceIn(0f, 1f)
			Keyframe(t = t, x = e.x, y = e.y, on = e.on, velocity = if (e.on) e.velocity.coerceIn(1, 127) else 1, color = e.color)
		}
		return Pattern(name, keyframes)
	}
}
