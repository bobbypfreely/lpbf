package com.bobbypfreely.lpbf.ui

/**
 * Single trigger contract for "a pad was pressed/released" -- fed by BOTH a physical
 * Launchpad (via MidiControllerBridge, real hardware) and the on-screen VirtualLaunchpadGridView
 * (finger taps). Whatever fires it, the marking logic that consumes it doesn't need to care
 * which source it came from.
 */
interface PadInputListener {
	fun onPadDown(x: Int, y: Int)
	fun onPadUp(x: Int, y: Int)

	/** Physical Launchpads have 8 side buttons (0-7) that page between 8 separate
	 * 64-pad grids ("chains"). Default no-op since the virtual on-screen grid has no
	 * hardware chain buttons of its own -- only MidiControllerBridge fires this. */
	fun onChainTouch(c: Int, upDown: Boolean) {}
}
