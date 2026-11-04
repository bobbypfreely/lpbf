package com.bobbypfreely.lpbf.lightshow

import android.content.Context

/**
 * On-device Lights Bot.
 *
 * Prompt → scene primitives → Pattern (0..1 keyframes).
 * No cloud. Output goes through the same pipeline as presets
 * (applyPatternShortcut → editable events → export).
 *
 * Learning: when the user opts in, saved patterns can be stored
 * as bot notes (see [BotNoteStore]) and later matched by tags.
 */
object LightsBot {

	data class Result(
		val pattern: Pattern,
		val status: String,          // short line shown to user
		val primitivesUsed: List<String>,
	)

	/**
	 * Main entry. Returns null only if the prompt is empty / unusable.
	 * Always produces *some* Pattern when the prompt has content —
	 * better a simple flash than silence.
	 */
	fun run(
		prompt: String,
		gridWidth: Int = 8,
		gridHeight: Int = 8,
		context: Context? = null,   // optional: enables bot-note lookup
	): Result? {
		val clean = prompt.trim()
		if (clean.isEmpty()) return null

		// 1. Try user-taught notes first (opt-in library)
		if (context != null) {
			val note = BotNoteStore.match(context, clean)
			if (note != null) {
				return Result(
					pattern = note.pattern,
					status = "Learned: ${note.name}",
					primitivesUsed = listOf("user_note:${note.name}"),
				)
			}
		}

		// 2. Parse prompt → ordered list of primitives
		val plan = PhraseParser.parse(clean)

		// 3. Codegen each primitive and merge
		val keyframes = ArrayList<Keyframe>()
		val used = ArrayList<String>()
		var tCursor = 0f

		for (step in plan) {
			val piece = PrimitiveCodegen.generate(step, gridWidth, gridHeight)
			if (piece.keyframes.isEmpty()) continue
			// shift this piece so it starts at tCursor
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
			// fallback so we never return nothing
			val fallback = BuiltInPatterns.flash(gridWidth, gridHeight)
			return Result(fallback, "Fallback flash", listOf("flash"))
		}

		val name = used.joinToString("_").ifBlank { "bot" }
		val pattern = Pattern(name, keyframes)
		val status = "Applied " + used.joinToString(" + ")
		return Result(pattern, status, used)
	}
}