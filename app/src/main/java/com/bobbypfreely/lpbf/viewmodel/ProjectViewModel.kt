package com.bobbypfreely.lpbf.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.bobbypfreely.lpbf.audio.DecodedAudio
import com.bobbypfreely.lpbf.lightshow.LightshowColorWheel
import com.bobbypfreely.lpbf.lightshow.Pattern
import com.bobbypfreely.lpbf.marking.ButtonRef
import com.bobbypfreely.lpbf.marking.MarkingSession
import com.bobbypfreely.lpbf.ui.PadInputListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Surfaced to the UI when a mark would create a >8s segment -- the user must pick one.
 * button is null for marks made on the Mark and Cut screen (no button chosen yet --
 * that happens later on Place); non-null only for the retired pad-press-marks flow. */
data class CapPromptState(
	val gapMs: Int,
	val maxMs: Int,
	val button: ButtonRef?,
	val requestedStopMs: Int,
)

/**
 * Activity-scoped: every tab shares the exact same MarkingSession and decoded audio,
 * switching tabs never loses state.
 *
 * Marking and mapping are decoupled by default:
 *   - Mark & Cut: dropMark() cuts at the playhead with no button (loop=1, wormhole=-1).
 *   - Place: assigns the next unassigned cut to a pad (EDIT), previews (PLAY), or
 *     assigns + previews (HYBRID) -- so you can map on the fly after the first track
 *     without re-selecting each cut.
 * Physical Launchpad presses are routed here via PadInputListener; only Place /
 * Lightshow act on them while their tab is active.
 */
class ProjectViewModel : ViewModel(), PadInputListener {

	private val _decodedAudio = MutableLiveData<DecodedAudio?>(null)
	val decodedAudio: LiveData<DecodedAudio?> = _decodedAudio

	private val _markingSession = MutableLiveData<MarkingSession?>(null)
	val markingSession: LiveData<MarkingSession?> = _markingSession

	private val _connectedDeviceName = MutableLiveData<String?>(null)
	val connectedDeviceName: LiveData<String?> = _connectedDeviceName

	private val _capPrompt = MutableLiveData<CapPromptState?>(null)
	val capPrompt: LiveData<CapPromptState?> = _capPrompt

	private val _segmentVersion = MutableLiveData(0)
	val segmentVersion: LiveData<Int> = _segmentVersion

	private val debugLogBuilder = StringBuilder()
	private val _debugLog = MutableLiveData<String>()
	val debugLog: LiveData<String> = _debugLog

	fun logDebug(msg: String) {
		synchronized(debugLogBuilder) {
			debugLogBuilder.append(msg).append('\n')
			_debugLog.postValue(debugLogBuilder.toString())
		}
	}

	var cachedFilePath: String? = null
	var currentProjectId: String? = null

	fun setDecodedAudio(audio: DecodedAudio, filePath: String) {
		cachedFilePath = filePath
		_decodedAudio.value = audio
		_markingSession.value = MarkingSession(audio.totalDurationMs)
		notifySegmentsChanged()
	}

	fun setConnectedDeviceName(name: String?) {
		_connectedDeviceName.value = name
	}

	fun notifySegmentsChanged() {
		_segmentVersion.value = (_segmentVersion.value ?: 0) + 1
	}

	fun dropMark(atMs: Int) {
		val session = _markingSession.value ?: run {
			logDebug("dropMark($atMs) but no MarkingSession -- import a track first")
			return
		}
		if (session.isSpliced) return
		logDebug("dropMark($atMs), lastMark=${session.lastMarkMs()}")
		if (atMs <= session.lastMarkMs()) {
			logDebug("Ignored: $atMs <= lastMark=${session.lastMarkMs()} (too close to previous mark)")
			return
		}
		when (val result = session.recordMark(atMs, null)) {
			is MarkingSession.RecordResult.Committed -> notifySegmentsChanged()
			is MarkingSession.RecordResult.ExceedsCap -> {
				_capPrompt.value = CapPromptState(result.gapMs, result.maxMs, null, atMs)
			}
		}
	}

	enum class PlaceMode { EDIT, PLAY, HYBRID }

	private val _placeMode = MutableLiveData(PlaceMode.EDIT)
	val placeMode: LiveData<PlaceMode> = _placeMode

