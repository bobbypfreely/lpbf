package com.bobbypfreely.lpbf.harness

import com.bobbypfreely.lpbf.lightshow.Pattern
import com.bobbypfreely.lpbf.marking.ButtonRef
import com.bobbypfreely.lpbf.unipack.AutoPlayPress

/**
 * Shared contracts between modules. Each module only talks through these ports —
 * swap an implementation and the rest of the app keeps working with no rewiring.
 */

/** One clip fire: plays fully on its own voice; does not cancel other fires. */
data class ClipFire(
	val startMs: Int,
	val endMs: Int,
	val pattern: Pattern? = null,
	val button: ButtonRef? = null,
) {
	val durationMs: Int get() = (endMs - startMs).coerceAtLeast(1)
}

/** Polyphonic audio — every fire rings out; overlapping pads stack. */
interface SoundModule {
	fun attachSource(path: String)
	/** Fire a range; does not stop other voices. */
	fun fire(startMs: Int, durationMs: Int)
	fun release()
}

/** LED patterns for a clip (grid + hardware). */
interface LightModule {
	fun play(pattern: Pattern?, durationMs: Int)
	fun clear()
	fun release()
}

/** Resolve pad → clip from the current project map / stack cycle. */
interface MapModule {
	fun clipForPad(chain: Int, x: Int, y: Int): ClipFire?
	fun assignNextCut(chain: Int, x: Int, y: Int): ClipFire?
	/** Pad labels for active chain: "1", "1 3", etc. */
	fun labelsForChain(chain: Int): Map<Pair<Int, Int>, String>
}

/** Optional AutoPlay timeline (labels now; timed run later). */
interface AutoPlayModule {
	fun presses(): List<AutoPlayPress>
	fun isEmpty(): Boolean = presses().isEmpty()
}

/** Pad / chain / function input from grid or hardware. */
interface InputModule {
	fun onPadDown(chain: Int, x: Int, y: Int)
	fun onPadUp(chain: Int, x: Int, y: Int) {}
	fun onChain(chain: Int) {}
	fun onFunction(f: Int) {}
}
