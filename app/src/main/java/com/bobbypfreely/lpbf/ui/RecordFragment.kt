package com.bobbypfreely.lpbf.ui

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.activity.result.contract.ActivityResultContracts
import com.bobbypfreely.lpbf.R
import com.bobbypfreely.lpbf.audio.AudioDecoder
import com.bobbypfreely.lpbf.viewmodel.ProjectViewModel
import kotlin.concurrent.thread

class RecordFragment : Fragment(R.layout.fragment_record) {

	private val viewModel: ProjectViewModel by activityViewModels()

	private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
		if (uri != null) decodeAndLoad(uri)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		val importButton = view.findViewById<Button>(R.id.importButton)
		val statusText = view.findViewById<TextView>(R.id.statusText)
		val connectionText = view.findViewById<TextView>(R.id.connectionText)
		val grid = view.findViewById<VirtualLaunchpadGridView>(R.id.padGrid)

		grid.listener = viewModel

		importButton.setOnClickListener {
			pickAudio.launch(arrayOf("audio/*"))
		}

		viewModel.connectedDeviceName.observe(viewLifecycleOwner) { name ->
			connectionText.text = if (name != null) {
				"Connected: $name"
			} else {
				"No Launchpad connected \u2014 using on-screen grid"
			}
		}

		viewModel.decodedAudio.observe(viewLifecycleOwner) { audio ->
			if (audio != null) {
				statusText.text = "Decoded: ${audio.totalDurationMs}ms, ${audio.pcm.size} bytes PCM, " +
					"${audio.sampleRate}Hz, ${audio.channels}ch. Tap a pad to start."
			}
		}

		viewModel.segmentVersion.observe(viewLifecycleOwner) {
			val session = viewModel.markingSession.value
			val count = session?.segmentCount ?: 0
			if (viewModel.decodedAudio.value != null && count > 0) {
				statusText.text = "$count segment(s) marked so far."
			}
		}

		viewModel.isPlaying.observe(viewLifecycleOwner) { playing ->
			if (playing) statusText.text = "Playing\u2026 press the pad again to mark the cut"
		}

		viewModel.capPrompt.observe(viewLifecycleOwner) { prompt ->
			if (prompt != null) showCapDialog(prompt.gapMs, prompt.maxMs)
		}

		// DIAGNOSTIC: shows every pad-down/up event as a Toast, including the exact
		// stopMs vs lastMark comparison, so we can see input + timing without logcat.
		viewModel.debugEvent.observe(viewLifecycleOwner) { msg ->
			Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
		}
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

	private fun decodeAndLoad(uri: Uri) {
		val context = requireContext().applicationContext
		val statusText = view?.findViewById<TextView>(R.id.statusText)
		activity?.runOnUiThread { statusText?.text = "Decoding\u2026" }
		thread(name = "lpbf-decode") {
			try {
				val audio = AudioDecoder.decode(context, uri)
				activity?.runOnUiThread {
					viewModel.setDecodedAudio(audio)
				}
			} catch (e: Exception) {
				android.util.Log.e("RecordFragment", "Decode failed", e)
				activity?.runOnUiThread {
					statusText?.text = "Decode FAILED: ${e.message}"
				}
			}
		}
	}
}
