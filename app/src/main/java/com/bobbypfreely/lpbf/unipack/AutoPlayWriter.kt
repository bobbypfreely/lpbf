package com.bobbypfreely.lpbf.unipack

import com.bobbypfreely.lpbf.marking.ButtonRef

/**
 * Builds a UniPad-compatible `autoPlay` text file from mapped cuts.
 *
 * Format (1-indexed coords, leading blank line like other UniPack text files):
 *   c N        switch chain
 *   t X Y      instant touch (on+off)
 *   d MS       delay after the hit (clip duration, min 50ms)
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

	fun buildFromEntries(entries: List<UnipackWriter.SoundEntry>): String =
		build(entries.map { it.button to resolveDuration(it) })

	private fun resolveDuration(e: UnipackWriter.SoundEntry): Int {
		if (e.durationMs > 0) return e.durationMs
		return estimateWavDurationMs(e.wavBytes)
	}

	fun estimateWavDurationMs(wav: ByteArray): Int {
		if (wav.size < 44) return 50
		fun u16(o: Int) = (wav[o].toInt() and 0xFF) or ((wav[o + 1].toInt() and 0xFF) shl 8)
		fun u32(o: Int) = (wav[o].toInt() and 0xFF) or ((wav[o + 1].toInt() and 0xFF) shl 8) or ((wav[o + 2].toInt() and 0xFF) shl 16) or ((wav[o + 3].toInt() and 0xFF) shl 24)
		if (wav[0] != 'R'.code.toByte()) return 50
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
