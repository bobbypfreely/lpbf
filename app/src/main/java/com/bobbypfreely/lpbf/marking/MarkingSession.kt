package com.bobbypfreely.lpbf.marking

import com.bobbypfreely.lpbf.lightshow.Pattern

/**
 * Manages the full lifecycle of cutting one source track into ordered Clips:
 *   1. Live tap-marking (recordMark) while playing the track
 *   2. Ripple-edit fine-tuning (rippleMoveMark) and re-mapping (reassignButton)
 *   3. Locking it all in (splice)
 *
 * Marks are stored as plain millisecond positions, index 0 is always a fixed
 * mark at 0 (the track's start) and is never itself movable or removable.
 * Segment N is the gap between marks[N] and marks[N+1]; segmentButtons,
 * segmentPatterns, segmentLoops and segmentWormholes are parallel lists so a
 * segment's button, lightshow, loop and wormhole are each independent of the
 * marks that bound it.
 *
 * Live mark-while-playing is unchanged: recordMark / dropMark still create a
 * segment with button=null, loop=1 (play once), wormhole=-1. Mapping can happen
 * on the fly later (or via Place / hybrid) without selecting after the first cut.
 *
 * Nothing here touches actual audio or LED compilation -- this only tracks
 * timestamps, button assignments, loop/wormhole, and (duration-agnostic) light
 * Patterns. Real PCM slicing and keyLED event compilation both happen downstream
 * once splice() returns.
 */
class MarkingSession(private val trackDurationMs: Int) {

	companion object {
		const val MAX_SEGMENT_MS = 8000

		/** Rebuilds a session from previously-saved marks/buttons/patterns (project resume
		 * or Unipack import). Bypasses recordMark()'s live-input validation since this
		 * data was already valid when it was saved.
		 * [loops] / [wormholes] default so old call sites keep working. */
		fun restore(
			trackDurationMs: Int,
			marks: List<Int>,
			buttons: List<ButtonRef?>,
			patterns: List<Pattern?> = List(buttons.size) { null },
			loops: List<Int> = List(buttons.size) { 1 },
			wormholes: List<Int> = List(buttons.size) { -1 },
		): MarkingSession {
			val session = MarkingSession(trackDurationMs)
			session.marks.clear()
			session.marks.addAll(marks)
			session.segmentButtons.clear()
			session.segmentButtons.addAll(buttons)
			session.segmentPatterns.clear()
			session.segmentPatterns.addAll(
				if (patterns.size == buttons.size) patterns else List(buttons.size) { null }
			)
			session.segmentLoops.clear()
			session.segmentLoops.addAll(
				if (loops.size == buttons.size) loops else List(buttons.size) { 1 }
			)
			session.segmentWormholes.clear()
			session.segmentWormholes.addAll(
				if (wormholes.size == buttons.size) wormholes else List(buttons.size) { -1 }
			)
			return session
		}
	}

	sealed class RecordResult {
		data class Committed(val segmentIndex: Int, val durationMs: Int) : RecordResult()
		data class ExceedsCap(val gapMs: Int, val maxMs: Int) : RecordResult()
	}

	private val marks = mutableListOf(0)
	private val segmentButtons = mutableListOf<ButtonRef?>()
	private val segmentPatterns = mutableListOf<Pattern?>()
	private val segmentLoops = mutableListOf<Int>()
	private val segmentWormholes = mutableListOf<Int>()

	private data class Snapshot(
		val marks: List<Int>,
		val buttons: List<ButtonRef?>,
		val patterns: List<Pattern?>,
		val loops: List<Int>,
		val wormholes: List<Int>,
	)
	private val undoStack = ArrayDeque<Snapshot>()
	private val redoStack = ArrayDeque<Snapshot>()

	var isSpliced = false
		private set

	// ---- Read-only views -------------------------------------------------

	val segmentCount: Int get() = segmentButtons.size

	fun segment(index: Int): Segment {
		return Segment(
			marks[index], marks[index + 1],
			segmentButtons[index], segmentPatterns[index],
			segmentLoops.getOrElse(index) { 1 },
			segmentWormholes.getOrElse(index) { -1 },
		)
	}

	fun segments(): List<Segment> = (0 until segmentCount).map { segment(it) }

	fun lastMarkMs(): Int = marks.last()

	fun marksSnapshot(): List<Int> = marks.toList()
	fun buttonsSnapshot(): List<ButtonRef?> = segmentButtons.toList()
	fun patternsSnapshot(): List<Pattern?> = segmentPatterns.toList()
	fun loopsSnapshot(): List<Int> = segmentLoops.toList()
	fun wormholesSnapshot(): List<Int> = segmentWormholes.toList()

	// ---- Phase 1: live tap-marking ----------------------------------------

	/**
	 * Drops a mark at [atMs], creating a new segment from the previous mark to this one,
	 * tentatively linked to [button]. Live mark-while-playing passes button=null;
	 * mapping happens later (or on the fly via Place / hybrid). Defaults: loop=1,
	 * wormhole=-1 so behaviour matches a normal Unipad one-shot hit.
	 */
	fun recordMark(atMs: Int, button: ButtonRef?, loop: Int = 1, wormhole: Int = -1): RecordResult {
		checkNotSpliced()
		val prev = marks.last()
		require(atMs > prev) { "New mark ($atMs) must be after the previous mark ($prev)" }
		require(atMs <= trackDurationMs) { "New mark ($atMs) is past the end of the track ($trackDurationMs)" }

		val gap = atMs - prev
		if (gap > MAX_SEGMENT_MS) {
			return RecordResult.ExceedsCap(gap, MAX_SEGMENT_MS)
		}
		return commitMark(atMs, button, loop, wormhole)
	}

