package com.bobbypfreely.lpbf.waveform

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Ableton Live .als -> LPBF translate.
 *
 * A .als is gzip-compressed XML (sometimes plain XML). This reader streams it
 * with XmlPullParser and never holds the unzipped document in one String.
 *
 * Times inside the set are beats. [beatsToMs] is the conversion:
 * ms = beats * 60000.0 / bpm.
 *
 * MidiNoteEvent Time is clip-relative. [noteToTimelineBeat] adds the enclosing
 * clip CurrentStart (arrangement position). That is the single place to change
 * if a real project shows the offset is wrong.
 */
object AlsReader {

	data class Mark(
		val timeMs: Int,
		/** Timeline beat that [timeMs] was converted from (clip start + note Time). */
		val beat: Double,
		val durationMs: Int,
		val midiNote: Int? = null,
		val velocity: Int = 0,
	)

	/**
	 * One pad hit that can later become a keyLED on/off (chain x y file, delay in ms).
	 * [colorIndex] is a Launchpad palette index, currently equal to velocity.
	 * x: 0 left .. 7 right. y: 0 bottom .. 7 top.
	 */
	data class LightEvent(
		val x: Int,
		val y: Int,
		val chain: Int,
		val velocity: Int,
		val colorIndex: Int,
		val startMs: Int,
		val durationMs: Int,
		val midiNote: Int,
		val beat: Double,
	)

	/** 8x8 pad. y = 0 is the bottom row. chain is 0-based (Unipack chain 1). */
	data class PadCoord(val x: Int, val y: Int, val chain: Int = 0)

	data class AlsReadResult(
		val title: String,
		val bpm: Double,
		val audioFile: File?,
		val marks: List<Mark>,
		val lightEvents: List<LightEvent>,
		val warnings: List<String>,
	)

	private val AUDIO_EXT = arrayOf(
		".wav", ".wave", ".aif", ".aiff", ".mp3", ".flac", ".ogg", ".m4a", ".aac",
	)

	private val LIGHT_TRACK = Regex("light|\\bled\\b|launchpad|unipad|keyled", RegexOption.IGNORE_CASE)

	/**
	 * Pick the project .als, not a copy under Backup/.
	 * Prefers a shallow file whose name matches the folder (Live's "Name Project/Name.als").
	 */
	fun findProjectAls(projectDir: File): File? {
		if (!projectDir.isDirectory) {
			return projectDir.takeIf { it.isFile && it.name.endsWith(".als", ignoreCase = true) }
		}
		val all = projectDir.walkTopDown().maxDepth(4).filter { file ->
			file.isFile && file.name.endsWith(".als", ignoreCase = true)
		}.toList()
		if (all.isEmpty()) return null
		val primary = all.filter { !isBackupPath(it) }
		val pool = if (primary.isNotEmpty()) primary else all
		val dirName = projectDir.name
			.removeSuffix(" Project")
			.removeSuffix(" project")
		return pool.minWithOrNull(
			compareBy<File>(
				{ relativeDepth(projectDir, it) },
				{ nameScore(it, dirName) },
				{ it.name.length },
			)
		)
	}

	private fun isBackupPath(file: File): Boolean {
		var dir = file.parentFile
		while (dir != null) {
			if (dir.name.equals("Backup", ignoreCase = true)) return true
			dir = dir.parentFile
		}
		return false
	}

	private fun relativeDepth(root: File, file: File): Int {
		val rootPath = root.absolutePath
		var depth = 0
		var dir: File? = file.parentFile
		while (dir != null) {
			if (dir.absolutePath == rootPath) return depth
			depth++
			if (depth > 8) return 99
			dir = dir.parentFile
		}
		return 99
	}

	/** 0 if the set name matches the folder, else 1. */
	private fun nameScore(file: File, dirName: String): Int {
		return if (file.nameWithoutExtension.equals(dirName, ignoreCase = true)) 0 else 1
	}

