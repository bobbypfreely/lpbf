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

/** Shell: top bar opens drawers. AP plays autoPlay or cuts in order. */
class MainActivity : AppCompatActivity() {

	private lateinit var viewModel: ProjectViewModel
	private lateinit var centerPadContainer: FrameLayout
	private lateinit var presetsPanel: LinearLayout
	private lateinit var presetsList: LinearLayout
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
	private val presetsWidthDp = 132
	private val bottomDrawerHeightDp = 340
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
		presetsPanel = findViewById(R.id.presetsPanel)
		presetsList = findViewById(R.id.presetsList)
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
		// NOTE: remainder restored from last good commit via Termux if this stub is incomplete
		wireUi()
		updateCenterInsets()
	}

	private fun wireUi() {
		findViewById<View?>(R.id.btnCloseLeft)?.setOnClickListener { if (leftOpen) setLeftOpen(false) }
		findViewById<View?>(R.id.btnCloseRight)?.setOnClickListener { if (rightOpen) setRightOpen(false) }
		findViewById<View?>(R.id.btnCloseBottom)?.setOnClickListener { if (bottomOpen) setBottomOpen(false) }
		findViewById<View?>(R.id.btnTopPlay)?.setOnClickListener {
			viewModel.enterUiMode(ProjectViewModel.UiMode.PLAY)
			setLeftOpen(false); setRightOpen(false); setBottomOpen(false)
		}
		findViewById<View?>(R.id.btnTopMap)?.setOnClickListener { setRightOpen(true); setLeftOpen(false) }
		findViewById<View?>(R.id.btnTopLights)?.setOnClickListener { setLeftOpen(true); setRightOpen(false) }
		findViewById<View?>(R.id.btnTopWave)?.setOnClickListener { setBottomOpen(true) }
		findViewById<View?>(R.id.btnModeMap)?.setOnClickListener {
			viewModel.enterUiMode(ProjectViewModel.UiMode.EDIT); setRightOpen(true)
		}
		findViewById<View?>(R.id.btnModeLights)?.setOnClickListener {
			viewModel.enterUiMode(ProjectViewModel.UiMode.LIGHTS); setLeftOpen(true)
		}
		findViewById<View?>(R.id.btnModeHybrid)?.setOnClickListener {
			viewModel.enterUiMode(ProjectViewModel.UiMode.HYBRID)
		}
	}

	private fun setLeftOpen(open: Boolean) {
		if (leftOpen == open) { if (open) ensureLightshowFragment(); return }
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
		if (bottomOpen == open) { if (open) ensureWaveformFragment(); return }
		bottomOpen = open
		bottomDrawer.visibility = if (open) View.VISIBLE else View.GONE
		viewModel.isMarkAndCutTabActive = open
		if (open) ensureWaveformFragment() else viewModel.autosave(applicationContext)
		updateCenterInsets()
	}

	private fun ensureLightshowFragment() {
		if (supportFragmentManager.findFragmentById(R.id.lightshowPlaceholder) != null) return
		supportFragmentManager.beginTransaction()
			.replace(R.id.lightshowPlaceholder, LightshowFragment()).commit()
	}

	private fun ensureWaveformFragment() {
		if (supportFragmentManager.findFragmentById(R.id.waveformPlaceholder) != null) return
		supportFragmentManager.beginTransaction()
			.replace(R.id.waveformPlaceholder, MarkAndCutFragment()).commit()
	}

	private fun refreshSideLists() {}

	private fun updateCenterInsets() {
		val density = resources.displayMetrics.density
		val showPresets = leftOpen && viewModel.uiMode.value == ProjectViewModel.UiMode.LIGHTS
		if (::presetsPanel.isInitialized) {
			presetsPanel.visibility = if (showPresets) View.VISIBLE else View.GONE
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

	override fun onPause() {
		super.onPause()
		viewModel.autosave(applicationContext)
	}
}
