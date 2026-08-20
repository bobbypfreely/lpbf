package com.bobbypfreely.lpbf.audio

import java.io.ByteArrayOutputStream

/** Writes standard 44-byte PCM WAV headers. Pure byte-math, no Android dependencies. */
object WavWriter {

	/**
	 * Wraps raw 16-bit PCM frame data with a WAV header and returns the complete file bytes.
	 * [pcm] must already be interleaved-by-channel 16-bit little-endian samples.
	 */
	fun wrap(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int = 16): ByteArray {
		val blockAlign = channels * bitsPerSample / 8
		val byteRate = sampleRate * blockAlign
		val dataSize = pcm.size

		val out = ByteArrayOutputStream(44 + dataSize)
		out.writeAscii("RIFF")
		out.writeLE32(36 + dataSize)
		out.writeAscii("WAVE")
		out.writeAscii("fmt ")
		out.writeLE32(16)                 // fmt chunk size
		out.writeLE16(1)                   // PCM format
		out.writeLE16(channels)
		out.writeLE32(sampleRate)
		out.writeLE32(byteRate)
		out.writeLE16(blockAlign)
		out.writeLE16(bitsPerSample)
		out.writeAscii("data")
		out.writeLE32(dataSize)
		out.write(pcm)
		return out.toByteArray()
	}

	private fun ByteArrayOutputStream.writeAscii(s: String) = write(s.toByteArray(Charsets.US_ASCII))

	private fun ByteArrayOutputStream.writeLE16(v: Int) {
		write(v and 0xFF)
		write((v shr 8) and 0xFF)
	}

	private fun ByteArrayOutputStream.writeLE32(v: Int) {
		write(v and 0xFF)
		write((v shr 8) and 0xFF)
		write((v shr 16) and 0xFF)
		write((v shr 24) and 0xFF)
	}
}
