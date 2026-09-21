package com.bobbypfreely.lpbf.ui

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.bobbypfreely.lpbf.MainActivity
import com.bobbypfreely.lpbf.R
import com.bobbypfreely.lpbf.audio.AudioPlaybackController
import com.bobbypfreely.lpbf.lightshow.Keyframe
import com.bobbypfreely.lpbf.lightshow.LightshowColorWheel
import com.bobbypfreely.lpbf.lightshow.Pattern
import com.bobbypfreely.lpbf.manager.LaunchpadColor
import com.bobbypfreely.lpbf.midi.MidiConnection
import com.bobbypfreely.lpbf.viewmodel.ProjectViewModel

/**
 * Lightshow authoring UI hosted in the LED drawer.
 * Pad interaction and LED paint use the **main** VirtualLaunchpadGridView in MainActivity
 * (this fragment's grid is gone/hidden). Stacked cuts on the same pad cycle on re-tap.
 */
class LightshowFragment : Fragment(R.layout.fragment_lightshow) {

	private val viewModel: ProjectViewModel by activityViewModels()

	private lateinit var statusText: TextView
	private lateinit var rowLabel: TextView
	private lateinit var buttonRow: LinearLayout
	private lateinit var editControls: LinearLayout
	private lateinit var velocityText: TextView
	private lateinit var timeText: TextView
	private lateinit var timeMinus1000: Button
	private lateinit var timeMinus100: Button
	private lateinit var timeMinus10: Button
	private lateinit var timeMinus1: Button
	private lateinit var timePlus1: Button
	private lateinit var timePlus10: Button
	private lateinit var timePlus100: Button
	private lateinit var timePlus1000: Button
	private lateinit var eventListContainer: LinearLayout
	private lateinit var grid: VirtualLaunchpadGridView

	private val hardwareButtons = mutableListOf<Button>()
	private val previewHandler = Handler(Looper.getMainLooper())

	private val FIXED_DURATION_MS = 200

	private var audioPreview: AudioPlaybackController? = null

	private data class EditEvent(val x: Int, val y: Int, val velocity: Int, val startMs: Int, val durationMs: Int)

	private val events = mutableListOf<EditEvent>()
	private var authoringTimeMs = 0
	private var loadedForSegment: Int? = null

	/** Prefer the center Launchpad; fall back to the (hidden) local grid. */
	private fun paintGrid(): VirtualLaunchpadGridView {
		val main = (activity as? MainActivity)?.mainLaunchpadGrid()
		return main ?: grid
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		statusText = view.findViewById(R.id.lightshowStatusText)
		rowLabel = view.findViewById(R.id.lightshowRowLabel)
		buttonRow = view.findViewById(R.id.lightshowButtonRow)
		editControls = view.findViewById(R.id.lightshowEditControls)
		velocityText = view.findViewById(R.id.lightshowVelocityText)
		timeText = view.findViewById(R.id.lightshowTimeText)
		timeMinus1000 = view.findViewById(R.id.lightshowTimeMinus1000)
		timeMinus100 = view.findViewById(R.id.lightshowTimeMinus100)
		timeMinus10 = view.findViewById(R.id.lightshowTimeMinus10)
		timeMinus1 = view.findViewById(R.id.lightshowTimeMinus1)
		timePlus1 = view.findViewById(R.id.lightshowTimePlus1)
		timePlus10 = view.findViewById(R.id.lightshowTimePlus10)
		timePlus100 = view.findViewById(R.id.lightshowTimePlus100)
		timePlus1000 = view.findViewById(R.id.lightshowTimePlus1000)
		eventListContainer = view.findViewById(R.id.lightshowEventListContainer)
		grid = view.findViewById(R.id.lightshowGrid)

		buildHardwareButtonRow()

		timeMinus1000.setOnClickListener { adjustTime(-1000) }
		timeMinus100.setOnClickListener { adjustTime(-100) }
		timeMinus10.setOnClickListener { adjustTime(-10) }
		timeMinus1.setOnClickListener { adjustTime(-1) }
		timePlus1.setOnClickListener { adjustTime(+1) }
		timePlus10.setOnClickListener { adjustTime(+10) }
		timePlus100.setOnClickListener { adjustTime(+100) }
		timePlus1000.setOnClickListener { adjustTime(+1000) }
		view.findViewById<Button>(R.id.lightshowVelocityMinus).setOnClickListener { viewModel.nudgeVelocity(-1) }
		view.findViewById<Button>(R.id.lightshowVelocityPlus).setOnClickListener { viewModel.nudgeVelocity(+1) }
		view.findViewById<Button>(R.id.lightshowPlay).setOnClickListener { playPreview() }
		view.findViewById<Button>(R.id.lightshowStop).setOnClickListener { stopPreview() }
		view.findViewById<Button>(R.id.lightshowSave).setOnClickListener { saveAndDeselect() }
		view.findViewById<Button>(R.id.lightshowBack).setOnClickListener {
			stopPreview()
			viewModel.deselectLightshowSegment()
		}

		viewModel.selectedLightshowSegment.observe(viewLifecycleOwner) { onSelectionChanged(it) }
		viewModel.currentChain.observe(viewLifecycleOwner) { refresh() }
		viewModel.colorHueSlot.observe(viewLifecycleOwner) { refreshHardwareButtonHighlight(); refreshVelocityText() }
		viewModel.colorSaturationLevel.observe(viewLifecycleOwner) { refreshVelocityText() }
		viewModel.colorVelocityOverride.observe(viewLifecycleOwner) { refreshVelocityText() }
		viewModel.segmentVersion.observe(viewLifecycleOwner) { refresh() }
		viewModel.markingSession.observe(viewLifecycleOwner) { refresh() }

		viewModel.lightshowPadPress.observe(viewLifecycleOwner) { press ->
			if (press != null) {
				placeEvent(press.x, press.y, press.velocity)
				viewModel.clearLightshowPadPress()
			}
		}
	}