	fun setPlaceMode(mode: PlaceMode) {
		_placeMode.value = mode
	}

	private val _currentChain = MutableLiveData(0)
	val currentChain: LiveData<Int> = _currentChain

	fun setCurrentChain(chain: Int) {
		_currentChain.value = chain.coerceIn(0, 7)
	}

	var isPlaceTabActive: Boolean = false

	data class PreviewRequest(val startMs: Int, val endMs: Int, val pattern: Pattern? = null, val x: Int = -1, val y: Int = -1)
	private val _previewRequest = MutableLiveData<PreviewRequest?>(null)
	val previewRequest: LiveData<PreviewRequest?> = _previewRequest

	fun clearPreviewRequest() {
		_previewRequest.value = null
	}

	private val previewCycleIndex = HashMap<ButtonRef, Int>()

	override fun onPadDown(x: Int, y: Int) {
		if (isLightshowTabActive) {
			if (_selectedLightshowSegment.value != null) {
				_lightshowPadPress.value = LightshowPadPress(x, y, currentColorVelocity())
			} else {
				selectLightshowPad(x, y)
			}
			return
		}
		if (!isPlaceTabActive) {
			logDebug("Pad DOWN ($x,$y) ignored -- not on Place or Lightshow tab")
			return
		}
		when (_placeMode.value) {
			PlaceMode.PLAY -> previewPad(x, y)
			PlaceMode.HYBRID -> assignAndPreview(x, y)
			else -> assignNextSegment(x, y)
		}
	}

	override fun onPadUp(x: Int, y: Int) {}

	override fun onChainTouch(c: Int, upDown: Boolean) {
		if (!upDown) return
		if (isLightshowTabActive) {
			if (_selectedLightshowSegment.value != null) {
				setColorHueSlot(c)
			} else {
				setCurrentChain(c)
			}
			return
		}
		if (isPlaceTabActive) {
			setCurrentChain(c)
		}
	}

	fun assignNextSegment(x: Int, y: Int) {
		val session = _markingSession.value ?: return
		if (session.isSpliced) return
		val nextIndex = (0 until session.segmentCount).firstOrNull { session.segment(it).button == null }
		if (nextIndex == null) {
			logDebug("Pad ($x,$y): all cuts already assigned -- nothing left to auto-advance to")
			return
		}
		val chain = _currentChain.value ?: 0
		session.reassignButton(nextIndex, ButtonRef(chain = chain, x = x, y = y))
		logDebug("Assigned cut ${nextIndex + 1} -> chain $chain pad ($x,$y)")
		notifySegmentsChanged()
	}

	private fun assignAndPreview(x: Int, y: Int) {
		val session = _markingSession.value ?: return
		if (session.isSpliced) return
		val nextIndex = (0 until session.segmentCount).firstOrNull { session.segment(it).button == null }
		if (nextIndex == null) {
			previewPad(x, y)
			return
		}
		val chain = _currentChain.value ?: 0
		session.reassignButton(nextIndex, ButtonRef(chain = chain, x = x, y = y))
		notifySegmentsChanged()
		val seg = session.segment(nextIndex)
		logDebug("Assigned + previewing cut ${nextIndex + 1} -> chain $chain pad ($x,$y)")
		_previewRequest.value = PreviewRequest(seg.startMs, seg.endMs, seg.lightPattern, x, y)
	}

	private fun previewPad(x: Int, y: Int) {
		val session = _markingSession.value ?: return
		val button = ButtonRef(chain = _currentChain.value ?: 0, x = x, y = y)
		val matches = session.segments().withIndex().filter { it.value.button == button }
		if (matches.isEmpty()) {
			logDebug("Pad ($x,$y): nothing mapped here yet")
			return
		}
		val cycle = previewCycleIndex.getOrDefault(button, 0) % matches.size
		previewCycleIndex[button] = cycle + 1
		val (segIndex, seg) = matches[cycle]
		logDebug("Preview pad ($x,$y): cut ${segIndex + 1} (${seg.startMs}-${seg.endMs}ms)")
		_previewRequest.value = PreviewRequest(seg.startMs, seg.endMs, seg.lightPattern, x, y)
	}

	var isLightshowTabActive: Boolean = false

	private val _selectedLightshowSegment = MutableLiveData<Int?>(null)
	val selectedLightshowSegment: LiveData<Int?> = _selectedLightshowSegment

