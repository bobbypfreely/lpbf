package com.bobbypfreely.lpbf.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.bobbypfreely.lpbf.waveform.ExoPlaybackController

/** Polyphonic pad voices — shared concat source or discrete Unipack WAV files. */
class PadVoicePool(
	context: Context,
	voiceCount: Int = 16,
) {
	private data class Voice(
		val controller: ExoPlaybackController,
		val stopHandler: Handler = Handler(Looper.getMainLooper()),
		var busyUntilElapsed: Long = 0L,
		var generation: Int = 0,
		var loadedPath: String? = null,
	)

	private val appContext = context.applicationContext
	private val maxVoices = voiceCount.coerceIn(2, 24)
	private var voices: ArrayList<Voice>? = null
	private var sharedPath: String? = null

	private fun ensureVoices(): ArrayList<Voice> {
		voices?.let { return it }
		val created = ArrayList<Voice>(maxVoices)
		repeat(maxVoices) { created.add(Voice(ExoPlaybackController(appContext))) }
		voices = created
		return created
	}

	private fun pickVoice(list: ArrayList<Voice>, now: Long): Voice {
		return list.filter { it.busyUntilElapsed <= now }.minByOrNull { it.busyUntilElapsed }
			?: list.minByOrNull { it.busyUntilElapsed }
			?: list.first()
	}

	fun ensureLoaded(path: String) {
		sharedPath = path
	}

	fun play(startMs: Int, durationMs: Int) {
		val path = sharedPath ?: return
		val list = ensureVoices()
		val dur = durationMs.coerceAtLeast(1)
		val now = SystemClock.elapsedRealtime()
		val voice = pickVoice(list, now)
		voice.stopHandler.removeCallbacksAndMessages(null)
		voice.generation += 1
		val gen = voice.generation
		try {
			if (voice.loadedPath != path) {
				voice.controller.load(path)
				voice.loadedPath = path
			}
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

	fun playFile(path: String, durationMs: Int = 0) {
		if (path.isBlank()) return
		val list = ensureVoices()
		val now = SystemClock.elapsedRealtime()
		val voice = pickVoice(list, now)
		voice.stopHandler.removeCallbacksAndMessages(null)
		voice.generation += 1
		val gen = voice.generation
		try {
			if (voice.loadedPath != path) {
				voice.controller.load(path)
				voice.loadedPath = path
			}
			voice.controller.playFrom(0)
		} catch (_: Exception) {
			return
		}
		val dur = if (durationMs > 0) durationMs.coerceAtLeast(1) else 60_000
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
		sharedPath = null
	}
}
