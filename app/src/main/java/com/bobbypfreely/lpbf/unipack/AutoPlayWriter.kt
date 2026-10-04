package com.bobbypfreely.lpbf.unipack

import com.bobbypfreely.lpbf.marking.ButtonRef

/**
 * Builds a UniPad-compatible `autoPlay` text file from mapped cuts.
 *
 * Format (1-indexed coords, leading blank line like other UniPack text files):
 *   c N        switch chain
 *   t X Y      instant touch (on+off) — matches one-shot pad hits
 *   d MS       delay after the hit (clip duration, min 50ms)
 *
 * Matches what Make-Unipack-style tools generate from keySound + sound lengths,
 * and what AutoPlayReader already parses on import.
 */
object AutoPlayWriter {

	/**
	 * @param events ordered list of (button, durationMs) — typically export order / timeline order
	 * @return autoPlay file body, or empty string if nothing to write
	 */
	fun build(events: List<Pair<ButtonRef, Int>>): String {
		if (events.isEmpty()) return ""
		val sb = StringBuilder("\n")
		var lastChain = Int.MIN_VALUE
		events.forEach { (button, durationMs) ->
			val chain1 = button.chain + 1
			if (button.chain != lastChain) {
				sb.append("c ").append(chain1).append('\n')
				lastChain = button.chain
			}
			sb.append("t ")
				.append(button.x + 1).append(' ')
				.append(button.y + 1).append('\n')
			val delay = durationMs.coerceAtLeast(50)
			sb.append("d ").append(delay).append('\n')
		}
		return sb.toString()
	}

	/** Convenience from SoundEntry list (durationMs on each entry). */
	fun buildFromEntries(entries: List<UnipackWriter.SoundEntry>): String =
		build(entries.map { it.button to it.durationMs })
}
