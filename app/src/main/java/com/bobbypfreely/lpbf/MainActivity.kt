package com.bobbypfreely.lpbf

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.bobbypfreely.lpbf.midi.MidiConnection
import com.bobbypfreely.lpbf.ui.MidiControllerBridge
import com.bobbypfreely.lpbf.ui.VirtualLaunchpadGridView
import com.bobbypfreely.lpbf.viewmodel.ProjectViewModel
import com.bobbypfreely.lpbf.waveform.ExoPlaybackController
import com.bobbypfreely.lpbf.waveform.MarkAndCutFragment

/**
 * Launchpad's Best Friend -- center = full Launchpad chrome (top 8 + 8x8 + side chains).
 * Left KeyLED / right KeySound / bottom Mark&Cut drawers inset the pad square, never cover it.
 * Pad hits preview the mapped cut via a single reused ExoPlaybackController (less clicky than
 * create/destroy per hit).
 *
 * Default place mode is PLAY: after Unipack import, taps fire clips instead of re-assigning.
 */
class MainActivity : AppCompatActivity() {

	private lateinit var viewModel: ProjectViewModel

	private lateinit var centerPadContainer: FrameLayout
	private lateinit var leftDrawer: LinearLayout
	private lateinit var rightDrawer: LinearLayout
	private lateinit var bottomDrawer: LinearLayout
	private lateinit var launchpadGrid: VirtualLaunchpadGridView

	private lateinit var ledEditor: EditText
	private lateinit var soundEditor: EditText

	private var leftOpen = false
	private var rightOpen = false
	private var bottomOpen = false

	private val baseSideMarginDp = 56
	private val baseBottomMarginDp = 56
	private val drawerWidthDp = 240
	private val bottomDrawerHeightDp = 320

	private val soundLines = mutableListOf<String>()
	private val ledLines = mutableListOf<String>()

	private var previewController: ExoPlaybackController? = null
	private val previewStopHandler = Handler(Looper.getMainLooper())
	private var previewLoadedPath: String? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		CrashLogger.install(applicationContext)
		setContentView(R.layout.activity_main)

		CrashLogger.getLastCrash(this)?.let { trace ->
			AlertDialog.Builder(this)
				.setTitle("Last crash")
				.setMessage(trace)
				.setPositiveButton("Copy") { _, _ ->
					val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
					cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", trace))
					CrashLogger.clear(this)
				}
				.setNegativeButton("Dismiss") { _, _ -> CrashLogger.clear(this) }
				.show()
		}

		viewModel = ViewModelProvider(this)[ProjectViewModel::class.java]

		MidiConnection.controller = MidiControllerBridge(viewModel)
		MidiConnection.connectionObserver = object : MidiConnection.ConnectionObserver {
			override fun onConnected(snapshot: MidiConnection.ConnectedDeviceSnapshot) {
				viewModel.setConnectedDeviceName(snapshot.name)
			}
			override fun onDisconnected() {
				viewModel.setConnectedDeviceName(null)
			}
		}

		centerPadContainer = findViewById(R.id.centerPadContainer)
		leftDrawer = findViewById(R.id.leftDrawer)
		rightDrawer = findViewById(R.id.rightDrawer)
		bottomDrawer = findViewById(R.id.bottomDrawer)
		launchpadGrid = findViewById(R.id.mainLaunchpadGrid)

		ledEditor = findViewById(R.id.ledEditor)
		soundEditor = findViewById(R.id.soundEditor)

		launchpadGrid.listener = viewModel
		viewModel.isPlaceTabActive = true
		// PLAY: after import, pad taps fire clips; switch to EDIT/HYBRID only when mapping
		viewModel.setPlaceMode(ProjectViewModel.PlaceMode.PLAY)

		findViewById<View>(R.id.leftPillHandle).setOnClickListener { toggleLeft() }
		findViewById<View>(R.id.rightPillHandle).setOnClickListener { toggleRight() }
		findViewById<View>(R.id.bottomPillHandle).setOnClickListener { toggleBottom() }
		findViewById<View?>(R.id.btnExportUnipack)?.setOnClickListener { exportUnipack() }

		viewModel.segmentVersion.observe(this) { refreshSideLists() }
		viewModel.markingSession.observe(this) { refreshSideLists() }
		viewModel.currentChain.observe(this) { chain ->
			launchpadGrid.setActiveChain(chain ?: 0)
			refreshSideLists()
		}
		viewModel.previewRequest.observe(this) { req ->
			if (req != null) {
				if (req.x >= 0 && req.y >= 0) {
					launchpadGrid.setPadLit(req.x, req.y, 0xFF00ADB5.toInt())
					launchpadGrid.postDelayed({ launchpadGrid.clearPad(req.x, req.y) }, 200)
				}
				playPadPreview(req.startMs, req.endMs)
				viewModel.clearPreviewRequest()
			}
		}

