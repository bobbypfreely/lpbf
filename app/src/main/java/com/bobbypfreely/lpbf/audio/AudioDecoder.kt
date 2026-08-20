package com.bobbypfreely.lpbf.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Decodes a full audio file (any format MediaCodec supports - mp3, aac, wav, etc.)
 * into raw 16-bit PCM held in memory, via MediaExtractor + MediaCodec.
 *
 * NOTE: standard Android decode boilerplate, but written without an Android runtime
 * available to actually run it. Needs real-device verification before trusting it,
 * particularly the end-of-stream draining logic and output format timing.
 */
object AudioDecoder {

	fun decode(filePath: String): DecodedAudio {
		val extractor = MediaExtractor()
		extractor.setDataSource(filePath)

		var trackIndex = -1
		var format: MediaFormat? = null
		for (i in 0 until extractor.trackCount) {
			val f = extractor.getTrackFormat(i)
			val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
			if (mime.startsWith("audio/")) {
				trackIndex = i
				format = f
				break
			}
		}
		require(trackIndex >= 0 && format != null) { "No audio track found in $filePath" }
		extractor.selectTrack(trackIndex)

		val mime = format.getString(MediaFormat.KEY_MIME)!!
		val codec = MediaCodec.createDecoderByType(mime)
		codec.configure(format, null, null, 0)
		codec.start()

		val pcmOut = ByteArrayOutputStream()
		var outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
		var outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

		val bufferInfo = MediaCodec.BufferInfo()
		var sawInputEos = false
		var sawOutputEos = false
		val timeoutUs = 10_000L

		while (!sawOutputEos) {
			// Feed input
			if (!sawInputEos) {
				val inIndex = codec.dequeueInputBuffer(timeoutUs)
				if (inIndex >= 0) {
					val inBuffer: ByteBuffer = codec.getInputBuffer(inIndex)!!
					val sampleSize = extractor.readSampleData(inBuffer, 0)
					if (sampleSize < 0) {
						codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
						sawInputEos = true
					} else {
						val presentationTimeUs = extractor.sampleTime
						codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
						extractor.advance()
					}
				}
			}

			// Drain output
			val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
			when {
				outIndex >= 0 -> {
					val outBuffer: ByteBuffer = codec.getOutputBuffer(outIndex)!!
					if (bufferInfo.size > 0) {
						val chunk = ByteArray(bufferInfo.size)
						outBuffer.position(bufferInfo.offset)
						outBuffer.limit(bufferInfo.offset + bufferInfo.size)
						outBuffer.get(chunk)
						pcmOut.write(chunk)
					}
					codec.releaseOutputBuffer(outIndex, false)
					if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						sawOutputEos = true
					}
				}
				outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
					val newFormat = codec.outputFormat
					outputSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
					outputChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
				}
				// INFO_TRY_AGAIN_LATER: just loop again
			}
		}

		codec.stop()
		codec.release()
		extractor.release()

		return DecodedAudio(pcmOut.toByteArray(), outputSampleRate, outputChannels)
	}
}
