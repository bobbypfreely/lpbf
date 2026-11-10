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

/** Place: assigns each provisional segment (from Mark and Cut) to a Launchpad button, */
class PlaceFragment : Fragment(R.layout.fragment_place) {

	private val viewModel: ProjectViewModel by activityViewModels()

	private lateinit var statusText: TextView
	private lateinit var modeToggle: Button
	private lateinit var chainSelectorRow: LinearLayout
	private lateinit var segmentListContainer: LinearLayout
	private lateinit var grid: VirtualLaunchpadGridView

	private val chainButtons = mutableListOf<Button>()

	private var previewController: ExoPlaybackController? = null
	private val previewStopHandler = Handler(Looper.getMainLooper())

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		statusText = view.findViewById(R.id.placeStatusText)
		modeToggle = view.findViewById(R.id.placeModeToggle)
		chainSelectorRow = view.findViewById(R.id.chainSelectorRow)
		segmentListContainer = view.findViewById(R.id.segmentListContainer)
		grid = view.findViewById(R.id.placeGrid)

		buildChainSelector()

		// Virtual grid taps go through the exact same code path a physical Launchpad
		// press does -- PadInputListener doesn't care which one fired it, and neither
		// does the mode/chain logic that lives in ProjectViewModel.
		grid.listener = object : PadInputListener {
			override fun onPadDown(x: Int, y: Int) = viewModel.onPadDown(x, y)
			override fun onPadUp(x: Int, y: Int) = viewModel.onPadUp(x, y)
		}

		modeToggle.setOnClickListener {
			val next = when (viewModel.placeMode.value) {
				ProjectViewModel.PlaceMode.EDIT -> ProjectViewModel.PlaceMode.PLAY
				ProjectViewModel.PlaceMode.PLAY -> ProjectViewModel.PlaceMode.HYBRID
				else -> ProjectViewModel.PlaceMode.EDIT
			}
			viewModel.setPlaceMode(next)
		}

		viewModel.placeMode.observe(viewLifecycleOwner) { mode ->
			modeToggle.text = "Mode: ${mode.name.lowercase().replaceFirstChar { it.uppercase() }}"
			refresh()
		}

		viewModel.currentChain.observe(viewLifecycleOwner) { refresh() }

