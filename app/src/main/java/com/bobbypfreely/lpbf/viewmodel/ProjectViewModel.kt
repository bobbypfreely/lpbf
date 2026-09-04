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
 * Marking and mapping are now fully decoupled: Mark and Cut creates marks with no
 * button attached (via dropMark()); Place is what assigns a button to each cut
 * afterward. PadInputListener is still implemented here for physical Launchpad
 * presses, but onPadDown/onPadUp are now stubs -- their old "press a pad to both
 * play AND choose its button" behavior belonged to the retired design. Place will
 * give them real (different) meaning: pressing a pad assigns the next unassigned
 * cut to that button.
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

	/** Simple observable trigger for "something changed, refresh the UI." */
	private val _segmentVersion = MutableLiveData(0)
	val segmentVersion: LiveData<Int> = _segmentVersion

	/** Accumulates the FULL log text and always posts the complete string, never just the
	 * newest delta -- postValue() from a background thread can silently coalesce/drop
	 * intermediate values under rapid succession, so posting the whole history each time
	 * guarantees nothing displayed is ever permanently lost, even if some updates merge. */
	private val debugLogBuilder = StringBuilder()
	private val _debugLog = MutableLiveData<String>()
	val debugLog: LiveData<String> = _debugLog

	fun logDebug(msg: String) {
		synchronized(debugLogBuilder) {
			debugLogBuilder.append(msg).append('\n')
			_debugLog.postValue(debugLogBuilder.toString())
		}
	}

	/** Path to the locally-cached copy of the imported file, set by whoever imports it. */
	var cachedFilePath: String? = null

	fun setDecodedAudio(audio: DecodedAudio, filePath: String) {
		// cachedFilePath must be set BEFORE _decodedAudio.value -- LiveData notifies
		// observers synchronously on that assignment, and MarkAndCutFragment's observer
		// reads cachedFilePath to load the player. Setting it after left the player
		// loading a stale (or null, on the very first import) path every time.
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

	// ---- Mark and Cut: drop a mark at the current playhead position, no button attached ----

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

	// ---- Place: EDIT mode has pad presses assign the next unassigned cut, in order.
	// Tapping a pad that's already assigned stacks another cut onto it (multi-trigger
	// cycling) -- MarkingSession has no unique-button constraint, so this needs no extra
	// state. PLAY mode instead previews whatever's mapped to that pad; pressing the same
	// pad repeatedly cycles through every cut stacked on it. ----

	enum class PlaceMode { EDIT, PLAY, HYBRID }

	private val _placeMode = MutableLiveData(PlaceMode.EDIT)
	val placeMode: LiveData<PlaceMode> = _placeMode

	fun setPlaceMode(mode: PlaceMode) {
		_placeMode.value = mode
	}

	/** Which of the Launchpad's 8 side-button "chains" (64-pad pages) Place is currently
	 * editing/previewing on. Synced automatically from a real device via onChainTouch()
	 * below when its physical chain buttons are pressed; the on-screen chain selector on
	 * Place sets it directly for virtual use. */
	private val _currentChain = MutableLiveData(0)
	val currentChain: LiveData<Int> = _currentChain

	fun setCurrentChain(chain: Int) {
		_currentChain.value = chain.coerceIn(0, 7)
	}

	/** True only while the Place fragment is the visible tab (set from its onResume/onPause),
	 * so a physical Launchpad press on another tab can't silently reassign or preview a cut. */
	var isPlaceTabActive: Boolean = false

	/** One-shot event: PlaceFragment observes this and actually plays the range, then
	 * clears it back to null so rotation/re-observe doesn't replay it. */
	data class PreviewRequest(val startMs: Int, val endMs: Int)
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

	/** A real Launchpad's 8 side buttons page between its 8 chains -- keep the app's
	 * selected chain in sync with whichever one is actually lit on the hardware. On
	 * Lightshow, the same 8 physical buttons instead mean "pick this hue" while a cut
	 * is selected for editing -- Place's paging behavior only applies on Place. */
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

	/** Assigns [x],[y] on the current chain to the first segment with no button, or stacks
	 * onto an existing assignment if every segment already has one. */
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

	/** Hybrid mode: same auto-advance assignment as Edit, but immediately previews the cut
	 * that was just placed so you hear what you mapped without a second tap. Once every
	 * cut is assigned, falls back to plain cycling preview like Play mode. */
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
		_previewRequest.value = PreviewRequest(seg.startMs, seg.endMs)
	}

	/** Finds every cut mapped to [x],[y] on the current chain and previews the next one in
	 * line, cycling back to the first after the last -- this is what makes a stacked
	 * (multi-trigger) pad play a different cut each press instead of always the same one. */
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
		_previewRequest.value = PreviewRequest(seg.startMs, seg.endMs)
	}

	// ---- Lightshow: mirrors Place's "select a mapped pad" interaction, but for editing
	// that cut's light Pattern instead of assigning audio. Only pads that already have a
	// segment mapped to them (on the currently-viewed chain) can be selected -- tapping an
	// unmapped pad is a no-op, since there's no cut to attach a lightshow to yet. Tapping
	// the same pad again cycles through any stacked cuts on it, same convention as Place's
	// previewCycleIndex. Nothing is compiled to keyLED events here -- LightshowFragment
	// only ever calls assignLightPatternToSelected(), same "provisional until Finalize"
	// rule audio and Place already follow. ----

	/** True only while the Lightshow fragment is the visible tab. */
	var isLightshowTabActive: Boolean = false

	/** Index into markingSession.segments() of the cut currently being edited, or null
	 * when nothing is selected (LightshowFragment shows the mapped-pad overview instead). */
	private val _selectedLightshowSegment = MutableLiveData<Int?>(null)
	val selectedLightshowSegment: LiveData<Int?> = _selectedLightshowSegment

	private val lightshowCycleIndex = HashMap<ButtonRef, Int>()

	/** Finds every cut mapped to (x,y) on the current chain and selects the next one in
	 * line for editing, cycling back to the first after the last -- mirrors previewPad()
	 * in Place. No-op if nothing is mapped there yet. */
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

	/** Returns to the mapped-pad overview without discarding anything already saved. */
	fun deselectLightshowSegment() {
		_selectedLightshowSegment.value = null
	}

	/** One-shot: fired instead of re-selecting when a pad (virtual or physical) is pressed
	 * while a cut is already selected for editing -- LightshowFragment observes this and
	 * appends a light event at (x,y) using the given velocity, since only the fragment
	 * knows the in-progress event list and current authoring time. [velocity] is the
	 * palette index the hardware/virtual color picker was showing at the moment of the
	 * press, captured here so a later color-picker change can't retroactively alter an
	 * already-placed event. */
	data class LightshowPadPress(val x: Int, val y: Int, val velocity: Int)
	private val _lightshowPadPress = MutableLiveData<LightshowPadPress?>(null)
	val lightshowPadPress: LiveData<LightshowPadPress?> = _lightshowPadPress

	fun clearLightshowPadPress() {
		_lightshowPadPress.value = null
	}

	/** Commits [pattern] (or clears it, with null) as the currently-selected cut's lightshow. */
	fun assignLightPatternToSelected(pattern: Pattern?) {
		val session = _markingSession.value ?: return
		val index = _selectedLightshowSegment.value ?: return
		session.assignPattern(index, pattern)
		notifySegmentsChanged()
	}

	// -- Hardware color picker: 8 chain buttons = hue slot (see LightshowColorWheel),
	// Up/Down = saturation step, Left/Right = also step hue. Resolves to a real palette
	// velocity (0-127), never arbitrary RGB, matching what real keyLED files contain. --

	private val _colorHueSlot = MutableLiveData(LightshowColorWheel.SLOT_RED)
	val colorHueSlot: LiveData<Int> = _colorHueSlot

	private val _colorSaturationLevel = MutableLiveData(0)
	val colorSaturationLevel: LiveData<Int> = _colorSaturationLevel

	/** Set only by nudgeVelocity() below -- lets you dial in ANY of the 128 real palette
	 * entries directly, not just the curated hue/saturation ladders (those only reach
	 * 122 of 128 by design, grouped for a clean 8-button feel; this is the escape hatch
	 * for the rest, or for picking a specific neighbor of a ladder entry). Cleared
	 * whenever a hue or saturation is explicitly chosen, so picking Red always gives a
	 * clean, predictable color to nudge from -- it doesn't fight a leftover override.
	 */
	private val _colorVelocityOverride = MutableLiveData<Int?>(null)
	val colorVelocityOverride: LiveData<Int?> = _colorVelocityOverride

	/** The palette velocity (0-127) the current selection resolves to -- either the
	 * hue+saturation ladder pick, or the raw override if nudgeVelocity() has been used
	 * since the last hue/saturation change. */
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

	/** Nudges the current velocity by [delta] (typically +-1), wrapping past either end
	 * of the full 0-127 palette -- e.g. 127 + 1 wraps to 0, 0 - 1 wraps to 127. This is
	 * the direct fix for "we can't add different colors": every one of the 128 real
	 * palette entries is reachable this way, not just the ones in a hue's ladder. */
	fun nudgeVelocity(delta: Int) {
		val base = currentColorVelocity()
		val next = ((base + delta) % 128 + 128) % 128
		_colorVelocityOverride.value = next
	}

	// ---- Mark & Cut arrow-key navigation: left/right jumps between marks on a
	// physical Launchpad's top-row function keys. Reuses the same jump/highlight
	// mechanism Place's long-press already triggers -- this is just another way to
	// fire the same one-shot event. Up/down (f=0,1) reserved for later. ----

	/** True only while Mark & Cut is the visible tab (set from its onResume/onPause). */
	var isMarkAndCutTabActive: Boolean = false

	private var arrowNavIndex: Int? = null

	override fun onFunctionKeyTouch(f: Int, upDown: Boolean) {
		if (!upDown) return

		if (isLightshowTabActive) {
			if (_selectedLightshowSegment.value == null) return
			when (f) {
				0 -> stepSaturation(+1) // Up: more saturated/vivid
				1 -> stepSaturation(-1) // Down: softer
				2 -> stepHue(-1)        // Left: previous hue (also reachable via chain buttons)
				3 -> stepHue(+1)        // Right: next hue
			}
			return
		}

		if (!isMarkAndCutTabActive) return
		val session = _markingSession.value ?: return
		if (session.segmentCount == 0) return

		when (f) {
			2 -> { // Left: previous mark
				val cur = arrowNavIndex
				val next = if (cur == null) session.segmentCount - 1 else (cur - 1 + session.segmentCount) % session.segmentCount
				arrowNavIndex = next
				requestJumpToMark(next)
			}
			3 -> { // Right: next mark
				val cur = arrowNavIndex
				val next = if (cur == null) 0 else (cur + 1) % session.segmentCount
				arrowNavIndex = next
				requestJumpToMark(next)
			}
			// 0 (Up), 1 (Down): no assigned behavior yet on Mark & Cut
		}
	}

	// ---- Project save/resume: persists the imported track + MarkingSession state to
	// app-private storage so a project doesn't have to be finished start-to-finish in
	// one sitting. Track audio is copied (not raw PCM) and re-decoded on load -- decode
	// is fast now and this keeps saved projects small. ----

	data class ProjectSummary(val id: String, val name: String, val savedAtMs: Long)
	data class LoadedProjectData(val trackFilePath: String, val trackDurationMs: Int, val session: MarkingSession)

	private var currentProjectId: String? = null

	private fun projectsRoot(context: android.content.Context) = File(context.filesDir, "lpbf_projects")

	/** Copies the current track + serializes the current MarkingSession to disk. Returns
	 * false if there's nothing to save (no track imported yet). */
	fun saveCurrentProject(context: android.content.Context, name: String): Boolean {
		val session = _markingSession.value ?: return false
		val srcPath = cachedFilePath ?: return false
		val srcTrack = File(srcPath)
		if (!srcTrack.exists()) return false

		val id = currentProjectId ?: java.util.UUID.randomUUID().toString().also { currentProjectId = it }
		val dir = File(projectsRoot(context), id)
		dir.mkdirs()

		val ext = srcTrack.extension.ifEmpty { "audio" }
		val trackDest = File(dir, "track.$ext")
		srcTrack.copyTo(trackDest, overwrite = true)

		val json = JSONObject()
		json.put("projectName", name)
		json.put("trackFileName", trackDest.name)
		json.put("trackDurationMs", _decodedAudio.value?.totalDurationMs ?: 0)
		json.put("savedAtMs", System.currentTimeMillis())
		json.put("marks", JSONArray(session.marksSnapshot()))

		val buttonsArray = JSONArray()
		session.buttonsSnapshot().forEach { b ->
			if (b == null) {
				buttonsArray.put(JSONObject.NULL)
			} else {
				val bo = JSONObject()
				bo.put("chain", b.chain)
				bo.put("x", b.x)
				bo.put("y", b.y)
				buttonsArray.put(bo)
			}
		}
		json.put("buttons", buttonsArray)

		val patternsArray = JSONArray()
		session.patternsSnapshot().forEach { p -> patternsArray.put(patternToJson(p)) }
		json.put("patterns", patternsArray)

		File(dir, "session.json").writeText(json.toString())
		logDebug("Saved project '$name' (id=$id)")
		return true
	}

	fun listSavedProjects(context: android.content.Context): List<ProjectSummary> {
		val root = projectsRoot(context)
		if (!root.exists()) return emptyList()
		return root.listFiles { f -> f.isDirectory }?.mapNotNull { dir ->
			val sessionFile = File(dir, "session.json")
			if (!sessionFile.exists()) return@mapNotNull null
			try {
				val json = JSONObject(sessionFile.readText())
				ProjectSummary(
					id = dir.name,
					name = json.optString("projectName", dir.name),
					savedAtMs = json.optLong("savedAtMs", 0L)
				)
			} catch (e: Exception) {
				null
			}
		}?.sortedByDescending { it.savedAtMs } ?: emptyList()
	}

	/** Reads the persisted track path + rebuilds the MarkingSession for [id], but does
	 * NOT decode audio itself -- decode is a blocking call the caller (a Fragment) should
	 * run off the main thread, then pass the result to [applyLoadedProject]. */
	fun readProjectForLoad(context: android.content.Context, id: String): LoadedProjectData? {
		val dir = File(projectsRoot(context), id)
		val sessionFile = File(dir, "session.json")
		if (!sessionFile.exists()) return null
		return try {
			val json = JSONObject(sessionFile.readText())
			val trackFile = File(dir, json.getString("trackFileName"))
			if (!trackFile.exists()) return null
			val trackDurationMs = json.optInt("trackDurationMs", 0)

			val marksArray = json.getJSONArray("marks")
			val marks = (0 until marksArray.length()).map { marksArray.getInt(it) }

			val buttonsArray = json.getJSONArray("buttons")
			val buttons = (0 until buttonsArray.length()).map { i ->
				val item = buttonsArray.get(i)
				if (item == JSONObject.NULL) {
					null
				} else {
					val bo = item as JSONObject
					ButtonRef(chain = bo.getInt("chain"), x = bo.getInt("x"), y = bo.getInt("y"))
				}
			}

			// Older saved projects predate lightshow patterns -- default to all-null so
			// they still load cleanly instead of throwing on a missing key.
			val patternsArray = json.optJSONArray("patterns")
			val patterns = if (patternsArray != null) {
				(0 until patternsArray.length()).map { i -> patternFromJson(patternsArray.opt(i)) }
			} else {
				List(buttons.size) { null }
			}

			currentProjectId = id
			LoadedProjectData(trackFile.absolutePath, trackDurationMs, MarkingSession.restore(trackDurationMs, marks, buttons, patterns))
		} catch (e: Exception) {
			logDebug("Failed to read project $id: ${e.message}")
			null
		}
	}

	/** Applies a project after its track has been decoded (by the caller, off-thread). */
	fun applyLoadedProject(audio: DecodedAudio, loaded: LoadedProjectData) {
		cachedFilePath = loaded.trackFilePath
		_decodedAudio.value = audio
		_markingSession.value = loaded.session
		arrowNavIndex = null
		notifySegmentsChanged()
	}

	/** Applies a completed concatenated import (plain "pre-cut tracks" pick, or a
	 * Unipack's keySound mapping read via UnipackReader) as a brand new project --
	 * reuses MarkingSession.restore() exactly like project load does, since both are
	 * really the same operation: "here's a track + marks + buttons, make it current." */
	fun applyMultiClipImport(result: com.bobbypfreely.lpbf.audio.MultiClipImportResult) {
		cachedFilePath = result.cachedFilePath
		_decodedAudio.value = result.decodedAudio
		_markingSession.value = MarkingSession.restore(result.decodedAudio.totalDurationMs, result.marks, result.buttons, result.patterns)
		currentProjectId = null
		arrowNavIndex = null
		notifySegmentsChanged()
	}

	// ---- Jump-to-mark: Place (long-press a cut) asks Mark & Cut to scroll to and
	// highlight the mark ending that segment. MainActivity switches tabs; MarkAndCutFragment
	// does the scroll+highlight itself, both observing the same one-shot event. ----

	private val _jumpToMarkRequest = MutableLiveData<Int?>(null) // segment index whose END mark to highlight
	val jumpToMarkRequest: LiveData<Int?> = _jumpToMarkRequest

	fun requestJumpToMark(segmentIndex: Int) {
		_jumpToMarkRequest.value = segmentIndex
	}

	fun clearJumpToMarkRequest() {
		_jumpToMarkRequest.value = null
	}

	// ---- Cap prompt resolution, called from the dialog the UI shows ----

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

	// ---- Pattern <-> JSON, used only by save/load above. Kept minimal -- Pattern's
	// fields are all primitives already, this just avoids pulling in a JSON library
	// wrapper for four fields. ----

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
