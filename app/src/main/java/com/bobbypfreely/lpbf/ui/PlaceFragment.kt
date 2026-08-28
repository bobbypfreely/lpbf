package com.bobbypfreely.lpbf.ui

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.bobbypfreely.lpbf.R
import com.bobbypfreely.lpbf.viewmodel.ProjectViewModel
import com.bobbypfreely.lpbf.waveform.ExoPlaybackController

/**
 * Place: assigns each provisional segment (from Mark and Cut) to a Launchpad button,
 * and lets you preview the mapping before exporting.
 *
 * Two modes, toggled by placeModeToggle:
 *  - EDIT (default): tapping a pad assigns the next unassigned cut to it, auto-advancing
 *    down the ordered list. Tapping an already-assigned pad stacks another cut onto it
 *    (multi-trigger cycling) -- MarkingSession has no unique-button constraint.
 *  - PLAY: tapping a pad previews whatever's mapped to it instead of assigning anything.
 *    Repeated presses on a stacked pad cycle through each cut mapped there.
 *
 * Both physical Launchpad presses and on-screen grid taps are routed through the exact
 * same ProjectViewModel.onPadDown/onPadUp so mode logic only has to live in one place.
 *
 * Long-pressing a cut in the list jumps back to Mark & Cut with that mark highlighted,
 * for quick fine-adjustment without a dedicated screen.
 */
class PlaceFragment : Fragment(R.layout.fragment_place) {

	private val viewModel: ProjectViewModel by activityViewModels()

	private lateinit var statusText: TextView
	private lateinit var modeToggle: Button
	private lateinit var segmentListContainer: LinearLayout
	private lateinit var grid: VirtualLaunchpadGridView

	private var previewController: ExoPlaybackController? = null
	private val previewStopHandler = Handler(Looper.getMainLooper())

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		statusText = view.findViewById(R.id.placeStatusText)
		modeToggle = view.findViewById(R.id.placeModeToggle)
		segmentListContainer = view.findViewById(R.id.segmentListContainer)
		grid = view.findViewById(R.id.placeGrid)

		// Virtual grid taps go through the exact same code path a physical Launchpad
		// press does -- PadInputListener doesn't care which one fired it, and neither
		// does the mode logic that lives in ProjectViewModel.
		grid.listener = object : PadInputListener {
			override fun onPadDown(x: Int, y: Int) = viewModel.onPadDown(x, y)
			override fun onPadUp(x: Int, y: Int) = viewModel.onPadUp(x, y)
		}

		modeToggle.setOnClickListener {
			val next = if (viewModel.placeMode.value == ProjectViewModel.PlaceMode.EDIT) {
				ProjectViewModel.PlaceMode.PLAY
			} else {
				ProjectViewModel.PlaceMode.EDIT
			}
			viewModel.setPlaceMode(next)
		}

		viewModel.placeMode.observe(viewLifecycleOwner) { mode ->
			modeToggle.text = "Mode: ${mode.name.lowercase().replaceFirstChar { it.uppercase() }}"
			refresh()
		}

		viewModel.previewRequest.observe(viewLifecycleOwner) { request ->
			if (request != null) {
				playPreview(request.startMs, request.endMs)
				viewModel.clearPreviewRequest()
			}
		}

		viewModel.segmentVersion.observe(viewLifecycleOwner) { refresh() }
		viewModel.markingSession.observe(viewLifecycleOwner) { refresh() }
	}

	override fun onResume() {
		super.onResume()
		viewModel.isPlaceTabActive = true
	}

	override fun onPause() {
		super.onPause()
		viewModel.isPlaceTabActive = false
		previewStopHandler.removeCallbacksAndMessages(null)
		previewController?.release()
		previewController = null
	}

	// ---- Preview playback (Play mode) ----
	//
	// A fresh ExoPlaybackController is created per preview and fully released (not just
	// paused) as soon as it's done, or the moment this tab loses focus. Previously this
	// held one long-lived instance, which could stay alive at the same time as Mark &
	// Cut's own player and fight it for audio focus, breaking playback elsewhere in the
	// app. Only ever one of these exists at a time now.

	private fun playPreview(startMs: Int, endMs: Int) {
		val path = viewModel.cachedFilePath ?: return

		previewStopHandler.removeCallbacksAndMessages(null)
		previewController?.release()

		val controller = ExoPlaybackController(requireContext())
		controller.load(path)
		previewController = controller
		controller.playFrom(startMs)

		val durationMs = (endMs - startMs).coerceAtLeast(0).toLong()
		previewStopHandler.postDelayed({
			controller.pause()
			controller.release()
			if (previewController === controller) previewController = null
		}, durationMs)
	}

	// ---- Segment list + grid rendering ----

	private fun refresh() {
		val session = viewModel.markingSession.value

		if (session == null || session.segmentCount == 0) {
			statusText.text = "Import and mark a track first."
			segmentListContainer.removeAllViews()
			grid.clearAllPads()
			return
		}

		val segments = session.segments()
		val nextIndex = segments.indexOfFirst { it.button == null }
		val isPlayMode = viewModel.placeMode.value == ProjectViewModel.PlaceMode.PLAY

		statusText.text = when {
			isPlayMode -> "Play mode: tap a pad to preview its cut(s)."
			nextIndex == -1 -> "All ${segments.size} cut(s) assigned. Tap a pad again to stack another cut on it."
			else -> "Cut ${nextIndex + 1} of ${segments.size} -- tap a pad to assign it."
		}

		segmentListContainer.removeAllViews()
		segments.forEachIndexed { index, seg ->
			val row = TextView(requireContext()).apply {
				textSize = 13f
				setPadding(8, 8, 8, 8)
				val label = seg.button?.let { "pad (${it.x}, ${it.y})" } ?: "unassigned"
				text = "Cut ${index + 1}  --  ${seg.durationMs}ms  --  $label"
				if (index == nextIndex && !isPlayMode) {
					setBackgroundColor(Color.parseColor("#2A2A3E"))
					setTextColor(Color.parseColor("#00ADB5"))
				} else {
					setTextColor(Color.parseColor("#DDDDDD"))
				}
				setOnLongClickListener {
					viewModel.requestJumpToMark(index)
					true
				}
			}
			segmentListContainer.addView(row)
		}

		// Light every pad that has at least one segment assigned to it.
		grid.clearAllPads()
		val litColor = Color.parseColor("#00ADB5")
		segments.forEach { seg ->
			seg.button?.let { grid.setPadLit(it.x, it.y, litColor) }
		}
	}

	override fun onDestroyView() {
		previewStopHandler.removeCallbacksAndMessages(null)
		previewController?.release()
		previewController = null
		super.onDestroyView()
	}
}
