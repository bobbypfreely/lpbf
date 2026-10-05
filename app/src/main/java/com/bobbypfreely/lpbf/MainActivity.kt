package com.bobbypfreely.lpbf

import android.content.Intent
import android.net.Uri
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
import com.bobbypfreely.lpbf.lightshow.Pattern
import com.bobbypfreely.lpbf.midi.MidiConnection
import com.bobbypfreely.lpbf.ui.LightshowFragment
import com.bobbypfreely.lpbf.ui.MidiControllerBridge
import com.bobbypfreely.lpbf.ui.VirtualLaunchpadGridView
import com.bobbypfreely.lpbf.viewmodel.ProjectViewModel
import com.bobbypfreely.lpbf.waveform.MarkAndCutFragment

/**
 * Shell with no side/bottom pills. Top bar first-press opens drawers.
 * AP plays autoPlay file sequence, or all cuts in mark order when none.
 * Silent autosave on mode change / onPause. First-open tips per mode.
 */
class MainActivity : AppCompatActivity() {

	private lateinit var viewModel: ProjectViewModel
	private lateinit var centerPadContainer: FrameLayout
	private lateinit var leftDrawer: LinearLayout
	private lateinit var rightDrawer: LinearLayout
	private lateinit var bottomDrawer: LinearLayout
	private lateinit var launchpadGrid: VirtualLaunchpadGridView
	private lateinit var modeLabel: TextView
	private lateinit var ledEditor: EditText
	private lateinit var soundEditor: EditText
	private var leftOpen = false
	private var rightOpen = false
	private var bottomOpen = false
	private val baseSideMarginDp = 12
	private val baseBottomMarginDp = 12
	private val drawerWidthDp = 240
	private val bottomDrawerHeightDp = 480
	private val soundLines = mutableListOf<String>()
	private val ledLines = mutableListOf<String>()
	private lateinit var harness: com.bobbypfreely.lpbf.harness.LpbfHarness
	private var lightsPlaying = false
	private val autoPlayHandler = Handler(Looper.getMainLooper())
	private var autoPlayRunning = false

	fun mainLaunchpadGrid(): VirtualLaunchpadGridView = launchpadGrid

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		CrashLogger.install(applicationContext)
		setContentView(R.layout.activity_main)
		CrashLogger.getLastCrash(this)?.let { trace ->
			AlertDialog.Builder(this).setTitle("Last crash").setMessage(trace)
				.setPositiveButton("Copy") { _, _ ->
					val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
					cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", trace))
					CrashLogger.clear(this)
				}.setNegativeButton("Dismiss") { _, _ -> CrashLogger.clear(this) }.show()
		}
		viewModel = ViewModelProvider(this)[ProjectViewModel::class.java]
		MidiConnection.controller = MidiControllerBridge(viewModel)
		MidiConnection.connectionObserver = object : MidiConnection.ConnectionObserver {
			override fun onConnected(snapshot: MidiConnection.ConnectedDeviceSnapshot) {
				viewModel.setConnectedDeviceName(snapshot.name)
			}
			override fun onDisconnected() { viewModel.setConnectedDeviceName(null) }
		}
		centerPadContainer = findViewById(R.id.centerPadContainer)
		leftDrawer = findViewById(R.id.leftDrawer)
		rightDrawer = findViewById(R.id.rightDrawer)
		bottomDrawer = findViewById(R.id.bottomDrawer)
		launchpadGrid = findViewById(R.id.mainLaunchpadGrid)
		modeLabel = findViewById(R.id.modeLabel)
		ledEditor = findViewById(R.id.ledEditor)
		soundEditor = findViewById(R.id.soundEditor)
		launchpadGrid.listener = viewModel
		try {
			harness = com.bobbypfreely.lpbf.harness.LpbfHarness.assemble(
				context = this, grid = { launchpadGrid },
				isLightsAuthoring = { viewModel.uiMode.value == ProjectViewModel.UiMode.LIGHTS },
				onLightsBusy = { busy -> lightsPlaying = busy; if (!busy) refreshSideLists() },
			)
		} catch (e: Exception) {
			android.util.Log.e("MainActivity", "Harness assemble failed", e)
		}
		viewModel.isPlaceTabActive = true
		viewModel.enterUiMode(ProjectViewModel.UiMode.PLAY)

