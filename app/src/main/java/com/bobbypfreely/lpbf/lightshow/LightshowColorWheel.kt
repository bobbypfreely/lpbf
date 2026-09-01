package com.bobbypfreely.lpbf.lightshow

/**
 * Maps the Lightshow editor's hardware color picker (8 chain buttons = hue, Up/Down
 * function keys = saturation, Left/Right = also step hue) onto real entries in
 * LaunchpadColor.ARGB -- NOT arbitrary RGB. Every real-world keyLED file inspected uses
 * "a <velocity>" (auto/palette color), never a literal hex color, so the editor should
 * only ever resolve to a palette index (0-127), matching what actual Unipacks contain.
 *
 * Each ladder below was derived (not guessed) by loading LaunchpadColor.ARGB, converting
 * every entry to HSV, and keeping only entries within +-18 degrees of the pure hue center
 * (0/60/120/180/240/300), sorted by saturation descending. Index 0 in each ladder is the
 * most vivid/saturated entry for that hue; later indices are progressively softer --
 * that's what Up/Down steps through.
 *
 * Chain button slots (0-7): 0=Red 1=Yellow 2=Green 3=Cyan 4=Blue 5=Magenta 6=White 7=Clear
 */
object LightshowColorWheel {

	const val SLOT_RED = 0
	const val SLOT_YELLOW = 1
	const val SLOT_GREEN = 2
	const val SLOT_CYAN = 3
	const val SLOT_BLUE = 4
	const val SLOT_MAGENTA = 5
	const val SLOT_WHITE = 6
	const val SLOT_CLEAR = 7

	val SLOT_NAMES = listOf("Red", "Yellow", "Green", "Cyan", "Blue", "Magenta", "White", "Clear")

	// Saturation ladders: index 0 = most vivid, last = softest. Derived from LaunchpadColor.ARGB.
	private val RED = intArrayOf(121, 106, 5, 6, 7, 107)
	private val YELLOW = intArrayOf(124, 97, 62, 125, 13, 14)
	private val GREEN = intArrayOf(123, 76, 21, 88, 87)
	private val CYAN = intArrayOf(33, 35, 34, 68, 90, 32)
	private val BLUE = intArrayOf(47, 80, 51, 112, 44, 103)
	private val MAGENTA = intArrayOf(57, 55, 54, 53, 94, 82)
	private val WHITE = intArrayOf(3, 8, 70, 71)

	private val LADDERS = arrayOf(RED, YELLOW, GREEN, CYAN, BLUE, MAGENTA, WHITE)

	/** Number of saturation steps available for a given hue slot (0 for Clear, which has none). */
	fun saturationSteps(hueSlot: Int): Int {
		if (hueSlot == SLOT_CLEAR) return 0
		return LADDERS[hueSlot].size
	}

	/**
	 * Resolves a (hueSlot, saturationLevel) pair to a real palette velocity (0-127).
	 * saturationLevel is clamped into the ladder's range. SLOT_CLEAR always returns 0
	 * (off), matching keyLED's "off" semantics.
	 */
	fun velocityFor(hueSlot: Int, saturationLevel: Int): Int {
		if (hueSlot == SLOT_CLEAR) return 0
		val ladder = LADDERS[hueSlot.coerceIn(0, LADDERS.size - 1)]
		val level = saturationLevel.coerceIn(0, ladder.size - 1)
		return ladder[level]
	}
}
