package com.bobbypfreely.lpbf.lightshow

import android.os.Handler
import android.os.Looper

/**
 * Mini Heaven-style LED scheduler for LPBF preview.
 *
 * - schedule(delayMs, owner, job)
 * - cancelAll(owner) so re-trigger / stop doesn't leave stale pad lights
 * - Runs on main Handler (UI + MIDI safe on Android)
 *
 * Not a port of Amethyst Heaven — same *idea*: timed jobs + owner cancel.
 */
class LightScheduler(
	private val handler: Handler = Handler(Looper.getMainLooper()),
) {
	private data class Job(
		val owner: Any,
		val runnable: Runnable,
	)

	private val jobs = mutableListOf<Job>()
	private val lock = Any()

	/** Schedule [block] after [delayMs]. Tag with [owner] for batch cancel. */
	fun schedule(delayMs: Long, owner: Any, block: () -> Unit) {
		val runnable = object : Runnable {
			override fun run() {
				try {
					block()
				} finally {
					synchronized(lock) {
						jobs.removeAll { it.runnable === this }
					}
				}
			}
		}
		synchronized(lock) {
			jobs += Job(owner, runnable)
		}
		handler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
	}

	/** Cancel every pending job for this owner (e.g. this fragment / preview session). */
	fun cancelAll(owner: Any) {
		synchronized(lock) {
			val mine = jobs.filter { it.owner === owner }
			mine.forEach { handler.removeCallbacks(it.runnable) }
			jobs.removeAll { it.owner === owner }
		}
	}

	/** Cancel everything. */
	fun cancelAll() {
		synchronized(lock) {
			jobs.forEach { handler.removeCallbacks(it.runnable) }
			jobs.clear()
		}
	}

	/**
	 * Play a [LightRoll] (or compiled events) onto [onPad].
	 * [onPad](x, y, velocity) — velocity 0 = off.
	 */
	fun playRoll(
		roll: LightRoll,
		owner: Any,
		onPad: (x: Int, y: Int, velocity: Int) -> Unit,
		onComplete: (() -> Unit)? = null,
	) {
		cancelAll(owner)
		val notes = roll.notes
		if (notes.isEmpty()) {
			onComplete?.invoke()
			return
		}

		for (note in notes) {
			val grad = note.gradient
			if (grad.isNullOrEmpty() || grad.size < 2) {
				schedule(note.startMs.toLong(), owner) {
					onPad(note.x, note.y, note.velocity.coerceIn(1, 127))
				}
				schedule(note.endMs.toLong(), owner) {
					onPad(note.x, note.y, 0)
				}
			} else {
				// Gradient: step velocity along the note
				val fps = 30
				val frameMs = 1000 / fps
				var ms = note.startMs
				while (ms < note.endMs) {
					val frac = ((ms - note.startMs).toFloat() / note.durationMs).coerceIn(0f, 1f)
					val vel = interpolateVel(grad, frac)
					val at = ms.toLong()
					schedule(at, owner) { onPad(note.x, note.y, vel) }
					ms += frameMs
				}
				schedule(note.endMs.toLong(), owner) { onPad(note.x, note.y, 0) }
			}
		}

		val end = roll.effectiveDurationMs().toLong()
		schedule(end, owner) { onComplete?.invoke() }
	}

	/**
	 * Play an existing [Pattern] scaled to [durationMs].
	 */
	fun playPattern(
		pattern: Pattern,
		durationMs: Int,
		owner: Any,
		onPad: (x: Int, y: Int, velocity: Int) -> Unit,
		onComplete: (() -> Unit)? = null,
	) {
		cancelAll(owner)
		val dur = durationMs.coerceAtLeast(1)
		for (k in pattern.keyframes) {
			val at = (k.t * dur).toLong().coerceAtLeast(0L)
			val v = if (k.on) k.velocity.coerceIn(1, 127) else 0
			schedule(at, owner) { onPad(k.x, k.y, v) }
		}
		schedule(dur.toLong(), owner) { onComplete?.invoke() }
	}
}

/** Package-private helper so LightScheduler can sample gradients without exposing compiler guts. */
private fun interpolateVel(stops: List<GradientStop>, t: Float): Int {
	val sorted = stops.sortedBy { it.t }
	if (sorted.isEmpty()) return 100
	if (t <= sorted.first().t) return sorted.first().velocity.coerceIn(1, 127)
	if (t >= sorted.last().t) return sorted.last().velocity.coerceIn(1, 127)
	for (i in 0 until sorted.lastIndex) {
		val a = sorted[i]
		val b = sorted[i + 1]
		if (t >= a.t && t <= b.t) {
			val span = (b.t - a.t).coerceAtLeast(0.0001f)
			val u = (t - a.t) / span
			return (a.velocity + (b.velocity - a.velocity) * u).toInt().coerceIn(1, 127)
		}
	}
	return sorted.last().velocity.coerceIn(1, 127)
}