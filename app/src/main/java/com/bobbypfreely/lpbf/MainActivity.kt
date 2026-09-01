package com.bobbypfreely.lpbf

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.widget.ViewPager2
import com.bobbypfreely.lpbf.midi.MidiConnection
import com.bobbypfreely.lpbf.ui.MidiControllerBridge
import com.bobbypfreely.lpbf.ui.ProjectPagerAdapter
import com.bobbypfreely.lpbf.viewmodel.ProjectViewModel
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

	private lateinit var viewModel: ProjectViewModel

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		CrashLogger.install(applicationContext)
		setContentView(R.layout.activity_main)

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

		val pager = findViewById<ViewPager2>(R.id.viewPager)
		val tabs = findViewById<TabLayout>(R.id.tabLayout)
		pager.adapter = ProjectPagerAdapter(this)

		val tabTitles = listOf("Mark & Cut", "Place", "Lightshow", "Splice")
		TabLayoutMediator(tabs, pager) { tab, position ->
			tab.text = tabTitles[position]
		}.attach()

		// Route real hardware pad presses into the same listener the virtual grid uses.
		MidiConnection.controller = MidiControllerBridge(viewModel)
		MidiConnection.connectionObserver = object : MidiConnection.ConnectionObserver {
			override fun onConnected(snapshot: MidiConnection.ConnectedDeviceSnapshot) {
				viewModel.setConnectedDeviceName(snapshot.name)
			}

			override fun onDisconnected() {
				viewModel.setConnectedDeviceName(null)
			}
		}

		// NOTE: this catches a Launchpad plugged in WHILE the app is running via
		// UsbMidiHandlerActivity's intent-filter. A device already plugged in before
		// the app launches needs an explicit deviceList scan here too -- untested,
		// flagging as a real-device check item.

			// Long-pressing a cut on Place asks to jump back to Mark & Cut (tab 0) and
			// highlight its mark; MarkAndCutFragment does the actual scroll+highlight
			// and clears the request once it's applied.
			viewModel.jumpToMarkRequest.observe(this) { segIndex ->
				if (segIndex != null) {
					pager.setCurrentItem(0, true)
				}
			}
	}
}
