package com.bobbypfreely.lpbf.harness

import com.bobbypfreely.lpbf.lightshow.Pattern
import com.bobbypfreely.lpbf.marking.ButtonRef
import com.bobbypfreely.lpbf.unipack.AutoPlayPress

data class ClipFire(
	val startMs: Int,
	val endMs: Int,
	val pattern: Pattern? = null,
	val button: ButtonRef? = null,
) {
	val durationMs: Int get() = (endMs - startMs).coerceAtLeast(1)
}

interface SoundModule {
	fun attachSource(path: String)
	fun fire(startMs: Int, durationMs: Int)
	fun playFile(path: String, durationMs: Int = 0) {}
	fun release()
}

interface LightModule {
	fun play(pattern: Pattern?, durationMs: Int)
	fun clear()
	fun release()
}

interface MapModule {
	fun clipForPad(chain: Int, x: Int, y: Int): ClipFire?
	fun assignNextCut(chain: Int, x: Int, y: Int): ClipFire?
	fun labelsForChain(chain: Int): Map<Pair<Int, Int>, String>
}

interface AutoPlayModule {
	fun presses(): List<AutoPlayPress>
	fun isEmpty(): Boolean = presses().isEmpty()
}

interface InputModule {
	fun onPadDown(chain: Int, x: Int, y: Int)
	fun onPadUp(chain: Int, x: Int, y: Int) {}
	fun onChain(chain: Int) {}
	fun onFunction(f: Int) {}
}
