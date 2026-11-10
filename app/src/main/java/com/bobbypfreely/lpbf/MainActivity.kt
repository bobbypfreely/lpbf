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
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import com.bobbypfreely.lpbf.ui.LightshowFragment
import com.bobbypfreely.lpbf.ui.PadInputListener
import com.bobbypfreely.lpbf.ui.VirtualLaunchpadGridView
import com.bobbypfreely.lpbf.viewmodel.ProjectViewModel
import com.bobbypfreely.lpbf.waveform.MarkAndCutFragment

/** Main shell: launchpad, drawers, modes. */
class MainActivity : AppCompatActivity(), PadInputListener {

	private val viewModel: ProjectViewModel by viewModels()

	private lateinit var launchpadGrid: VirtualLaunchpadGridView
	private lateinit var centerPadContainer: FrameLayout
	private lateinit var leftDrawer: View
	private lateinit var rightDrawer: View
	private lateinit var bottomDrawer: View
	private lateinit var presetsPanel: View
	private lateinit var presetsList: LinearLayout
	private lateinit var modeLabel: TextView
	private lateinit var ledFolderSummary: TextView
	private lateinit var soundFolderSummary: TextView
	private lateinit var ledEditor: EditText
	private lateinit var soundEditor: EditText
	private var leftOpen = false
	private var rightOpen = false
	private var bottomOpen = false
	private val baseSideMarginDp = 12
	private val baseBottomMarginDp = 12
	private val drawerWidthDp = 240
	private val presetsWidthDp = 132
	private val bottomDrawerHeightDp = 340
	private val soundLines = mutableListOf<String>()
	private val ledLines = mutableListOf<String>()
	private lateinit var harness: com.bobbypfreely.lpbf.harness.LpbfHarness
	private var lightsPlaying = false
	private val autoPlayHandler = Handler(Looper.getMainLooper())
	private var autoPlayRunning = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		WindowCompat.setDecorFitsSystemWindows(window, false)
		setContentView(R.layout.activity_main)

		launchpadGrid = findViewById(R.id.mainLaunchpadGrid)
		centerPadContainer = findViewById(R.id.centerPadContainer)
		leftDrawer = findViewById(R.id.leftDrawer)
		rightDrawer = findViewById(R.id.rightDrawer)
		bottomDrawer = findViewById(R.id.bottomDrawer)
		presetsPanel = findViewById(R.id.presetsPanel)
		presetsList = findViewById(R.id.presetsList)
		modeLabel = findViewById(R.id.modeLabel)
		ledFolderSummary = findViewById(R.id.ledFolderSummary)
		soundFolderSummary = findViewById(R.id.soundFolderSummary)
		ledEditor = findViewById(R.id.ledEditor)
		soundEditor = findViewById(R.id.soundEditor)