		updateCenterInsets()
		ensureWaveformFragment()
	}

	override fun onDestroy() {
		previewStopHandler.removeCallbacksAndMessages(null)
		previewController?.release()
		previewController = null
		super.onDestroy()
	}

	private fun playPadPreview(startMs: Int, endMs: Int) {
		val path = viewModel.cachedFilePath ?: return
		previewStopHandler.removeCallbacksAndMessages(null)

		val controller = previewController ?: ExoPlaybackController(this).also { previewController = it }
		if (previewLoadedPath != path) {
			controller.load(path)
			previewLoadedPath = path
		}
		controller.playFrom(startMs.coerceAtLeast(0))
		val durationMs = (endMs - startMs).coerceAtLeast(1).toLong()
		previewStopHandler.postDelayed({
			controller.pause()
		}, durationMs)
	}

	private fun ensureWaveformFragment() {
		if (supportFragmentManager.findFragmentByTag("mark_and_cut") == null) {
			supportFragmentManager.beginTransaction()
				.replace(R.id.waveformPlaceholder, MarkAndCutFragment(), "mark_and_cut")
				.commitNowAllowingStateLoss()
		}
	}

	private fun refreshSideLists() {
		val session = viewModel.markingSession.value
		val activeChain = viewModel.currentChain.value ?: 0
		soundLines.clear()
		ledLines.clear()
		if (session != null) {
			session.segments().forEachIndexed { i, seg ->
				val b = seg.button
				if (b != null) {
					val chain = b.chain + 1
					val x = b.x + 1
					val y = b.y + 1
					val loopPart = if (seg.loop != 1 || seg.wormhole >= 0) "  loop=${seg.loop}" else ""
					val whPart = if (seg.wormhole >= 0) "  wh=${seg.wormhole + 1}" else ""
					soundLines.add("$chain $x $y  cut${i + 1}$loopPart$whPart")
					if (seg.lightPattern != null) {
						ledLines.add("$chain $x $y  ${seg.lightPattern.name.ifBlank { "pattern" }}")
					}
				} else {
					soundLines.add("cut${i + 1}  (unmapped)")
				}
			}
		}
		findViewById<TextView?>(R.id.soundFolderSummary)?.text =
			if (soundLines.isEmpty()) "(no mappings yet)" else soundLines.joinToString("\n")
		findViewById<TextView?>(R.id.ledFolderSummary)?.text =
			if (ledLines.isEmpty()) "(no LED patterns yet)" else ledLines.joinToString("\n")
		soundEditor.setText(soundLines.joinToString("\n"))
		ledEditor.setText(ledLines.joinToString("\n"))

		launchpadGrid.clearAllPads()
		launchpadGrid.clearAllHighlights()
		val litColor = 0xFF00ADB5.toInt()
		session?.segments()?.forEachIndexed { index, seg ->
			val b = seg.button ?: return@forEachIndexed
			if (b.chain != activeChain) return@forEachIndexed
			launchpadGrid.setPadLit(b.x, b.y, litColor, (index + 1).toString())
		}
	}

	private fun exportUnipack() {
		val inputTitle = EditText(this).apply { hint = "Title"; setText("LPBF Pack") }
		val inputProducer = EditText(this).apply { hint = "Producer"; setText("LPBF") }
		val box = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(48, 16, 48, 0)
			addView(inputTitle)
			addView(inputProducer)
		}
		AlertDialog.Builder(this)
			.setTitle("Create Unipack")
			.setView(box)
			.setPositiveButton("Export") { _, _ ->
				when (val result = viewModel.createUnipack(this, inputTitle.text.toString(), inputProducer.text.toString())) {
					is ProjectViewModel.UnipackExportResult.Success ->
						AlertDialog.Builder(this)
							.setTitle("Exported")
							.setMessage("${result.soundCount} sounds, ${result.lightshowCount} LEDs\n${result.displayPath}")
							.setPositiveButton("OK", null)
							.show()
					is ProjectViewModel.UnipackExportResult.Blocked ->
						AlertDialog.Builder(this).setMessage("Segments over 8s: ${result.overCapSegmentIndices}").setPositiveButton("OK", null).show()
					is ProjectViewModel.UnipackExportResult.NothingToExport ->
						AlertDialog.Builder(this).setMessage("Nothing to export -- import and map first.").setPositiveButton("OK", null).show()
					is ProjectViewModel.UnipackExportResult.Failed ->
						AlertDialog.Builder(this).setMessage("Failed: ${result.message}").setPositiveButton("OK", null).show()
				}
			}
			.setNegativeButton("Cancel", null)
			.show()
	}

	private fun toggleLeft() {
		leftOpen = !leftOpen
		leftDrawer.visibility = if (leftOpen) View.VISIBLE else View.GONE
		viewModel.isLightshowTabActive = leftOpen && !bottomOpen
		viewModel.isPlaceTabActive = true
		updateCenterInsets()
		refreshSideLists()
	}

	private fun toggleRight() {
		rightOpen = !rightOpen
		rightDrawer.visibility = if (rightOpen) View.VISIBLE else View.GONE
		viewModel.isPlaceTabActive = true
		updateCenterInsets()
		refreshSideLists()
	}

	private fun toggleBottom() {
		bottomOpen = !bottomOpen
		bottomDrawer.visibility = if (bottomOpen) View.VISIBLE else View.GONE
		viewModel.isMarkAndCutTabActive = bottomOpen
		if (bottomOpen) ensureWaveformFragment()
		updateCenterInsets()
	}

	private fun updateCenterInsets() {
		val density = resources.displayMetrics.density
		val left = if (leftOpen) (drawerWidthDp * density).toInt() else (baseSideMarginDp * density).toInt()
		val right = if (rightOpen) (drawerWidthDp * density).toInt() else (baseSideMarginDp * density).toInt()
		val bottom = if (bottomOpen) (bottomDrawerHeightDp * density).toInt() else (baseBottomMarginDp * density).toInt()
		val top = (8 * density).toInt()

		val lp = centerPadContainer.layoutParams as ViewGroup.MarginLayoutParams
		lp.leftMargin = left
		lp.rightMargin = right
		lp.bottomMargin = bottom
		lp.topMargin = top
		centerPadContainer.layoutParams = lp
		centerPadContainer.requestLayout()
		launchpadGrid.requestLayout()
	}
}