	private val lightshowCycleIndex = HashMap<ButtonRef, Int>()

	private fun selectLightshowPad(x: Int, y: Int) {
		val session = _markingSession.value ?: return
		val button = ButtonRef(chain = _currentChain.value ?: 0, x = x, y = y)
		val matches = session.segments().withIndex().filter { it.value.button == button }
		if (matches.isEmpty()) {
			logDebug("Lightshow pad ($x,$y): nothing mapped here yet")
			return
		}
		val cycle = lightshowCycleIndex.getOrDefault(button, 0) % matches.size
		lightshowCycleIndex[button] = cycle + 1
		val (segIndex, _) = matches[cycle]
		_selectedLightshowSegment.value = segIndex
		logDebug("Lightshow pad ($x,$y): editing cut ${segIndex + 1}")
	}

	fun deselectLightshowSegment() {
		_selectedLightshowSegment.value = null
	}

	data class LightshowPadPress(val x: Int, val y: Int, val velocity: Int)
	private val _lightshowPadPress = MutableLiveData<LightshowPadPress?>(null)
	val lightshowPadPress: LiveData<LightshowPadPress?> = _lightshowPadPress

	fun clearLightshowPadPress() {
		_lightshowPadPress.value = null
	}

	fun assignLightPatternToSelected(pattern: Pattern?) {
		val session = _markingSession.value ?: return
		val index = _selectedLightshowSegment.value ?: return
		session.assignPattern(index, pattern)
		notifySegmentsChanged()
	}

	private val _colorHueSlot = MutableLiveData(LightshowColorWheel.SLOT_RED)
	val colorHueSlot: LiveData<Int> = _colorHueSlot

	private val _colorSaturationLevel = MutableLiveData(0)
	val colorSaturationLevel: LiveData<Int> = _colorSaturationLevel

	private val _colorVelocityOverride = MutableLiveData<Int?>(null)
	val colorVelocityOverride: LiveData<Int?> = _colorVelocityOverride

	fun currentColorVelocity(): Int =
		_colorVelocityOverride.value
			?: LightshowColorWheel.velocityFor(_colorHueSlot.value ?: 0, _colorSaturationLevel.value ?: 0)

	fun setColorHueSlot(slot: Int) {
		_colorHueSlot.value = slot.coerceIn(0, LightshowColorWheel.SLOT_NAMES.size - 1)
		_colorSaturationLevel.value = 0
		_colorVelocityOverride.value = null
	}

	fun stepHue(delta: Int) {
		val count = LightshowColorWheel.SLOT_NAMES.size
		val next = (((_colorHueSlot.value ?: 0) + delta) % count + count) % count
		setColorHueSlot(next)
	}

	fun stepSaturation(delta: Int) {
		val slot = _colorHueSlot.value ?: 0
		val steps = LightshowColorWheel.saturationSteps(slot)
		if (steps <= 0) return
		val next = ((_colorSaturationLevel.value ?: 0) + delta).coerceIn(0, steps - 1)
		_colorSaturationLevel.value = next
		_colorVelocityOverride.value = null
	}

	fun nudgeVelocity(delta: Int) {
		val base = currentColorVelocity()
		val next = ((base + delta) % 128 + 128) % 128
		_colorVelocityOverride.value = next
	}

	var isMarkAndCutTabActive: Boolean = false
	private var arrowNavIndex: Int? = null

	override fun onFunctionKeyTouch(f: Int, upDown: Boolean) {
		if (!upDown) return
		if (isLightshowTabActive) {
			if (_selectedLightshowSegment.value == null) return
			when (f) {
				0 -> stepSaturation(+1)
				1 -> stepSaturation(-1)
				2 -> stepHue(-1)
				3 -> stepHue(+1)
			}
			return
		}
		if (!isMarkAndCutTabActive) return
		val session = _markingSession.value ?: return
		if (session.segmentCount == 0) return
		when (f) {
			2 -> {
				val cur = arrowNavIndex
				val next = if (cur == null) session.segmentCount - 1 else (cur - 1 + session.segmentCount) % session.segmentCount
				arrowNavIndex = next
				requestJumpToMark(next)
			}
			3 -> {
				val cur = arrowNavIndex
				val next = if (cur == null) 0 else (cur + 1) % session.segmentCount
				arrowNavIndex = next
				requestJumpToMark(next)
			}
		}
	}

