package com.bobbypfreely.lpbf.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
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
 * Lightshow authoring in the LED drawer.
 * - No bottom time/velocity strip — edit happens in the Events list.
 * - Tap an event to select its time group; pick a color → prompt to set velocity
 *   for all pads on at that same start time (not the whole clip).
 * - Long-press an event to remove it. Tap main grid to place at selected time.
 */
class LightshowFragment : Fragment(R.layout.fragment_lightshow) {

	private val viewModel: ProjectViewModel by activityViewModels()

	private lateinit var statusText: TextView
	private lateinit var rowLabel: TextView
	private lateinit var buttonRow: LinearLayout
	private lateinit var editControls: LinearLayout
	private lateinit var eventListContainer: LinearLayout
	private lateinit var grid: VirtualLaunchpadGridView

	private val hardwareButtons = mutableListOf<Button>()
	private val previewHandler = Handler(Looper.getMainLooper())

	private val FIXED_DURATION_MS = 200

	private var audioPreview: AudioPlaybackController? = null

	private data class EditEvent(
		val x: Int,
		val y: Int,
		var velocity: Int,
		var startMs: Int,
		var durationMs: Int,
	)

	private val events = mutableListOf<EditEvent>()
	/** Selected simultaneous group = events sharing this startMs. */
	private var selectedStartMs: Int? = null
	private var loadedForSegment: Int? = null

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
		eventListContainer = view.findViewById(R.id.lightshowEventListContainer)
		grid = view.findViewById(R.id.lightshowGrid)

		buildHardwareButtonRow()

		view.findViewById<Button>(R.id.lightshowPlay).setOnClickListener { playPreview() }
		view.findViewById<Button>(R.id.lightshowStop).setOnClickListener { stopPreview() }
		view.findViewById<Button>(R.id.lightshowSave).setOnClickListener { savePattern(staySelected = true) }
		view.findViewById<Button>(R.id.lightshowBack).setOnClickListener {
			stopPreview()
			viewModel.deselectLightshowSegment()
		}

		viewModel.selectedLightshowSegment.observe(viewLifecycleOwner) { onSelectionChanged(it) }
		viewModel.currentChain.observe(viewLifecycleOwner) { refresh() }
		viewModel.colorHueSlot.observe(viewLifecycleOwner) { refreshHardwareButtonHighlight() }
		viewModel.segmentVersion.observe(viewLifecycleOwner) { refresh() }
		viewModel.markingSession.observe(viewLifecycleOwner) { refresh() }
		viewModel.lightshowEditorEpoch.observe(viewLifecycleOwner) {
			val sel = viewModel.selectedLightshowSegment.value
			if (sel != null) {
				loadedForSegment = null
				onSelectionChanged(sel)
			}
		}

