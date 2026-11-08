package com.bobbypfreely.lpbf.lightshow

/**
 * Piano-roll style light note for LPBF.
 *
 * One note = one pad lit for a time range, optionally with a color gradient.
 * Compiles down to existing [Pattern] / [Keyframe] so export + bot stay compatible.
 *
 * Coordinates: 0-based grid (same as Keyframe / VirtualLaunchpadGridView).
 * Times: milliseconds within the cut (absolute to the segment).
 */
data class LightNote(
	val id: String,
	val x: Int,
	val y: Int,
	val startMs: Int,
	val endMs: Int,
	/** Solid velocity 1–127 when [gradient] is null. */
	val velocity: Int = 100,
	/** Optional color envelope over the note duration (normalized 0..1 stops). */
	val gradient: List<GradientStop>? = null,
) {
	val durationMs: Int get() = (endMs - startMs).coerceAtLeast(1)

	init {
		require(endMs >= startMs) { "endMs must be >= startMs" }
	}
}

/**
 * One stop in a note gradient.
 * [t] is 0..1 across the note; [velocity] is Launchpad palette index 1–127
 * (LPBF still speaks velocity until full RGB export exists).
 */
data class GradientStop(
	val t: Float,
	val velocity: Int,
)

/**
 * A named collection of notes for one cut — the piano-roll document.
 */
data class LightRoll(
	val name: String,
	val notes: List<LightNote>,
	/** Optional total length hint (ms); otherwise max note end. */
	val durationMs: Int? = null,
) {
	fun effectiveDurationMs(): Int =
		durationMs ?: notes.maxOfOrNull { it.endMs } ?: 0
}