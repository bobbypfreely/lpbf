package com.bobbypfreely.lpbf.audio

import com.bobbypfreely.lpbf.marking.CommittedClip
import kotlin.math.min

/** A CommittedClip with real audio attached: the actual WAV bytes and the precise real duration. */
data class ExportedClip(
	val fileName: String,        // "001.wav", "002.wav"...
	val wavBytes: ByteArray,
	val preciseDurationMs: Int,  // derived from real frame count, NOT the nominal mark timestamps
	val button: com.bobbypfreely.lpbf.marking.ButtonRef?,
)

/**
 * Runs after MarkingSession.splice() succeeds: turns each CommittedClip's timestamps
 * into a real WAV file sliced from the decoded source track.
 *
 * Applies a short linear fade-in / fade-out (default 10 ms) on every clip so sequential
 * pad hits don't click/pop at the splice points. Fade is pure PCM math — Unipack format
 * is unchanged.
 */
object ClipExporter {

	/** Fade length in milliseconds. Keep short so short cuts still have body. */
	private const val FADE_MS = 10

	fun export(audio: DecodedAudio, clips: List<CommittedClip>): List<ExportedClip> {
		return clips.map { clip ->
			val (pcmSlice, preciseDurationMs) = audio.slice(clip.startMs, clip.endMs)
			val faded = applyFade(pcmSlice, audio.sampleRate, audio.channels, FADE_MS)
			val wav = WavWriter.wrap(faded, audio.sampleRate, audio.channels)
			ExportedClip(
				fileName = clip.fileName,
				wavBytes = wav,
				preciseDurationMs = preciseDurationMs,
				button = clip.button,
			)
		}
	}

	/**
	 * Linear fade-in at the start and fade-out at the end of 16-bit little-endian
	 * interleaved PCM. Safe on short clips (fade is clamped to half the clip length).
	 */
	private fun applyFade(pcm: ByteArray, sampleRate: Int, channels: Int, fadeMs: Int): ByteArray {
		if (pcm.isEmpty() || fadeMs <= 0) return pcm
		val bytesPerFrame = channels * 2
		val totalFrames = pcm.size / bytesPerFrame
		if (totalFrames < 4) return pcm

		var fadeFrames = (sampleRate * fadeMs / 1000).coerceAtLeast(1)
		fadeFrames = min(fadeFrames, totalFrames / 2)

		val out = pcm.copyOf()
		// Fade-in
		for (f in 0 until fadeFrames) {
			val gain = f.toFloat() / fadeFrames
			scaleFrame(out, f, channels, gain)
		}
		// Fade-out
		for (f in 0 until fadeFrames) {
			val frameIndex = totalFrames - 1 - f
			val gain = f.toFloat() / fadeFrames
			scaleFrame(out, frameIndex, channels, gain)
		}
		return out
	}

	private fun scaleFrame(pcm: ByteArray, frameIndex: Int, channels: Int, gain: Float) {
		val base = frameIndex * channels * 2
		for (ch in 0 until channels) {
			val i = base + ch * 2
			if (i + 1 >= pcm.size) return
			val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
			val signed = sample.toShort().toInt()
			val scaled = (signed * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
			pcm[i] = (scaled and 0xFF).toByte()
			pcm[i + 1] = ((scaled shr 8) and 0xFF).toByte()
		}
	}
}