	override fun onResume() {
		super.onResume()
		// Flag is owned by ProjectViewModel.enterUiMode(LIGHTS); do not fight it here.
		if (viewModel.uiMode.value == ProjectViewModel.UiMode.LIGHTS) {
			viewModel.isLightshowTabActive = true
		}
		refresh()
	}

	override fun onPause() {
		stopPreview()
		// Leave isLightshowTabActive alone -- leaving the drawer mid-LIGHTS still uses main pad.
		super.onPause()
	}

	private fun buildHardwareButtonRow() {
		buttonRow.removeAllViews()
		hardwareButtons.clear()
		for (slot in 0 until 8) {
			val button = Button(requireContext()).apply {
				textSize = 10f
				layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
					marginEnd = if (slot < 7) 2 else 0
				}
				setPadding(0, 6, 0, 6)
				setOnClickListener {
					if (viewModel.selectedLightshowSegment.value != null) {
						viewModel.setColorHueSlot(slot)
					} else {
						viewModel.setCurrentChain(slot)
					}
				}
			}
			buttonRow.addView(button)
			hardwareButtons.add(button)
		}
	}

	private fun refreshHardwareButtonHighlight() {
		val editing = viewModel.selectedLightshowSegment.value != null
		val activeChain = viewModel.currentChain.value ?: 0
		val activeHue = viewModel.colorHueSlot.value ?: 0

		hardwareButtons.forEachIndexed { i, button ->
			button.text = if (editing) LightshowColorWheel.SLOT_NAMES[i].take(3) else (i + 1).toString()
			val isActive = if (editing) i == activeHue else i == activeChain
			if (isActive) {
				button.setBackgroundColor(Color.parseColor("#00ADB5"))
				button.setTextColor(Color.parseColor("#0F0F1A"))
			} else {
				button.setBackgroundColor(Color.parseColor("#1A1A2E"))
				button.setTextColor(Color.parseColor("#DDDDDD"))
			}
		}
	}

	private fun refreshVelocityText() {
		velocityText.text = "Velocity: ${viewModel.currentColorVelocity()}"
	}

	private fun onSelectionChanged(segmentIndex: Int?) {
		editControls.visibility = if (segmentIndex != null) View.VISIBLE else View.GONE
		rowLabel.text = if (segmentIndex != null) "Color:" else "Chain:"

		if (segmentIndex == null) {
			loadedForSegment = null
			events.clear()
			stopPreview()
			refresh()
			return
		}

		if (loadedForSegment != segmentIndex) {
			loadedForSegment = segmentIndex
			events.clear()
			authoringTimeMs = 0
			val session = viewModel.markingSession.value
			val segment = session?.segment(segmentIndex)
			val pattern = segment?.lightPattern
			if (pattern != null && segment.durationMs > 0) {
				events.addAll(reconstructEvents(pattern, segment.durationMs))
			}
		}
		refresh()
	}

	private fun reconstructEvents(pattern: Pattern, durationMs: Int): List<EditEvent> {
		val sorted = pattern.keyframes.sortedBy { it.t }
		val result = mutableListOf<EditEvent>()
		val used = BooleanArray(sorted.size)
		sorted.forEachIndexed { i, kf ->
			if (!kf.on || used[i]) return@forEachIndexed
			val offIndex = sorted.indexOfFirst { o -> !o.on && o.x == kf.x && o.y == kf.y && o.t >= kf.t }
			val offT = if (offIndex >= 0) { used[offIndex] = true; sorted[offIndex].t } else kf.t
			val startMs = (kf.t * durationMs).toInt()
			val endMs = (offT * durationMs).toInt()
			result.add(EditEvent(kf.x, kf.y, kf.velocity, startMs, (endMs - startMs).coerceAtLeast(10)))
		}
		return result.sortedBy { it.startMs }
	}

	private fun adjustTime(deltaMs: Int) {
		authoringTimeMs = (authoringTimeMs + deltaMs).coerceAtLeast(0)
		timeText.text = "Time: ${authoringTimeMs}ms"
	}

	private fun placeEvent(x: Int, y: Int, velocity: Int) {
		events.add(EditEvent(x, y, velocity, authoringTimeMs, FIXED_DURATION_MS))
		events.sortBy { it.startMs }
		viewModel.logDebug("Lightshow: placed pad ($x,$y) vel=$velocity at ${authoringTimeMs}ms for ${FIXED_DURATION_MS}ms")
		refresh()
	}

	private fun removeEvent(event: EditEvent) {
		events.remove(event)
		refresh()
	}

	private fun playPreview() {
		stopPreview()
		val g = paintGrid()
		g.clearAllPads()
		val driver = MidiConnection.driver
		events.forEach { ev ->
			val argb = LaunchpadColor.ARGB.getOrElse(ev.velocity) { LaunchpadColor.ARGB[0] }.toInt()
			previewHandler.postDelayed({
				g.setPadLit(ev.x, ev.y, argb)
				driver.sendPadLed(ev.x, ev.y, ev.velocity)
			}, ev.startMs.toLong())
			previewHandler.postDelayed({
				g.clearPad(ev.x, ev.y)
				driver.sendPadLed(ev.x, ev.y, 0)
			}, (ev.startMs + ev.durationMs).toLong())
		}

		val audio = viewModel.decodedAudio.value
		val segmentIndex = viewModel.selectedLightshowSegment.value
		val session = viewModel.markingSession.value
		if (audio != null && segmentIndex != null && session != null) {
			val segment = session.segment(segmentIndex)
			val controller = AudioPlaybackController(audio)
			audioPreview = controller
			controller.playFrom(segment.startMs)
			previewHandler.postDelayed({ controller.stop() }, segment.durationMs.toLong())
		}
	}

	private fun stopPreview() {
		previewHandler.removeCallbacksAndMessages(null)
		val driver = MidiConnection.driver
		events.forEach { ev -> driver.sendPadLed(ev.x, ev.y, 0) }
		audioPreview?.stop()
		audioPreview = null
	}

	private fun saveAndDeselect() {
		stopPreview()
		val segmentIndex = viewModel.selectedLightshowSegment.value ?: return
		val session = viewModel.markingSession.value ?: return
		val segment = session.segment(segmentIndex)
		val durationMs = segment.durationMs.coerceAtLeast(1)

		if (events.isEmpty()) {
			viewModel.assignLightPatternToSelected(null)
			viewModel.deselectLightshowSegment()
			return
		}

		val keyframes = mutableListOf<Keyframe>()
		events.forEach { ev ->
			val onT = (ev.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
			val offT = ((ev.startMs + ev.durationMs).toFloat() / durationMs).coerceIn(onT, 1f)
			keyframes.add(Keyframe(t = onT, x = ev.x, y = ev.y, on = true, velocity = ev.velocity.coerceIn(1, 127)))
			keyframes.add(Keyframe(t = offT, x = ev.x, y = ev.y, on = false, velocity = 1))
		}
		val pattern = Pattern(name = "cut${segmentIndex + 1}", keyframes = keyframes)
		viewModel.assignLightPatternToSelected(pattern)
		viewModel.deselectLightshowSegment()
	}

	private fun refresh() {
		val session = viewModel.markingSession.value
		val activeChain = viewModel.currentChain.value ?: 0
		val selected = viewModel.selectedLightshowSegment.value
		refreshHardwareButtonHighlight()

		val g = paintGrid()
		if (session == null || session.segmentCount == 0) {
			statusText.text = "Import and mark a track first."
			eventListContainer.removeAllViews()
			g.clearAllPads()
			g.clearAllHighlights()
			return
		}

		if (selected != null) {
			renderEditMode(session, selected, g)
		} else {
			renderOverview(session, activeChain, g)
		}
	}

	private fun renderOverview(
		session: com.bobbypfreely.lpbf.marking.MarkingSession,
		activeChain: Int,
		g: VirtualLaunchpadGridView,
	) {
		val segments = session.segments()
		val mappedOnChain = segments.filter { it.button?.chain == activeChain }
		val stackHint = mappedOnChain.groupBy { it.button!!.x to it.button!!.y }.count { it.value.size > 1 }
		statusText.text = when {
			mappedOnChain.isEmpty() ->
				"Chain ${activeChain + 1} -- no cuts mapped. Map on SOUND first."
			stackHint > 0 ->
				"Chain ${activeChain + 1} -- tap pad to edit. Re-tap cycles stacked cuts ($stackHint pads)."
			else ->
				"Chain ${activeChain + 1} -- tap a highlighted pad to edit its lightshow."
		}

		eventListContainer.removeAllViews()
		g.clearAllPads()
		g.clearAllHighlights()

		val highlightColor = Color.parseColor("#00ADB5")
		val seenPads = HashSet<Pair<Int, Int>>()
		segments.forEach { seg ->
			val button = seg.button
			if (button != null && button.chain == activeChain) {
				val key = button.x to button.y
				if (seenPads.add(key)) {
					g.setPadHighlighted(button.x, button.y, highlightColor)
				}
				if (seg.lightPattern != null) {
					g.setPadLit(button.x, button.y, Color.parseColor("#33334A"))
				}
			}
		}
	}

	private fun renderEditMode(
		session: com.bobbypfreely.lpbf.marking.MarkingSession,
		segmentIndex: Int,
		g: VirtualLaunchpadGridView,
	) {
		val segment = session.segment(segmentIndex)
		val button = segment.button
		val stackCount = if (button != null) {
			session.segments().count { it.button == button }
		} else 1
		val stackPart = if (stackCount > 1) "  [${stackCount} on pad]" else ""
		statusText.text = if (button != null) {
			"Editing cut ${segmentIndex + 1} (${segment.durationMs}ms) pad (${button.x + 1},${button.y + 1})$stackPart"
		} else {
			"Editing cut ${segmentIndex + 1} (${segment.durationMs}ms)"
		}
		timeText.text = "Time: ${authoringTimeMs}ms"

		g.clearAllPads()
		g.clearAllHighlights()
		if (button != null) {
			g.setPadHighlighted(button.x, button.y, Color.parseColor("#FFFFFF"))
		}
		events.forEach { ev ->
			val argb = LaunchpadColor.ARGB.getOrElse(ev.velocity) { LaunchpadColor.ARGB[0] }.toInt()
			g.setPadLit(ev.x, ev.y, argb)
		}

		eventListContainer.removeAllViews()
		events.forEachIndexed { i, ev ->
			val row = TextView(requireContext()).apply {
				textSize = 12f
				setPadding(6, 6, 6, 6)
				setTextColor(Color.parseColor("#DDDDDD"))
				text = "${i + 1}. (${ev.x + 1},${ev.y + 1}) v=${ev.velocity} ${ev.startMs}-${ev.startMs + ev.durationMs}ms"
				setOnLongClickListener {
					removeEvent(ev)
					true
				}
			}
			eventListContainer.addView(row)
		}
		if (events.isEmpty()) {
			val hint = TextView(requireContext()).apply {
				textSize = 11f
				setPadding(6, 6, 6, 6)
				setTextColor(Color.parseColor("#888888"))
				text = "Pick a color, set Time, tap main pads to place. Long-press event to remove."
			}
			eventListContainer.addView(hint)
		}
	}

	override fun onDestroyView() {
		stopPreview()
		super.onDestroyView()
	}
}
