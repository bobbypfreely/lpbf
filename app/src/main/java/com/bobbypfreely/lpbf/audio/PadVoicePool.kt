package com.bobbypfreely.lpbf.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.bobbypfreely.lpbf.waveform.ExoPlaybackController

/**
 * Small pool of ExoPlayers so multiple pad clips can overlap (polyphony).
 *
 * The old single-preview path reused one player and cancelled its stop callback on
 * every new hit — second pad always killed the first. Here each voice has its own
 * stop timer; firing a new pad never pauses the others. When all voices are busy,
 * the oldest (soonest-to-finish) is stolen.
 *
 * All voices share the same concatenated import file path (seek + play a range).
 * Main-thread only (ExoPlayer requirement).
 */
class PadVoicePool(
	context: Context,
	voiceCount: Int = 8,
) {
	private data class Voice(
		val controller: ExoPlaybackController,
		val stopHandler: Handler = Handler(Looper.getMainLooper()),
		var busyUntilElapsed: Long = 0L,
		var generation: Int = 0,
	)

	private val appContext = context.applicationContext
	private val voices = List(voiceCount.coerceIn(2, 16)) {
		Voice(ExoPlaybackController(appContext))
	}
	private var loadedPath: String? = null

	fun ensureLoaded(path: String) {
		if (path == loadedPath) return
		loadedPath = path
		voices.forEach { voice ->
			voice.stopHandler.removeCallbacksAndMessages(null)
			voice.controller.load(path)
			voice.busyUntilElapsed = 0L
		}
	}

	/** Play [durationMs] starting at [startMs] on a free (or stolen) voice. */
	fun play(startMs: Int, durationMs: Int) {
		if (loadedPath == null) return
		val dur = durationMs.coerceAtLeast(1)
		val now = SystemClock.elapsedRealtime()
		// Prefer free voice; otherwise steal the one that finishes soonest
		val voice = voices
			.filter { it.busyUntilElapsed <= now }
			.minByOrNull { it.busyUntilElapsed }
			?: voices.minByOrNull { it.busyUntilElapsed }
			?: return

		voice.stopHandler.removeCallbacksAndMessages(null)
		voice.generation += 1
		val gen = voice.generation
		voice.controller.playFrom(startMs.coerceAtLeast(0))
		voice.busyUntilElapsed = now + dur
		voice.stopHandler.postDelayed({
			if (voice.generation == gen) {
				voice.controller.pause()
			}
		}, dur.toLong())
	}

	fun release() {
		voices.forEach { voice ->
			voice.stopHandler.removeCallbacksAndMessages(null)
			voice.controller.release()
		}
		loadedPath = null
	}
}
