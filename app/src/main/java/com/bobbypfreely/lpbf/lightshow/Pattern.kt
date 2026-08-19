package com.bobbypfreely.lpbf.lightshow

/**
 * A single lighting event within a Pattern, authored independent of real-world duration.
 *
 * @param t         Time as a fraction of the pattern's total duration, 0.0 (start) to 1.0 (end).
 * @param x         0-indexed pad column. Use -1 to target the round/chain-wide "mc" LED.
 * @param y         0-indexed pad row.
 * @param on        true = pad turns on at this time, false = pad turns off.
 * @param velocity  1-127 brightness/palette index. Only meaningful when [on] is true.
 * @param color     Optional literal ARGB color override. When null, the velocity-based
 *                  palette color is used (matches keyLED's "a <velocity>" auto-color form).
 */
data class Keyframe(
	val t: Float,
	val x: Int,
	val y: Int,
	val on: Boolean,
	val velocity: Int = 100,
	val color: Int? = null,
) {
	init {
		require(t in 0f..1f) { "Keyframe.t must be within 0.0..1.0, was $t" }
		require(velocity in 1..127) { "Keyframe.velocity must be within 1..127, was $velocity" }
	}
}

/**
 * A named, duration-agnostic lightshow. Author it once against a target grid size;
 * PatternCompiler scales it to fit any clip length at compile time.
 */
data class Pattern(
	val name: String,
	val keyframes: List<Keyframe>,
)

/**
 * Fade envelope applied on top of a Pattern at compile time. Fully independent of the
 * pattern itself -- any pattern can be compiled with or without fade, no authoring changes needed.
 *
 * @param fadeInFraction   Fraction (0.0-1.0) of total duration over which "on" events ramp
 *                         up from near-zero to full velocity. 0f disables fade-in.
 * @param fadeOutFraction  Fraction (0.0-1.0) of total duration, counted from the end, over
 *                         which "on" events ramp down toward zero. 0f disables fade-out.
 */
data class FadeEnvelope(
	val fadeInFraction: Float = 0f,
	val fadeOutFraction: Float = 0f,
) {
	companion object {
		val NONE = FadeEnvelope(0f, 0f)
	}

	init {
		require(fadeInFraction in 0f..1f)
		require(fadeOutFraction in 0f..1f)
	}

	/** Returns the 0.0-1.0 brightness multiplier for an "on" event at time [t] (0.0-1.0). */
	fun multiplierAt(t: Float): Float {
		if (fadeInFraction > 0f && t < fadeInFraction) {
			return (t / fadeInFraction).coerceIn(0f, 1f)
		}
		if (fadeOutFraction > 0f && t > 1f - fadeOutFraction) {
			return ((1f - t) / fadeOutFraction).coerceIn(0f, 1f)
		}
		return 1f
	}
}