	fun read(alsFile: File, projectDir: File = alsFile.parentFile ?: alsFile): AlsReadResult {
		val warnings = mutableListOf<String>()
		val title = alsFile.nameWithoutExtension
		val parsed = try {
			openAlsStream(alsFile).use { input ->
				val parser = Xml.newPullParser()
				try {
					parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
				} catch (_: Exception) {
				}
				parser.setInput(input, "UTF-8")
				parse(parser)
			}
		} catch (e: Exception) {
			return AlsReadResult(
				title = title,
				bpm = 120.0,
				audioFile = null,
				marks = emptyList(),
				lightEvents = emptyList(),
				warnings = listOf("Could not read .als: ${e.message ?: e.javaClass.simpleName}"),
			)
		}

		if (!parsed.sawAbleton) warnings.add("File does not look like Ableton Live XML")

		val bpm = when {
			parsed.bpm != null -> parsed.bpm
			parsed.tempoAutomation != null -> parsed.tempoAutomation.also {
				warnings.add("Tempo Manual missing - used automation BPM $it")
			}
			else -> 120.0.also {
				warnings.add("Tempo not found - assuming 120 BPM")
			}
		}

		if (parsed.audioRefs.isEmpty()) {
			warnings.add("No audio FileRef paths found in the project")
		}
		val audioFile = resolveMainAudio(parsed.audioRefs, projectDir, alsFile, warnings)

		val notes = parsed.arrangementNotes.ifEmpty {
			parsed.otherNotes.also {
				if (it.isNotEmpty() && parsed.arrangementNotes.isEmpty()) {
					warnings.add("No arrangement MIDI - marks from session clips")
				}
			}
		}

		val primaryMarks = if (notes.isNotEmpty()) {
			notes.map { it.toMark(bpm) }
		} else {
			val starts = parsed.arrangementAudioStarts.ifEmpty { parsed.otherAudioStarts }
			if (starts.isEmpty()) {
				warnings.add("No MidiNoteEvent or AudioClip times found - no cuts to mark")
				emptyList()
			} else {
				warnings.add("No MIDI notes - marks from AudioClip times")
				starts.map { beat ->
					Mark(timeMs = beatsToMs(beat, bpm), beat = beat, durationMs = 0)
				}
			}
		}

		val asdMarks = audioFile?.let { readAsdMarks(it, warnings) }.orEmpty()
		val marks = mergeMarks(primaryMarks, asdMarks)

		val lightNotes = lightNotesFor(parsed, notes)
		if (lightNotes.isEmpty() && (parsed.arrangementNotes.isNotEmpty() || parsed.otherNotes.isNotEmpty())) {
			warnings.add("No MIDI track name contained 'Light' - light events skipped")
		}
		var unmappedLights = 0
		val lightEvents = ArrayList<LightEvent>(lightNotes.size)
		for (note in lightNotes) {
			val midi = note.midiNote
			val pad = if (midi == null) null else midiNoteToPad(midi)
			if (midi == null || pad == null) {
				unmappedLights++
				continue
			}
			val beat = noteToTimelineBeat(note.noteTime, note.clipStart)
			lightEvents.add(
				LightEvent(
					x = pad.x,
					y = pad.y,
					chain = pad.chain,
					velocity = note.velocity,
					colorIndex = velocityToColorIndex(note.velocity),
					startMs = beatsToMs(beat, bpm),
					durationMs = beatsToMs(note.durationBeats, bpm),
					midiNote = midi,
					beat = beat,
				)
			)
		}
		if (unmappedLights > 0) {
			warnings.add(
				"$unmappedLights light note(s) outside MIDI 60-123 (C4 = bottom-left) - not mapped to pads"
			)
		}
		lightEvents.sortBy { it.startMs }

		return AlsReadResult(
			title = title,
			bpm = bpm,
			audioFile = audioFile,
			marks = marks,
			lightEvents = lightEvents,
			warnings = warnings,
		)
	}

	/** ms = beats * 60000.0 / bpm */
	fun beatsToMs(beats: Double, bpm: Double): Int {
		if (bpm <= 0.0 || beats.isNaN() || bpm.isNaN()) return 0
		val ms = beats * 60000.0 / bpm
		if (ms.isNaN() || ms.isInfinite()) return 0
		return ms.toInt().coerceAtLeast(0)
	}

	/**
	 * Timeline beat for a note. Live stores MidiNoteEvent Time relative to the
	 * clip; CurrentStart is the clip's arrangement beat. Pass 0 when there is
	 * no enclosing clip.
	 */
	fun noteToTimelineBeat(noteTimeBeats: Double, clipStartBeat: Double): Double {
		return clipStartBeat + noteTimeBeats
	}

