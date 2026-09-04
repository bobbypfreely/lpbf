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
import com.bobbypfreely.lpbf.R
import com.bobbypfreely.lpbf.audio.AudioPlaybackController
import com.bobbypfreely.lpbf.lightshow.Keyframe
import com.bobbypfreely.lpbf.lightshow.LightshowColorWheel
import com.bobbypfreely.lpbf.lightshow.Pattern
import com.bobbypfreely.lpbf.manager.LaunchpadColor
import com.bobbypfreely.lpbf.midi.MidiConnection
import com.bobbypfreely.lpbf.viewmodel.ProjectViewModel

/**
 * Lightshow: assigns each provisional segment (from Mark and Cut, mapped to a button on
 * Place) its own LED Pattern, one per cut -- same relationship audio has to a button.
 * Nothing is compiled to keyLED events here; that only happens at Finalize, mirroring
 * how Splice doesn't cut audio until you commit there either.
 *
 * Two modes:
 *  - OVERVIEW (nothing selected): grid border-highlights every mapped pad on the current
 *    chain. Tapping one selects it for editing -- tapping again cycles through any
 *    stacked cuts on that same pad, same convention Place uses for preview cycling.
 *  - EDIT (a cut is selected): the 8 chain buttons become a hardware color picker
 *    (see LightshowColorWheel) -- Up/Down steps saturation, Left/Right also steps hue.
 *    Tapping any pad on the grid places a light event there at the current authoring
 *    time, using the current color, for the current duration. Play previews the whole
 *    assembled sequence -- virtual grid, real hardware if connected, AND this cut's
 *    actual audio seeked to its own startMs -- so you can hear whether the lights and
 *    sound line up, not just watch the lights alone. Save compiles the event list into
 *    a duration-agnostic Pattern and stores it on the segment.
 *
 * Both physical Launchpad presses and on-screen grid taps route through the same
 * ProjectViewModel.onPadDown/onChainTouch/onFunctionKeyTouch Place already uses --
 * mode logic (select vs. place-event) lives once, in the ViewModel.
 */
class LightshowFragment : Fragment(R.layout.fragment_lightshow) {

	private val viewModel: ProjectViewModel by activityViewModels()

	private lateinit var statusText: TextView
	private lateinit var rowLabel: TextView
	private lateinit var buttonRow: LinearLayout
	private lateinit var editControls: LinearLayout
	private lateinit var velocityText: TextView
	private lateinit var stepRow: LinearLayout
	private lateinit var timeText: TextView
	private lateinit var durationText: TextView
	private lateinit var timeMinus: Button
	private lateinit var timePlus: Button
	private lateinit var durationMinus: Button
	private lateinit var durationPlus: Button
	private lateinit var eventListContainer: LinearLayout
	private lateinit var grid: VirtualLaunchpadGridView

	private val hardwareButtons = mutableListOf<Button>()
	private val stepButtons = mutableListOf<Button>()
	private val previewHandler = Handler(Looper.getMainLooper())

	/** Shared granularity for both Time and Duration's -/+ buttons. 50ms was the only
	 * option originally and left no room for fine placement -- this is user-selectable
	 * now, defaulting back to 50 since that's still the most common case. */
	private val stepOptions = intArrayOf(10, 25, 50, 100)
	private var stepMs = 50

	/** Built fresh against whichever DecodedAudio is current each time Play is pressed --
	 * cheap to construct, and this avoids holding a stale reference across a project
	 * switch. Plays the real decoded track (same PCM Mark and Cut and Place already
	 * share), seeked to this cut's own startMs, so Play previews audio and lights
	 * together instead of lights alone. */
	private var audioPreview: AudioPlaybackController? = null

	// ---- Local editing state for whichever cut is currently selected. Reset whenever
	// the ViewModel's selection changes. Nothing here is durable until Save. ----

	private data class EditEvent(val x: Int, val y: Int, val velocity: Int, val startMs: Int, val durationMs: Int)

	private val events = mutableListOf<EditEvent>()
	private var authoringTimeMs = 0
	private var authoringDurationMs = 200
	private var loadedForSegment: Int? = null

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		statusText = view.findViewById(R.id.lightshowStatusText)
		rowLabel = view.findViewById(R.id.lightshowRowLabel)
		buttonRow = view.findViewById(R.id.lightshowButtonRow)
		editControls = view.findViewById(R.id.lightshowEditControls)
		velocityText = view.findViewById(R.id.lightshowVelocityText)
		stepRow = view.findViewById(R.id.lightshowStepRow)
		timeText = view.findViewById(R.id.lightshowTimeText)
		durationText = view.findViewById(R.id.lightshowDurationText)
		timeMinus = view.findViewById(R.id.lightshowTimeMinus)
		timePlus = view.findViewById(R.id.lightshowTimePlus)
		durationMinus = view.findViewById(R.id.lightshowDurationMinus)
		durationPlus = view.findViewById(R.id.lightshowDurationPlus)
		eventListContainer = view.findViewById(R.id.lightshowEventListContainer)
		grid = view.findViewById(R.id.lightshowGrid)

