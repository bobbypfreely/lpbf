package com.bobbypfreely.lpbf.harness

import android.content.Context
import com.bobbypfreely.lpbf.lightshow.Pattern
import com.bobbypfreely.lpbf.ui.VirtualLaunchpadGridView

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

	fun fireFile(path: String, durationMs: Int = 0) {
		sound.playFile(path, durationMs)
	}

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
