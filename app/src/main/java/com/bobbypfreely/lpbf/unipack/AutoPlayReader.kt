package com.bobbypfreely.lpbf.unipack

import com.bobbypfreely.lpbf.marking.ButtonRef
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/** One real button press, in true chronological order, with the exact millisecond it
 * happens and which slot in that pad's keySound queue is firing at that moment.
 * [occurrenceIndex] is 0 for the first time this exact (chain,x,y) is pressed since the
 * last chain switch, 1 for the second, etc. -- matches the real circular-queue-reset
 * rule documented at unipad.io/docs/unipack/autoPlay ("switching chains resets all
 * button circular queue counters"). Still needs to be taken mod that pad's keySound
 * entry count by the caller if it can exceed the number of entries actually mapped. */
data class AutoPlayPress(val button: ButtonRef, val timestampMs: Int, val occurrenceIndex: Int)

/**
 * Parses an optional autoPlay file -- format from unipad.io/docs/unipack/autoPlay:
 *   chain <n> / c <n>         switch the active chain (1-indexed on disk, like keySound)
 *   on <x> <y> / o <x> <y>    press down (paired with a later off)
 *   off <x> <y> / f <x> <y>   release -- doesn't itself start a new press
 *   touch <x> <y> / t <x> <y> instantaneous on+off at once
 *   delay <ms> / d <ms>       advance the clock before the next event
 *
 * Running this top to bottom is the ONLY source of truth for when a pad actually gets
 * played during the real performance -- keySound's file order is often just whatever
 * raster order the authoring tool wrote entries in, and carries no relationship to real
 * playback order or timing at all.
 *
 * autoPlay is optional -- most packs don't have one yet (none of Bobby's own currently
 * do). Callers must fall back to keySound's file order when this returns empty.
 */
object AutoPlayReader {

	fun read(file: File): List<AutoPlayPress> {
		val presses = mutableListOf<AutoPlayPress>()
		var chain = 0
		var t = 0
		var occurrenceCounts = mutableMapOf<ButtonRef, Int>()

		BufferedReader(InputStreamReader(file.inputStream())).useLines { lines ->
			lines.forEach { raw ->
				val s = raw.trim()
				if (s.isEmpty()) return@forEach
				val split = s.split("\\s+".toRegex())
				try {
					when (split[0]) {
						"chain", "c" -> {
							chain = split[1].toInt() - 1
							// A chain switch resets every pad's queue counter -- without
							// this, occurrence tracking silently drifts wrong after the
							// first chain change in the song.
							occurrenceCounts = mutableMapOf()
						}
						"on", "o", "touch", "t" -> {
							val x = split[1].toInt() - 1
							val y = split[2].toInt() - 1
							val button = ButtonRef(chain, x, y)
							val occurrence = occurrenceCounts.getOrDefault(button, 0)
							occurrenceCounts[button] = occurrence + 1
							presses.add(AutoPlayPress(button, t, occurrence))
						}
						"off", "f" -> { /* release -- no new press to record */ }
						"delay", "d" -> t += (split[1].toIntOrNull() ?: 0)
					}
				} catch (e: Exception) {
					// Malformed line -- skip it rather than lose the rest of the file.
				}
			}
		}
		return presses
	}
}
