package com.bobbypfreely.lpbf.waveform

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Wraps ExoPlayer for the Mark and Cut screen's play/pause/seek needs, replacing the
 * hand-rolled MediaCodec/AudioTrack code that was crashing on real hardware (the
 * "Cannot create AudioTrack" issue). ExoPlayer handles device/OEM quirks we were
 * hitting ourselves, and plays directly from the local cached file path.
 *
 * Must be created and used on the main thread (ExoPlayer requirement).
 */
class ExoPlaybackController(context: Context) {

	private val player = ExoPlayer.Builder(context).build()

	var onPositionUpdate: ((Int) -> Unit)? = null
	var onPlaybackStateChanged: ((Boolean) -> Unit)? = null

	/** Raw ExoPlayer state/duration/position, piped to the on-screen debug log so a
	 * silent stall (no crash, no error) can actually be diagnosed instead of guessed at. */
	var onDebugEvent: ((String) -> Unit)? = null

	init {
		// This is a preview/editing tool, not a background media app, and more than one
		// ExoPlayer instance can be alive at once (Mark & Cut's own player plus Place's
		// separate preview player). Without this, ExoPlayer's default audio-focus
		// handling means one instance starting playback silently force-pauses the
		// other via an audio focus loss callback -- which looks exactly like "Play just
		// stops working" with no error anywhere. Not requesting focus at all makes every
		// Play/preview action do exactly what it says, regardless of what else in the
		// app might be playing.
		player.setAudioAttributes(
			AudioAttributes.Builder()
				.setUsage(C.USAGE_MEDIA)
				.setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
				.build(),
			/* handleAudioFocus = */ false
		)

		player.addListener(object : Player.Listener {
			override fun onIsPlayingChanged(isPlaying: Boolean) {
				onPlaybackStateChanged?.invoke(isPlaying)
			}

			override fun onPlaybackStateChanged(state: Int) {
				val stateName = when (state) {
					Player.STATE_IDLE -> "IDLE"
					Player.STATE_BUFFERING -> "BUFFERING"
					Player.STATE_READY -> "READY"
					Player.STATE_ENDED -> "ENDED"
					else -> "UNKNOWN($state)"
				}
				onDebugEvent?.invoke(
					"ExoPlayer state=$stateName player.duration=${player.duration}ms " +
						"position=${player.currentPosition}ms playWhenReady=${player.playWhenReady}"
				)
			}

			override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
				onDebugEvent?.invoke("ExoPlayer ERROR: ${error.errorCodeName}: ${error.message}")
			}
		})
	}

	fun load(filePath: String) {
		val file = java.io.File(filePath)
		onDebugEvent?.invoke("ExoPlaybackController.load: $filePath (${file.length()} bytes on disk)")
		player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
		player.prepare()
	}

	fun playFrom(ms: Int) {
		player.seekTo(ms.toLong())
		player.play()
	}

	fun pause() {
		player.pause()
	}

	/** Stops playback and returns the exact position (ms) it stopped at. */
	fun stop(): Int {
		val pos = currentPositionMs()
		player.pause()
		return pos
	}

	fun seekTo(ms: Int) {
		player.seekTo(ms.toLong())
	}

	fun currentPositionMs(): Int = player.currentPosition.toInt()

	val isPlaying: Boolean get() = player.isPlaying

	fun release() {
		player.release()
	}
}
