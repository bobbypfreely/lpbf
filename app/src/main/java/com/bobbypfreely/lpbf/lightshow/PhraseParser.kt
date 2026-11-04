package com.bobbypfreely.lpbf.lightshow

/**
 * Offline keyword / phrase rules → ordered list of [PrimitiveStep].
 * No network, no model. Deterministic.
 */
object PhraseParser {

	data class PrimitiveStep(
		val name: String,
		val params: Map<String, String> = emptyMap(),
	)

	fun parse(prompt: String): List<PrimitiveStep> {
		val p = prompt.lowercase()
		val steps = ArrayList<PrimitiveStep>()

		// order matters — more specific phrases first
		fun has(vararg phrases: String) = phrases.any { p.contains(it) }

		// explosions / blooms
		if (has("explod", "firework", "bloom", "flower", "burst", "radial", "from the center", "inside out", "outward")) {
			val colors = extractColors(p)
			steps += PrimitiveStep("bloom", mapOf("colors" to colors.joinToString(",")))
		}

		// cascades / sweeps
		if (has("cascade", "waterfall", "sweep", "rain", "fall down", "top to bottom")) {
			steps += PrimitiveStep("cascade")
		}
		if (has("row wipe", "horizontal", "left to right", "right to left", "streak across")) {
			steps += PrimitiveStep("row_wipe")
		}
		if (has("column wipe", "col wipe", "vertical", "up to down", "streak down")) {
			steps += PrimitiveStep("col_wipe")
		}

		// fills / solids
		if (has("fill", "solid", "whole pad", "all pads", "flood")) {
			steps += PrimitiveStep("fill")
		}

		// flash
		if (has("flash", "blink", "strobe", "pop")) {
			steps += PrimitiveStep("flash")
		}

		// whip / hard off
		if (has("whip", "snap off", "hard off", "cut off", "kill", "blackout")) {
			steps += PrimitiveStep("whip_off")
		}

		// wheel / spin
		if (has("wheel", "spin", "rotate", "turn", "circle")) {
			steps += PrimitiveStep("wheel")
		}

		// single / sparkle
		if (has("sparkle", "twinkle", "stars")) {
			steps += PrimitiveStep("sparkle")
		}

		// if nothing matched, give a safe default based on length
		if (steps.isEmpty()) {
			steps += if (p.length < 12) PrimitiveStep("flash") else PrimitiveStep("bloom")
		}

		return steps
	}

	private fun extractColors(p: String): List<String> {
		val known = listOf(
			"red", "orange", "yellow", "green", "cyan", "blue",
			"purple", "pink", "white", "magenta",
		)
		return known.filter { p.contains(it) }
	}
}