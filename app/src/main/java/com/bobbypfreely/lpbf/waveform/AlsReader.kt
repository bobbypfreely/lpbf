package com.bobbypfreely.lpbf.waveform

import java.io.BufferedInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Ableton Live .als → LPBF translate (phase 1: audio track + cut marks).
 *
 * .als is gzip-compressed UTF-8 XML. Times are in **beats**.
 * ms = beats * 60000 / BPM
 *
 * We do not clone the DAW — we pull the main audio + performance hit times
 * and mark the waveform so the user can map / export a Unipack.
 */
object AlsReader {

	data class AlsClipMark(
		/** Absolute ms on the imported audio timeline */
		val timeMs: Int,
		/** Optional duration of the note/clip in ms (0 if unknown) */
		val durationMs: Int = 0,
		/** MIDI note if this mark came from a MidiNoteEvent */
		val midiNote: Int? = null,
		val velocity: Int = 100,
	)

	data class AlsAudioRef(
		/** Path as written in the XML (relative or absolute) */
		val rawPath: String,
		val fileName: String,
	)

	data class AlsReadResult(
		val title: String,
		val bpm: Double,
		/** Best-guess main audio file (resolved under projectDir when possible) */
		val audioFile: File?,
		/** All audio refs found (for debugging / multi-clip later) */
		val allAudioRefs: List<AlsAudioRef>,
		/** Sorted unique mark times derived from MIDI/clip beats → ms */
		val marks: List<AlsClipMark>,
		val warnings: List<String>,
	)

	private val AUDIO_EXT = listOf(".wav", ".wave", ".aif", ".aiff", ".mp3", ".flac", ".ogg", ".m4a")

	/**
	 * @param alsFile  the .als file (already on disk)
	 * @param projectDir  folder that contains the .als and usually Samples/ — used to resolve relative paths
	 */
	fun read(alsFile: File, projectDir: File = alsFile.parentFile ?: alsFile): AlsReadResult {
		val warnings = mutableListOf<String>()
		val xml = try {
			gunzipToString(alsFile)
		} catch (e: Exception) {
			// Some exports are plain XML
			try {
				alsFile.readText(Charsets.UTF_8)
			} catch (e2: Exception) {
				return AlsReadResult(
					title = alsFile.nameWithoutExtension,
					bpm = 120.0,
					audioFile = null,
					allAudioRefs = emptyList(),
					marks = emptyList(),
					warnings = listOf("Could not read .als: ${e.message}"),
				)
			}
		}

		if (!xml.contains("<Ableton") && !xml.contains("LiveSet")) {
			warnings.add("File does not look like Ableton Live XML")
		}

		val bpm = extractTempo(xml) ?: 120.0.also {
			warnings.add("Tempo not found — assuming 120 BPM")
		}

		val title = extractTitle(xml) ?: alsFile.nameWithoutExtension

		val audioRefs = extractAudioRefs(xml)
		if (audioRefs.isEmpty()) {
			warnings.add("No audio FileRef/SampleRef paths found in XML")
		}

		val audioFile = resolveMainAudio(audioRefs, projectDir, alsFile, warnings)

		// MidiNoteEvent Time/Duration are in beats → ms
		val marks = extractMidiMarks(xml, bpm)
			.ifEmpty {
				// Fallback: AudioClip times if no MIDI
				extractAudioClipMarks(xml, bpm).also {
					if (it.isEmpty()) warnings.add("No MidiNoteEvent or AudioClip times found — no cuts to mark")
					else warnings.add("No MIDI notes — marks from AudioClip times")
				}
			}

		return AlsReadResult(
			title = title,
			bpm = bpm,
			audioFile = audioFile,
			allAudioRefs = audioRefs,
			marks = marks,
			warnings = warnings,
		)
	}

	fun gunzipToString(alsFile: File): String {
		BufferedInputStream(alsFile.inputStream()).use { bis ->
			// .als is gzip; detect magic 1f 8b
			bis.mark(4)
			val b0 = bis.read()
			val b1 = bis.read()
			bis.reset()
			val stream = if (b0 == 0x1f && b1 == 0x8b) {
				GZIPInputStream(bis)
			} else {
				bis
			}
			return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
		}
	}

