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
 * hue centers (0/60/120/180/240/300 degrees) it's closest to. Within a bucket, entries are
 * sorted by hue-closeness first, saturation second, so index 0 is always the purest match
 * for that color and later indices drift toward neighboring hues/softer tones only as
 * needed -- that's what Up/Down steps through. This covers the real palette fully instead
 * of an arbitrarily narrow slice of it.
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

	// Saturation ladders: index 0 = closest match to the pure hue (and, among ties, most
	// vivid), later indices progressively drift toward neighboring hues/softer tones.
	// Every usable colorful entry in LaunchpadColor.ARGB (118 of 128) is classified into
	// whichever of these 6 buckets it's hue-closest to, so nothing vivid gets left out --
	// this reaches 122 of 128 total palette entries (plus White's own 4), versus 35
	// reachable under the first, artificially narrow version of this table.
	private val RED = intArrayOf(106, 6, 7, 107, 121, 5, 60, 120, 72, 4, 108, 127, 105, 83)
	private val YELLOW = intArrayOf(124, 97, 113, 125, 62, 15, 13, 14, 74, 99, 126, 100, 109, 73, 85, 98, 110, 111, 11, 12, 84, 9, 61, 10, 16, 17, 63, 18, 122, 96, 86, 19)
	private val GREEN = intArrayOf(87, 88, 123, 76, 21, 27, 23, 22, 26, 25, 64, 75)
	private val CYAN = intArrayOf(32, 90, 33, 35, 34, 68, 29, 37, 78, 38, 31, 102, 30, 39, 77, 36, 28, 65, 114, 24, 89, 40, 20, 41, 79, 42, 45, 101, 92)
	private val BLUE = intArrayOf(93, 80, 44, 112, 51, 47, 103, 69, 50, 46, 116, 48, 67, 49, 115, 104, 91, 66, 43)
	private val MAGENTA = intArrayOf(52, 82, 53, 54, 55, 94, 57, 58, 56, 59, 95, 81)
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
