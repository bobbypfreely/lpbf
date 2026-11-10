package com.bobbypfreely.lpbf.lightshow

/** Ported from unipad-android (kimjisub/unipad-android, LGPL-2.1), */
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
			val x: Int,       // -1 means "mc" (round/chain-wide LED); -1,-1 together means
			                  // the single fixed "l" (scene-launch) button -- see KeyLedReader/
			                  // KeyLedWriter. Only the plain -1-for-mc case is confirmed
			                  // against the real struct; the -1,-1 "l" convention is LPBF's
			                  // own extension, inferred from real keyLED files, not confirmed
			                  // against the original source.
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
