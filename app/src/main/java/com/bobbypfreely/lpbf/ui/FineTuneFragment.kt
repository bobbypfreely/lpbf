package com.bobbypfreely.lpbf.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.bobbypfreely.lpbf.R
import com.bobbypfreely.lpbf.viewmodel.ProjectViewModel

/**
 * STUB. Phase 2: zoomable waveform with draggable, ripple-adjusting marks.
 * Reads from the same shared MarkingSession -- segments/marks are already
 * real by the time this screen matters, this just needs the waveform view
 * and drag-to-ripple gesture handling built on top.
 */
class FineTuneFragment : Fragment(R.layout.fragment_stub) {

	private val viewModel: ProjectViewModel by activityViewModels()

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		val text = view.findViewById<TextView>(R.id.stubText)

		viewModel.segmentVersion.observe(viewLifecycleOwner) {
			val session = viewModel.markingSession.value
			text.text = if (session == null) {
				"Load a track in Record first."
			} else {
				"${session.segmentCount} segment(s) ready to fine-tune.\n\n(Waveform + ripple-drag UI coming next.)"
			}
		}
	}
}
