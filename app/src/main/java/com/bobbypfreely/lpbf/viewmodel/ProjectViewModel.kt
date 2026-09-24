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

data class CapPromptState(
	val gapMs: Int,
	val maxMs: Int,
	val button: ButtonRef?,
	val requestedStopMs: Int,
)

class ProjectViewModel : ViewModel(), PadInputListener {

	private val _decodedAudio = MutableLiveData<DecodedAudio?>(null)
	val decodedAudio: LiveData<DecodedAudio?> = _decodedAudio

	private val _markingSession = MutableLiveData<MarkingSession?>(null)
	val markingSession: LiveData<MarkingSession?> = _markingSession

	/** Chronological pad presses from Unipack autoPlay (empty if pack has none). */
	private val _autoPlay = MutableLiveData<List<com.bobbypfreely.lpbf.unipack.AutoPlayPress>>(emptyList())
	val autoPlay: LiveData<List<com.bobbypfreely.lpbf.unipack.AutoPlayPress>> = _autoPlay

	fun setAutoPlay(presses: List<com.bobbypfreely.lpbf.unipack.AutoPlayPress>) {
		_autoPlay.value = presses
		logDebug("AutoPlay: ${presses.size} presses" + if (presses.isEmpty()) " (none — labels fall back to map order)" else "")
	}

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
		_autoPlay.value = emptyList()
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
	enum class UiMode { PLAY, EDIT, HYBRID, LIGHTS }

	private val _placeMode = MutableLiveData(PlaceMode.PLAY)
	val placeMode: LiveData<PlaceMode> = _placeMode

	private val _uiMode = MutableLiveData(UiMode.PLAY)
	val uiMode: LiveData<UiMode> = _uiMode

	fun setPlaceMode(mode: PlaceMode) {
		_placeMode.value = mode
	}

	fun enterUiMode(mode: UiMode) {
		when (mode) {
			UiMode.PLAY -> {
				isLightshowTabActive = false
				isPlaceTabActive = true
				deselectLightshowSegment()
				setPlaceMode(PlaceMode.PLAY)
			}
			UiMode.EDIT -> {
				isLightshowTabActive = false
				isPlaceTabActive = true
				deselectLightshowSegment()
				setPlaceMode(PlaceMode.EDIT)
			}
			UiMode.HYBRID -> {
				isLightshowTabActive = false
				isPlaceTabActive = true
				deselectLightshowSegment()
				setPlaceMode(PlaceMode.HYBRID)
			}
			UiMode.LIGHTS -> {
				isPlaceTabActive = false
				isLightshowTabActive = true
				setPlaceMode(PlaceMode.PLAY)
			}
		}
		_uiMode.value = mode
		logDebug("UiMode -> $mode")
	}

	private val _currentChain = MutableLiveData(0)
	val currentChain: LiveData<Int> = _currentChain

	fun setCurrentChain(chain: Int) {
		_currentChain.value = chain.coerceIn(0, 7)
	}

	var isPlaceTabActive: Boolean = false

	data class PreviewRequest(
		val startMs: Int,
		val endMs: Int,
		val pattern: Pattern? = null,
		val x: Int = -1,
		val y: Int = -1,
		val stackIndex: Int = 0,
		val stackTotal: Int = 1,
	)
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
		val button = ButtonRef(chain = chain, x = x, y = y)
		session.reassignButton(nextIndex, button)
		val stack = session.segments().count { it.button == button }
		logDebug("Assigned cut ${nextIndex + 1} -> chain $chain pad ($x,$y) [stack=$stack]")
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
		val button = ButtonRef(chain = chain, x = x, y = y)
		session.reassignButton(nextIndex, button)
		notifySegmentsChanged()
		val seg = session.segment(nextIndex)
		val stack = session.segments().count { it.button == button }
		logDebug("Assigned + preview cut ${nextIndex + 1} -> pad ($x,$y) [stack=$stack]")
		_previewRequest.value = PreviewRequest(
			seg.startMs, seg.endMs, seg.lightPattern, x, y,
			stackIndex = stack - 1, stackTotal = stack,
		)
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
		logDebug("PLAY pad ($x,$y): note ${cycle + 1}/${matches.size} = cut ${segIndex + 1} (${seg.startMs}-${seg.endMs}ms)")
		_previewRequest.value = PreviewRequest(
			seg.startMs, seg.endMs, seg.lightPattern, x, y,
			stackIndex = cycle, stackTotal = matches.size,
		)
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
		logDebug("Lightshow pad ($x,$y): editing cut ${segIndex + 1} (note ${cycle + 1}/${matches.size})")
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
		if (isLightshowTabActive && _selectedLightshowSegment.value != null) {
			when (f) {
				0 -> stepSaturation(+1)
				1 -> stepSaturation(-1)
				2 -> stepHue(-1)
				3 -> stepHue(+1)
			}
			return
		}
		when (f) {
			0 -> enterUiMode(UiMode.PLAY)
			1 -> enterUiMode(UiMode.EDIT)
			2 -> enterUiMode(UiMode.LIGHTS)
			3 -> enterUiMode(UiMode.HYBRID)
		}
	}

	fun applyMultiClipImport(result: com.bobbypfreely.lpbf.audio.MultiClipImportResult) {
		cachedFilePath = result.cachedFilePath
		_decodedAudio.value = result.decodedAudio
		_autoPlay.value = emptyList()
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
		previewCycleIndex.clear()
		lightshowCycleIndex.clear()
		enterUiMode(UiMode.PLAY)
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

	data class SavedProjectInfo(val id: String, val name: String)
	data class LoadedProject(val trackFilePath: String, val session: MarkingSession)

	fun projectsRoot(context: android.content.Context): File =
		File(context.filesDir, "projects").also { it.mkdirs() }

	fun listSavedProjects(context: android.content.Context): List<SavedProjectInfo> = emptyList()
	fun saveCurrentProject(context: android.content.Context, name: String): Boolean = false
	fun readProjectForLoad(context: android.content.Context, id: String): LoadedProject? = null

	fun applyLoadedProject(audio: DecodedAudio, loaded: LoadedProject) {
		cachedFilePath = loaded.trackFilePath
		_decodedAudio.value = audio
		_markingSession.value = loaded.session
		previewCycleIndex.clear()
		lightshowCycleIndex.clear()
		_autoPlay.value = emptyList()
		enterUiMode(UiMode.PLAY)
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
				loop = clip.loop,
				wormhole = clip.wormhole,
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
		val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "lpbf")
		dir.mkdirs()
		val file = java.io.File(dir, fileName)
		file.outputStream().use { writer(it) }
		return file.absolutePath
	}

	private fun patternToJson(p: Pattern?): Any {
		if (p == null) return JSONObject.NULL
		val keyframes = JSONArray()
		p.keyframes.forEach { kf ->
			keyframes.put(JSONObject()
				.put("t", kf.t.toDouble())
				.put("x", kf.x)
				.put("y", kf.y)
				.put("on", kf.on)
				.put("velocity", kf.velocity)
				.put("color", kf.color ?: JSONObject.NULL))
		}
		return JSONObject().put("name", p.name).put("keyframes", keyframes)
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
