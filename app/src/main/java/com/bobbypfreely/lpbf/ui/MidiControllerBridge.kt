package com.bobbypfreely.lpbf.ui

import com.bobbypfreely.lpbf.midi.controller.MidiController

/**
 * Wraps the ported MidiController so a real, physically-connected Launchpad drives the same
 * PadInputListener contract the on-screen virtual grid uses. Register this as
 * MidiConnection.controller to receive real hardware pad presses.
 */
class MidiControllerBridge(private val listener: PadInputListener) : MidiController() {

	override fun onAttach() {}
	override fun onDetach() {}

	override fun onPadTouch(x: Int, y: Int, upDown: Boolean, velocity: Int) {
		if (upDown) listener.onPadDown(x, y) else listener.onPadUp(x, y)
	}

	override fun onFunctionKeyTouch(f: Int, upDown: Boolean) {}
	override fun onChainTouch(c: Int, upDown: Boolean) {}
	override fun onUnknownEvent(cmd: Int, sig: Int, note: Int, velocity: Int) {}
}
