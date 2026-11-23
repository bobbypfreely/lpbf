package com.bobbypfreely.lpbf.waveform

import java.io.File

object AlsReader {
	data class AlsClipMark(val timeMs: Int, val durationMs: Int = 0, val midiNote: Int? = null, val velocity: Int = 100)
	data class AlsAudioRef(val rawPath: String, val fileName: String)
	data class AlsReadResult(
		val title: String,
		val bpm: Double,
		val audioFile: File?,
		val allAudioRefs: List<AlsAudioRef>,
		val marks: List<AlsClipMark>,
		val warnings: List<String>,
	)

	fun read(alsFile: File, projectDir: File = alsFile.parentFile ?: alsFile): AlsReadResult =
		AlsReadResult(alsFile.nameWithoutExtension, 120.0, null, emptyList(), emptyList(), listOf("AlsReader stub"))
}
