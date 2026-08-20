package com.bobbypfreely.lpbf.audio

import com.bobbypfreely.lpbf.marking.CommittedClip

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
 */
object ClipExporter {

	fun export(audio: DecodedAudio, clips: List<CommittedClip>): List<ExportedClip> {
		return clips.map { clip ->
			val (pcmSlice, preciseDurationMs) = audio.slice(clip.startMs, clip.endMs)
			val wav = WavWriter.wrap(pcmSlice, audio.sampleRate, audio.channels)
			ExportedClip(
				fileName = clip.fileName,
				wavBytes = wav,
				preciseDurationMs = preciseDurationMs,
				button = clip.button,
			)
		}
	}
}