		buildHardwareButtonRow()
		buildStepRow()

		// Same principle as Place: virtual grid taps and physical Launchpad presses both
		// funnel through ProjectViewModel.onPadDown, so this fragment never has to know
		// which one fired.
		grid.listener = object : PadInputListener {
			override fun onPadDown(x: Int, y: Int) = viewModel.onPadDown(x, y)
			override fun onPadUp(x: Int, y: Int) {}
		}

		timeMinus.setOnClickListener { adjustTime(-stepMs) }
		timePlus.setOnClickListener { adjustTime(+stepMs) }
		durationMinus.setOnClickListener { adjustDuration(-stepMs) }
		durationPlus.setOnClickListener { adjustDuration(+stepMs) }
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
		viewModel.isLightshowTabActive = true
	}

	override fun onPause() {
		super.onPause()
		viewModel.isLightshowTabActive = false
		stopPreview()
	}

	// ---- Dual-purpose 8-button row: chain selector in overview, color picker in edit ----

	private fun buildHardwareButtonRow() {
		buttonRow.removeAllViews()
		hardwareButtons.clear()
		for (slot in 0 until 8) {
			val button = Button(requireContext()).apply {
				textSize = 11f
				layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
					marginEnd = if (slot < 7) 4 else 0
				}
				setPadding(0, 8, 0, 8)
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
			button.text = if (editing) LightshowColorWheel.SLOT_NAMES[i] else (i + 1).toString()
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

	// ---- Step-size selector: governs both Time and Duration's -/+ granularity ----

	private fun buildStepRow() {
		stepRow.removeAllViews()
		stepButtons.clear()
		stepOptions.forEachIndexed { i, step ->
			val button = Button(requireContext()).apply {
				text = "${step}ms"
				textSize = 11f
				layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
					marginEnd = if (i < stepOptions.size - 1) 4 else 0
				}
				setPadding(0, 8, 0, 8)
				setOnClickListener { setStep(step) }
			}
			stepRow.addView(button)
			stepButtons.add(button)
		}
		refreshStepHighlight()
	}

	private fun setStep(step: Int) {
		stepMs = step
		refreshStepHighlight()
		timeMinus.text = "-$stepMs"
		timePlus.text = "+$stepMs"
		durationMinus.text = "-$stepMs"
		durationPlus.text = "+$stepMs"
	}

	private fun refreshStepHighlight() {
		stepButtons.forEachIndexed { i, button ->
			val isActive = stepOptions[i] == stepMs
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

	// ---- Selection changes: enter/exit edit mode, load any existing pattern back in ----

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
			authoringDurationMs = 200
			val session = viewModel.markingSession.value
			val segment = session?.segment(segmentIndex)
			val pattern = segment?.lightPattern
			if (pattern != null && segment.durationMs > 0) {
				events.addAll(reconstructEvents(pattern, segment.durationMs))
			}
		}
		refresh()
	}

	/** Reverses PatternCompiler's fractional scaling back into editable ms events by
	 * pairing each On keyframe with the next Off keyframe at the same (x,y). Best-effort:
	 * a hand-authored or built-in Pattern that doesn't cleanly pair on/off per pad will
	 * just show fewer events than it technically contains, not crash. */
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

	// ---- Time/duration steppers ----

	private fun adjustTime(deltaMs: Int) {
		authoringTimeMs = (authoringTimeMs + deltaMs).coerceAtLeast(0)
		timeText.text = "Time: ${authoringTimeMs}ms"
	}

	private fun adjustDuration(deltaMs: Int) {
		authoringDurationMs = (authoringDurationMs + deltaMs).coerceAtLeast(10)
		durationText.text = "Duration: ${authoringDurationMs}ms"
	}

	// ---- Placing / removing events ----

	private fun placeEvent(x: Int, y: Int, velocity: Int) {
		events.add(EditEvent(x, y, velocity, authoringTimeMs, authoringDurationMs))
		events.sortBy { it.startMs }
		viewModel.logDebug("Lightshow: placed pad ($x,$y) vel=$velocity at ${authoringTimeMs}ms for ${authoringDurationMs}ms")
		refresh()
	}

	private fun removeEvent(event: EditEvent) {
		events.remove(event)
		refresh()
	}

	// ---- Preview playback ----

	private fun playPreview() {
		stopPreview()
		grid.clearAllPads()
		val driver = MidiConnection.driver
		events.forEach { ev ->
			val argb = LaunchpadColor.ARGB.getOrElse(ev.velocity) { LaunchpadColor.ARGB[0] }.toInt()
			previewHandler.postDelayed({
				grid.setPadLit(ev.x, ev.y, argb)
				driver.sendPadLed(ev.x, ev.y, ev.velocity)
			}, ev.startMs.toLong())
			previewHandler.postDelayed({
				grid.clearPad(ev.x, ev.y)
				driver.sendPadLed(ev.x, ev.y, 0)
			}, (ev.startMs + ev.durationMs).toLong())
		}

		// Play the real cut's audio alongside the lights, so you can hear whether they
		// line up -- not just watch the lights in isolation.
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

	// ---- Save: compile the event list into a duration-agnostic Pattern ----

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

	// ---- Rendering ----

	private fun refresh() {
		val session = viewModel.markingSession.value
		val activeChain = viewModel.currentChain.value ?: 0
		val selected = viewModel.selectedLightshowSegment.value
		refreshHardwareButtonHighlight()

		if (session == null || session.segmentCount == 0) {
			statusText.text = "Import and mark a track first."
			eventListContainer.removeAllViews()
			grid.clearAllPads()
			grid.clearAllHighlights()
			return
		}

		if (selected != null) {
			renderEditMode(session, selected)
		} else {
			renderOverview(session, activeChain)
		}
	}

	private fun renderOverview(session: com.bobbypfreely.lpbf.marking.MarkingSession, activeChain: Int) {
		val segments = session.segments()
		val mappedOnChain = segments.filter { it.button?.chain == activeChain }
		statusText.text = if (mappedOnChain.isEmpty()) {
			"Chain ${activeChain + 1} -- no cuts mapped here yet. Map cuts on Place first."
		} else {
			"Chain ${activeChain + 1} -- tap a highlighted pad to edit its lightshow."
		}

		eventListContainer.removeAllViews()
		grid.clearAllPads()
		grid.clearAllHighlights()

		val highlightColor = Color.parseColor("#00ADB5")
		val seenPads = HashSet<Pair<Int, Int>>()
		segments.forEach { seg ->
			val button = seg.button
			if (button != null && button.chain == activeChain) {
				val key = button.x to button.y
				if (seenPads.add(key)) {
					grid.setPadHighlighted(button.x, button.y, highlightColor)
				}
				if (seg.lightPattern != null) {
					// A cut that already has a lightshow gets a soft fill so it's visible
					// at a glance which mapped pads still need one.
					grid.setPadLit(button.x, button.y, Color.parseColor("#33334A"))
				}
			}
		}
	}

	private fun renderEditMode(session: com.bobbypfreely.lpbf.marking.MarkingSession, segmentIndex: Int) {
		val segment = session.segment(segmentIndex)
		val button = segment.button
		statusText.text = if (button != null) {
			"Editing cut ${segmentIndex + 1} (${segment.durationMs}ms) -- pad (${button.x}, ${button.y})"
		} else {
			"Editing cut ${segmentIndex + 1} (${segment.durationMs}ms)"
		}
		timeText.text = "Time: ${authoringTimeMs}ms"
		durationText.text = "Duration: ${authoringDurationMs}ms"

		grid.clearAllPads()
		grid.clearAllHighlights()
		if (button != null) {
			grid.setPadHighlighted(button.x, button.y, Color.parseColor("#FFFFFF"))
		}
		events.forEach { ev ->
			val argb = LaunchpadColor.ARGB.getOrElse(ev.velocity) { LaunchpadColor.ARGB[0] }.toInt()
			grid.setPadLit(ev.x, ev.y, argb)
		}

		eventListContainer.removeAllViews()
		events.forEachIndexed { i, ev ->
			val row = TextView(requireContext()).apply {
				textSize = 13f
				setPadding(8, 8, 8, 8)
				setTextColor(Color.parseColor("#DDDDDD"))
				text = "${i + 1}. pad (${ev.x},${ev.y}) vel=${ev.velocity} -- ${ev.startMs}-${ev.startMs + ev.durationMs}ms"
				setOnLongClickListener {
					removeEvent(ev)
					true
				}
			}
			eventListContainer.addView(row)
		}
		if (events.isEmpty()) {
			val hint = TextView(requireContext()).apply {
				textSize = 12f
				setPadding(8, 8, 8, 8)
				setTextColor(Color.parseColor("#888888"))
				text = "Pick a color below, then tap pads on the grid to place events. Long-press an event here to remove it."
			}
			eventListContainer.addView(hint)
		}
	}

	override fun onDestroyView() {
		stopPreview()
		super.onDestroyView()
	}
}
