package com.bobbypfreely.lpbf.lightshow

/** Compiles LightRoll / LightNote → normalized-time Pattern (t in 0..1). */
object LightRollCompiler {

	/** Frames per second used when expanding gradients into keyframes. */
	var gradientFps: Int = 30

	fun toPattern(roll: LightRoll): Pattern {
		val duration = roll.effectiveDurationMs().coerceAtLeast(1)
		val kf = ArrayList<Keyframe>()

		for (note in roll.notes) {
			val startT = (note.startMs.toFloat() / duration).coerceIn(0f, 1f)
			val endT = (note.endMs.toFloat() / duration).coerceIn(startT, 1f)
			val grad = note.gradient

			if (grad.isNullOrEmpty() || grad.size < 2) {
				kf += Keyframe(startT, note.x, note.y, true, note.velocity.coerceIn(1, 127))
				kf += Keyframe(endT, note.x, note.y, false, 1)
			} else {
				// Sample gradient along the note
				val noteDur = note.durationMs.coerceAtLeast(1)
				val frameMs = (1000f / gradientFps.coerceAtLeast(1)).coerceAtLeast(1f)
				var ms = 0
				while (ms < noteDur) {
					val frac = (ms.toFloat() / noteDur).coerceIn(0f, 1f)
					val v = interpolateVelocity(grad, frac)
					val t = (note.startMs + ms).toFloat() / duration
					kf += Keyframe(t.coerceIn(0f, 1f), note.x, note.y, true, v)
					ms += frameMs.toInt()
				}
				// Final sample at end-ε then off
				val vEnd = interpolateVelocity(grad, 1f)
				kf += Keyframe((endT - 0.001f).coerceAtLeast(startT), note.x, note.y, true, vEnd)
				kf += Keyframe(endT, note.x, note.y, false, 1)
			}
		}

		kf.sortWith(compareBy({ it.t }, { if (it.on) 0 else 1 }))
		return Pattern(roll.name.ifBlank { "roll" }, kf)
	}

	/** Rebuild a rough roll from an existing Pattern (for editor load). */
	fun fromPattern(pattern: Pattern, durationMs: Int): LightRoll {
		val dur = durationMs.coerceAtLeast(1)
		val sorted = pattern.keyframes.sortedBy { it.t }
		val notes = mutableListOf<LightNote>()
		val used = BooleanArray(sorted.size)
		var id = 0

		sorted.forEachIndexed { i, onKf ->
			if (!onKf.on || used[i]) return@forEachIndexed
			// Find matching off
			val offIdx = sorted.indexOfFirst { o ->
				!o.on && o.x == onKf.x && o.y == onKf.y && o.t >= onKf.t
			}
			val offT = if (offIdx >= 0) {
				used[offIdx] = true
				sorted[offIdx].t
			} else {
				onKf.t
			}
			used[i] = true
			val startMs = (onKf.t * dur).toInt()
			val endMs = (offT * dur).toInt().coerceAtLeast(startMs + 10)
			notes += LightNote(
				id = "n${id++}",
				x = onKf.x,
				y = onKf.y,
				startMs = startMs,
				endMs = endMs,
				velocity = onKf.velocity.coerceIn(1, 127),
			)
		}

		return LightRoll(name = pattern.name, notes = notes.sortedBy { it.startMs }, durationMs = dur)
	}

	private fun interpolateVelocity(stops: List<GradientStop>, t: Float): Int {
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
}