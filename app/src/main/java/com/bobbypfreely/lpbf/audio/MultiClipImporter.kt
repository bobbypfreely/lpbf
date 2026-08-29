package com.bobbypfreely.lpbf.audio

import com.bobbypfreely.lpbf.marking.ButtonRef
import java.io.ByteArrayOutputStream
import java.io.File

/** One audio source to fold into a concatenated import timeline, with an optional
 * pre-existing button assignment (used when importing a Unipack's keySound mapping;
 * left null for a plain "import pre-cut tracks" pick where nothing's mapped yet). */
data class ImportClipSource(val filePath: String, val button: ButtonRef? = null)

data class MultiClipImportResult(
	val decodedAudio: DecodedAudio,
	val cachedFilePath: String,
	val marks: List<Int>,          // includes leading 0, same format MarkingSession.restore() expects
	val buttons: List<ButtonRef?>, // one per segment, size == marks.size - 1
	val skipped: List<String>,     // human-readable reasons any source didn't make it in
)

/**
 * Decodes each source in order and concatenates them into ONE synthetic PCM timeline,
 * auto-generating a mark at every boundary. This is what lets "already cut" audio --
 * whether from a plain folder of files, or read out of an imported Unipack's keySound
 * table -- reuse the exact same MarkingSession/Place/Splice pipeline as anything cut
 * by hand on Mark & Cut, with zero changes to that pipeline.
 */
object MultiClipImporter {

	fun buildConcatenatedImport(sources: List<ImportClipSource>, cacheDir: File): MultiClipImportResult {
		require(sources.isNotEmpty()) { "No sources to import" }

		var referenceSampleRate = -1
		var referenceChannels = -1
		val pcmOut = ByteArrayOutputStream()
		val marks = mutableListOf(0)
		val buttons = mutableListOf<ButtonRef?>()
		val skipped = mutableListOf<String>()
		var cumulativeMs = 0

		for (source in sources) {
			val decoded = try {
				AudioDecoder.decode(source.filePath)
			} catch (e: Exception) {
				skipped.add("${File(source.filePath).name}: ${e.message}")
				continue
			}

			if (referenceSampleRate == -1) {
				referenceSampleRate = decoded.sampleRate
				referenceChannels = decoded.channels
			} else if (decoded.sampleRate != referenceSampleRate || decoded.channels != referenceChannels) {
				skipped.add(
					"${File(source.filePath).name}: ${decoded.sampleRate}Hz/${decoded.channels}ch doesn't match " +
						"the rest of this import (${referenceSampleRate}Hz/${referenceChannels}ch)"
				)
				continue
			}

			pcmOut.write(decoded.pcm)
			cumulativeMs += decoded.totalDurationMs
			marks.add(cumulativeMs)
			buttons.add(source.button)
		}

		check(referenceSampleRate != -1) { "Every source failed to decode -- nothing to import" }

		val combinedPcm = pcmOut.toByteArray()
		val combinedAudio = DecodedAudio(combinedPcm, referenceSampleRate, referenceChannels)

		val wavBytes = WavWriter.wrap(combinedPcm, referenceSampleRate, referenceChannels)
		val outFile = File(cacheDir, "lpbf_import_${System.currentTimeMillis()}.wav")
		outFile.writeBytes(wavBytes)

		return MultiClipImportResult(
			decodedAudio = combinedAudio,
			cachedFilePath = outFile.absolutePath,
			marks = marks,
			buttons = buttons,
			skipped = skipped,
		)
	}
}
