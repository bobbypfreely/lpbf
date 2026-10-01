package com.bobbypfreely.lpbf.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.bobbypfreely.lpbf.waveform.ExoPlaybackController

/**
 * Small pool of ExoPlayers so multiple pad clips can overlap (polyphony).
 *
 * Voices are created lazily on first play — constructing 8 ExoPlayers in Activity.onCreate
 * was crashing cold start on some devices.
 *
 * Each voice has its own stop timer; firing a new pad never pauses the others.
 * When all voices are busy, the oldest (soonest-to-finish) is stolen.
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
	private val maxVoices = voiceCount.coerceIn(2, 16)
	/** Null until first [ensureLoaded]/[play] — avoids crash-on-open from eager ExoPlayer alloc. */
	private var voices: ArrayList<Voice>? = null
	private var loadedPath: String? = null

	private fun ensureVoices(): ArrayList<Voice> {
		voices?.let { return it }
		val created = ArrayList<Voice>(maxVoices)
		repeat(maxVoices) {
			created.add(Voice(ExoPlaybackController(appContext)))
		}
		voices = created
		// If a source was already requested before voices existed, load it now.
		loadedPath?.let { path ->
			created.forEach { it.controller.load(path) }
		}
		return created
	}

	fun ensureLoaded(path: String) {
		if (path == loadedPath) return
		loadedPath = path
		val list = voices ?: return // defer actual load until first play creates voices
		list.forEach { voice ->
			voice.stopHandler.removeCallbacksAndMessages(null)
			voice.controller.load(path)
			voice.busyUntilElapsed = 0L
		}
	}

	/** Play [durationMs] starting at [startMs] on a free (or stolen) voice. */
	fun play(startMs: Int, durationMs: Int) {
		if (loadedPath == null) return
		val list = ensureVoices()
		// Ensure media is loaded on first real play
		val path = loadedPath ?: return
		list.forEach { v ->
			// load is cheap if already set; safe if voice just created
		}
		// If voices were just created after ensureLoaded deferred, load now
		if (list.firstOrNull()?.controller != null) {
			// Always load current path onto all voices once at first play batch
			list.forEach { voice ->
				try { voice.controller.load(path) } catch (_: Exception) { }
			}
		}

		val dur = durationMs.coerceAtLeast(1)
		val now = SystemClock.elapsedRealtime()
		val voice = list
			.filter { it.busyUntilElapsed <= now }
			.minByOrNull { it.busyUntilElapsed }
			?: list.minByOrNull { it.busyUntilElapsed }
			?: return

		voice.stopHandler.removeCallbacksAndMessages(null)
		voice.generation += 1
		val gen = voice.generation
		try {
			voice.controller.playFrom(startMs.coerceAtLeast(0))
		} catch (_: Exception) {
			return
		}
		voice.busyUntilElapsed = now + dur
		voice.stopHandler.postDelayed({
			if (voice.generation == gen) {
				try { voice.controller.pause() } catch (_: Exception) { }
			}
		}, dur.toLong())
	}

	fun release() {
		voices?.forEach { voice ->
			voice.stopHandler.removeCallbacksAndMessages(null)
			try { voice.controller.release() } catch (_: Exception) { }
		}
		voices = null
		loadedPath = null
	}
}