	/** ms = beats * 60000 / bpm */
	fun beatsToMs(beats: Double, bpm: Double): Int {
		if (bpm <= 0.0) return 0
		return (beats * 60000.0 / bpm).toInt().coerceAtLeast(0)
	}

	// ---- XML field extraction (Live schema varies by version; attribute scan is resilient) ----

	private fun extractTempo(xml: String): Double? {
		// Common: <Manual Value="95" /> inside Tempo; or Tempo attribute
		val patterns = listOf(
			Regex("""<Tempo[^>]*>[\s\S]*?<Manual\s+Value="([0-9.]+)""""),
			Regex("""<Tempo\s+Value="([0-9.]+)""""),
			Regex("""<AutoColorTempo[^>]*Value="([0-9.]+)""""),
			Regex("""Name="Tempo"[^>]*>[\s\S]*?<Manual\s+Value="([0-9.]+)""""),
			Regex("""<ArrangerLocator[^>]*>[\s\S]{0,200}?Tempo[\s\S]{0,200}?Value="([0-9.]+)""""),
		)
		for (re in patterns) {
			val m = re.find(xml)
			if (m != null) {
				val v = m.groupValues[1].toDoubleOrNull()
				if (v != null && v in 20.0..400.0) return v
			}
		}
		// Last resort: first Manual Value that looks like BPM near "Tempo"
		val near = Regex("""Tempo[\s\S]{0,400}?Value="([0-9.]+)"""").find(xml)
		val v = near?.groupValues?.get(1)?.toDoubleOrNull()
		if (v != null && v in 20.0..400.0) return v
		return null
	}

	private fun extractTitle(xml: String): String? {
		// LiveSet sometimes has no title; use first meaningful Name on the set
		Regex("""<Ableton[^>]*MajorVersion="(\d+)"""").find(xml) // version anchor only
		return null // caller falls back to filename
	}

	private fun extractAudioRefs(xml: String): List<AlsAudioRef> {
		val found = linkedMapOf<String, AlsAudioRef>()
		// Ableton FileRef uses RelativePath / Path / AbsolutePath / OriginalFileRef
		val pathRe = Regex(
			"""<(?:RelativePath|Path|AbsolutePath|OriginalFileRef)\s+Value="([^"]+)"""",
			RegexOption.IGNORE_CASE,
		)
		for (m in pathRe.findAll(xml)) {
			val raw = m.groupValues[1].trim()
			if (raw.isEmpty()) continue
			val normalized = raw.replace('\\', '/')
			if (!AUDIO_EXT.any { normalized.endsWith(it, ignoreCase = true) }) continue
			val name = normalized.substringAfterLast('/')
			found.putIfAbsent(normalized, AlsAudioRef(rawPath = normalized, fileName = name))
		}
		// Name elements that look like audio files
		val nameRe = Regex("""<Name\s+Value="([^"]+\.(?:wav|wave|aif|aiff|mp3|flac|ogg|m4a))"""", RegexOption.IGNORE_CASE)
		for (m in nameRe.findAll(xml)) {
			val name = m.groupValues[1].trim().replace('\\', '/')
			val justName = name.substringAfterLast('/')
			found.putIfAbsent(name, AlsAudioRef(rawPath = name, fileName = justName))
		}
		return found.values.toList()
	}

	private fun extractMidiMarks(xml: String, bpm: Double): List<AlsClipMark> {
		// <MidiNoteEvent Time="1.5" Duration="0.25" Velocity="100" ... />
		val re = Regex(
			"""<MidiNoteEvent\s+([^>]+)/?>""",
			RegexOption.IGNORE_CASE,
		)
		val attr = { s: String, key: String ->
			Regex("""$key="([^"]+)"""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)
		}
		val out = mutableListOf<AlsClipMark>()
		for (m in re.findAll(xml)) {
			val body = m.groupValues[1]
			val timeBeats = attr(body, "Time")?.toDoubleOrNull() ?: continue
			val durBeats = attr(body, "Duration")?.toDoubleOrNull() ?: 0.0
			// Key / Note / Pitch — Live uses different attrs across versions
			val note = attr(body, "Key")?.toIntOrNull()
				?: attr(body, "Note")?.toIntOrNull()
				?: attr(body, "Pitch")?.toIntOrNull()
			val vel = attr(body, "Velocity")?.toDoubleOrNull()?.toInt()?.coerceIn(1, 127) ?: 100
			out.add(
				AlsClipMark(
					timeMs = beatsToMs(timeBeats, bpm),
					durationMs = beatsToMs(durBeats, bpm),
					midiNote = note,
					velocity = vel,
				)
			)
		}
		return out
			.sortedBy { it.timeMs }
			.distinctBy { it.timeMs } // one mark per time for cut boundaries
	}

	private fun extractAudioClipMarks(xml: String, bpm: Double): List<AlsClipMark> {
		// CurrentStart / Time on AudioClip — often in beats
		val marks = mutableListOf<AlsClipMark>()
		val re = Regex("""<(?:AudioClip|ClipSlot)[^>]*?CurrentStart\s+Value="([0-9.]+)"""", RegexOption.IGNORE_CASE)
		for (m in re.findAll(xml)) {
			val beats = m.groupValues[1].toDoubleOrNull() ?: continue
			marks.add(AlsClipMark(timeMs = beatsToMs(beats, bpm)))
		}
		val timeRe = Regex("""<AudioClip[^>]*?\sTime="([0-9.]+)"""", RegexOption.IGNORE_CASE)
		for (m in timeRe.findAll(xml)) {
			val beats = m.groupValues[1].toDoubleOrNull() ?: continue
			marks.add(AlsClipMark(timeMs = beatsToMs(beats, bpm)))
		}
		return marks.sortedBy { it.timeMs }.distinctBy { it.timeMs }
	}

	/**
	 * Prefer a long "full track" file over one-shot drum samples when several refs exist.
	 * Resolve relative paths under projectDir (and common Samples/ layouts).
	 * Falls back to scanning the whole project folder for any audio if XML paths don't resolve.
	 */
	private fun resolveMainAudio(
		refs: List<AlsAudioRef>,
		projectDir: File,
		alsFile: File,
		warnings: MutableList<String>,
	): File? {
		// Always scan the project folder for audio files (Ableton paths are often absolute or broken after copy)
		val onDisk = projectDir.walkTopDown().maxDepth(6).filter { f ->
			f.isFile && AUDIO_EXT.any { f.name.endsWith(it, ignoreCase = true) }
		}.toList()

		if (onDisk.isEmpty()) {
			warnings.add("No audio files found under ${projectDir.absolutePath}. Pick the project folder that contains Samples/ (or Collect All and Save first).")
			if (refs.isNotEmpty()) {
				warnings.add("XML referenced ${refs.size} path(s) but none exist on disk: ${refs.take(3).joinToString { it.fileName }}")
			}
			return null
		}

		// Try to match XML refs to files on disk by filename
		val matched = refs.mapNotNull { ref ->
			onDisk.firstOrNull { it.name.equals(ref.fileName, ignoreCase = true) }
				?: resolveRef(ref, projectDir, alsFile)
		}.distinctBy { it.absolutePath }

		val pool = if (matched.isNotEmpty()) matched else onDisk
		val best = pool.maxByOrNull { it.length() }!!

		if (matched.isEmpty() && refs.isNotEmpty()) {
			warnings.add("XML paths didn't resolve — using largest audio on disk: ${best.name}")
		} else if (pool.size > 1) {
			warnings.add("Picked '${best.name}' (${pool.size} audio files found; largest wins for now)")
		}
		return best
	}

	private fun resolveRef(ref: AlsAudioRef, projectDir: File, alsFile: File): File? {
		val raw = ref.rawPath.replace('\\', '/')
		val candidates = mutableListOf<File>()
		if (raw.startsWith("/") || raw.matches(Regex("""^[A-Za-z]:/.*"""))) {
			candidates.add(File(raw))
		}
		candidates.add(File(projectDir, raw))
		candidates.add(File(projectDir, raw.substringAfterLast('/')))
		candidates.add(File(projectDir, "Samples/Imported/${ref.fileName}"))
		candidates.add(File(projectDir, "Samples/Processed/${ref.fileName}"))
		candidates.add(File(projectDir, "Samples/${ref.fileName}"))
		candidates.add(File(alsFile.parentFile, ref.fileName))
		candidates.add(File(alsFile.parentFile, "Samples/Imported/${ref.fileName}"))
		return candidates.firstOrNull { it.isFile }
	}
}