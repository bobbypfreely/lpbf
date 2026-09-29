package com.bobbypfreely.lpbf.harness

import android.content.Context
import com.bobbypfreely.lpbf.lightshow.Pattern
import com.bobbypfreely.lpbf.ui.VirtualLaunchpadGridView

/**
 * Modular wiring board for LPBF.
 *
 * Plug modules in once via [assemble]; MainActivity / ViewModel only call
 * [setSource], [fire], [release]. Swap any module (sound, lights, map, autoplay)
 * without touching the others.
 *
 *   val harness = LpbfHarness.assemble(context, gridProvider, isLightsMode)
 *   harness.setSource(cachedWavPath)
 *   harness.fire(startMs, endMs, pattern)  // audio + lights; polyphony on audio
 *   harness.release()
 */
class LpbfHarness(
	val sound: SoundModule,
	val lights: LightModule,
	val map: MapModule? = null,
	val autoPlay: AutoPlayModule? = null,
) {
	private var sourcePath: String? = null

	fun setSource(path: String?) {
		sourcePath = path
		if (path != null) sound.attachSource(path)
	}

	/**
	 * Fire a clip: sound plays out on its own voice (overlaps OK);
	 * lights play alongside. Second fire does not stop the first sound.
	 */
	fun fire(startMs: Int, endMs: Int, pattern: Pattern? = null) {
		val dur = (endMs - startMs).coerceAtLeast(1)
		val path = sourcePath
		if (path != null) {
			sound.attachSource(path)
			sound.fire(startMs.coerceAtLeast(0), dur)
		}
		lights.play(pattern, dur)
	}

	fun fire(clip: ClipFire) = fire(clip.startMs, clip.endMs, clip.pattern)

	/** Pad hit in PLAY: resolve map → fire. Returns false if nothing mapped. */
	fun playPad(chain: Int, x: Int, y: Int): Boolean {
		val clip = map?.clipForPad(chain, x, y) ?: return false
		fire(clip)
		return true
	}

	fun lightsBusy(): Boolean = (lights as? LightModuleImpl)?.isBusy == true

	fun release() {
		sound.release()
		lights.release()
	}

	companion object {
		/**
		 * One-call default stack. Pass custom modules to replace any piece.
		 */
		fun assemble(
			context: Context,
			grid: () -> VirtualLaunchpadGridView?,
			isLightsAuthoring: () -> Boolean = { false },
			onLightsBusy: (Boolean) -> Unit = {},
			sound: SoundModule = SoundModuleImpl(context),
			lights: LightModule = LightModuleImpl(grid, isLightsAuthoring, onLightsBusy),
			map: MapModule? = null,
			autoPlay: AutoPlayModule? = null,
		): LpbfHarness = LpbfHarness(sound, lights, map, autoPlay)
	}
}
