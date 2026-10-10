package com.bobbypfreely.lpbf.unipack

import com.bobbypfreely.lpbf.marking.ButtonRef

sealed class AutoPlayElement {
	data class On(
		val button: ButtonRef,
		val occurrenceIndex: Int,
	) : AutoPlayElement()

	data class Off(
		val button: ButtonRef,
	) : AutoPlayElement()

	data class Chain(
		val chain: Int,
	) : AutoPlayElement()

	data class Delay(
		val delayMs: Int,
	) : AutoPlayElement()
}

data class AutoPlayPress(
	val button: ButtonRef,
	val timestampMs: Int,
	val occurrenceIndex: Int,
)

data class AutoPlayProgram(
	val elements: List<AutoPlayElement>,
	val presses: List<AutoPlayPress>,
	val rawText: String? = null,
)
