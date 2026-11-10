package com.bobbypfreely.lpbf.lightshow

import android.content.Context

/** Pattern generator from a short description. */
object LightsBot {

	data class Result(
		val pattern: Pattern,
		val status: String,          
		val primitivesUsed: List<String>,
	)

	/** Main entry. Returns null only if the prompt is empty / unusable. */
	fun run(
		prompt: String,
		gridWidth: Int = 8,
		gridHeight: Int = 8,
		context: Context? = null,   // optional notes
	): Result? {
		val clean = prompt.trim()
		if (clean.isEmpty()) return null

		// saved notes
		if (context != null) {
			val note = BotNoteStore.match(context, clean)
			if (note != null) {
				return Result(
					pattern = note.pattern,
					status = "Note: ${note.name}",
					primitivesUsed = listOf("note:${note.name}"),
				)
			}
		}

		// parse prompt
		val plan = PhraseParser.parse(clean)

		// codegen + merge
		val keyframes = ArrayList<Keyframe>()
		val used = ArrayList<String>()
		var tCursor = 0f

		for (step in plan) {
			val piece = PrimitiveCodegen.generate(step, gridWidth, gridHeight)
			if (piece.keyframes.isEmpty()) continue
			
			val localMax = piece.keyframes.maxOfOrNull { it.t } ?: 0f
			for (k in piece.keyframes) {
				keyframes += Keyframe(
					t = (tCursor + k.t * (1f - tCursor)).coerceIn(0f, 1f),
					x = k.x,
					y = k.y,
					on = k.on,
					velocity = k.velocity,
				)
			}
			used += step.name
			tCursor = (tCursor + localMax * 0.85f).coerceAtMost(0.95f)
		}

		if (keyframes.isEmpty()) {
			// fallback
			val fallback = BuiltInPatterns.flash(gridWidth, gridHeight)
			return Result(fallback, "Pattern: flash", listOf("flash"))
		}

		val name = used.joinToString("_").ifBlank { "pattern" }
		val pattern = Pattern(name, keyframes)
		val status = "Pattern: " + used.joinToString(" + ")
		return Result(pattern, status, used)
	}
} 