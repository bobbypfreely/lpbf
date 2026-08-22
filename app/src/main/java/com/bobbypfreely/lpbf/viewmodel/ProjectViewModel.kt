package com.bobbypfreely.lpbf.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.bobbypfreely.lpbf.audio.AudioPlaybackController
import com.bobbypfreely.lpbf.audio.DecodedAudio
import com.bobbypfreely.lpbf.marking.ButtonRef
import com.bobbypfreely.lpbf.marking.MarkingSession
import com.bobbypfreely.lpbf.ui.PadInputListener

/** Surfaced to the UI when a mark would create a >8s segment -- the user must pick one. */
data class CapPromptState(
	val gapMs: Int,
	val maxMs: Int,
	val button: ButtonRef,
	val requestedStopMs: Int,
)

/**
 * Activity-scoped: all three phase fragments (Record / Fine-tune / Splice) share the exact
 * same MarkingSession and decoded audio, switching tabs never loses state. Also owns the
 * actual pad-press business logic as a PadInputListener, so it's identical whether the press
 * came from a physical Launchpad or the on-screen virtual grid.
 */
class ProjectViewModel : ViewModel(), PadInputListener {

	private val _decodedAudio = MutableLiveData<DecodedAudio?>(null)
	val decodedAudio: LiveData<DecodedAudio?> = _decodedAudio

	private val _markingSession = MutableLiveData<MarkingSession?>(null)
	val markingSession: LiveData<MarkingSession?> = _markingSession

	private val _selectedButton = MutableLiveData<ButtonRef?>(null)
	val selectedButton: LiveData<ButtonRef?> = _selectedButton

	private val _connectedDeviceName = MutableLiveData<String?>(null)
	val connectedDeviceName: LiveData<String?> = _connectedDeviceName

	private val _isPlaying = MutableLiveData(false)
	val isPlaying: LiveData<Boolean> = _isPlaying

	private val _capPrompt = MutableLiveData<CapPromptState?>(null)
	val capPrompt: LiveData<CapPromptState?> = _capPrompt

	/** Simple observable trigger for "something changed, refresh the UI." */
	private val _segmentVersion = MutableLiveData(0)
	val segmentVersion: LiveData<Int> = _segmentVersion

	/** DIAGNOSTIC: fires unconditionally the instant a pad event is received, before
	 * any other logic runs -- lets us confirm whether input is even reaching here at all,
	 * independent of whether playback/marking afterward works. Remove once things are stable. */
	private val _debugEvent = MutableLiveData<String>()
	val debugEvent: LiveData<String> = _debugEvent

	var playbackController: AudioPlaybackController? = null
		private set

	fun setDecodedAudio(audio: DecodedAudio) {
		_decodedAudio.value = audio
		playbackController = AudioPlaybackController(audio) { msg -> _debugEvent.postValue(msg) }
		_markingSession.value = MarkingSession(audio.totalDurationMs)
		notifySegmentsChanged()
	}

	fun setConnectedDeviceName(name: String?) {
		_connectedDeviceName.value = name
	}

	private fun notifySegmentsChanged() {
		_segmentVersion.value = (_segmentVersion.value ?: 0) + 1
	}

	// ---- PadInputListener: identical whether fired by MIDI hardware or the virtual grid ----

	override fun onPadDown(x: Int, y: Int) {
		_debugEvent.postValue("DOWN pad ($x,$y)")
		try {
			val session = _markingSession.value ?: run {
				_debugEvent.postValue("DOWN ($x,$y) but no MarkingSession -- import a track first")
				return
			}
			if (session.isSpliced) return
			val button = ButtonRef(chain = 0, x = x, y = y)
			_selectedButton.value = button
			playbackController?.playFrom(session.lastMarkMs())
			_isPlaying.value = true
		} catch (e: Exception) {
			android.util.Log.e("ProjectViewModel", "onPadDown error", e)
			_debugEvent.postValue("onPadDown ERROR: ${e.message}")
		}
	}

	override fun onPadUp(x: Int, y: Int) {
		_debugEvent.postValue("UP pad ($x,$y)")
		try {
			val session = _markingSession.value ?: return
			if (session.isSpliced) return
			val stopMs = playbackController?.stop() ?: return
			_isPlaying.value = false
			val button = ButtonRef(chain = 0, x = x, y = y)
			_debugEvent.postValue("UP ($x,$y) at stopMs=$stopMs, lastMark=${session.lastMarkMs()}")

			if (stopMs <= session.lastMarkMs()) {
				_debugEvent.postValue("Ignored: stopMs=$stopMs <= lastMark=${session.lastMarkMs()} (press too short / no playback progress)")
				return
			}

			when (val result = session.recordMark(stopMs, button)) {
				is MarkingSession.RecordResult.Committed -> notifySegmentsChanged()
				is MarkingSession.RecordResult.ExceedsCap -> {
					_capPrompt.value = CapPromptState(result.gapMs, result.maxMs, button, stopMs)
				}
			}
		} catch (e: Exception) {
			android.util.Log.e("ProjectViewModel", "onPadUp error", e)
			_debugEvent.postValue("onPadUp ERROR: ${e.message}")
		}
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

	override fun onCleared() {
		playbackController?.stop()
		super.onCleared()
	}
}