	fun resolveCapAutoSplit(button: ButtonRef?, loop: Int = 1, wormhole: Int = -1): RecordResult.Committed {
		checkNotSpliced()
		val autoMs = (marks.last() + MAX_SEGMENT_MS).coerceAtMost(trackDurationMs)
		return commitMark(autoMs, button, loop, wormhole)
	}

	fun resolveCapPlaceAnyway(atMs: Int, button: ButtonRef?, loop: Int = 1, wormhole: Int = -1): RecordResult.Committed {
		checkNotSpliced()
		return commitMark(atMs, button, loop, wormhole)
	}

	private fun commitMark(atMs: Int, button: ButtonRef?, loop: Int, wormhole: Int): RecordResult.Committed {
		pushUndoSnapshot()
		marks.add(atMs)
		segmentButtons.add(button)
		segmentPatterns.add(null)
		segmentLoops.add(loop)
		segmentWormholes.add(wormhole)
		return RecordResult.Committed(segmentCount - 1, atMs - marks[marks.size - 2])
	}

	// ---- Phase 2: ripple editing -------------------------------------------

	sealed class RippleResult {
		object Success : RippleResult()
		data class Rejected(val reason: String) : RippleResult()
	}

	fun rippleMoveMark(index: Int, newPositionMs: Int): RippleResult {
		checkNotSpliced()
		require(index in 1 until marks.size) { "Mark index $index is out of range" }

		val lowerBound = marks[index - 1]
		if (newPositionMs <= lowerBound) {
			return RippleResult.Rejected("Can't move mark before the previous mark ($lowerBound ms)")
		}

		val delta = newPositionMs - marks[index]
		val newLastMark = marks.last() + delta
		if (newLastMark > trackDurationMs) {
			return RippleResult.Rejected("Ripple would push the final mark past the end of the track")
		}

		pushUndoSnapshot()
		for (i in index until marks.size) {
			marks[i] = marks[i] + delta
		}
		return RippleResult.Success
	}

	fun reassignButton(segmentIndex: Int, button: ButtonRef?) {
		checkNotSpliced()
		require(segmentIndex in segmentButtons.indices) { "Segment index $segmentIndex is out of range" }
		pushUndoSnapshot()
		segmentButtons[segmentIndex] = button
	}

	fun assignPattern(segmentIndex: Int, pattern: Pattern?) {
		checkNotSpliced()
		require(segmentIndex in segmentPatterns.indices) { "Segment index $segmentIndex is out of range" }
		pushUndoSnapshot()
		segmentPatterns[segmentIndex] = pattern
	}

	fun setLoop(segmentIndex: Int, loop: Int) {
		checkNotSpliced()
		require(segmentIndex in segmentLoops.indices) { "Segment index $segmentIndex is out of range" }
		pushUndoSnapshot()
		segmentLoops[segmentIndex] = loop
	}

	fun setWormhole(segmentIndex: Int, wormhole: Int) {
		checkNotSpliced()
		require(segmentIndex in segmentWormholes.indices) { "Segment index $segmentIndex is out of range" }
		pushUndoSnapshot()
		segmentWormholes[segmentIndex] = wormhole
	}

	// ---- Undo / redo -------------------------------------------------------

	private fun pushUndoSnapshot() {
		undoStack.addLast(Snapshot(
			marks.toList(), segmentButtons.toList(), segmentPatterns.toList(),
			segmentLoops.toList(), segmentWormholes.toList(),
		))
		redoStack.clear()
	}

	fun canUndo(): Boolean = undoStack.isNotEmpty()
	fun canRedo(): Boolean = redoStack.isNotEmpty()

	fun undo() {
		checkNotSpliced()
		val snap = undoStack.removeLastOrNull() ?: return
		redoStack.addLast(Snapshot(
			marks.toList(), segmentButtons.toList(), segmentPatterns.toList(),
			segmentLoops.toList(), segmentWormholes.toList(),
		))
		restoreSnapshot(snap)
	}

	fun redo() {
		checkNotSpliced()
		val snap = redoStack.removeLastOrNull() ?: return
		undoStack.addLast(Snapshot(
			marks.toList(), segmentButtons.toList(), segmentPatterns.toList(),
			segmentLoops.toList(), segmentWormholes.toList(),
		))
		restoreSnapshot(snap)
	}

	private fun restoreSnapshot(snap: Snapshot) {
		marks.clear(); marks.addAll(snap.marks)
		segmentButtons.clear(); segmentButtons.addAll(snap.buttons)
		segmentPatterns.clear(); segmentPatterns.addAll(snap.patterns)
		segmentLoops.clear(); segmentLoops.addAll(snap.loops)
		segmentWormholes.clear(); segmentWormholes.addAll(snap.wormholes)
	}

	// ---- Phase 3: splice ----------------------------------------------------

	sealed class SpliceResult {
		data class Success(val clips: List<CommittedClip>) : SpliceResult()
		data class Blocked(val overCapSegmentIndices: List<Int>) : SpliceResult()
	}

	fun splice(): SpliceResult {
		checkNotSpliced()
		val overCap = segments().withIndex().filter { it.value.durationMs > MAX_SEGMENT_MS }.map { it.index }
		if (overCap.isNotEmpty()) {
			return SpliceResult.Blocked(overCap)
		}
		isSpliced = true
		val clips = segments().mapIndexed { i, seg ->
			CommittedClip(
				index = i + 1,
				startMs = seg.startMs,
				endMs = seg.endMs,
				button = seg.button,
				lightPattern = seg.lightPattern,
				loop = seg.loop,
				wormhole = seg.wormhole,
			)
		}
		return SpliceResult.Success(clips)
	}

	private fun checkNotSpliced() {
		check(!isSpliced) { "Session already spliced -- marks are locked" }
	}
}
