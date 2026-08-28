package com.bobbypfreely.lpbf.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.bobbypfreely.lpbf.audio.DecodedAudio
import com.bobbypfreely.lpbf.marking.ButtonRef
import com.bobbypfreely.lpbf.marking.MarkingSession
import com.bobbypfreely.lpbf.ui.PadInputListener

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

	private fun logDebug(msg: String) {
		synchronized(debugLogBuilder) {
			debugLogBuilder.append(msg).append('\n')
			_debugLog.postValue(debugLogBuilder.toString())
		}
	}

	/** Path to the locally-cached copy of the imported file, set by whoever imports it. */
	var cachedFilePath: String? = null

	fun setDecodedAudio(audio: DecodedAudio, filePath: String) {
		_decodedAudio.value = audio
		cachedFilePath = filePath
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

	enum class PlaceMode { EDIT, PLAY }

	private val _placeMode = MutableLiveData(PlaceMode.EDIT)
	val placeMode: LiveData<PlaceMode> = _placeMode

	fun setPlaceMode(mode: PlaceMode) {
		_placeMode.value = mode
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
		if (!isPlaceTabActive) {
			logDebug("Pad DOWN ($x,$y) ignored -- not on Place tab")
			return
		}
		when (_placeMode.value) {
			PlaceMode.PLAY -> previewPad(x, y)
			else -> assignNextSegment(x, y)
		}
	}

	override fun onPadUp(x: Int, y: Int) {}

	/** Assigns [x],[y] (chain 0 -- dual-Launchpad chain selection not yet wired into
	 * PadInputListener) to the first segment with no button, or stacks onto an existing
	 * assignment if every segment already has one. */
	fun assignNextSegment(x: Int, y: Int) {
		val session = _markingSession.value ?: return
		if (session.isSpliced) return

		val nextIndex = (0 until session.segmentCount).firstOrNull { session.segment(it).button == null }
		if (nextIndex == null) {
			logDebug("Pad ($x,$y): all cuts already assigned -- nothing left to auto-advance to")
			return
		}

		session.reassignButton(nextIndex, ButtonRef(chain = 0, x = x, y = y))
		logDebug("Assigned cut ${nextIndex + 1} -> pad ($x,$y)")
		notifySegmentsChanged()
	}

	/** Finds every cut mapped to [x],[y] and previews the next one in line, cycling back
	 * to the first after the last -- this is what makes a stacked (multi-trigger) pad
	 * play a different cut each press instead of always the same one. */
	private fun previewPad(x: Int, y: Int) {
		val session = _markingSession.value ?: return
		val button = ButtonRef(chain = 0, x = x, y = y)
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
}
