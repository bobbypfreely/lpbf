package com.bobbypfreely.lpbf.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.bobbypfreely.lpbf.R
import com.bobbypfreely.lpbf.viewmodel.ProjectViewModel

/**
 * Finalize: one button, "Create Unipack", does the whole job in a single tap --
 * splices the marks into real clips, compiles every cut's lightshow, and writes a
 * complete Unipack zip to Documents/lpbf/. See ProjectViewModel.createUnipack().
 */
class SpliceFragment : Fragment(R.layout.fragment_splice) {

	private val viewModel: ProjectViewModel by activityViewModels()

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		val statusText = view.findViewById<TextView>(R.id.spliceStatusText)
		val resultText = view.findViewById<TextView>(R.id.spliceResultText)
		val titleInput = view.findViewById<EditText>(R.id.spliceTitleInput)
		val producerInput = view.findViewById<EditText>(R.id.spliceProducerInput)
		val createButton = view.findViewById<Button>(R.id.spliceCreateButton)

		viewModel.segmentVersion.observe(viewLifecycleOwner) {
			val session = viewModel.markingSession.value
			statusText.text = if (session == null) {
				"Load a track and mark some segments first."
			} else if (session.isSpliced) {
				"Already spliced -- Create Unipack again to re-export."
			} else {
				"${session.segmentCount} segment(s) ready."
			}
		}

		createButton.setOnClickListener {
			resultText.text = "Creating..."
			val title = titleInput.text.toString()
			val producer = producerInput.text.toString()

			when (val result = viewModel.createUnipack(requireContext(), title, producer)) {
				is ProjectViewModel.UnipackExportResult.Success -> {
					resultText.text = "Created ${result.soundCount} sound(s), ${result.lightshowCount} lightshow(s).\n" +
						"Saved to ${result.displayPath}"
				}
				is ProjectViewModel.UnipackExportResult.Blocked -> {
					resultText.text = "Blocked: segment(s) ${result.overCapSegmentIndices} still exceed 8s. Fix in Fine-tune first."
				}
				is ProjectViewModel.UnipackExportResult.NothingToExport -> {
					resultText.text = "Nothing to export yet -- import a track and map some pads first."
				}
				is ProjectViewModel.UnipackExportResult.Failed -> {
					resultText.text = "Failed: ${result.message}"
				}
			}
		}
	}
}
