package com.bobbypfreely.lpbf.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread

/**
 * Drives playback for Phase 1 live tap-marking: play from an arbitrary position,
 * stop and report exactly where playback was when stopped (that position becomes
 * the new mark). Also unverified against a real device - AudioTrack timing/thread
 * lifecycle in particular needs on-device confirmation.
 */
class AudioPlaybackController(private val audio: DecodedAudio) {

	private var audioTrack: AudioTrack? = null
	private var playThread: Thread? = null

	@Volatile private var currentFrame = 0
	@Volatile private var stopRequested = false

	val isPlaying: Boolean get() = playThread?.isAlive == true

	/** Starts playback from [fromMs]. No-op if already playing. */
	fun playFrom(fromMs: Int) {
		if (isPlaying) return
		stopRequested = false
		currentFrame = audio.msToFrame(fromMs)

		val channelConfig = if (audio.channels == 1) {
			AudioFormat.CHANNEL_OUT_MONO
		} else {
			AudioFormat.CHANNEL_OUT_STEREO
		}
		val minBufferSize = AudioTrack.getMinBufferSize(
			audio.sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
		)

		val track = AudioTrack.Builder()
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

		audioTrack = track
		track.play()

		val bytesPerFrame = audio.channels * 2
		playThread = thread(name = "lpbf-playback") {
			var frame = currentFrame
			val chunkFrames = 4096
			while (!stopRequested && frame < audio.totalFrames) {
				val framesToWrite = minOf(chunkFrames, audio.totalFrames - frame)
				val byteOffset = frame * bytesPerFrame
				val byteLength = framesToWrite * bytesPerFrame
				track.write(audio.pcm, byteOffset, byteLength)
				frame += framesToWrite
				currentFrame = frame
			}
			track.stop()
		}
	}

	/** Stops playback and returns the exact position (ms) it stopped at - this becomes the new mark. */
	fun stop(): Int {
		stopRequested = true
		playThread?.join(500)
		audioTrack?.release()
		audioTrack = null
		return currentPositionMs()
	}

	fun currentPositionMs(): Int {
		return (currentFrame.toLong() * 1000 / audio.sampleRate).toInt()
	}
}