		findViewById<View?>(R.id.btnCloseLeft)?.setOnClickListener { if (leftOpen) setLeftOpen(false) }
		findViewById<View?>(R.id.btnCloseRight)?.setOnClickListener { if (rightOpen) setRightOpen(false) }
		findViewById<View?>(R.id.btnCloseBottom)?.setOnClickListener { if (bottomOpen) setBottomOpen(false) }
		findViewById<View?>(R.id.btnExportUnipack)?.setOnClickListener { exportUnipack() }

		findViewById<View?>(R.id.btnTopPlay)?.setOnClickListener {
			stopAutoPlaySequence()
			viewModel.enterUiMode(ProjectViewModel.UiMode.PLAY)
			setLeftOpen(false); setRightOpen(false); setBottomOpen(false)
		}
		findViewById<View?>(R.id.btnTopMap)?.setOnClickListener {
			viewModel.enterUiMode(ProjectViewModel.UiMode.EDIT)
			setRightOpen(true); setLeftOpen(false)
		}
		findViewById<View?>(R.id.btnTopLights)?.setOnClickListener {
			viewModel.enterUiMode(ProjectViewModel.UiMode.LIGHTS)
			setLeftOpen(true); setRightOpen(false)
		}
		findViewById<View?>(R.id.btnTopWave)?.setOnClickListener { setBottomOpen(true) }
		findViewById<View?>(R.id.btnModeHybrid)?.setOnClickListener {
			viewModel.enterUiMode(ProjectViewModel.UiMode.HYBRID)
		}
		findViewById<View?>(R.id.btnModeMap)?.setOnClickListener {
			viewModel.enterUiMode(ProjectViewModel.UiMode.EDIT)
			setRightOpen(true)
		}
		findViewById<View?>(R.id.btnModeLights)?.setOnClickListener {
			viewModel.enterUiMode(ProjectViewModel.UiMode.LIGHTS)
			setLeftOpen(true)
		}
		findViewById<View?>(R.id.btnAutoPlayToggle)?.setOnClickListener {
			if (autoPlayRunning) {
				stopAutoPlaySequence()
			} else {
				viewModel.setAutoPlayEnabled(true)
				startAutoPlaySequence()
			}
			refreshSideLists(); updateModeChrome()
		}
		findViewById<View?>(R.id.btnGuide)?.setOnClickListener {
			viewModel.toggleGuideMode(); refreshSideLists(); updateModeChrome()
		}
		findViewById<View?>(R.id.btnDonate)?.setOnClickListener { showDonateDialog() }

