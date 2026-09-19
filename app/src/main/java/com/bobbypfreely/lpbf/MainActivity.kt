package com.bobbypfreely.lpbf

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.bobbypfreely.lpbf.midi.MidiConnection
import com.bobbypfreely.lpbf.ui.MidiControllerBridge
import com.bobbypfreely.lpbf.viewmodel.ProjectViewModel

/**
 * Non-functional UI shell – Launchpad’s Best Friend
 * Center  = big Launchpad grid
 * Left    = KeyLED drawer
 * Right   = KeySound drawer
 * Bottom  = Waveform drawer
 *
 * All existing libraries kept. Real wiring comes later.
 */
class MainActivity : AppCompatActivity() {

	private lateinit var viewModel: ProjectViewModel

	private lateinit var leftDrawer: LinearLayout
	private lateinit var rightDrawer: LinearLayout
	private lateinit var bottomDrawer: LinearLayout

	private var leftOpen = false
	private var rightOpen = false
	private var bottomOpen = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		CrashLogger.install(applicationContext)
		setContentView(R.layout.activity_main)

		// Keep crash dialog
		CrashLogger.getLastCrash(this)?.let { trace ->
			android.app.AlertDialog.Builder(this)
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

		// Keep MIDI bridge alive so hardware still works later
		MidiConnection.controller = MidiControllerBridge(viewModel)
		MidiConnection.connectionObserver = object : MidiConnection.ConnectionObserver {
			override fun onConnected(snapshot: MidiConnection.ConnectedDeviceSnapshot) {
				viewModel.setConnectedDeviceName(snapshot.name)
			}
			override fun onDisconnected() {
				viewModel.setConnectedDeviceName(null)
			}
		}

		leftDrawer = findViewById(R.id.leftDrawer)
		rightDrawer = findViewById(R.id.rightDrawer)
		bottomDrawer = findViewById(R.id.bottomDrawer)

		findViewById<View>(R.id.leftPillHandle).setOnClickListener { toggleLeft() }
		findViewById<View>(R.id.rightPillHandle).setOnClickListener { toggleRight() }
		findViewById<View>(R.id.bottomPillHandle).setOnClickListener { toggleBottom() }
	}

	private fun toggleLeft() {
		leftOpen = !leftOpen
		leftDrawer.visibility = if (leftOpen) View.VISIBLE else View.GONE
	}

	private fun toggleRight() {
		rightOpen = !rightOpen
		rightDrawer.visibility = if (rightOpen) View.VISIBLE else View.GONE
	}

	private fun toggleBottom() {
		bottomOpen = !bottomOpen
		bottomDrawer.visibility = if (bottomOpen) View.VISIBLE else View.GONE
	}
}
