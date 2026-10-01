package com.bobbypfreely.lpbf.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.bobbypfreely.lpbf.waveform.ExoPlaybackController

/**
 * Polyphonic pad voices. ExoPlayers are created only on first [play] so Activity
 * open does not allocate 8 players (that was crashing cold start on some devices).
 * Each fire plays out on its own voice; a new fire does not stop others.
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
	private var voices: ArrayList<Voice>? = null
	private var loadedPath: String? = null
	private var pathLoadedOnVoices: String? = null

	private fun ensureVoices(): ArrayList<Voice> {
		voices?.let { return it }
		val created = ArrayList<Voice>(maxVoices)
		repeat(maxVoices) { created.add(Voice(ExoPlaybackController(appContext))) }
		voices = created
		return created
	}

	private fun applySource(list: ArrayList<Voice>, path: String) {
		if (pathLoadedOnVoices == path) return
		list.forEach { voice ->
			voice.stopHandler.removeCallbacksAndMessages(null)
			try { voice.controller.load(path) } catch (_: Exception) { }
			voice.busyUntilElapsed = 0L
		}
		pathLoadedOnVoices = path
	}

	fun ensureLoaded(path: String) {
		loadedPath = path
		voices?.let { applySource(it, path) }
	}

	fun play(startMs: Int, durationMs: Int) {
		val path = loadedPath ?: return
		val list = ensureVoices()
		applySource(list, path)

		val dur = durationMs.coerceAtLeast(1)
		val now = SystemClock.elapsedRealtime()
		val voice = list.filter { it.busyUntilElapsed <= now }.minByOrNull { it.busyUntilElapsed }
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
		pathLoadedOnVoices = null
	}
}