		viewModel.autoPlayEnabled.observe(this) { refreshSideLists(); updateModeChrome() }
		viewModel.guideMode.observe(this) { refreshSideLists(); updateModeChrome() }
		viewModel.segmentVersion.observe(this) { refreshSideLists() }
		viewModel.markingSession.observe(this) { refreshSideLists() }
		viewModel.autoPlay.observe(this) { refreshSideLists() }
		viewModel.currentChain.observe(this) { chain ->
			launchpadGrid.setActiveChain(chain ?: 0); refreshSideLists()
		}
		viewModel.previewRequest.observe(this) { req ->
			if (req != null) {
				if (req.pattern == null && req.x >= 0 && req.y >= 0) {
					val flash = if (req.stackTotal > 1) "${req.stackIndex + 1}/${req.stackTotal}" else null
					launchpadGrid.setPadLit(req.x, req.y, 0xFF00ADB5.toInt(), flash)
					launchpadGrid.postDelayed({ if (!lightsPlaying) refreshSideLists() }, 220)
				}
				playPadPreview(req.startMs, req.endMs, req.pattern)
				if (viewModel.guideMode.value == true) {
					viewModel.advanceGuideIfMatch(req.x, req.y); refreshSideLists()
				}
				viewModel.clearPreviewRequest()
			}
		}
		viewModel.uiMode.observe(this) { mode ->
			applyUiMode(mode)
			showTipForMode(mode)
			viewModel.autosave(applicationContext)
		}
		updateCenterInsets(); updateModeChrome()
	}

	override fun onPause() {
		viewModel.autosave(applicationContext)
		super.onPause()
	}

	override fun onDestroy() {
		stopAutoPlaySequence()
		if (::harness.isInitialized) harness.release()
		super.onDestroy()
	}

	private fun showDonateDialog() {
		val msg = "LPBF is free now forever — no ads, no paywalls. This is something I have always wanted, THIS IS NOT IN COMPETITION OR MEANT TO REPLACE THE ORIGINAL, THIS IS MEANT TO COMPLIMENT IT WITH NEW PROJECTS FOR EVERYONE, So go download Unipad.\n\n" +
			"Optional tip let's me know I'm right on the correct path.\n\n" +
			"© Bobby P. Freely · LPBF\nbobbyp.freely@gmail.com"
		AlertDialog.Builder(this)
			.setTitle("Support LPBF")
			.setMessage(msg)
			.setPositiveButton("Cash App") { _, _ ->
				try {
					startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cash.app/$" + "Mysterp")))
				} catch (e: Exception) {
					android.widget.Toast.makeText(this, "Cash App: $" + "Mysterp", android.widget.Toast.LENGTH_LONG).show()
				}
			}
			.setNeutralButton("PayPal") { _, _ ->
				try {
					val uri = Uri.parse(
						"https://www.paypal.com/cgi-bin/webscr?cmd=_donations" +
							"&business=bobbyp.freely%40gmail.com&currency_code=USD&item_name=LPBF"
					)
					startActivity(Intent(Intent.ACTION_VIEW, uri))
				} catch (e: Exception) {
					android.widget.Toast.makeText(this, "PayPal: bobbyp.freely@gmail.com", android.widget.Toast.LENGTH_LONG).show()
				}
			}
			.setNegativeButton("Close", null)
			.show()
	}

	private fun showTipForMode(mode: ProjectViewModel.UiMode?) {
		val m = mode ?: return
		val prefs = getSharedPreferences("lpbf_tips", MODE_PRIVATE)
		val key = "tip_" + m.name
		if (prefs.getBoolean(key, false)) return
		val (title, body) = when (m) {
			ProjectViewModel.UiMode.PLAY -> "PLAY" to
				"Clean grid. Tap mapped pads to trigger cuts + lights.\n\n" +
				"AP plays the sequence. GUIDE highlights the next pad to hit."
			ProjectViewModel.UiMode.EDIT -> "MAP / SOUND" to
				"Tap pads to assign the next unmapped cut.\n\n" +
				"Open the sound list to jump a cut into the waveform editor.\n" +
				"Export Unipack when you're done — autosave runs in the background."
			ProjectViewModel.UiMode.HYBRID -> "HYBRID" to
				"Assign and preview in one tap.\n\n" +
				"Great for building the map while hearing each cut."
			ProjectViewModel.UiMode.LIGHTS -> "LIGHTS / LED" to
				"Select a mapped pad, then paint a light pattern.\n\n" +
				"Chain buttons pick hue. Patterns export into the Unipack keyLED folder."
		}
		AlertDialog.Builder(this)
			.setTitle(title)
			.setMessage(body)
			.setPositiveButton("Got it") { _, _ ->
				prefs.edit().putBoolean(key, true).apply()
			}
			.setCancelable(true)
			.setOnCancelListener { prefs.edit().putBoolean(key, true).apply() }
			.show()
	}

	private fun showWaveformTipIfNeeded() {
		val prefs = getSharedPreferences("lpbf_tips", MODE_PRIVATE)
		if (prefs.getBoolean("tip_WAVE", false)) return
		AlertDialog.Builder(this)
			.setTitle("Waveform")
			.setMessage(
				"1. Import a track\n" +
				"2. Play, then Mark Cut Here as you go\n" +
				"3. Map cuts onto pads (SOUND / HYBRID)\n" +
				"4. Export Unipack for UniPad\n\n" +
				"Autosave runs when you change tabs — Recover after a crash."
			)
			.setPositiveButton("Got it") { _, _ ->
				prefs.edit().putBoolean("tip_WAVE", true).apply()
			}
			.setOnCancelListener { prefs.edit().putBoolean("tip_WAVE", true).apply() }
			.show()
	}

	private fun updateModeChrome() {
		val ap = viewModel.autoPlayEnabled.value == true
		val guide = viewModel.guideMode.value == true
		findViewById<TextView?>(R.id.btnAutoPlayToggle)?.apply {
			text = when {
				autoPlayRunning -> "STOP"
				ap -> "AP ON"
				else -> "AP OFF"
			}
			setTextColor(when {
				autoPlayRunning -> 0xFFFF8A80.toInt()
				ap -> 0xFF81C784.toInt()
				else -> 0xFF8888AA.toInt()
			})
		}
		findViewById<TextView?>(R.id.btnGuide)?.apply {
			setTextColor(if (guide) 0xFFFFB74D.toInt() else 0xFF8888AA.toInt())
		}
	}

	private fun applyGuideHighlight() {
		if (viewModel.guideMode.value != true) return
		val next = viewModel.nextGuidePad() ?: return
		launchpadGrid.setPadHighlighted(next.first, next.second, 0xFFFF9800.toInt())
	}

	private fun applyUiMode(mode: ProjectViewModel.UiMode?) {
		val m = mode ?: ProjectViewModel.UiMode.PLAY
		modeLabel.text = when (m) {
			ProjectViewModel.UiMode.PLAY -> "PLAY"
			ProjectViewModel.UiMode.EDIT -> "MAP / SOUND"
			ProjectViewModel.UiMode.HYBRID -> "HYBRID"
			ProjectViewModel.UiMode.LIGHTS -> "LIGHTS / LED"
		}
		modeLabel.setTextColor(when (m) {
			ProjectViewModel.UiMode.PLAY -> 0xFF00ADB5.toInt()
			ProjectViewModel.UiMode.EDIT -> 0xFF81C784.toInt()
			ProjectViewModel.UiMode.HYBRID -> 0xFFFFB74D.toInt()
			ProjectViewModel.UiMode.LIGHTS -> 0xFFB39DDB.toInt()
		})
		when (m) {
			ProjectViewModel.UiMode.PLAY -> { }
			ProjectViewModel.UiMode.EDIT -> { if (!rightOpen) setRightOpen(true); if (leftOpen) setLeftOpen(false) }
			ProjectViewModel.UiMode.HYBRID -> { }
			ProjectViewModel.UiMode.LIGHTS -> {
				if (!leftOpen) setLeftOpen(true); if (rightOpen) setRightOpen(false); ensureLightshowFragment()
			}
		}
		refreshSideLists()
	}

	private fun playPadPreview(startMs: Int, endMs: Int, pattern: Pattern?) {
		if (!::harness.isInitialized) return
		try { harness.setSource(viewModel.cachedFilePath); harness.fire(startMs, endMs, pattern) }
		catch (e: Exception) { android.util.Log.e("MainActivity", "Pad fire failed", e) }
	}

	private fun ensureWaveformFragment() {
		if (supportFragmentManager.findFragmentByTag("mark_and_cut") == null) {
			supportFragmentManager.beginTransaction()
				.replace(R.id.waveformPlaceholder, MarkAndCutFragment(), "mark_and_cut")
				.commitNowAllowingStateLoss()
		}
	}

	private fun ensureLightshowFragment() {
		if (supportFragmentManager.findFragmentByTag("lightshow") == null) {
			supportFragmentManager.beginTransaction()
				.replace(R.id.lightshowPlaceholder, LightshowFragment(), "lightshow")
				.commitNowAllowingStateLoss()
		}
	}

	private fun refreshSideLists() {
		val session = viewModel.markingSession.value
		val activeChain = viewModel.currentChain.value ?: 0
		soundLines.clear(); ledLines.clear()
		if (session != null) {
			session.segments().forEachIndexed { i, seg ->
				val b = seg.button
				if (b != null) {
					val loopPart = if (seg.loop != 1 || seg.wormhole >= 0) "  loop=${seg.loop}" else ""
					val whPart = if (seg.wormhole >= 0) "  wh=${seg.wormhole + 1}" else ""
					soundLines.add("${b.chain + 1} ${b.x + 1} ${b.y + 1}  cut${i + 1}$loopPart$whPart")
					if (seg.lightPattern != null) ledLines.add("${b.chain + 1} ${b.x + 1} ${b.y + 1}  ${seg.lightPattern.name.ifBlank { "pattern" }}")
				} else soundLines.add("cut${i + 1}  (unmapped)")
			}
		}
		val soundSummary = findViewById<TextView?>(R.id.soundFolderSummary)
		soundSummary?.text = if (soundLines.isEmpty()) "(no mappings yet)" else soundLines.joinToString("\n")
		findViewById<TextView?>(R.id.ledFolderSummary)?.text = if (ledLines.isEmpty()) "(no LED patterns yet)" else ledLines.joinToString("\n")
		soundEditor.setText(soundLines.joinToString("\n"))
		ledEditor.setText(ledLines.joinToString("\n"))
		soundSummary?.setOnClickListener {
			if (soundLines.isEmpty()) return@setOnClickListener
			if (!bottomOpen) setBottomOpen(true)
			val labels = soundLines.mapIndexed { i, s -> "Cut ${i + 1}: $s" }.toTypedArray()
			AlertDialog.Builder(this).setTitle("Edit cut in waveform")
				.setItems(labels) { _, which ->
					if (!bottomOpen) setBottomOpen(true)
					viewModel.requestJumpToMark(which)
				}.setNegativeButton("Cancel", null).show()
		}
		val mode = viewModel.uiMode.value
		if (mode == ProjectViewModel.UiMode.LIGHTS) return
		if (lightsPlaying) return
		launchpadGrid.clearAllPads(); launchpadGrid.clearAllHighlights()
		if (autoPlayRunning) return
		val litColor = 0xFF00ADB5.toInt()
		val autoPlay = viewModel.autoPlay.value.orEmpty()
		val showAutoPlayLabels = viewModel.autoPlayEnabled.value == true
		val chainPresses = autoPlay.filter { it.button.chain == activeChain }
		if (mode != ProjectViewModel.UiMode.PLAY || showAutoPlayLabels) {
			if (showAutoPlayLabels && chainPresses.isNotEmpty()) {
				val padNums = LinkedHashMap<Pair<Int, Int>, MutableList<Int>>()
				chainPresses.forEachIndexed { i, press ->
					padNums.getOrPut(press.button.x to press.button.y) { mutableListOf() }.add(i + 1)
				}
				padNums.forEach { (pad, nums) -> launchpadGrid.setPadLit(pad.first, pad.second, litColor, nums.joinToString(" ")) }
			} else if (mode != ProjectViewModel.UiMode.PLAY) {
				val padNums = LinkedHashMap<Pair<Int, Int>, MutableList<Int>>()
				var n = 0
				session?.segments()?.forEach { seg ->
					val b = seg.button ?: return@forEach
					if (b.chain != activeChain) return@forEach
					n += 1
					padNums.getOrPut(b.x to b.y) { mutableListOf() }.add(n)
				}
				padNums.forEach { (pad, nums) -> launchpadGrid.setPadLit(pad.first, pad.second, litColor, nums.joinToString(" ")) }
			}
		}
		applyGuideHighlight()
	}

	private fun exportUnipack() {
		val inputTitle = EditText(this).apply { hint = "Title"; setText("LPBF Pack") }
		val inputProducer = EditText(this).apply { hint = "Producer"; setText("LPBF") }
		val box = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
			addView(inputTitle); addView(inputProducer)
		}
		AlertDialog.Builder(this).setTitle("Create Unipack").setView(box)
			.setPositiveButton("Export") { _, _ ->
				when (val result = viewModel.createUnipack(this, inputTitle.text.toString(), inputProducer.text.toString())) {
					is ProjectViewModel.UnipackExportResult.Success ->
						AlertDialog.Builder(this).setTitle("Exported")
							.setMessage("${result.soundCount} sounds, ${result.lightshowCount} LEDs\n${result.displayPath}")
							.setPositiveButton("OK", null).show()
					is ProjectViewModel.UnipackExportResult.Blocked ->
						AlertDialog.Builder(this).setMessage("Segments over 8s: ${result.overCapSegmentIndices}").setPositiveButton("OK", null).show()
					is ProjectViewModel.UnipackExportResult.NothingToExport ->
						AlertDialog.Builder(this).setMessage("Nothing to export -- import and map first.").setPositiveButton("OK", null).show()
					is ProjectViewModel.UnipackExportResult.Failed ->
						AlertDialog.Builder(this).setMessage("Failed: ${result.message}").setPositiveButton("OK", null).show()
				}
			}.setNegativeButton("Cancel", null).show()
	}

	private fun startAutoPlaySequence() {
		stopAutoPlaySequence()
		val session = viewModel.markingSession.value
		val presses = viewModel.autoPlay.value.orEmpty()
		if (presses.isNotEmpty()) {
			autoPlayRunning = true
			updateModeChrome()
			val t0 = presses.first().timestampMs
			presses.forEachIndexed { idx, press ->
				val delay = (press.timestampMs - t0).toLong().coerceAtLeast(0L)
				autoPlayHandler.postDelayed({
					if (!autoPlayRunning) return@postDelayed
					fireAutoPlayPress(press)
					if (idx == presses.lastIndex) {
						autoPlayHandler.postDelayed({ stopAutoPlaySequence() }, 400L)
					}
				}, delay)
			}
			return
		}
		if (session == null || session.segmentCount == 0) {
			modeLabel.text = "AP: mark cuts first"
			return
		}
		val cuts = session.segments()
		autoPlayRunning = true
		updateModeChrome()
		var acc = 0L
		cuts.forEachIndexed { idx, seg ->
			val delay = acc
			val hold = seg.durationMs.toLong().coerceAtLeast(200L)
			val b = seg.button
			autoPlayHandler.postDelayed({
				if (!autoPlayRunning) return@postDelayed
				if (b != null) {
					launchpadGrid.setPadLit(b.x, b.y, 0xFF00ADB5.toInt(), "${idx + 1}")
				} else {
					modeLabel.text = "AP cut ${idx + 1}/${cuts.size}"
				}
				playPadPreview(seg.startMs, seg.endMs, seg.lightPattern)
				if (idx == cuts.lastIndex) {
					autoPlayHandler.postDelayed({ stopAutoPlaySequence() }, hold)
				}
			}, delay)
			acc += hold
		}
	}

	private fun fireAutoPlayPress(press: com.bobbypfreely.lpbf.unipack.AutoPlayPress) {
		val session = viewModel.markingSession.value ?: return
		val b = press.button
		val matches = session.segments().withIndex().filter { it.value.button == b }
		if (matches.isEmpty()) return
		val pick = matches[press.occurrenceIndex % matches.size]
		val seg = pick.value
		launchpadGrid.setPadLit(b.x, b.y, 0xFF00ADB5.toInt(), "${press.occurrenceIndex + 1}")
		playPadPreview(seg.startMs, seg.endMs, seg.lightPattern)
	}

	private fun stopAutoPlaySequence() {
		autoPlayRunning = false
		autoPlayHandler.removeCallbacksAndMessages(null)
		updateModeChrome()
		refreshSideLists()
	}

	private fun setLeftOpen(open: Boolean) {
		if (leftOpen == open) {
			if (open) ensureLightshowFragment()
			return
		}
		leftOpen = open
		leftDrawer.visibility = if (open) View.VISIBLE else View.GONE
		if (open) ensureLightshowFragment()
		updateCenterInsets(); refreshSideLists()
	}

	private fun setRightOpen(open: Boolean) {
		if (rightOpen == open) return
		rightOpen = open
		rightDrawer.visibility = if (open) View.VISIBLE else View.GONE
		updateCenterInsets(); refreshSideLists()
	}

	private fun setBottomOpen(open: Boolean) {
		if (bottomOpen == open) {
			if (open) ensureWaveformFragment()
			return
		}
		bottomOpen = open
		bottomDrawer.visibility = if (open) View.VISIBLE else View.GONE
		viewModel.isMarkAndCutTabActive = open
		if (open) {
			ensureWaveformFragment()
			showWaveformTipIfNeeded()
		} else {
			viewModel.autosave(applicationContext)
		}
		updateCenterInsets()
	}

	private fun updateCenterInsets() {
		val density = resources.displayMetrics.density
		val left = if (leftOpen) (drawerWidthDp * density).toInt() else (baseSideMarginDp * density).toInt()
		val right = if (rightOpen) (drawerWidthDp * density).toInt() else (baseSideMarginDp * density).toInt()
		val bottom = if (bottomOpen) (bottomDrawerHeightDp * density).toInt() else (baseBottomMarginDp * density).toInt()
		val lp = centerPadContainer.layoutParams as ViewGroup.MarginLayoutParams
		lp.leftMargin = left; lp.rightMargin = right; lp.bottomMargin = bottom; lp.topMargin = (8 * density).toInt()
		centerPadContainer.layoutParams = lp; centerPadContainer.requestLayout(); launchpadGrid.requestLayout()
	}
}
