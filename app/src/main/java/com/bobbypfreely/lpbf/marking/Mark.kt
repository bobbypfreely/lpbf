package com.bobbypfreely.lpbf.marking

import com.bobbypfreely.lpbf.lightshow.Pattern

/** A boundary timestamp in the source track. Segments are the gaps between consecutive Marks. */
data class Mark(
	val id: String,
	val positionMs: Int,
)

/** Which grid button (if any) a provisional segment is tentatively linked to. */
data class ButtonRef(
	val chain: Int,
	val x: Int,
	val y: Int,
)

/**
 * A provisional slice of the source track between two adjacent Marks.
 * Nothing is cut from the audio yet -- this is purely a timestamp range plus
 * whatever button it's currently tied to, both freely editable until splice().
 *
 * [lightPattern] is this cut's own lightshow, same relationship as audio has to
 * its button: one per cut, not per button. A button with multiple stacked cuts
 * (multi-trigger) gets one lightPattern per cut, matching how a real Unipack's
 * keyLED folder uses one lettered file per stacked mapping on the same pad.
 * Like the audio, nothing is compiled to keyLED events until Finalize.
 */
data class Segment(
	val startMs: Int,
	val endMs: Int,
	val button: ButtonRef?,
	val lightPattern: Pattern? = null,
) {
	val durationMs: Int get() = endMs - startMs
}

/** Result of an operation that finalizes an audio slice - what MarkingSession hands off to the WAV exporter. */
data class CommittedClip(
	val index: Int,          // 1-based, matches on-disk numbering: 001.wav, 002.wav...
	val startMs: Int,
	val endMs: Int,
	val button: ButtonRef?,
	val lightPattern: Pattern? = null,
) {
	val durationMs: Int get() = endMs - startMs
	val fileName: String get() = "%03d.wav".format(index)
}