		harness = com.bobbypfreely.lpbf.harness.LpbfHarness.assemble(this)
		launchpadGrid.listener = this

		ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootCoordinator)) { v, insets ->
			val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
			insets
		}

		wireTopBar()
		viewModel.uiMode.observe(this) { updateModeChrome() }
		viewModel.segmentsVersion.observe(this) { refreshSideLists(); repaintPadLabels() }
		updateCenterInsets()
	}

	private fun wireTopBar() {
		findViewById<View?>(R.id.btnCloseLeft)?.setOnClickListener { if (leftOpen) setLeftOpen(false) }
		findViewById<View?>(R.id.btnCloseRight)?.setOnClickListener { if (rightOpen) setRightOpen(false) }
		findViewById<View?>(R.id.btnCloseBottom)?.setOnClickListener { if (bottomOpen) setBottomOpen(false) }
		findViewById<View?>(R.id.btnTopPlay)?.setOnClickListener {
			viewModel.enterUiMode(ProjectViewModel.UiMode.PLAY)
			setLeftOpen(false); setRightOpen(false); setBottomOpen(false)
		}
		findViewById<View?>(R.id.btnTopMap)?.setOnClickListener {
			setRightOpen(true); setLeftOpen(false)
		}
		findViewById<View?>(R.id.btnTopLights)?.setOnClickListener {
			setLeftOpen(true); setRightOpen(false)
		}
		findViewById<View?>(R.id.btnTopWave)?.setOnClickListener { setBottomOpen(true) }
		findViewById<View?>(R.id.btnModeMap)?.setOnClickListener {
			viewModel.enterUiMode(ProjectViewModel.UiMode.EDIT)
			setRightOpen(true)
		}
		findViewById<View?>(R.id.btnModeLights)?.setOnClickListener {
			viewModel.enterUiMode(ProjectViewModel.UiMode.LIGHTS)
			setLeftOpen(true)
		}
		findViewById<View?>(R.id.btnModeHybrid)?.setOnClickListener {
			viewModel.enterUiMode(ProjectViewModel.UiMode.HYBRID)
		}
		findViewById<View?>(R.id.btnExportUnipack)?.setOnClickListener { exportUnipack() }
		findViewById<View?>(R.id.btnDonate)?.setOnClickListener { showDonate() }
		findViewById<View?>(R.id.btnAutoPlayToggle)?.setOnClickListener { toggleAutoPlayLabels() }
		findViewById<View?>(R.id.btnGuide)?.setOnClickListener { toggleGuide() }
	}

	private fun showDonate() {
		AlertDialog.Builder(this)
			.setTitle("Tip")
			.setMessage("CashApp $Mysterp\nPayPal bobbyp.freely@gmail.com")
			.setPositiveButton("OK", null)
			.show()
	}

	private var autoPlayLabelsOn = true
	private var guideOn = false

	private fun toggleAutoPlayLabels() {
		autoPlayLabelsOn = !autoPlayLabelsOn
		findViewById<TextView?>(R.id.btnAutoPlayToggle)?.text = if (autoPlayLabelsOn) "AP ON" else "AP OFF"
		repaintPadLabels()
	}

	private fun toggleGuide() {
		guideOn = !guideOn
		findViewById<TextView?>(R.id.btnGuide)?.setTextColor(
			if (guideOn) 0xFF00E5FF.toInt() else 0xFF8888AA.toInt()
		)
	}

	private fun exportUnipack() {
		val result = viewModel.createUnipack(applicationContext)
		Toast.makeText(this, result.toString(), Toast.LENGTH_LONG).show()
	}

	private fun updateModeChrome() {
		val mode = viewModel.uiMode.value ?: ProjectViewModel.UiMode.PLAY
		modeLabel.text = mode.name
		updateCenterInsets()
	}

	private fun refreshSideLists() {
		// best-effort summaries; ignore if session empty
		try {
			val session = viewModel.markingSession.value
			if (session == null) {
				soundFolderSummary.text = "(no mappings yet)"
				ledFolderSummary.text = "(no LED patterns yet)"
				return
			}
			val segs = session.segments()
			val mapped = segs.mapIndexedNotNull { i, s -> s.button?.let { i to it } }
			soundFolderSummary.text = if (mapped.isEmpty()) "(no mappings yet)" else
				mapped.joinToString("\n") { (i, b) -> "cut ${i + 1} -> c${b.chain + 1} (${b.x + 1},${b.y + 1})" }
			val lit = segs.mapIndexedNotNull { i, s -> if (s.lightPattern != null) i else null }
			ledFolderSummary.text = if (lit.isEmpty()) "(no LED patterns yet)" else
				lit.joinToString("\n") { "cut ${it + 1} has lightshow" }
		} catch (_: Exception) { }
	}

	private fun repaintPadLabels() {
		if (lightsPlaying) return
		launchpadGrid.clearAllPads()
		if (!autoPlayLabelsOn) return
		val session = viewModel.markingSession.value ?: return
		val chain = launchpadGrid.activeChain
		// group by pad
		val byPad = linkedMapOf<Pair<Int, Int>, MutableList<Int>>()
		session.segments().forEachIndexed { idx, seg ->
			val b = seg.button ?: return@forEachIndexed
			if (b.chain != chain) return@forEachIndexed
			byPad.getOrPut(b.x to b.y) { mutableListOf() }.add(idx + 1)
		}
		for ((xy, nums) in byPad) {
			launchpadGrid.setPadLit(xy.first, xy.second, 0xFF2A2A40.toInt(), nums.joinToString(" "))
		}
	}

	private fun ensureLightshowFragment() {
		if (supportFragmentManager.findFragmentById(R.id.lightshowPlaceholder) != null) return
		supportFragmentManager.commit {
			replace(R.id.lightshowPlaceholder, LightshowFragment())
		}
	}

	private fun ensureWaveformFragment() {
		if (supportFragmentManager.findFragmentById(R.id.waveformPlaceholder) != null) return
		supportFragmentManager.commit {
			replace(R.id.waveformPlaceholder, MarkAndCutFragment())
		}
	}

	fun mainLaunchpadGrid(): VirtualLaunchpadGridView = launchpadGrid

	override fun onPadDown(x: Int, y: Int) {
		val chain = launchpadGrid.activeChain
		val button = com.bobbypfreely.lpbf.marking.ButtonRef(chain, x, y)
		val mode = viewModel.uiMode.value ?: ProjectViewModel.UiMode.PLAY
		when (mode) {
			ProjectViewModel.UiMode.EDIT -> viewModel.handleEditPadTap(button)
			ProjectViewModel.UiMode.PLAY, ProjectViewModel.UiMode.HYBRID -> playMappedPad(button)
			ProjectViewModel.UiMode.LIGHTS -> { /* lightshow fragment owns grid */ }
		}
	}

	override fun onPadUp(x: Int, y: Int) {}

	override fun onChainTouch(c: Int, upDown: Boolean) {
		if (!upDown) return
		launchpadGrid.setActiveChain(c)
		repaintPadLabels()
	}

	override fun onFunctionKeyTouch(f: Int, upDown: Boolean) {
		if (!upDown) return
		when (f) {
			0 -> viewModel.enterUiMode(ProjectViewModel.UiMode.PLAY)
			1 -> viewModel.enterUiMode(ProjectViewModel.UiMode.EDIT)
			2 -> viewModel.enterUiMode(ProjectViewModel.UiMode.LIGHTS)
			3 -> viewModel.enterUiMode(ProjectViewModel.UiMode.HYBRID)
		}
	}

	private fun playMappedPad(button: com.bobbypfreely.lpbf.marking.ButtonRef) {
		val session = viewModel.markingSession.value ?: return
		val matches = session.segments().withIndex().filter { it.value.button == button }
		if (matches.isEmpty()) return
		val cycle = viewModel.nextPreviewCycle(button)
		val pick = matches[cycle % matches.size]
		val seg = pick.value
		val discrete = viewModel.soundPathFor(button, cycle)
		val dur = seg.durationMs.coerceAtLeast(1)
		if (discrete != null) {
			try { harness.fireFile(discrete, dur) } catch (_: Exception) {
				playPadPreview(seg.startMs, seg.endMs, seg.lightPattern)
			}
		} else {
			playPadPreview(seg.startMs, seg.endMs, seg.lightPattern)
		}
		seg.lightPattern?.let { pat ->
			lightsPlaying = true
			// simple fire-and-forget via harness light module if present
			try {
				harness.firePattern(pat, dur) {
					lightsPlaying = false
					repaintPadLabels()
				}
			} catch (_: Exception) {
				lightsPlaying = false
			}
		}
	}

	private fun playPadPreview(startMs: Int, endMs: Int, pattern: com.bobbypfreely.lpbf.lightshow.Pattern?) {
		try {
			harness.fireRange(startMs, endMs)
		} catch (_: Exception) { }
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
		} else {
			viewModel.autosave(applicationContext)
		}
		updateCenterInsets()
	}

	private fun updateCenterInsets() {
		val density = resources.displayMetrics.density
		val showPresets = leftOpen && viewModel.uiMode.value == ProjectViewModel.UiMode.LIGHTS
		if (::presetsPanel.isInitialized) {
			presetsPanel.visibility = if (showPresets) View.VISIBLE else View.GONE
			if (showPresets) ensurePresetsFilled()
		}
		val left = if (leftOpen) (drawerWidthDp * density).toInt() else (baseSideMarginDp * density).toInt()
		val right = when {
			rightOpen -> (drawerWidthDp * density).toInt()
			showPresets -> (presetsWidthDp * density).toInt()
			else -> (baseSideMarginDp * density).toInt()
		}
		val screenH = resources.displayMetrics.heightPixels
		val maxBottom = (screenH * 0.42f).toInt()
		val bottomWant = (bottomDrawerHeightDp * density).toInt()
		val bottom = if (bottomOpen) minOf(bottomWant, maxBottom).coerceAtLeast((240 * density).toInt()) else (baseBottomMarginDp * density).toInt()
		if (bottomOpen) {
			val bLp = bottomDrawer.layoutParams
			if (bLp.height != bottom) {
				bLp.height = bottom
				bottomDrawer.layoutParams = bLp
			}
		}
		val lp = centerPadContainer.layoutParams as ViewGroup.MarginLayoutParams
		lp.leftMargin = left
		lp.rightMargin = right
		lp.bottomMargin = bottom
		lp.topMargin = (4 * density).toInt()
		centerPadContainer.layoutParams = lp
		centerPadContainer.requestLayout()
		launchpadGrid.requestLayout()
	}

	private var presetsFilled = false

	private fun ensurePresetsFilled() {
		if (!::presetsList.isInitialized) return
		if (presetsFilled && presetsList.childCount > 0) return
		presetsList.removeAllViews()
		val patterns = try {
			com.bobbypfreely.lpbf.lightshow.PatternShortcutStore.allAvailable(this)
		} catch (_: Exception) {
			com.bobbypfreely.lpbf.lightshow.BuiltInPatterns.all()
		}
		val density = resources.displayMetrics.density
		for (pattern in patterns) {
			val tv = TextView(this).apply {
				text = pattern.name
				setTextColor(0xFFD0D0FF.toInt())
				textSize = 12f
				setPadding((8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt())
				setOnClickListener {
					val sel = viewModel.selectedLightshowSegment.value
					if (sel == null) {
						Toast.makeText(this@MainActivity, "Select a cut first", Toast.LENGTH_SHORT).show()
					} else {
						viewModel.applyPatternShortcut(pattern, sel)
						viewModel.logDebug("Applied preset '${pattern.name}' to cut ${sel + 1}")
					}
				}
			}
			presetsList.addView(tv)
		}
		presetsFilled = true
		viewModel.logDebug("Presets panel: ${patterns.size} shortcuts")
	}

	override fun onPause() {
		super.onPause()
		viewModel.autosave(applicationContext)
	}
}