	/** Applies a MultiClipImporter result (Unipack or pre-cut folder).
	 * Buttons, light patterns, loops and wormholes all travel with each segment so
	 * Unipack import → edit → export round-trips cleanly. Live mark-while-playing
	 * never goes through here -- that path still uses dropMark() with defaults. */
	fun applyMultiClipImport(result: com.bobbypfreely.lpbf.audio.MultiClipImportResult) {
		cachedFilePath = result.cachedFilePath
		_decodedAudio.value = result.decodedAudio
		_markingSession.value = MarkingSession.restore(
			result.decodedAudio.totalDurationMs,
			result.marks,
			result.buttons,
			result.patterns,
			result.loops,
			result.wormholes,
		)
		currentProjectId = null
		arrowNavIndex = null
		notifySegmentsChanged()
	}

	private val _jumpToMarkRequest = MutableLiveData<Int?>(null)
	val jumpToMarkRequest: LiveData<Int?> = _jumpToMarkRequest

	fun requestJumpToMark(segmentIndex: Int) {
		_jumpToMarkRequest.value = segmentIndex
	}

	fun clearJumpToMarkRequest() {
		_jumpToMarkRequest.value = null
	}

	fun resolveCapAutoSplit() {
		val session = _markingSession.value ?: return
		val prompt = _capPrompt.value ?: return
		session.resolveCapAutoSplit(prompt.button)
		_capPrompt.value = null
		notifySegmentsChanged()
	}

	fun resolveCapPlaceAnyway() {
		val session = _markingSession.value ?: return
		val prompt = _capPrompt.value ?: return
		session.resolveCapPlaceAnyway(prompt.requestedStopMs, prompt.button)
		_capPrompt.value = null
		notifySegmentsChanged()
	}

	fun resolveCapCancel() {
		_capPrompt.value = null
	}

	// ---- Project save / load (minimal stubs kept for compile; full JSON path restored below) ----

	data class SavedProjectInfo(val id: String, val name: String)
	data class LoadedProject(val trackFilePath: String, val session: MarkingSession)

	fun projectsRoot(context: android.content.Context): File =
		File(context.filesDir, "projects").also { it.mkdirs() }

	fun listSavedProjects(context: android.content.Context): List<SavedProjectInfo> {
		val root = projectsRoot(context)
		return root.listFiles()?.mapNotNull { dir ->
			if (!dir.isDirectory) return@mapNotNull null
			val meta = File(dir, "meta.json")
			if (!meta.exists()) return@mapNotNull null
			try {
				val obj = JSONObject(meta.readText())
				SavedProjectInfo(dir.name, obj.optString("name", dir.name))
			} catch (_: Exception) {
				null
			}
		} ?: emptyList()
	}

	fun saveCurrentProject(context: android.content.Context, name: String): Boolean {
		val session = _markingSession.value ?: return false
		val srcPath = cachedFilePath ?: return false
		val srcTrack = File(srcPath)
		if (!srcTrack.exists()) return false
		val id = currentProjectId ?: java.util.UUID.randomUUID().toString().also { currentProjectId = it }
		val dir = File(projectsRoot(context), id)
		dir.mkdirs()
		try {
			srcTrack.copyTo(File(dir, "track.wav"), overwrite = true)
			val marks = JSONArray(session.marksSnapshot())
			val buttons = JSONArray()
			session.buttonsSnapshot().forEach { b ->
				if (b == null) buttons.put(JSONObject.NULL)
				else buttons.put(JSONObject().put("chain", b.chain).put("x", b.x).put("y", b.y))
			}
			val patterns = JSONArray()
			session.patternsSnapshot().forEach { p -> patterns.put(patternToJson(p)) }
			val loops = JSONArray(session.loopsSnapshot())
			val wormholes = JSONArray(session.wormholesSnapshot())
			val data = JSONObject()
				.put("marks", marks)
				.put("buttons", buttons)
				.put("patterns", patterns)
				.put("loops", loops)
				.put("wormholes", wormholes)
			File(dir, "session.json").writeText(data.toString())
			File(dir, "meta.json").writeText(JSONObject().put("name", name).toString())
			return true
		} catch (e: Exception) {
			logDebug("saveCurrentProject failed: ${e.message}")
			return false
		}
	}

