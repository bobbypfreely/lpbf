package com.bobbypfreely.lpbf.ui

/** Single trigger contract for "a pad was pressed/released" -- fed by BOTH a physical */
interface PadInputListener {
	fun onPadDown(x: Int, y: Int)
	fun onPadUp(x: Int, y: Int)

	/** /** Physical Launchpads have 8 side buttons (0-7) that page between 8 separate */
	fun onChainTouch(c: Int, upDown: Boolean) {}

	/** Top-row function keys (Unipad calls these Up/Down/Left/Right/Session/User1/
	 * User2/Mixer, indices 0-7). Default no-op for the same reason as onChainTouch. */
	fun onFunctionKeyTouch(f: Int, upDown: Boolean) {}
}
