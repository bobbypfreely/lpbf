package com.bobbypfreely.lpbf.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.bobbypfreely.lpbf.R
import com.bobbypfreely.lpbf.audio.ClipExporter
import com.bobbypfreely.lpbf.marking.MarkingSession
import com.bobbypfreely.lpbf.viewmodel.ProjectViewModel

/**
 * Phase 3: locks marks and exports real WAV files. The splice+export logic itself is
 * real (MarkingSession.splice() -> ClipExporter.export()) -- what's still a stub is the
 * polished UI (per-segment list, blocked-segment highlighting). Functional placeholder.
 */
class SpliceFragment : Fragment(R.layout.fragment_stub) {

	private val viewModel: ProjectViewModel by activityViewModels()

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		val text = view.findViewById<TextView>(R.id.stubText)
		val button = view.findViewById<Button>(R.id.stubButton)
		button.text = "Splice & Export"
		button.visibility = View.VISIBLE

		viewModel.segmentVersion.observe(viewLifecycleOwner) {
			val session = viewModel.markingSession.value
			text.text = if (session == null) {
				"Load a track and mark some segments first."
			} else if (session.isSpliced) {
				"Already spliced."
			} else {
				"${session.segmentCount} segment(s) ready. Tap Splice & Export when timing looks right."
			}
		}

		button.setOnClickListener {
			val session = viewModel.markingSession.value ?: return@setOnClickListener
			val audio = viewModel.decodedAudio.value ?: return@setOnClickListener

			when (val result = session.splice()) {
				is MarkingSession.SpliceResult.Blocked -> {
					text.text = "Blocked: segment(s) ${result.overCapSegmentIndices} still exceed 8s. Fix in Fine-tune first."
				}
				is MarkingSession.SpliceResult.Success -> {
					val exported = ClipExporter.export(audio, result.clips)
					text.text = "Exported ${exported.size} clip(s): " +
						exported.joinToString(", ") { it.fileName }
					// NOTE: exported[i].wavBytes still needs writing to the project's
					// sounds/ folder on disk -- that's the next piece (unipack zip assembly).
				}
			}
		}
	}
}
