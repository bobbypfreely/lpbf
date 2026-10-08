package com.bobbypfreely.lpbf.unipack

import com.bobbypfreely.lpbf.marking.ButtonRef

/**
 * Builds a UniPad-compatible `autoPlay` text file from mapped cuts.
 *
 * Real packs (and UniPad hardware behavior) use press/release pairs:
 *   chain N
 *   on X Y
 *   delay <holdMs>
 *   off X Y
 *   delay <gap>
 *
 * Overlapping cuts interleave on/off on a shared timeline so multiple pads can
 * be held at once — matching Nevs-style performance scores.
 *
 * Tokens are **long form** (chain/on/off/delay). Short form (c/o/f/d) is also
 * valid per UniPad docs; long form matches community packs more closely.
 *
 * [touch]/t] is only for instantaneous hits; we do not use it for normal cuts.
 */
object AutoPlayWriter {

	private data class TimedEvent(
		val timeMs: Int,
		val isOn: Boolean,
		val button: ButtonRef,
	)

	/**
	 * Sequential fallback when startMs is unavailable: on → hold duration → off → next.
	 */
	fun build(events: List<Pair<ButtonRef, Int>>): String {
		if (events.isEmpty()) return ""
		val entries = events.mapIndexed { i, (button, dur) ->
			UnipackWriter.SoundEntry(
				button = button,
				soundFileName = "%03d.wav".format(i + 1),
				wavBytes = ByteArray(0),
				keyLedFileName = null,
				keyLedText = null,
				durationMs = dur.coerceAtLeast(50),
				startMs = events.take(i).sumOf { it.second.coerceAtLeast(50) },
			)
		}
		return buildTimeline(entries)
	}

	/**
	 * Preferred export path. Uses startMs for a real performance timeline when present;
	 * otherwise sequential on/off from durations.
	 */
	fun buildFromEntries(entries: List<UnipackWriter.SoundEntry>): String {
		if (entries.isEmpty()) return ""
		val withTime = entries.filter { it.startMs >= 0 }
		if (withTime.isNotEmpty()) return buildTimeline(withTime)
		return build(entries.map { it.button to resolveDuration(it) })
	}

	/**
	 * Performance autoPlay: absolute start times, real holds, overlapping on/off.
	 */
	fun buildTimeline(entries: List<UnipackWriter.SoundEntry>): String {
		if (entries.isEmpty()) return ""

		val timed = ArrayList<TimedEvent>(entries.size * 2)
		for (e in entries) {
			val start = e.startMs.coerceAtLeast(0)
			val hold = resolveDuration(e).coerceAtLeast(50)
			val b = e.button
			timed.add(TimedEvent(start, isOn = true, button = b))
			timed.add(TimedEvent(start + hold, isOn = false, button = b))
		}

		timed.sortWith(
			compareBy<TimedEvent> { it.timeMs }
				.thenBy { if (it.isOn) 1 else 0 }
				.thenBy { it.button.chain }
				.thenBy { it.button.x }
				.thenBy { it.button.y }
		)

		val sb = StringBuilder("\n")
		var lastChain = Int.MIN_VALUE
		var clock = 0

		for (ev in timed) {
			val gap = (ev.timeMs - clock).coerceAtLeast(0)
			if (gap > 0) {
				sb.append("delay ").append(gap).append('\n')
				clock = ev.timeMs
			}
			if (ev.button.chain != lastChain) {
				sb.append("chain ").append(ev.button.chain + 1).append('\n')
				lastChain = ev.button.chain
			}
			if (ev.isOn) {
				sb.append("on ")
					.append(ev.button.x + 1).append(' ')
					.append(ev.button.y + 1).append('\n')
			} else {
				sb.append("off ")
					.append(ev.button.x + 1).append(' ')
					.append(ev.button.y + 1).append('\n')
			}
		}
		return sb.toString()
	}

	private fun resolveDuration(e: UnipackWriter.SoundEntry): Int {
		if (e.durationMs > 0) return e.durationMs
		if (e.wavBytes.isNotEmpty()) return estimateWavDurationMs(e.wavBytes)
		return 50
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
