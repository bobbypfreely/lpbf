package com.bobbypfreely.lpbf.harness

import android.os.Handler
import android.os.Looper
import com.bobbypfreely.lpbf.lightshow.LedAnimation
import com.bobbypfreely.lpbf.lightshow.Pattern
import com.bobbypfreely.lpbf.lightshow.PatternCompiler
import com.bobbypfreely.lpbf.manager.LaunchpadColor
import com.bobbypfreely.lpbf.midi.MidiConnection
import com.bobbypfreely.lpbf.ui.VirtualLaunchpadGridView

/**
 * Plays a light pattern on the main grid + MIDI driver.
 * Note: current policy still clears previous pattern when a new one starts
 * (audio is polyphonic; lights can be upgraded the same way later).
 */
class LightModuleImpl(
	private val grid: () -> VirtualLaunchpadGridView?,
	private val isLightsAuthoring: () -> Boolean = { false },
	private val onBusyChanged: (Boolean) -> Unit = {},
) : LightModule {

	private val handler = Handler(Looper.getMainLooper())
	@Volatile var isBusy: Boolean = false
		private set

	override fun play(pattern: Pattern?, durationMs: Int) {
		clear()
		if (pattern == null || pattern.keyframes.isEmpty() || durationMs <= 0) return
		if (isLightsAuthoring()) return

		val g = grid() ?: return
		val events = try {
			PatternCompiler.compile(pattern, durationMs)
		} catch (_: Exception) {
			return
		}

		isBusy = true
		onBusyChanged(true)
		val driver = MidiConnection.driver
		var tMs = 0
		for (ev in events) {
			when (ev) {
				is LedAnimation.LedEvent.Delay -> tMs += ev.delay
				is LedAnimation.LedEvent.On -> {
					val at = tMs.toLong()
					val x = ev.x
					val y = ev.y
					val vel = ev.velocity.coerceIn(0, 127)
					if (x < 0 || y < 0) continue
					handler.postDelayed({
						val argb = LaunchpadColor.ARGB.getOrElse(vel) { LaunchpadColor.ARGB[0] }.toInt()
						val paint = if ((argb ushr 24) == 0) 0xFF000000.toInt() or (argb and 0x00FFFFFF) else argb
						g.setPadLit(x, y, paint)
						try { driver.sendPadLed(x, y, vel) } catch (_: Exception) { }
					}, at)
				}
				is LedAnimation.LedEvent.Off -> {
					val at = tMs.toLong()
					val x = ev.x
					val y = ev.y
					if (x < 0 || y < 0) continue
					handler.postDelayed({
						g.clearPad(x, y)
						try { driver.sendPadLed(x, y, 0) } catch (_: Exception) { }
					}, at)
				}
				else -> { }
			}
		}
		handler.postDelayed({
			isBusy = false
			onBusyChanged(false)
		}, durationMs.toLong() + 40)
	}

	override fun clear() {
		handler.removeCallbacksAndMessages(null)
		isBusy = false
		onBusyChanged(false)
		try { MidiConnection.driver.sendClearLed() } catch (_: Exception) { }
	}

	override fun release() = clear()
}
