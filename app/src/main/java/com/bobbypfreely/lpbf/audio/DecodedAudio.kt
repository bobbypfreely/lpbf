package com.bobbypfreely.lpbf.audio

import kotlin.math.roundToInt

/**
 * Fully-decoded PCM audio for one source track, kept in memory. Tracks are short
 * enough (a song, not an album) that decoding whole-file up front is simpler and
 * more reliable than streaming, and it's what makes scrub/preview playback in
 * Phase 1 tap-marking responsive.
 */
class DecodedAudio(
	val pcm: ByteArray,      // interleaved 16-bit little-endian samples
	val sampleRate: Int,
	val channels: Int,
) {
	private val bitsPerSample = 16
	private val bytesPerFrame = channels * bitsPerSample / 8

	val totalFrames: Int get() = pcm.size / bytesPerFrame
	val totalDurationMs: Int get() = (totalFrames.toLong() * 1000 / sampleRate).toInt()

	fun msToFrame(ms: Int): Int = (ms / 1000.0 * sampleRate).roundToInt()

	/**
	 * Slices PCM for [startMs, endMs) and returns both the raw bytes and the
	 * PRECISE real duration in ms derived from the actual frame count -- not the
	 * nominal endMs-startMs, which can be off by a fraction of a ms depending on
	 * sample rate. That precise value is what must feed PatternCompiler later.
	 */
	fun slice(startMs: Int, endMs: Int): Pair<ByteArray, Int> {
		require(startMs in 0..totalDurationMs) { "startMs $startMs out of range" }
		require(endMs in startMs..totalDurationMs) { "endMs $endMs out of range" }

		val startFrame = msToFrame(startMs)
		val endFrame = msToFrame(endMs)
		val startByte = startFrame * bytesPerFrame
		val endByte = endFrame * bytesPerFrame

		val slice = pcm.copyOfRange(startByte, endByte)
		val realFrames = endFrame - startFrame
		val realDurationMs = (realFrames.toLong() * 1000 / sampleRate).toInt()
		return slice to realDurationMs
	}
}
