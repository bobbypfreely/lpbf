package com.bobbypfreely.lpbf.unipack

import com.bobbypfreely.lpbf.marking.ButtonRef

/**
 * Builds a UniPad-compatible `autoPlay` text file from mapped cuts.
 *
 * **Timeline mode (default when [UnipackWriter.SoundEntry.startMs] is set):**
 * Events ordered by absolute start time in the source track. Pads that share the
 * same startMs fire together (chords). Delay between groups is the gap between
 * start times — samples may overlap in UniPad, matching real multi-pad packs.
 *
 * **Sequential fallback** (no start times): one `t` + `d duration` per cut in list order.
 *
 * Format (1-indexed coords, leading blank line):
 *   c N        switch chain
 *   t X Y      instant touch (on+off)
 *   d MS       delay before the next event group
 */
object AutoPlayWriter {

	fun build(events: List<Pair<ButtonRef, Int>>): String {
		if (events.isEmpty()) return ""
		val sb = StringBuilder("\n")
		var lastChain = Int.MIN_VALUE
		events.forEach { (button, durationMs) ->
			if (button.chain != lastChain) {
				sb.append("c ").append(button.chain + 1).append('\n')
				lastChain = button.chain
			}
			sb.append("t ").append(button.x + 1).append(' ').append(button.y + 1).append('\n')
			sb.append("d ").append(durationMs.coerceAtLeast(50)).append('\n')
		}
		return sb.toString()
	}

	/**
	 * Preferred export path. Uses startMs for a real performance timeline when present;
	 * otherwise sequential durations.
	 */
	fun buildFromEntries(entries: List<UnipackWriter.SoundEntry>): String {
		if (entries.isEmpty()) return ""
		val withTime = entries.filter { it.startMs >= 0 }
		if (withTime.isNotEmpty()) return buildTimeline(withTime)
		return build(entries.map { it.button to resolveDuration(it) })
	}

	/**
	 * Performance autoPlay from absolute cut start times.
	 * Same startMs → multiple `t` lines (chord). Delay = gap to next start.
	 */
	fun buildTimeline(entries: List<UnipackWriter.SoundEntry>): String {
		if (entries.isEmpty()) return ""
		val sorted = entries.sortedWith(
			compareBy<UnipackWriter.SoundEntry> { it.startMs }
				.thenBy { it.button.chain }
				.thenBy { it.button.x }
				.thenBy { it.button.y }
		)
		val groups = sorted.groupBy { it.startMs }.toSortedMap()
		val starts = groups.keys.toList()
		val sb = StringBuilder("\n")
		var lastChain = Int.MIN_VALUE
		starts.forEachIndexed { gi, start ->
			val group = groups.getValue(start)
			group.forEach { e ->
				val b = e.button
				if (b.chain != lastChain) {
					sb.append("c ").append(b.chain + 1).append('\n')
					lastChain = b.chain
				}
				sb.append("t ").append(b.x + 1).append(' ').append(b.y + 1).append('\n')
			}
			val delay = if (gi < starts.lastIndex) {
				(starts[gi + 1] - start).coerceAtLeast(0)
			} else {
				group.maxOf { resolveDuration(it) }.coerceAtLeast(50)
			}
			sb.append("d ").append(delay).append('\n')
		}
		return sb.toString()
	}

	private fun resolveDuration(e: UnipackWriter.SoundEntry): Int {
		if (e.durationMs > 0) return e.durationMs
		return estimateWavDurationMs(e.wavBytes)
	}

	fun estimateWavDurationMs(wav: ByteArray): Int {
		if (wav.size < 44) return 50
		fun u16(o: Int) = (wav[o].toInt() and 0xFF) or ((wav[o + 1].toInt() and 0xFF) shl 8)
		fun u32(o: Int) = (wav[o].toInt() and 0xFF) or
			((wav[o + 1].toInt() and 0xFF) shl 8) or
			((wav[o + 2].toInt() and 0xFF) shl 16) or
			((wav[o + 3].toInt() and 0xFF) shl 24)
		if (wav[0] != 'R'.code.toByte() || wav[8] != 'W'.code.toByte()) return 50
		val channels = u16(22).coerceAtLeast(1)
		val sampleRate = u32(24).coerceAtLeast(1)
		val bitsPerSample = u16(34).coerceAtLeast(8)
		val bytesPerSec = sampleRate * channels * (bitsPerSample / 8)
		if (bytesPerSec <= 0) return 50
		var dataSize = u32(40)
		if (dataSize <= 0 || dataSize > wav.size) dataSize = wav.size - 44
		return (dataSize.toLong() * 1000L / bytesPerSec).toInt().coerceAtLeast(50)
	}
}