	/**
	 * Pad dialect (change this function, not the call sites):
	 * MIDI 60 / C4 is the bottom-left pad of the 8x8.
	 * Four 4x4 banks, 16 notes each, in order:
	 *   0 bottom-left, 1 top-left, 2 bottom-right (columns 5-8), 3 top-right.
	 * Inside a bank, notes run left to right, then upward (row 0 = bottom).
	 * chain stays 0 until a chain split is chosen.
	 * Returns null outside 60..123.
	 */
	fun midiNoteToPad(note: Int): PadCoord? {
		if (note !in 60..123) return null
		val index = note - 60
		val bank = index / 16
		val within = index % 16
		val col = within % 4
		val row = within / 4
		val originX: Int
		val originY: Int
		when (bank) {
			0 -> { originX = 0; originY = 0 }
			1 -> { originX = 0; originY = 4 }
			2 -> { originX = 4; originY = 0 }
			else -> { originX = 4; originY = 4 }
		}
		return PadCoord(x = originX + col, y = originY + row, chain = 0)
	}

	/** Launchpad color index. 1:1 with MIDI velocity until a palette is chosen. */
	fun velocityToColorIndex(velocity: Int): Int = velocity.coerceIn(0, 127)

	private fun openAlsStream(alsFile: File): InputStream {
		val raw = BufferedInputStream(alsFile.inputStream())
		raw.mark(4)
		val b0 = raw.read()
		val b1 = raw.read()
		raw.reset()
		return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw) else raw
	}

	private class Frame(val name: String) {
		var nameLocked: Boolean = false
		var trackId: String? = null
		var trackName: String? = null
		/** Ableton TrackGroupId of this track. -1 means ungrouped. */
		var groupId: String? = null
		var clipStart: Double? = null
		var arrangement: Boolean = false
		var midiKey: Int? = null
		val pendingNotes = mutableListOf<PendingNote>()
		/** Live 10 FileRef path built from RelativePathElement Dir attributes. */
		val relParts = ArrayList<String>(4)
	}

	private class PendingNote(
		val time: Double,
		val duration: Double,
		val velocity: Int,
		val explicitNote: Int?,
	)

	private class RawNote(
		val noteTime: Double,
		val clipStart: Double,
		val durationBeats: Double,
		val midiNote: Int?,
		val velocity: Int,
		val arrangement: Boolean,
		val trackName: String?,
		val groupId: String?,
		/** True when an ancestor track element (nested XML) was already named as lights. */
		val ancestorLight: Boolean,
	) {
		fun toMark(bpm: Double): Mark {
			val beat = noteToTimelineBeat(noteTime, clipStart)
			return Mark(
				timeMs = beatsToMs(beat, bpm),
				beat = beat,
				durationMs = beatsToMs(durationBeats, bpm),
				midiNote = midiNote,
				velocity = velocity,
			)
		}
	}

	private class AudioRef(val rawPath: String, val fileName: String)

	private class Parsed(
		val bpm: Double?,
		val tempoAutomation: Double?,
		val sawAbleton: Boolean,
		val audioRefs: List<AudioRef>,
		val arrangementNotes: List<RawNote>,
		val otherNotes: List<RawNote>,
		val arrangementAudioStarts: List<Double>,
		val otherAudioStarts: List<Double>,
		val groupNames: Map<String, String>,
		val groupParents: Map<String, String>,
	) {
		fun isLight(note: RawNote): Boolean {
			if (note.ancestorLight) return true
			if (note.trackName != null && LIGHT_TRACK.containsMatchIn(note.trackName)) return true
			var id = note.groupId
			var guard = 0
			while (id != null && id != "-1" && guard++ < 16) {
				val name = groupNames[id] ?: return false
				if (LIGHT_TRACK.containsMatchIn(name)) return true
				id = groupParents[id]
			}
			return false
		}
	}

	private class ParseState {
		val stack = ArrayDeque<Frame>()
		var bpm: Double? = null
		var bpmFromMaster: Boolean = false
		var tempoAutomation: Double? = null
		var tempoAutomationTime: Double = Double.POSITIVE_INFINITY
		var tempoAutomationFromMaster: Boolean = false
		var sawAbleton: Boolean = false
		val audioRefs = LinkedHashMap<String, AudioRef>()
		val arrangementNotes = mutableListOf<RawNote>()
		val otherNotes = mutableListOf<RawNote>()
		val arrangementAudioStarts = mutableListOf<Double>()
		val otherAudioStarts = mutableListOf<Double>()
		val groupNames = LinkedHashMap<String, String>()
		val groupParents = LinkedHashMap<String, String>()

		fun trackFrame(): Frame? = stack.lastOrNull {
			it.name == "MidiTrack" || it.name == "AudioTrack" || it.name == "GroupTrack"
		}

		fun lightContext(): Boolean = stack.any { frame ->
			(frame.name == "MidiTrack" || frame.name == "AudioTrack" || frame.name == "GroupTrack") &&
				frame.trackName?.let { LIGHT_TRACK.containsMatchIn(it) } == true
		}

		fun inMaster(): Boolean = stack.any { it.name == "MasterTrack" }

		fun addAudioStart(beat: Double, arrangement: Boolean) {
			if (arrangement) arrangementAudioStarts.add(beat) else otherAudioStarts.add(beat)
		}
	}

	private fun parse(parser: XmlPullParser): Parsed {
		val state = ParseState()
		var event = parser.eventType
		while (event != XmlPullParser.END_DOCUMENT) {
			when (event) {
				XmlPullParser.START_TAG -> onStart(parser, state)
				XmlPullParser.END_TAG -> onEnd(parser.name, state)
			}
			event = parser.next()
		}
		return Parsed(
			bpm = state.bpm,
			tempoAutomation = state.tempoAutomation,
			sawAbleton = state.sawAbleton,
			audioRefs = state.audioRefs.values.toList(),
			arrangementNotes = state.arrangementNotes,
			otherNotes = state.otherNotes,
			arrangementAudioStarts = state.arrangementAudioStarts,
			otherAudioStarts = state.otherAudioStarts,
			groupNames = state.groupNames,
			groupParents = state.groupParents,
		)
	}

	private fun onStart(parser: XmlPullParser, state: ParseState) {
		val name = parser.name ?: return
		if (name == "Ableton" || name == "LiveSet") state.sawAbleton = true
		when (name) {
			"Manual" -> captureTempo(parser, state)
			"EffectiveName", "UserName" -> captureTrackName(parser, state, name)
			"DeviceChain" -> state.trackFrame()?.nameLocked = true
			"CurrentStart" -> captureCurrentStart(parser, state)
			"MidiNoteEvent" -> captureNote(parser, state)
			"MidiKey" -> captureMidiKey(parser, state)
			"TrackGroupId" -> captureGroupId(parser, state)
			"FloatEvent" -> captureTempoAutomation(parser, state)
			"RelativePathElement" -> captureRelativePart(parser, state)
			"RelativePath", "Path", "AbsolutePath", "OriginalFileRef", "Name" ->
				considerAudio(attr(parser, "Value"), state)
		}
		val frame = Frame(name)
		if (name == "MidiTrack" || name == "AudioTrack" || name == "GroupTrack") {
			frame.trackId = attr(parser, "Id")
		}
		if (name == "MidiClip" || name == "AudioClip") {
			frame.arrangement = state.stack.any { it.name == "ArrangerAutomation" }
			if (name == "MidiClip") {
				frame.clipStart = attr(parser, "Time")?.toDoubleOrNull()
					?: attr(parser, "CurrentStart")?.toDoubleOrNull()
					?: 0.0
			} else {
				val inline = attr(parser, "CurrentStart")?.toDoubleOrNull()
					?: attr(parser, "Time")?.toDoubleOrNull()
				if (inline != null) state.addAudioStart(inline, frame.arrangement)
			}
		}
		state.stack.addLast(frame)
	}

	private fun onEnd(name: String?, state: ParseState) {
		val top = state.stack.lastOrNull()
		if (name == "KeyTrack" && top?.name == "KeyTrack") flushKeyTrack(top, state)
		if (name == "GroupTrack" && top?.name == "GroupTrack") rememberGroup(top, state)
		if (name == "RelativePath" && top?.name == "RelativePath" && top.relParts.isNotEmpty()) {
			considerAudio(top.relParts.joinToString("/"), state)
		}
		if (name == null) {
			state.stack.removeLastOrNull()
			return
		}
		while (state.stack.isNotEmpty()) {
			val popped = state.stack.removeLast()
			if (popped.name == name) break
		}
	}

	private fun captureTempo(parser: XmlPullParser, state: ParseState) {
		if (state.stack.lastOrNull()?.name != "Tempo") return
		val v = attr(parser, "Value")?.toDoubleOrNull() ?: return
		if (v !in 20.0..400.0) return
		if (state.inMaster()) {
			if (!state.bpmFromMaster) {
				state.bpm = v
				state.bpmFromMaster = true
			}
		} else if (!state.bpmFromMaster && state.bpm == null) {
			state.bpm = v
		}
	}

	private fun captureTempoAutomation(parser: XmlPullParser, state: ParseState) {
		if (state.bpmFromMaster) return
		if (state.stack.none { it.name == "Tempo" }) return
		val v = attr(parser, "Value")?.toDoubleOrNull() ?: return
		if (v !in 20.0..400.0) return
		val time = attr(parser, "Time")?.toDoubleOrNull() ?: 0.0
		if (state.inMaster()) {
			if (!state.tempoAutomationFromMaster || time < state.tempoAutomationTime) {
				state.tempoAutomation = v
				state.tempoAutomationTime = time
				state.tempoAutomationFromMaster = true
			}
		} else if (!state.tempoAutomationFromMaster && time < state.tempoAutomationTime) {
			state.tempoAutomation = v
			state.tempoAutomationTime = time
		}
	}

	private fun captureGroupId(parser: XmlPullParser, state: ParseState) {
		val id = attr(parser, "Value")?.trim().orEmpty()
		if (id.isEmpty() || id == "-1") return
		state.trackFrame()?.groupId = id
	}

	private fun captureRelativePart(parser: XmlPullParser, state: ParseState) {
		val part = attr(parser, "Dir") ?: attr(parser, "Value") ?: return
		val trimmed = part.trim().trim('/')
		if (trimmed.isEmpty()) return
		state.stack.lastOrNull { it.name == "RelativePath" }?.relParts?.add(trimmed)
	}

	private fun rememberGroup(frame: Frame, state: ParseState) {
		val id = frame.trackId ?: return
		val name = frame.trackName ?: return
		state.groupNames[id] = name
		val parent = frame.groupId
		if (parent != null && parent != "-1") state.groupParents[id] = parent
	}

	private fun captureTrackName(parser: XmlPullParser, state: ParseState, element: String) {
		val track = state.trackFrame() ?: return
		if (track.nameLocked) return
		val value = attr(parser, "Value")?.trim().orEmpty()
		if (value.isEmpty()) return
		if (element == "UserName") track.trackName = value
		else if (track.trackName.isNullOrEmpty()) track.trackName = value
	}

	private fun captureCurrentStart(parser: XmlPullParser, state: ParseState) {
		val parent = state.stack.lastOrNull() ?: return
		val beat = attr(parser, "Value")?.toDoubleOrNull() ?: return
		when (parent.name) {
			"MidiClip" -> parent.clipStart = beat
			"AudioClip" -> state.addAudioStart(beat, parent.arrangement)
		}
	}

	private fun captureNote(parser: XmlPullParser, state: ParseState) {
		val time = attr(parser, "Time")?.toDoubleOrNull() ?: return
		val duration = attr(parser, "Duration")?.toDoubleOrNull() ?: 0.0
		val velocity = attr(parser, "Velocity")?.toDoubleOrNull()?.toInt()?.coerceIn(0, 127) ?: 100
		val explicit = attr(parser, "Key")?.toIntOrNull()
			?: attr(parser, "Note")?.toIntOrNull()
			?: attr(parser, "Pitch")?.toIntOrNull()
		val pending = PendingNote(time, duration, velocity, explicit)
		val keyTrack = state.stack.lastOrNull { it.name == "KeyTrack" }
		if (keyTrack == null || explicit != null || keyTrack.midiKey != null) {
			emitNote(pending, explicit ?: keyTrack?.midiKey, state)
		} else {
			keyTrack.pendingNotes.add(pending)
		}
	}

	private fun captureMidiKey(parser: XmlPullParser, state: ParseState) {
		val keyTrack = state.stack.lastOrNull { it.name == "KeyTrack" } ?: return
		val key = attr(parser, "Value")?.toIntOrNull() ?: return
		keyTrack.midiKey = key
		flushKeyTrack(keyTrack, state)
	}

	private fun flushKeyTrack(frame: Frame, state: ParseState) {
		if (frame.pendingNotes.isEmpty()) return
		val notes = frame.pendingNotes.toList()
		frame.pendingNotes.clear()
		for (pending in notes) {
			emitNote(pending, pending.explicitNote ?: frame.midiKey, state)
		}
	}

	private fun emitNote(pending: PendingNote, midiNote: Int?, state: ParseState) {
		val clip = state.stack.lastOrNull { it.name == "MidiClip" }
		val track = state.trackFrame()
		val raw = RawNote(
			noteTime = pending.time,
			clipStart = clip?.clipStart ?: 0.0,
			durationBeats = pending.duration,
			midiNote = midiNote,
			velocity = pending.velocity,
			arrangement = clip?.arrangement == true,
			trackName = track?.trackName,
			groupId = track?.groupId,
			ancestorLight = state.lightContext(),
		)
		if (raw.arrangement) state.arrangementNotes.add(raw) else state.otherNotes.add(raw)
	}

	private fun considerAudio(raw: String?, state: ParseState) {
		if (raw.isNullOrBlank()) return
		val normalized = raw.trim().replace('\\', '/')
		if (!isAudioName(normalized)) return
		val fileName = normalized.substringAfterLast('/')
		state.audioRefs.putIfAbsent(normalized, AudioRef(normalized, fileName))
	}

	private fun isAudioName(path: String): Boolean {
		val lower = path.lowercase()
		return AUDIO_EXT.any { lower.endsWith(it) }
	}

	private fun attr(parser: XmlPullParser, key: String): String? {
		for (i in 0 until parser.attributeCount) {
			if (parser.getAttributeName(i)?.equals(key, ignoreCase = true) == true) {
				return parser.getAttributeValue(i)
			}
		}
		return null
	}

	private fun lightNotesFor(parsed: Parsed, markNotes: List<RawNote>): List<RawNote> {
		val fromMarks = markNotes.filter { parsed.isLight(it) }
		if (fromMarks.isNotEmpty()) return fromMarks
		return (parsed.arrangementNotes.asSequence() + parsed.otherNotes.asSequence())
			.filter { parsed.isLight(it) }
			.toList()
	}

	private fun mergeMarks(primary: List<Mark>, extra: List<Mark>): List<Mark> {
		val byTime = LinkedHashMap<Int, Mark>()
		for (mark in primary.sortedBy { it.timeMs }) {
			if (!byTime.containsKey(mark.timeMs)) byTime[mark.timeMs] = mark
		}
		for (mark in extra) {
			if (!byTime.containsKey(mark.timeMs)) byTime[mark.timeMs] = mark
		}
		return byTime.values.sortedBy { it.timeMs }
	}

	private fun readAsdMarks(audio: File, warnings: MutableList<String>): List<Mark> {
		val asd = siblingAsd(audio) ?: return emptyList()
		return try {
			val points = AsdReader.readWarpPoints(asd)
			if (points.isEmpty()) {
				warnings.add("ASD ${asd.name} had no readable warp markers - skipped")
				emptyList()
			} else {
				warnings.add("Found ${points.size} warp marker(s) in ${asd.name}")
				points.map { point ->
					Mark(
						timeMs = (point.seconds * 1000.0).toInt().coerceAtLeast(0),
						beat = point.beats,
						durationMs = 0,
					)
				}
			}
		} catch (e: Exception) {
			warnings.add("ASD ${asd.name} could not be read (${e.message ?: e.javaClass.simpleName}) - skipped")
			emptyList()
		}
	}

	private fun siblingAsd(audio: File): File? {
		val parent = audio.parentFile ?: return null
		val candidates = listOf(
			File(parent, audio.name + ".asd"),
			File(parent, audio.nameWithoutExtension + ".asd"),
		)
		return candidates.firstOrNull { it.isFile }
	}

	private fun resolveMainAudio(
		refs: List<AudioRef>,
		projectDir: File,
		alsFile: File,
		warnings: MutableList<String>,
	): File? {
		val onDisk = if (projectDir.isDirectory) {
			projectDir.walkTopDown().maxDepth(6).filter { file ->
				file.isFile && isAudioName(file.name)
			}.toList()
		} else {
			emptyList()
		}
		if (onDisk.isEmpty()) {
			warnings.add("No audio files found under ${projectDir.absolutePath}")
			if (refs.isNotEmpty()) {
				warnings.add(
					"XML referenced ${refs.size} path(s) but none exist on disk: " +
						refs.take(3).joinToString { it.fileName }
				)
			}
			return null
		}
		val matched = refs.mapNotNull { ref ->
			resolveRef(ref, projectDir, alsFile)
				?: onDisk.firstOrNull { it.name.equals(ref.fileName, ignoreCase = true) }
		}.distinctBy { it.absolutePath }
		val pool = if (matched.isNotEmpty()) matched else onDisk
		val best = pool.maxByOrNull { it.length() } ?: return null
		if (matched.isEmpty()) {
			warnings.add("XML paths did not resolve - using largest audio on disk: ${best.name}")
		} else if (pool.size > 1) {
			warnings.add("Picked '${best.name}' (${pool.size} audio files matched; largest wins)")
		}
		return best
	}

	private fun resolveRef(ref: AudioRef, projectDir: File, alsFile: File): File? {
		val raw = ref.rawPath.replace('\\', '/')
		val candidates = ArrayList<File>(8)
		if (raw.startsWith("/") || raw.matches(Regex("""^[A-Za-z]:/.*"""))) {
			candidates.add(File(raw))
		}
		candidates.add(File(projectDir, raw))
		candidates.add(File(projectDir, ref.fileName))
		candidates.add(File(projectDir, "Samples/Imported/${ref.fileName}"))
		candidates.add(File(projectDir, "Samples/Processed/${ref.fileName}"))
		candidates.add(File(projectDir, "Samples/${ref.fileName}"))
		val parent = alsFile.parentFile
		if (parent != null) {
			candidates.add(File(parent, ref.fileName))
			candidates.add(File(parent, "Samples/Imported/${ref.fileName}"))
		}
		return candidates.firstOrNull { it.isFile }
	}
}

