package com.bobbypfreely.lpbf.lightshow

/**
 * Ported from unipad-android (kimjisub/unipad-android, LGPL-2.1),
 * app/src/main/java/com/kimjisub/launchpad/unipack/struct/LedAnimation.kt
 *
 * This is the exact event model UniPackFolder.keyLed() parses a keyLED file into.
 * Kept byte-for-byte compatible so our PatternCompiler output is a drop-in match
 * for what any unipack reader (including the original Unipad app) already expects.
 */
class LedAnimation(
	val ledEvents: ArrayList<LedEvent>,
	val loop: Int,
	val num: Int,
) {
	companion object {
		const val DEFAULT_VELOCITY = 4
	}

	sealed interface LedEvent {
		class On(
			val x: Int,       // -1 means "mc" (round/chain-wide LED)
			val y: Int,
			val color: Int = -1,
			val velocity: Int = DEFAULT_VELOCITY,
		) : LedEvent

		class Off(
			val x: Int,
			val y: Int,
		) : LedEvent

		class Delay(
			val delay: Int,
		) : LedEvent

		class Chain(
			val chain: Int,
		) : LedEvent
	}
}
