package com.bobbypfreely.lpbf.lightshow

/**
 * Maps the Lightshow editor's hardware color picker (8 chain buttons = hue, Up/Down
 * function keys = saturation, Left/Right = also step hue) onto real entries in
 * LaunchpadColor.ARGB -- NOT arbitrary RGB. Every real-world keyLED file inspected uses
 * "a <velocity>" (auto/palette color), never a literal hex color, so the editor should
 * only ever resolve to a palette index (0-127), matching what actual Unipacks contain.
 *
 * Each ladder below was derived (not guessed) by loading LaunchpadColor.ARGB, converting
 * every entry to HSV, and classifying every usable colorful entry (118 of 128 -- the rest
 * are near-duplicates or too dim/translucent to read on a pad) into whichever of the 6
 * hue centers (0/60/120/180/240/300 degrees) it's closest to. Within a bucket, entries
 * are sorted by vividness (saturation, weighted so darker-but-saturated doesn't beat a
 * brighter true color) first, hue-closeness only as a tiebreaker -- NOT hue-closeness
 * first. An earlier version sorted hue-closeness first, which meant "Blue" defaulted to
 * velocity 93 (a pale lavender, hue=240 exactly but saturation=0.27) over velocity 66/67
 * (real vivid blues, hue~213-225 but saturation~0.8+) -- mathematically closest to the
 * pure hue isn't the same as "looks like a strong version of that color," and the whole
 * point of index 0 being the default pick is that it should look right immediately.
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

	// Saturation ladders: index 0 = most vivid match for that hue, later indices
	// progressively softer/more pastel. Every usable colorful entry in
	// LaunchpadColor.ARGB (118 of 128) is classified into whichever of these 6 buckets
	// it's hue-closest to, so nothing vivid gets left out -- this reaches 122 of 128
	// total palette entries (plus White's own 4).
	private val RED = intArrayOf(120, 72, 127, 60, 121, 106, 5, 108, 83, 6, 107, 7, 105, 4)
	private val YELLOW = intArrayOf(124, 84, 62, 97, 74, 126, 98, 9, 61, 96, 85, 125, 99, 111, 13, 10, 100, 86, 17, 63, 11, 109, 73, 122, 110, 14, 18, 12, 19, 15, 113, 16)
	private val GREEN = intArrayOf(25, 26, 27, 64, 21, 76, 22, 123, 75, 23, 88, 87)
	private val CYAN = intArrayOf(37, 38, 78, 35, 31, 33, 79, 34, 29, 30, 41, 77, 65, 68, 39, 101, 42, 45, 90, 102, 32, 24, 36, 20, 40, 28, 92, 89, 114)
	private val BLUE = intArrayOf(66, 67, 43, 46, 49, 47, 69, 80, 104, 50, 51, 44, 112, 48, 91, 103, 93, 116, 115)
	private val MAGENTA = intArrayOf(58, 57, 59, 95, 55, 54, 81, 53, 94, 56, 82, 52)
	private val WHITE = intArrayOf(8, 3, 70, 71)

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