/**
 * Ableton .asd analysis files are proprietary binary. Live 9/10 store warp
 * markers as the ASCII record "WarpMarker", 4 skipped bytes, then two
 * little-endian doubles (seconds, beats). Live 12 often uses a different
 * layout; if nothing plausible is found the caller skips with a warning.
 */
object AsdReader {

	data class WarpPoint(val seconds: Double, val beats: Double)

	private val NEEDLE = "WarpMarker".toByteArray(Charsets.US_ASCII)
	private const val MAX_BYTES = 32 * 1024 * 1024

	fun readWarpPoints(file: File): List<WarpPoint> {
		if (!file.isFile) return emptyList()
		if (file.length() > MAX_BYTES) {
			throw IllegalArgumentException("larger than ${MAX_BYTES} bytes")
		}
		val data = file.readBytes()
		val found = ArrayList<WarpPoint>()
		var i = 0
		val limit = data.size - NEEDLE.size
		while (i <= limit) {
			if (!matches(data, i, NEEDLE)) {
				i++
				continue
			}
			val pos = i + NEEDLE.size
			if (pos + 4 + 16 <= data.size) {
				val seconds = leDouble(data, pos + 4)
				val beats = leDouble(data, pos + 12)
				if (plausible(seconds, beats)) found.add(WarpPoint(seconds, beats))
			}
			i += NEEDLE.size
		}
		if (found.isNotEmpty()) return found.distinctBy { (it.seconds * 1000.0).toInt() }

		// Some files carry the marker as text: SecTime="..." BeatTime="..."
		val text = String(data, Charsets.ISO_8859_1)
		val textPoints = Regex("""SecTime="([0-9eE.+\-]+)"\s+BeatTime="([0-9eE.+\-]+)"""")
			.findAll(text)
			.mapNotNull { match ->
				val seconds = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
				val beats = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
				if (!plausible(seconds, beats)) null else WarpPoint(seconds, beats)
			}
			.distinctBy { (it.seconds * 1000.0).toInt() }
			.toList()
		return textPoints
	}

	private fun plausible(seconds: Double, beats: Double): Boolean {
		if (!seconds.isFinite() || !beats.isFinite()) return false
		if (seconds !in 0.0..(6.0 * 60.0 * 60.0)) return false
		if (beats !in -1024.0..1_000_000.0) return false
		return true
	}

	private fun matches(data: ByteArray, offset: Int, needle: ByteArray): Boolean {
		for (n in needle.indices) {
			if (data[offset + n] != needle[n]) return false
		}
		return true
	}

	private fun leDouble(data: ByteArray, offset: Int): Double {
		var bits = 0L
		for (i in 0 until 8) {
			bits = bits or ((data[offset + i].toLong() and 0xffL) shl (8 * i))
		}
		return Double.fromBits(bits)
	}
}
