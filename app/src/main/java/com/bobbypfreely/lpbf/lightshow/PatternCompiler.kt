package com.bobbypfreely.lpbf.lightshow

import kotlin.math.roundToInt

/**
 * Compiles a duration-agnostic [Pattern] into concrete [LedAnimation.LedEvent]s for a specific
 * clip length. This is the core of the "1 sec lights 1 sec, but the same pattern also fits a
 * 6 sec clip" requirement: patterns are authored once as 0.0-1.0 time fractions, and this
 * compiler is what turns fractions into real millisecond delays for a given cut.
 */
object PatternCompiler {

	private data class ScaledEvent(
		val atMs: Int,
		val keyframe: Keyframe,
		val resolvedVelocity: Int,
	)

	/**
	 * @param pattern      The pattern to compile.
	 * @param durationMs   Real length of the audio clip this lightshow will accompany.
	 * @param fade         Fade envelope to apply on top of the pattern. Pass [FadeEnvelope.NONE]
	 *                     for "no fade, just play the lights as authored."
	 */
	fun compile(pattern: Pattern, durationMs: Int, fade: FadeEnvelope = FadeEnvelope.NONE): List<LedAnimation.LedEvent> {
		require(durationMs > 0) { "durationMs must be positive" }

		// 1. Scale each keyframe's fractional time to an absolute millisecond offset,
		//    and resolve the fade-adjusted velocity for "on" events.
		val scaled = pattern.keyframes.map { kf ->
			val atMs = (kf.t * durationMs).roundToInt()
			val resolvedVelocity = if (kf.on) {
				val mult = fade.multiplierAt(kf.t)
				(kf.velocity * mult).roundToInt().coerceIn(1, 127)
			} else {
				kf.velocity
			}
			ScaledEvent(atMs, kf, resolvedVelocity)
		}

		// 2. Sort chronologically. Stable sort keeps authoring order for simultaneous events,
		//    which matters for visually layered effects (e.g. background wash then accent).
		val sorted = scaled.sortedBy { it.atMs }

		// 3. Walk the sorted list, grouping same-timestamp events together and emitting a
		//    single Delay event for each gap to the next distinct timestamp. This mirrors
		//    exactly how real keyLED files interleave multiple `o`/`f` lines before a `d`.
		val result = ArrayList<LedAnimation.LedEvent>()
		var i = 0
		var prevMs = 0
		val n = sorted.size
		while (i < n) {
			val curMs = sorted[i].atMs
			val gap = curMs - prevMs
			if (gap > 0) {
				result.add(LedAnimation.LedEvent.Delay(gap))
			}
			while (i < n && sorted[i].atMs == curMs) {
				val (_, kf, vel) = sorted[i]
				result.add(
					if (kf.on) {
						LedAnimation.LedEvent.On(kf.x, kf.y, kf.color ?: -1, vel)
					} else {
						LedAnimation.LedEvent.Off(kf.x, kf.y)
					}
				)
				i++
			}
			prevMs = curMs
		}

		return result
	}
}
