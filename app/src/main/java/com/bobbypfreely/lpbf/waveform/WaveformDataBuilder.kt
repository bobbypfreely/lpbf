package com.bobbypfreely.lpbf.waveform

import com.bobbypfreely.lpbf.audio.DecodedAudio
import kotlin.math.abs
import kotlin.math.sqrt

/** Frame-gain data ready to hand to WaveformView.setAudioData(). */
data class FrameGainData(
	val numFrames: Int,
	val frameGains: IntArray,
	val sampleRate: Int,
	val samplesPerFrame: Int,
)

/**
 * Computes waveform gain values directly from our own decoded PCM, replacing Ringdroid's
 * SoundFile decode step entirely (we already have PCM via AudioDecoder). The actual gain
 * algorithm itself IS ported from Ringdroid's SoundFile.java: split into fixed-size blocks
 * (1024 samples), take the max per-block amplitude (averaged across channels), sqrt it for
 * perceptual scaling. WaveformView's own zoom/smoothing pipeline handles the rest.
 */
object WaveformDataBuilder {

	const val SAMPLES_PER_FRAME = 1024

	fun build(audio: DecodedAudio): FrameGainData {
		val channels = audio.channels
		val totalSamples = audio.totalFrames // samples per channel
		val bytesPerSample = 2
		val bytesPerPcmFrame = channels * bytesPerSample
		val pcm = audio.pcm

		var numFrames = totalSamples / SAMPLES_PER_FRAME
		if (totalSamples % SAMPLES_PER_FRAME != 0) numFrames++
		if (numFrames < 1) numFrames = 1

		val frameGains = IntArray(numFrames)
		var pcmIndex = 0

		for (i in 0 until numFrames) {
			var gain = -1
			for (j in 0 until SAMPLES_PER_FRAME) {
				if (pcmIndex + bytesPerPcmFrame > pcm.size) break
				var value = 0
				for (k in 0 until channels) {
					val byteOffset = pcmIndex + k * bytesPerSample
					val sample = (((pcm[byteOffset + 1].toInt() and 0xFF) shl 8) or
						(pcm[byteOffset].toInt() and 0xFF)).toShort().toInt()
					value += abs(sample)
				}
				value /= channels
				if (gain < value) gain = value
				pcmIndex += bytesPerPcmFrame
			}
			if (gain < 0) gain = 0
			frameGains[i] = sqrt(gain.toDouble()).toInt()
		}

		return FrameGainData(numFrames, frameGains, audio.sampleRate, SAMPLES_PER_FRAME)
	}
}
