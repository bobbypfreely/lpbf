package com.bobbypfreely.lpbf.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread

/**
 * Drives playback for Phase 1 live tap-marking: play from an arbitrary position,
 * stop and report exactly where playback was when stopped (that position becomes
 * the new mark).
 *
 * Lifecycle ownership is deliberate: the playback thread ALONE creates, writes to,
 * stops, and releases the AudioTrack, start to finish (in a try/finally so it happens
 * whether the loop exits naturally or via stopRequested). stop() on the calling thread
 * only flips a flag and joins -- it never touches the AudioTrack directly. That avoids
 * a release() on one thread racing a write()/stop() on the other, which is what was
 * crashing the app on pad release.
 */
class AudioPlaybackController(private val audio: DecodedAudio) {

	private var playThread: Thread? = null

	@Volatile private var currentFrame = 0
	@Volatile private var stopRequested = false

	val isPlaying: Boolean get() = playThread?.isAlive == true

	/** Starts playback from [fromMs]. No-op if already playing. */
	fun playFrom(fromMs: Int) {
		if (isPlaying) return
		stopRequested = false
		currentFrame = audio.msToFrame(fromMs).coerceIn(0, audio.totalFrames)

		playThread = thread(name = "lpbf-playback") {
			var track: AudioTrack? = null
			try {
				val channelConfig = if (audio.channels == 1) {
					AudioFormat.CHANNEL_OUT_MONO
				} else {
					AudioFormat.CHANNEL_OUT_STEREO
				}
				val minBufferSize = AudioTrack.getMinBufferSize(
					audio.sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
				)
				if (minBufferSize <= 0) {
					Log.e("AudioPlaybackController", "Invalid minBufferSize=$minBufferSize, aborting playback")
					return@thread
				}

				track = AudioTrack.Builder()
					.setAudioAttributes(
						AudioAttributes.Builder()
							.setUsage(AudioAttributes.USAGE_MEDIA)
							.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
							.build()
					)
					.setAudioFormat(
						AudioFormat.Builder()
							.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
							.setSampleRate(audio.sampleRate)
							.setChannelMask(channelConfig)
							.build()
					)
					.setBufferSizeInBytes(minBufferSize)
					.setTransferMode(AudioTrack.MODE_STREAM)
					.build()

				track.play()

				val bytesPerFrame = audio.channels * 2
				// Smaller chunks = more frequent stopRequested checks = snappier, safer stop().
				val chunkFrames = 1024
				var frame = currentFrame
				while (!stopRequested && frame < audio.totalFrames) {
					val framesToWrite = minOf(chunkFrames, audio.totalFrames - frame)
					val byteOffset = frame * bytesPerFrame
					val byteLength = framesToWrite * bytesPerFrame
					track.write(audio.pcm, byteOffset, byteLength)
					frame += framesToWrite
					currentFrame = frame
				}
			} catch (e: Exception) {
				// Never let a playback error crash the app -- log it and stop cleanly.
				Log.e("AudioPlaybackController", "Playback error", e)
			} finally {
				try {
					track?.stop()
				} catch (e: Exception) {
					Log.e("AudioPlaybackController", "Error stopping track", e)
				}
				try {
					track?.release()
				} catch (e: Exception) {
					Log.e("AudioPlaybackController", "Error releasing track", e)
				}
			}
		}
	}

	/** Stops playback and returns the exact position (ms) it stopped at - this becomes the new mark. */
	fun stop(): Int {
		stopRequested = true
		try {
			playThread?.join(2000)
		} catch (e: InterruptedException) {
			Log.e("AudioPlaybackController", "Interrupted waiting for playback thread to stop", e)
		}
		return currentPositionMs()
	}

	fun currentPositionMs(): Int {
		return (currentFrame.toLong() * 1000 / audio.sampleRate).toInt()
	}
}
