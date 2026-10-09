package com.bobbypfreely.lpbf.unipack

import com.bobbypfreely.lpbf.marking.ButtonRef

/**
 * UniPad-compatible autoPlay program.
 *
 * Matches kimjisub/unipad-android unipack.struct.AutoPlay:
 * elements are on / off / chain / delay in file order. Timing is only advanced by Delay.
 * [touch] in the file is expanded to On then Off with no Delay between them.
 */
sealed class AutoPlayElement {
	/** Pad press. [occurrenceIndex] is the circular queue slot for this pad since last chain switch. */
	data class On(
		val button: ButtonRef,
		val occurrenceIndex: Int,
	) : AutoPlayElement()

	data class Off(
		val button: ButtonRef,
	) : AutoPlayElement()

	/** Switch active chain (0-based). Resets occurrence counters in the parser. */
	data class Chain(
		val chain: Int,
	) : AutoPlayElement()

	/** Advance the performance clock by [delayMs] before the next element. */
	data class Delay(
		val delayMs: Int,
	) : AutoPlayElement()
}

/**
 * Flattened press for labels / guide (derived from On elements + cumulative delay clock).
 */
data class AutoPlayPress(
	val button: ButtonRef,
	val timestampMs: Int,
	val occurrenceIndex: Int,
)

/** Full program: raw elements for playback, presses for labels, optional source text for export. */
data class AutoPlayProgram(
	val elements: List<AutoPlayElement>,
	val presses: List<AutoPlayPress>,
	val rawText: String? = null,
)
