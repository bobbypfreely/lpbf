package com.bobbypfreely.lpbf.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.bobbypfreely.lpbf.R
import com.bobbypfreely.lpbf.marking.MarkingSession
import com.bobbypfreely.lpbf.viewmodel.ProjectViewModel

/**
 * Place: assigns each provisional segment (from Mark and Cut) to a Launchpad button.
 *
 * Design: an ordered list of segments is shown top to bottom; the first one with no
 * button yet is the "next" target and is highlighted. Tapping any pad -- physical or
 * the on-screen grid below, both routed through ProjectViewModel's PadInputListener --
 * assigns that pad to the next target and auto-advances. Tapping a pad that's already
 * assigned stacks another segment onto it (multi-trigger cycling): MarkingSession
 * doesn't require unique buttons per segment, so this needs no new state, just repeated
 * calls to the same assignNextSegment() path.
 *
 * viewModel.isPlaceTabActive gates physical Launchpad presses so a hardware pad hit on
 * another tab doesn't silently reassign something here; it's set true/false from this
 * fragment's own onResume/onPause, so no other file needs to know this tab exists.
 */
class PlaceFragment : Fragment(R.layout.fragment_place) {

	private val viewModel: ProjectViewModel by activityViewModels()

	private lateinit var statusText: TextView
	private lateinit var segmentListContainer: LinearLayout
	private lateinit var grid: VirtualLaunchpadGridView

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		statusText = view.findViewById(R.id.placeStatusText)
		segmentListContainer = view.findViewById(R.id.segmentListContainer)
		grid = view.findViewById(R.id.placeGrid)

		// Virtual grid taps go through the exact same assignment path as a physical
		// Launchpad -- PadInputListener doesn't care which one fired it.
		grid.listener = object : PadInputListener {
			override fun onPadDown(x: Int, y: Int) {
				viewModel.assignNextSegment(x, y)
			}
			override fun onPadUp(x: Int, y: Int) {}
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
	}

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

		statusText.text = if (nextIndex == -1) {
			"All ${segments.size} cut(s) assigned. Tap a pad again to stack another cut on it."
		} else {
			"Cut ${nextIndex + 1} of ${segments.size} -- tap a pad to assign it."
		}

		segmentListContainer.removeAllViews()
		segments.forEachIndexed { index, seg ->
			val row = TextView(requireContext()).apply {
				textSize = 13f
				setPadding(8, 8, 8, 8)
				val label = seg.button?.let { "pad (${it.x}, ${it.y})" } ?: "unassigned"
				text = "Cut ${index + 1}  --  ${seg.durationMs}ms  --  $label"
				if (index == nextIndex) {
					setBackgroundColor(Color.parseColor("#2A2A3E"))
					setTextColor(Color.parseColor("#00ADB5"))
				} else {
					setTextColor(Color.parseColor("#DDDDDD"))
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
}