	fun readProjectForLoad(context: android.content.Context, id: String): LoadedProject? {
		val dir = File(projectsRoot(context), id)
		val track = File(dir, "track.wav")
		val sessionFile = File(dir, "session.json")
		if (!track.exists() || !sessionFile.exists()) return null
		return try {
			val obj = JSONObject(sessionFile.readText())
			val marks = mutableListOf<Int>()
			val marksArr = obj.getJSONArray("marks")
			for (i in 0 until marksArr.length()) marks.add(marksArr.getInt(i))
			val buttons = mutableListOf<ButtonRef?>()
			val buttonsArr = obj.getJSONArray("buttons")
			for (i in 0 until buttonsArr.length()) {
				val v = buttonsArr.opt(i)
				if (v == null || v == JSONObject.NULL) buttons.add(null)
				else {
					val bo = v as JSONObject
					buttons.add(ButtonRef(bo.getInt("chain"), bo.getInt("x"), bo.getInt("y")))
				}
			}
			val patterns = mutableListOf<Pattern?>()
			val patternsArr = obj.optJSONArray("patterns")
			if (patternsArr != null) {
				for (i in 0 until patternsArr.length()) patterns.add(patternFromJson(patternsArr.opt(i)))
			} else {
				repeat(buttons.size) { patterns.add(null) }
			}
			val loops = mutableListOf<Int>()
			val loopsArr = obj.optJSONArray("loops")
			if (loopsArr != null) {
				for (i in 0 until loopsArr.length()) loops.add(loopsArr.getInt(i))
			} else {
				repeat(buttons.size) { loops.add(1) }
			}
			val wormholes = mutableListOf<Int>()
			val wormholesArr = obj.optJSONArray("wormholes")
			if (wormholesArr != null) {
				for (i in 0 until wormholesArr.length()) wormholes.add(wormholesArr.getInt(i))
			} else {
				repeat(buttons.size) { wormholes.add(-1) }
			}
			val duration = marks.lastOrNull() ?: 0
			val session = MarkingSession.restore(duration, marks, buttons, patterns, loops, wormholes)
			LoadedProject(track.absolutePath, session)
		} catch (e: Exception) {
			logDebug("readProjectForLoad failed: ${e.message}")
			null
		}
	}

	fun applyLoadedProject(audio: DecodedAudio, loaded: LoadedProject) {
		cachedFilePath = loaded.trackFilePath
		_decodedAudio.value = audio
		_markingSession.value = loaded.session
		notifySegmentsChanged()
	}


	sealed class UnipackExportResult {
		data class Success(val displayPath: String, val soundCount: Int, val lightshowCount: Int) : UnipackExportResult()
		data class Blocked(val overCapSegmentIndices: List<Int>) : UnipackExportResult()
		object NothingToExport : UnipackExportResult()
		data class Failed(val message: String) : UnipackExportResult()
	}

