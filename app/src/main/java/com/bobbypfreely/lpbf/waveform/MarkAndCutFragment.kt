package com.bobbypfreely.lpbf.waveform

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.bobbypfreely.lpbf.R
import com.bobbypfreely.lpbf.audio.AudioDecoder
import com.bobbypfreely.lpbf.marking.MarkingSession
import com.bobbypfreely.lpbf.viewmodel.ProjectViewModel
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MarkAndCutFragment : Fragment(R.layout.fragment_mark_and_cut), WaveformView.WaveformListener {

	private val viewModel: ProjectViewModel by activityViewModels()

	private lateinit var waveformView: WaveformView
	private lateinit var markOverlay: MarkOverlayView
	private lateinit var waveformContainer: FrameLayout
	private lateinit var statusText: TextView
	private lateinit var connectionText: TextView
	private lateinit var playPauseButton: Button
	private lateinit var restartButton: Button
	private lateinit var playbackSeekBar: android.widget.SeekBar
	private lateinit var debugLog: TextView
	private lateinit var debugScroll: ScrollView

	private var exoController: ExoPlaybackController? = null
	private val markerViews = mutableListOf<MarkerView>()

	private var touchStartX = 0f
	private var touchInitialOffset = 0

	private data class DragState(val markIndex: Int, val originalMs: Int, val startRawX: Float)
	private var dragState: DragState? = null

		private var highlightedMarkMs: Int? = null
		private val highlightClearHandler = android.os.Handler(android.os.Looper.getMainLooper())

	private var isUserSeeking = false
	private val positionPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
	private val positionPollTick = object : Runnable {
		override fun run() {
			val controller = exoController
			if (controller != null && !isUserSeeking) {
				playbackSeekBar.progress = controller.currentPositionMs()
			}
			positionPollHandler.postDelayed(this, 200L)
		}
	}

	private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
		if (uri != null) decodeAndLoad(uri)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		waveformView = view.findViewById(R.id.waveformView)
		markOverlay = view.findViewById(R.id.markOverlay)
		waveformContainer = view.findViewById(R.id.waveformContainer)
		statusText = view.findViewById(R.id.statusText)
		connectionText = view.findViewById(R.id.connectionText)
		playPauseButton = view.findViewById(R.id.playPauseButton)
		restartButton = view.findViewById(R.id.restartButton)
		playbackSeekBar = view.findViewById(R.id.playbackSeekBar)
		debugLog = view.findViewById(R.id.debugLog)
		debugScroll = view.findViewById(R.id.debugScroll)

		markOverlay.waveformView = waveformView
		waveformView.setListener(this)

		view.findViewById<Button>(R.id.importButton).setOnClickListener {
			pickAudio.launch(arrayOf("audio/*"))
		}
		view.findViewById<Button>(R.id.zoomInButton).setOnClickListener {
			waveformView.zoomIn(); waveformView.invalidate(); refreshMarkers()
		}
		view.findViewById<Button>(R.id.zoomOutButton).setOnClickListener {
			waveformView.zoomOut(); waveformView.invalidate(); refreshMarkers()
		}
		playPauseButton.setOnClickListener { togglePlayPause() }
		restartButton.setOnClickListener { exoController?.playFrom(0) }
		playbackSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {}
			override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {
				isUserSeeking = true
			}
			override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {
				isUserSeeking = false
				exoController?.seekTo(seekBar.progress)
			}
		})
		view.findViewById<Button>(R.id.dropMarkButton).setOnClickListener { dropMark() }

		viewModel.connectedDeviceName.observe(viewLifecycleOwner) { name ->
			connectionText.text = if (name != null) "Connected: $name" else "No Launchpad connected"
		}

		viewModel.decodedAudio.observe(viewLifecycleOwner) { audio ->
			if (audio != null) {
				val gainData = WaveformDataBuilder.build(audio)
				waveformView.setAudioData(
					gainData.numFrames, gainData.frameGains, gainData.sampleRate, gainData.samplesPerFrame
				)
				statusText.text = "Decoded: ${audio.totalDurationMs}ms. Play, then tap 'Mark Cut Here' to cut."
				playbackSeekBar.max = audio.totalDurationMs
				setupPlayer()
				waveformView.post { refreshMarkers() }
			}
		}

		viewModel.segmentVersion.observe(viewLifecycleOwner) {
			refreshMarkers()
			val session = viewModel.markingSession.value
			val count = session?.segmentCount ?: 0
			if (viewModel.decodedAudio.value != null) {
				statusText.text = "$count cut(s) so far."
			}
		}

		viewModel.capPrompt.observe(viewLifecycleOwner) { prompt ->
			if (prompt != null) showCapDialog(prompt.gapMs, prompt.maxMs)
		}

		viewModel.debugLog.observe(viewLifecycleOwner) { fullText ->
			debugLog.text = fullText
			debugScroll.post { debugScroll.fullScroll(View.FOCUS_DOWN) }
		}

		viewModel.jumpToMarkRequest.observe(viewLifecycleOwner) { segIndex ->
			if (segIndex != null) {
				jumpAndHighlight(segIndex)
				viewModel.clearJumpToMarkRequest()
			}
		}
	}

	// ---- Player setup ----

	private fun setupPlayer() {
		val path = viewModel.cachedFilePath ?: return
		exoController?.release()
		val controller = ExoPlaybackController(requireContext())
		controller.load(path)
		controller.onPlaybackStateChanged = { playing ->
			activity?.runOnUiThread {
				playPauseButton.text = if (playing) "Pause" else "Play"
				if (playing) {
					positionPollHandler.removeCallbacks(positionPollTick)
					positionPollHandler.post(positionPollTick)
				} else {
					positionPollHandler.removeCallbacks(positionPollTick)
				}
			}
		}
		exoController = controller
	}

	private fun togglePlayPause() {
		val controller = exoController ?: return
		val session = viewModel.markingSession.value ?: return
		if (controller.isPlaying) {
			controller.pause()
		} else {
			controller.playFrom(session.lastMarkMs())
		}
	}

	private fun dropMark() {
		val controller = exoController ?: return
		val posMs = controller.currentPositionMs()
		viewModel.dropMark(posMs)
	}

	private fun showCapDialog(gapMs: Int, maxMs: Int) {
		AlertDialog.Builder(requireContext())
			.setTitle("Segment too long")
			.setMessage("This segment would be ${gapMs}ms, over the ${maxMs}ms cap.")
			.setPositiveButton("Auto-split at 8s") { _, _ -> viewModel.resolveCapAutoSplit() }
			.setNeutralButton("Place anyway") { _, _ -> viewModel.resolveCapPlaceAnyway() }
			.setNegativeButton("Cancel") { _, _ -> viewModel.resolveCapCancel() }
			.setCancelable(false)
			.show()
	}

	// ---- Marker handles: one draggable MarkerView per mark (except the fixed track-start mark) ----

	private fun refreshMarkers() {
		val session = viewModel.markingSession.value ?: return
		if (!waveformView.hasSoundFile()) return

		// Derive mark positions from segments: [0] + each segment's end.
		val markPositions = mutableListOf(0)
		session.segments().forEach { markPositions.add(it.endMs) }
		markOverlay.markPositionsMs = markPositions

		// Rebuild marker handles for all draggable marks (index 1..last).
		markerViews.forEach { waveformContainer.removeView(it) }
		markerViews.clear()

		val offset = waveformView.offset
		val handleSizePx = (24 * resources.displayMetrics.density).roundToInt()

		for (index in 1 until markPositions.size) {
			val ms = markPositions[index]
			val px = waveformView.millisecsToPixels(ms) - offset
			val marker = MarkerView(requireContext(), null)
			marker.setBackgroundResource(
				if (ms == highlightedMarkMs) R.drawable.marker_handle_highlighted else R.drawable.marker_handle
			)
			val lp = FrameLayout.LayoutParams(handleSizePx, handleSizePx)
			lp.leftMargin = px - handleSizePx / 2
			lp.topMargin = 0
			marker.layoutParams = lp
			marker.setListener(makeMarkerListener(index, ms))
			waveformContainer.addView(marker)
			markerViews.add(marker)
		}
	}

	/** Called when Place long-presses a cut to jump here. Scrolls the waveform so
	 * the mark ending that segment is roughly centered, and flashes its handle a
	 * different color for a few seconds so it's easy to find and drag. */
	private fun jumpAndHighlight(segmentIndex: Int) {
		val session = viewModel.markingSession.value ?: return
		if (segmentIndex !in session.segments().indices) return
		if (!waveformView.hasSoundFile()) return

		val ms = session.segment(segmentIndex).endMs
		highlightedMarkMs = ms
		highlightClearHandler.removeCallbacksAndMessages(null)

		val px = waveformView.millisecsToPixels(ms)
		val visibleWidth = waveformContainer.width.takeIf { it > 0 } ?: waveformView.width
		var newOffset = px - visibleWidth / 2
		if (newOffset < 0) newOffset = 0
		val maxPos = waveformView.maxPos()
		if (newOffset > maxPos) newOffset = maxPos
		waveformView.setParameters(waveformView.start, waveformView.end, newOffset)
		waveformView.invalidate()
		refreshMarkers()
		statusText.text = "Jumped to cut ${segmentIndex + 1}'s end mark for editing."

		highlightClearHandler.postDelayed({
			if (highlightedMarkMs == ms) {
				highlightedMarkMs = null
				refreshMarkers()
			}
		}, 3000L)
	}

	private fun makeMarkerListener(markIndex: Int, originalMs: Int) = object : MarkerView.MarkerListener {
		override fun markerTouchStart(marker: MarkerView, pos: Float) {
			dragState = DragState(markIndex, originalMs, pos)
		}

		override fun markerTouchMove(marker: MarkerView, pos: Float) {
			val state = dragState ?: return
			val delta = pos - state.startRawX
			marker.translationX = delta
		}

		override fun markerTouchEnd(marker: MarkerView) {
			val state = dragState ?: return
			dragState = null
			val session = viewModel.markingSession.value ?: return

			val offset = waveformView.offset
			val originalPx = waveformView.millisecsToPixels(state.originalMs) - offset
			val finalPx = originalPx + marker.translationX.roundToInt()
			val newMs = waveformView.pixelsToMillisecs(finalPx + offset)

			when (val result = session.rippleMoveMark(state.markIndex, newMs)) {
				is MarkingSession.RippleResult.Success -> viewModel.notifySegmentsChanged()
				is MarkingSession.RippleResult.Rejected -> {
					marker.translationX = 0f
					statusText.text = "Can't move mark: ${result.reason}"
				}
			}
		}

		override fun markerFocus(marker: MarkerView) {}
		override fun markerLeft(marker: MarkerView, velocity: Int) {}
		override fun markerRight(marker: MarkerView, velocity: Int) {}
		override fun markerEnter(marker: MarkerView) {}
		override fun markerKeyUp() {}
		override fun markerDraw() {}
	}

	// ---- WaveformView.WaveformListener: background pan/zoom ----

	override fun waveformTouchStart(x: Float) {
		touchStartX = x
		touchInitialOffset = waveformView.offset
	}

	override fun waveformTouchMove(x: Float) {
		val maxPos = waveformView.maxPos()
		var newOffset = (touchInitialOffset + (touchStartX - x)).roundToInt()
		if (newOffset < 0) newOffset = 0
		if (newOffset > maxPos) newOffset = maxPos
		waveformView.setParameters(waveformView.start, waveformView.end, newOffset)
		waveformView.invalidate()
		refreshMarkers()
	}

	override fun waveformTouchEnd() {}
	override fun waveformFling(x: Float) {}
	override fun waveformDraw() {}
	override fun waveformZoomIn() {
		waveformView.zoomIn(); waveformView.invalidate(); refreshMarkers()
	}
	override fun waveformZoomOut() {
		waveformView.zoomOut(); waveformView.invalidate(); refreshMarkers()
	}

	// ---- Import ----

	private fun decodeAndLoad(uri: Uri) {
		val context = requireContext().applicationContext
		activity?.runOnUiThread { statusText.text = "Copying file\u2026" }
		thread(name = "lpbf-decode") {
			try {
				val tempFile = copyUriToCacheFile(context, uri)
				activity?.runOnUiThread {
					statusText.text = "Copied ${tempFile.length()} bytes, decoding\u2026"
				}
				val audio = AudioDecoder.decode(tempFile.absolutePath)
				activity?.runOnUiThread {
					viewModel.setDecodedAudio(audio, tempFile.absolutePath)
				}
			} catch (e: Exception) {
				android.util.Log.e("MarkAndCutFragment", "Decode failed", e)
				activity?.runOnUiThread {
					statusText.text = "Decode FAILED: ${e.javaClass.simpleName}: ${e.message}"
				}
			}
		}
	}

	private fun copyUriToCacheFile(context: android.content.Context, uri: Uri): java.io.File {
		val tempFile = java.io.File(context.cacheDir, "lpbf_import_temp.audio")
		val input = context.contentResolver.openInputStream(uri)
			?: error("Could not open input stream for $uri")
		input.use { inStream ->
			tempFile.outputStream().use { outStream ->
				inStream.copyTo(outStream)
			}
		}
		return tempFile
	}

	override fun onDestroyView() {
		exoController?.release()
		exoController = null
		highlightClearHandler.removeCallbacksAndMessages(null)
		positionPollHandler.removeCallbacksAndMessages(null)
		super.onDestroyView()
	}
}
