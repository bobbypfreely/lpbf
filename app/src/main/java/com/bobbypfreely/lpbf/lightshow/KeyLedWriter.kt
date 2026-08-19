package com.bobbypfreely.lpbf.lightshow

/**
 * Serializes a list of LedEvents into the plain-text keyLED format that
 * UniPackFolder.keyLed() (in the original unipad-android source) parses.
 * This is the writer half that the upstream project never needed, since it's read-only.
 *
 * Format per line, matching the real files:
 *   o <x|mc> <y> a <velocity>     -- on, auto color from velocity palette
 *   o <x|mc> <y> <hexColor> <velocity>  -- on, explicit color
 *   f <x|mc> <y>                  -- off
 *   d <ms>                        -- delay
 *   c <chain>                     -- chain jump (not emitted by PatternCompiler; reserved)
 *
 * Coordinates are written 1-indexed to match the on-disk format (parser subtracts 1 on read).
 */
object KeyLedWriter {

	fun write(events: List<LedAnimation.LedEvent>): String {
		val sb = StringBuilder()
		for (event in events) {
			when (event) {
				is LedAnimation.LedEvent.On -> {
					val target = if (event.x == -1) "mc" else (event.x + 1).toString()
					if (event.color >= 0) {
						sb.append("o ").append(target).append(' ').append(event.y + 1)
							.append(' ').append(Integer.toHexString(event.color))
							.append(' ').append(event.velocity).append('\n')
					} else {
						sb.append("o ").append(target).append(' ').append(event.y + 1)
							.append(" a ").append(event.velocity).append('\n')
					}
				}

				is LedAnimation.LedEvent.Off -> {
					val target = if (event.x == -1) "mc" else (event.x + 1).toString()
					sb.append("f ").append(target).append(' ').append(event.y + 1).append('\n')
				}

				is LedAnimation.LedEvent.Delay -> {
					sb.append("d ").append(event.delay).append('\n')
				}

				is LedAnimation.LedEvent.Chain -> {
					sb.append("c ").append(event.chain + 1).append('\n')
				}
			}
		}
		return sb.toString()
	}

	/**
	 * keyLED filenames encode their own coordinates: "<chain> <x> <y> <loop>",
	 * e.g. "1 1 2 1" for chain 1, x=1, y=2, loop=1 (all 1-indexed on disk).
	 */
	fun fileName(chain: Int, x: Int, y: Int, loop: Int): String {
		return "${chain + 1} ${x + 1} ${y + 1} $loop"
	}
}