	fun createUnipack(context: android.content.Context, title: String, producerName: String): UnipackExportResult {
		val session = _markingSession.value ?: return UnipackExportResult.NothingToExport
		val audio = _decodedAudio.value ?: return UnipackExportResult.NothingToExport

		val clips = when (val result = session.splice()) {
			is MarkingSession.SpliceResult.Blocked -> return UnipackExportResult.Blocked(result.overCapSegmentIndices)
			is MarkingSession.SpliceResult.Success -> result.clips
		}
		if (clips.isEmpty()) return UnipackExportResult.NothingToExport

		val exported = com.bobbypfreely.lpbf.audio.ClipExporter.export(audio, clips)
		val clipsByFileName = clips.associateBy { it.fileName }

		val occurrenceCount = mutableMapOf<Triple<Int, Int, Int>, Int>()
		var lightshowCount = 0

		val entries = exported.mapNotNull { ex ->
			val clip = clipsByFileName[ex.fileName] ?: return@mapNotNull null
			val button = ex.button ?: return@mapNotNull null

			var keyLedFileName: String? = null
			var keyLedText: String? = null
			val pattern = clip.lightPattern
			val key = Triple(button.chain, button.x, button.y)
			val occurrence = occurrenceCount.getOrDefault(key, 0)
			occurrenceCount[key] = occurrence + 1

			if (pattern != null) {
				val ledEvents = com.bobbypfreely.lpbf.lightshow.PatternCompiler.compile(pattern, ex.preciseDurationMs)
				keyLedText = com.bobbypfreely.lpbf.lightshow.KeyLedWriter.write(ledEvents)
				val base = com.bobbypfreely.lpbf.lightshow.KeyLedWriter.fileName(button.chain, button.x, button.y, 1)
				keyLedFileName = if (occurrence == 0) base else "$base ${'a' + occurrence - 1}"
				lightshowCount++
			}

			com.bobbypfreely.lpbf.unipack.UnipackWriter.SoundEntry(
				button = button,
				soundFileName = ex.fileName,
				wavBytes = ex.wavBytes,
				keyLedFileName = keyLedFileName,
				keyLedText = keyLedText,
			)
		}
		if (entries.isEmpty()) return UnipackExportResult.NothingToExport

		val chainCount = (entries.maxOf { it.button.chain }) + 1
		val safeTitle = title.trim().ifEmpty { "Untitled" }
		val fileName = safeTitle.replace(Regex("[^A-Za-z0-9 _-]"), "_") + ".zip"

		return try {
			val displayPath = saveToDocumentsLpbf(context, fileName) { out ->
				com.bobbypfreely.lpbf.unipack.UnipackWriter.write(
					output = out,
					title = safeTitle,
					producerName = producerName.trim(),
					buttonX = 8,
					buttonY = 8,
					chainCount = chainCount.coerceAtLeast(1),
					entries = entries,
				)
			} ?: return UnipackExportResult.Failed("Could not create the file in Documents/lpbf")

			logDebug("Created Unipack '$fileName' -> $displayPath (${entries.size} sounds, $lightshowCount lightshows)")
			UnipackExportResult.Success(displayPath, entries.size, lightshowCount)
		} catch (e: Exception) {
			logDebug("Create Unipack failed: ${e.message}")
			UnipackExportResult.Failed(e.message ?: "Unknown error")
		}
	}

	private fun saveToDocumentsLpbf(context: android.content.Context, fileName: String, writer: (java.io.OutputStream) -> Unit): String? {
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
			val resolver = context.contentResolver
			val values = android.content.ContentValues().apply {
				put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
				put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
				put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Documents/lpbf")
			}
			val collection = android.provider.MediaStore.Files.getContentUri("external")
			val uri = resolver.insert(collection, values) ?: return null
			resolver.openOutputStream(uri)?.use { writer(it) } ?: return null
			return "Documents/lpbf/$fileName"
		}

		@Suppress("DEPRECATION")
		val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "lpbf")
		dir.mkdirs()
		val file = File(dir, fileName)
		file.outputStream().use { writer(it) }
		return file.absolutePath
	}

	private fun patternToJson(pattern: Pattern?): Any {
		if (pattern == null) return JSONObject.NULL
		val po = JSONObject()
		po.put("name", pattern.name)
		val keyframesArray = JSONArray()
		pattern.keyframes.forEach { kf ->
			val ko = JSONObject()
			ko.put("t", kf.t.toDouble())
			ko.put("x", kf.x)
			ko.put("y", kf.y)
			ko.put("on", kf.on)
			ko.put("velocity", kf.velocity)
			if (kf.color != null) ko.put("color", kf.color) else ko.put("color", JSONObject.NULL)
			keyframesArray.put(ko)
		}
		po.put("keyframes", keyframesArray)
		return po
	}

	private fun patternFromJson(value: Any?): Pattern? {
		if (value == null || value == JSONObject.NULL) return null
		return try {
			val po = value as JSONObject
			val name = po.optString("name", "")
			val keyframesArray = po.getJSONArray("keyframes")
			val keyframes = (0 until keyframesArray.length()).map { i ->
				val ko = keyframesArray.getJSONObject(i)
				com.bobbypfreely.lpbf.lightshow.Keyframe(
					t = ko.getDouble("t").toFloat(),
					x = ko.getInt("x"),
					y = ko.getInt("y"),
					on = ko.getBoolean("on"),
					velocity = ko.getInt("velocity"),
					color = if (ko.isNull("color")) null else ko.getInt("color"),
				)
			}
			Pattern(name, keyframes)
		} catch (e: Exception) {
			logDebug("Failed to parse saved lightshow pattern: ${e.message}")
			null
		}
	}
}
