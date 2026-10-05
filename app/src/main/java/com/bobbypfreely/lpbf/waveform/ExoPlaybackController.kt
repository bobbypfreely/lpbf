package com.bobbypfreely.lpbf.waveform

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Wraps ExoPlayer for Mark/Cut + pad preview. Created/used on main thread only.
 *
 * handleAudioFocus=false so multiple players (waveform + PadVoicePool) never kill each other.
 * After STATE_ENDED / source errors we re-prepare so Play works again without a full reload.
 */
class ExoPlaybackController(context: Context) {

	private val player = ExoPlayer.Builder(context.applicationContext).build()
	private var loadedPath: String? = null

	var onPositionUpdate: ((Int) -> Unit)? = null
	var onPlaybackStateChanged: ((Boolean) -> Unit)? = null
	var onDebugEvent: ((String) -> Unit)? = null

	init {
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
				onDebugEvent?.invoke(
					"ExoPlayer ERROR: ${error.errorCodeName}: ${error.message} path=$loadedPath"
				)
				try {
					player.stop()
					player.clearMediaItems()
				} catch (_: Exception) {
				}
			}
		})
	}

	fun load(filePath: String) {
		val file = File(filePath)
		if (!file.exists() || !file.isFile || file.length() <= 0L) {
			onDebugEvent?.invoke(
				"ExoPlaybackController.load FAILED: missing or empty file: $filePath " +
					"(exists=${file.exists()} length=${if (file.exists()) file.length() else -1})"
			)
			loadedPath = null
			try {
				player.stop()
				player.clearMediaItems()
			} catch (_: Exception) {
			}
			return
		}
		onDebugEvent?.invoke("ExoPlaybackController.load: $filePath (${file.length()} bytes on disk)")
		loadedPath = file.absolutePath
		val uri = Uri.parse("file://${file.absolutePath}")
		player.setMediaItem(MediaItem.fromUri(uri))
		player.prepare()
	}

	private fun ensureMediaLoaded(): Boolean {
		if (player.mediaItemCount > 0 && player.playbackState != Player.STATE_IDLE) {
			return true
		}
		val path = loadedPath ?: return false
		val file = File(path)
		if (!file.exists() || file.length() <= 0L) {
			onDebugEvent?.invoke("ensureMediaLoaded: file gone: $path")
			return false
		}
		return try {
			player.setMediaItem(MediaItem.fromUri(Uri.parse("file://${file.absolutePath}")))
			player.prepare()
			true
		} catch (e: Exception) {
			onDebugEvent?.invoke("ensureMediaLoaded failed: ${e.message}")
			false
		}
	}

	fun playFrom(ms: Int) {
		if (!ensureMediaLoaded()) {
			onDebugEvent?.invoke("playFrom aborted — no media (path=$loadedPath)")
			return
		}
		val duration = player.duration
		var target = ms.toLong().coerceAtLeast(0L)
		if (duration > 0L && target >= duration) {
			target = 0L
		}
		if (player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) {
			player.seekTo(target)
			player.playWhenReady = true
			if (player.playbackState == Player.STATE_IDLE) {
				player.prepare()
			}
		} else {
			player.seekTo(target)
		}
		player.play()
	}

	fun pause() {
		player.pause()
	}

	fun stop(): Int {
		val pos = currentPositionMs()
		player.pause()
		return pos
	}

	fun seekTo(ms: Int) {
		if (!ensureMediaLoaded()) return
		val duration = player.duration
		var target = ms.toLong().coerceAtLeast(0L)
		if (duration > 0L) target = target.coerceAtMost(duration)
		player.seekTo(target)
		if (player.playbackState == Player.STATE_IDLE) {
			player.prepare()
		}
	}

	fun currentPositionMs(): Int = player.currentPosition.toInt().coerceAtLeast(0)

	val isPlaying: Boolean get() = player.isPlaying

	fun release() {
		loadedPath = null
		try {
			player.release()
		} catch (_: Exception) {
		}
	}
}
