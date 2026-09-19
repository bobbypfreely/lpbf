package com.bobbypfreely.lpbf.marking

import com.bobbypfreely.lpbf.lightshow.Pattern

/** A boundary timestamp in the source track. Segments are the gaps between consecutive Marks. */
data class Mark(
	val id: String,
	val positionMs: Int,
)

/**
 * Which grid button (if any) a provisional segment is linked to.
 *
 * Coordinate system (matches Unipad / real Launchpad hardware):
 *   - chain: 0-indexed page (0 = chain 1 on disk, up to chainCount-1)
 *   - x:     0-indexed ROW    (vertical). Unipad keySound "X" is vertical.
 *   - y:     0-indexed COLUMN (horizontal). Unipad keySound "Y" is horizontal.
 *
 * On disk (keySound / keyLED filenames) everything is 1-indexed.
 * Inside LPBF everything is 0-indexed so array math stays clean.
 *
 * Example: keySound line "1 3 5 001.wav"  →  ButtonRef(chain=0, x=2, y=4)
 */
data class ButtonRef(
	val chain: Int,
	val x: Int,
	val y: Int,
)

/**
 * Unipad keySound loop semantics (kept as the raw number from the file):
 *   0 = play only while the pad is held (gate)
 *   1 = play once (default when omitted)
 *   N = play N times
 *
 * wormhole: target chain index to jump to after this sound finishes, or -1 for none.
 * (On disk wormhole is 1-indexed chain number; we store 0-indexed, -1 = none.)
 */

/**
 * A provisional slice of the source track between two adjacent Marks.
 * Nothing is cut from the audio yet -- this is purely a timestamp range plus
 * whatever button it's currently tied to, both freely editable until splice().
 *
 * [lightPattern] is this cut's own lightshow, same relationship as audio has to
 * its button: one per cut, not per button. A button with multiple stacked cuts
 * (multi-trigger) gets one lightPattern per cut, matching how a real Unipack's
 * keyLED folder uses one file per stacked mapping on the same pad.
 *
 * [loop] / [wormhole] travel with the cut so Unipack import → edit → export
 * round-trips cleanly. Live mark-while-playing still works: new marks default
 * to loop=1 (play once) and wormhole=-1 (none).
 */
data class Segment(
	val startMs: Int,
	val endMs: Int,
	val button: ButtonRef?,
	val lightPattern: Pattern? = null,
	val loop: Int = 1,
	val wormhole: Int = -1,
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
	val loop: Int = 1,
	val wormhole: Int = -1,
) {
	val durationMs: Int get() = endMs - startMs
	val fileName: String get() = "%03d.wav".format(index)
}
