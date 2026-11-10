package com.bobbypfreely.lpbf.lightshow

/** A single lighting event within a Pattern, authored independent of real-world duration. */
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
 * A named, normalized-time lightshow. Author it once against a target grid size;
 * PatternCompiler scales it to fit any clip length at compile time.
 */
data class Pattern(
	val name: String,
	val keyframes: List<Keyframe>,
)

/** Fade envelope applied on top of a Pattern at compile time. Fully independent of the */
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
