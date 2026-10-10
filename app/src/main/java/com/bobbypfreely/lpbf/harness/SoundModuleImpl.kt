package com.bobbypfreely.lpbf.harness

import android.content.Context
import com.bobbypfreely.lpbf.audio.PadVoicePool

/** Drop-in SoundModule over the existing PadVoicePool (16 voices, discrete file AP). */
class SoundModuleImpl(
	context: Context,
	voiceCount: Int = 16,
) : SoundModule {
	private val pool = PadVoicePool(context, voiceCount)

	override fun attachSource(path: String) = pool.ensureLoaded(path)

	override fun fire(startMs: Int, durationMs: Int) {
		pool.play(startMs, durationMs)
	}

	override fun playFile(path: String, durationMs: Int) {
		pool.playFile(path, durationMs)
	}

	override fun release() = pool.release()
}