		viewModel.lightshowPadPress.observe(viewLifecycleOwner) { press ->
			if (press != null) {
				placeEvent(press.x, press.y, press.velocity)
				viewModel.clearLightshowPadPress()
			}
		}
	}

	override fun onResume() {
		super.onResume()
		if (viewModel.uiMode.value == ProjectViewModel.UiMode.LIGHTS) {
			viewModel.isLightshowTabActive = true
		}
		refresh()
	}

	override fun onPause() {
		stopPreview()
		super.onPause()
	}

	private fun buildHardwareButtonRow() {
		buttonRow.removeAllViews()
		hardwareButtons.clear()
		for (slot in 0 until 8) {
			val button = Button(requireContext()).apply {
				textSize = 10f
				layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
					marginEnd = if (slot < 7) 2 else 0
					height = (36 * resources.displayMetrics.density).toInt()
				}
				setPadding(0, 4, 0, 4)
				setOnClickListener {
					if (viewModel.selectedLightshowSegment.value != null) {
						viewModel.setColorHueSlot(slot)
						val vel = viewModel.currentColorVelocity()
						promptApplyVelocityToTimeGroup(vel)
					} else {
						viewModel.setCurrentChain(slot)
					}
				}
			}
			buttonRow.addView(button)
			hardwareButtons.add(button)
		}
	}

	/**
	 * Apply [velocity] to every event that starts at the selected time
	 * (pads lighting together). Never rewrites the whole clip.
	 */
	private fun promptApplyVelocityToTimeGroup(velocity: Int) {
		val groupMs = selectedStartMs
		if (groupMs == null) {
			viewModel.logDebug("Pick an event first, then a color — velocity applies to that time only")
			statusText.text = "Tap an event, then a color to recolor that moment."
			return
		}
		val group = events.filter { it.startMs == groupMs }
		if (group.isEmpty()) return

		val padList = group.joinToString(", ") { "(${it.x + 1},${it.y + 1})" }
		AlertDialog.Builder(requireContext())
			.setTitle("Velocity $velocity @ ${groupMs}ms")
			.setMessage(
				"Apply velocity $velocity to all ${group.size} pad(s) lighting at ${groupMs}ms?\n\n$padList\n\n" +
					"(Only this moment — not the whole clip.)",
			)
			.setPositiveButton("Apply") { _, _ ->
				group.forEach { it.velocity = velocity.coerceIn(1, 127) }
				savePattern(staySelected = true)
				refresh()
				viewModel.logDebug("Velocity $velocity set on ${group.size} pad(s) at ${groupMs}ms")
			}
			.setNegativeButton("Cancel", null)
			.show()
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

	private fun onSelectionChanged(segmentIndex: Int?) {
		editControls.visibility = if (segmentIndex != null) View.VISIBLE else View.GONE
		rowLabel.text = if (segmentIndex != null) "Color (applies to selected time):" else "Chain:"

		if (segmentIndex == null) {
			loadedForSegment = null
			events.clear()
			selectedStartMs = null
			stopPreview()
			refresh()
			return
		}

		if (loadedForSegment != segmentIndex) {
			loadedForSegment = segmentIndex
			events.clear()
			selectedStartMs = null
			val session = viewModel.markingSession.value
			val segment = session?.segment(segmentIndex)
			val pattern = segment?.lightPattern
			if (pattern != null && segment.durationMs > 0) {
				events.addAll(reconstructEvents(pattern, segment.durationMs))
				selectedStartMs = events.firstOrNull()?.startMs
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
			val offT = if (offIndex >= 0) {
				used[offIndex] = true
				sorted[offIndex].t
			} else kf.t
			val startMs = (kf.t * durationMs).toInt()
			val endMs = (offT * durationMs).toInt()
			result.add(
				EditEvent(
					kf.x, kf.y, kf.velocity, startMs,
					(endMs - startMs).coerceAtLeast(10),
				),
			)
		}
		return result.sortedBy { it.startMs }
	}

	private fun placeEvent(x: Int, y: Int, velocity: Int) {
		val at = selectedStartMs ?: 0
		events.add(EditEvent(x, y, velocity, at, FIXED_DURATION_MS))
		events.sortBy { it.startMs }
		selectedStartMs = at
		viewModel.logDebug("Lightshow: pad ($x,$y) v=$velocity at ${at}ms")
		savePattern(staySelected = true)
		refresh()
	}

	private fun removeEvent(event: EditEvent) {
		events.remove(event)
		if (selectedStartMs != null && events.none { it.startMs == selectedStartMs }) {
			selectedStartMs = events.firstOrNull()?.startMs
		}
		savePattern(staySelected = true)
		refresh()
	}

	private fun showEventEditor(event: EditEvent) {
		val ctx = requireContext()
		val density = resources.displayMetrics.density
		val pad = (8 * density).toInt()
		val col = LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(pad, pad, pad, pad)
		}

		fun field(label: String, value: String): EditText {
			col.addView(TextView(ctx).apply {
				text = label
				setTextColor(Color.parseColor("#AAAAAA"))
				textSize = 11f
			})
			val et = EditText(ctx).apply {
				setText(value)
				setTextColor(Color.parseColor("#EEEEEE"))
				setHintTextColor(Color.parseColor("#666666"))
				inputType = android.text.InputType.TYPE_CLASS_NUMBER
			}
			col.addView(et)
			return et
		}

		val startEt = field("Start (ms)", event.startMs.toString())
		val durEt = field("Duration (ms)", event.durationMs.toString())
		val velEt = field("Velocity (1-127)", event.velocity.toString())
		val xEt = field("Pad X (1-8)", (event.x + 1).toString())
		val yEt = field("Pad Y (1-8)", (event.y + 1).toString())

		AlertDialog.Builder(ctx)
			.setTitle("Edit event")
			.setView(col)
			.setPositiveButton("OK") { _, _ ->
				val newStart = startEt.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: event.startMs
				val newDur = durEt.text.toString().toIntOrNull()?.coerceAtLeast(10) ?: event.durationMs
				val newVel = velEt.text.toString().toIntOrNull()?.coerceIn(1, 127) ?: event.velocity
				val nx = (xEt.text.toString().toIntOrNull() ?: (event.x + 1)).coerceIn(1, 8) - 1
				val ny = (yEt.text.toString().toIntOrNull() ?: (event.y + 1)).coerceIn(1, 8) - 1
				val idx = events.indexOf(event)
				if (idx >= 0) {
					events[idx] = EditEvent(nx, ny, newVel, newStart, newDur)
					selectedStartMs = newStart
				}
				events.sortBy { it.startMs }
				savePattern(staySelected = true)
				refresh()
			}
			.setNeutralButton("Delete") { _, _ -> removeEvent(event) }
			.setNegativeButton("Cancel", null)
			.show()
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

	private fun savePattern(staySelected: Boolean) {
		stopPreview()
		val segmentIndex = viewModel.selectedLightshowSegment.value ?: return
		val session = viewModel.markingSession.value ?: return
		val segment = session.segment(segmentIndex)
		val durationMs = segment.durationMs.coerceAtLeast(1)

		if (events.isEmpty()) {
			viewModel.assignLightPatternToSelected(null)
			if (!staySelected) viewModel.deselectLightshowSegment()
			return
		}

		val keyframes = mutableListOf<Keyframe>()
		events.forEach { ev ->
			val onT = (ev.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
			val offT = ((ev.startMs + ev.durationMs).toFloat() / durationMs).coerceIn(onT, 1f)
			keyframes.add(
				Keyframe(
					t = onT, x = ev.x, y = ev.y, on = true,
					velocity = ev.velocity.coerceIn(1, 127),
				),
			)
			keyframes.add(
				Keyframe(t = offT, x = ev.x, y = ev.y, on = false, velocity = 1),
			)
		}
		val name = segment.lightPattern?.name?.takeIf { it.isNotBlank() } ?: "cut${segmentIndex + 1}"
		viewModel.assignLightPatternToSelected(Pattern(name = name, keyframes = keyframes))
		if (!staySelected) {
			viewModel.deselectLightshowSegment()
		} else {
			loadedForSegment = segmentIndex
		}
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
		val patternName = segment.lightPattern?.name?.takeIf { it.isNotBlank() }
		val groupHint = selectedStartMs?.let { "  | focus ${it}ms" } ?: ""
		statusText.text = buildString {
			append("Cut ${segmentIndex + 1} (${segment.durationMs}ms)")
			if (button != null) append(" pad (${button.x + 1},${button.y + 1})")
			if (patternName != null) append(" [$patternName]")
			append(groupHint)
		}

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
		if (events.isEmpty()) {
			eventListContainer.addView(
				TextView(requireContext()).apply {
					textSize = 11f
					setPadding(6, 6, 6, 6)
					setTextColor(Color.parseColor("#888888"))
					text = "Empty — pick a PRESET on the right, or tap main pads to place lights."
				},
			)
			return
		}

		val groups = events.groupBy { it.startMs }.toSortedMap()
		groups.forEach { (startMs, group) ->
			val isFocus = selectedStartMs == startMs
			val header = TextView(requireContext()).apply {
				textSize = 11f
				setPadding(6, 10, 6, 4)
				setTextColor(if (isFocus) Color.parseColor("#00ADB5") else Color.parseColor("#8888AA"))
				text = if (isFocus) "▼ ${startMs}ms  (${group.size} pad(s)) — selected"
				else "▶ ${startMs}ms  (${group.size} pad(s))"
				setOnClickListener {
					selectedStartMs = startMs
					refresh()
				}
			}
			eventListContainer.addView(header)

			group.forEach { ev ->
				val row = TextView(requireContext()).apply {
					textSize = 12f
					setPadding(16, 8, 6, 8)
					val bg = if (isFocus) Color.parseColor("#252540") else Color.parseColor("#1A1A2E")
					setBackgroundColor(bg)
					setTextColor(Color.parseColor("#DDDDDD"))
					text = "(${ev.x + 1},${ev.y + 1})  v=${ev.velocity}  ${ev.startMs}+${ev.durationMs}ms"
					setOnClickListener {
						selectedStartMs = ev.startMs
						showEventEditor(ev)
					}
					setOnLongClickListener {
						removeEvent(ev)
						true
					}
				}
				eventListContainer.addView(row)
			}
		}
	}

	override fun onDestroyView() {
		stopPreview()
		super.onDestroyView()
	}
}