		viewModel.previewRequest.observe(viewLifecycleOwner) { request ->
			if (request != null) {
				playPreview(request.startMs, request.endMs)
				playLightshowLive(request.pattern, request.endMs - request.startMs)
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

	// ---- Chain selector (8 side-button pages, mirrors a real Launchpad) ----

	private fun buildChainSelector() {
		chainSelectorRow.removeAllViews()
		chainButtons.clear()
		for (chain in 0 until 8) {
			val button = Button(requireContext()).apply {
				text = (chain + 1).toString()
				textSize = 12f
				layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
					marginEnd = if (chain < 7) 4 else 0
				}
				setPadding(0, 8, 0, 8)
				setOnClickListener { viewModel.setCurrentChain(chain) }
			}
			chainSelectorRow.addView(button)
			chainButtons.add(button)
		}
	}

	private fun updateChainSelectorHighlight(activeChain: Int) {
		chainButtons.forEachIndexed { chain, button ->
			if (chain == activeChain) {
				button.setBackgroundColor(Color.parseColor("#00ADB5"))
				button.setTextColor(Color.parseColor("#0F0F1A"))
			} else {
				button.setBackgroundColor(Color.parseColor("#1A1A2E"))
				button.setTextColor(Color.parseColor("#DDDDDD"))
			}
		}
	}

	// ---- Preview playback (Play/Hybrid modes) ----
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

	/** /** The real "play with music and lightshow" piece: schedules this cut's Pattern */
	private fun playLightshowLive(pattern: com.bobbypfreely.lpbf.lightshow.Pattern?, durationMs: Int) {
		if (pattern == null || durationMs <= 0) return
		pattern.keyframes.forEach { kf ->
			if (kf.x < 0 || kf.y < 0) return@forEach // mc/l -- not on the visible grid
			val delayMs = (kf.t * durationMs).toLong().coerceAtLeast(0)
			previewStopHandler.postDelayed({
				if (kf.on) {
					val color = kf.color ?: velocityToColor(kf.velocity)
					grid.setPadLit(kf.x, kf.y, color)
				} else {
					grid.clearPad(kf.x, kf.y)
				}
			}, delayMs)
		}
	}

	/** /** Rough palette approximation for a raw velocity when no explicit color was given */
	private fun velocityToColor(velocity: Int): Int {
		val hue = (velocity.coerceIn(0, 127) / 127f) * 300f
		return Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
	}

	// ---- Segment list + grid rendering ----

	private fun refresh() {
		val session = viewModel.markingSession.value
		val activeChain = viewModel.currentChain.value ?: 0
		updateChainSelectorHighlight(activeChain)

		if (session == null || session.segmentCount == 0) {
			statusText.text = "Import and mark a track first."
			segmentListContainer.removeAllViews()
			grid.clearAllPads()
			return
		}

		val segments = session.segments()
		val nextIndex = segments.indexOfFirst { it.button == null }
		val mode = viewModel.placeMode.value ?: ProjectViewModel.PlaceMode.EDIT
		val isAssignMode = mode == ProjectViewModel.PlaceMode.EDIT || mode == ProjectViewModel.PlaceMode.HYBRID

		statusText.text = when {
			!isAssignMode -> "Chain ${activeChain + 1} -- Play mode: tap a pad to preview its cut(s)."
			nextIndex == -1 -> "Chain ${activeChain + 1} -- all ${segments.size} cut(s) assigned. Tap a pad again to stack another cut on it."
			mode == ProjectViewModel.PlaceMode.HYBRID -> "Chain ${activeChain + 1} -- Hybrid: cut ${nextIndex + 1} of ${segments.size}, tap a pad to place and hear it."
			else -> "Chain ${activeChain + 1} -- cut ${nextIndex + 1} of ${segments.size} -- tap a pad to assign it."
		}

		segmentListContainer.removeAllViews()
		segments.forEachIndexed { index, seg ->
			val row = TextView(requireContext()).apply {
				textSize = 13f
				setPadding(8, 8, 8, 8)
				val button = seg.button
				val label = when {
					button == null -> "unassigned"
					button.chain == activeChain -> "pad (${button.x}, ${button.y})"
					else -> "chain ${button.chain + 1}, pad (${button.x}, ${button.y})"
				}
				text = "Cut ${index + 1}  --  ${seg.durationMs}ms  --  $label"
				if (index == nextIndex && isAssignMode) {
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

		// Only light pads assigned on the chain currently being viewed -- a real
		// Launchpad's grid only ever shows the 64 pads of its currently selected chain.
		// Label each lit pad with the order it was FIRST placed in (1, 2, 3...) so a
		// sequence is readable at a glance; a stacked (multi-trigger) pad shows the
		// earliest cut's number since that's when the pad itself entered the sequence.
		grid.clearAllPads()
		val litColor = Color.parseColor("#00ADB5")
		val firstIndexByPad = HashMap<Pair<Int, Int>, Int>()
		segments.forEachIndexed { index, seg ->
			val button = seg.button
			if (button != null && button.chain == activeChain) {
				val key = button.x to button.y
				val existing = firstIndexByPad[key]
				if (existing == null || index < existing) {
					firstIndexByPad[key] = index
				}
			}
		}
		firstIndexByPad.forEach { (pad, index) ->
			grid.setPadLit(pad.first, pad.second, litColor, (index + 1).toString())
		}
	}

	override fun onDestroyView() {
		previewStopHandler.removeCallbacksAndMessages(null)
		previewController?.release()
		previewController = null
		super.onDestroyView()
	}
}
