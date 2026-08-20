package com.bobbypfreely.lpbf.marking

/**
 * Manages the full lifecycle of cutting one source track into ordered Clips:
 *   1. Live tap-marking (recordMark) while playing the track
 *   2. Ripple-edit fine-tuning (rippleMoveMark) and re-mapping (reassignButton)
 *   3. Locking it all in (splice)
 *
 * Marks are stored as plain millisecond positions, index 0 is always a fixed
 * mark at 0 (the track's start) and is never itself movable or removable.
 * Segment N is the gap between marks[N] and marks[N+1]; segmentButtons is a
 * parallel list so a segment's button assignment is independent of the marks
 * that bound it.
 *
 * Nothing here touches actual audio -- this only tracks timestamps and button
 * assignments. Real PCM slicing happens downstream once splice() returns.
 */
class MarkingSession(private val trackDurationMs: Int) {

	companion object {
		const val MAX_SEGMENT_MS = 8000
	}

	sealed class RecordResult {
		data class Committed(val segmentIndex: Int, val durationMs: Int) : RecordResult()
		data class ExceedsCap(val gapMs: Int, val maxMs: Int) : RecordResult()
	}

	private val marks = mutableListOf(0)
	private val segmentButtons = mutableListOf<ButtonRef?>()

	private data class Snapshot(val marks: List<Int>, val buttons: List<ButtonRef?>)
	private val undoStack = ArrayDeque<Snapshot>()
	private val redoStack = ArrayDeque<Snapshot>()

	var isSpliced = false
		private set

	// ---- Read-only views -------------------------------------------------

	val segmentCount: Int get() = segmentButtons.size

	fun segment(index: Int): Segment {
		return Segment(marks[index], marks[index + 1], segmentButtons[index])
	}

	fun segments(): List<Segment> = (0 until segmentCount).map { segment(it) }

	fun lastMarkMs(): Int = marks.last()

	// ---- Phase 1: live tap-marking ----------------------------------------

	/**
	 * Attempts to drop a mark at [atMs], creating a new segment from the previous
	 * mark to this one, tentatively linked to [button]. If the resulting segment
	 * would exceed MAX_SEGMENT_MS, nothing is committed -- call
	 * [resolveCapAutoSplit] or [resolveCapPlaceAnyway] based on the user's choice.
	 */
	fun recordMark(atMs: Int, button: ButtonRef?): RecordResult {
		checkNotSpliced()
		val prev = marks.last()
		require(atMs > prev) { "New mark ($atMs) must be after the previous mark ($prev)" }
		require(atMs <= trackDurationMs) { "New mark ($atMs) is past the end of the track ($trackDurationMs)" }

		val gap = atMs - prev
		if (gap > MAX_SEGMENT_MS) {
			return RecordResult.ExceedsCap(gap, MAX_SEGMENT_MS)
		}
		return commitMark(atMs, button)
	}

	/** User chose "auto-split at 8s": commits a mark exactly MAX_SEGMENT_MS after the previous one. */
	fun resolveCapAutoSplit(button: ButtonRef?): RecordResult.Committed {
		checkNotSpliced()
		val autoMs = (marks.last() + MAX_SEGMENT_MS).coerceAtMost(trackDurationMs)
		return commitMark(autoMs, button)
	}

	/** User chose "place it anyway": commits the original over-cap mark as-is. Must be fixed before splice(). */
	fun resolveCapPlaceAnyway(atMs: Int, button: ButtonRef?): RecordResult.Committed {
		checkNotSpliced()
		return commitMark(atMs, button)
	}

	private fun commitMark(atMs: Int, button: ButtonRef?): RecordResult.Committed {
		pushUndoSnapshot()
		marks.add(atMs)
		segmentButtons.add(button)
		return RecordResult.Committed(segmentCount - 1, atMs - marks[marks.size - 2])
	}

	// ---- Phase 2: ripple editing -------------------------------------------

	sealed class RippleResult {
		object Success : RippleResult()
		data class Rejected(val reason: String) : RippleResult()
	}

	/**
	 * Moves marks[index] to [newPositionMs]. Every mark AFTER index shifts by the
	 * same delta, all the way through to the last mark -- marks before index never
	 * move. This is what preserves the relative timing captured in Phase 1 while
	 * letting you nudge one boundary precisely.
	 *
	 * Note: because every downstream mark shifts by the identical delta, only
	 * ONE segment's duration actually changes -- the segment that ENDS at the
	 * moved mark. Every segment from that point on (including the very next
	 * one) keeps its exact original duration, since both of its boundaries
	 * shift by the same amount. This is what preserves your Phase 1 timing.
	 *
	 * index must be in 1 until marks.size (index 0, the track start, is fixed).
	 */
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

	/** Reassigns which button a provisional segment is linked to. Freely editable pre-splice. */
	fun reassignButton(segmentIndex: Int, button: ButtonRef?) {
		checkNotSpliced()
		require(segmentIndex in segmentButtons.indices) { "Segment index $segmentIndex is out of range" }
		pushUndoSnapshot()
		segmentButtons[segmentIndex] = button
	}

	// ---- Undo / redo (scoped to this session only) -------------------------

	private fun pushUndoSnapshot() {
		undoStack.addLast(Snapshot(marks.toList(), segmentButtons.toList()))
		redoStack.clear()
	}

	fun canUndo(): Boolean = undoStack.isNotEmpty()
	fun canRedo(): Boolean = redoStack.isNotEmpty()

	fun undo() {
		checkNotSpliced()
		val snap = undoStack.removeLastOrNull() ?: return
		redoStack.addLast(Snapshot(marks.toList(), segmentButtons.toList()))
		restoreSnapshot(snap)
	}

	fun redo() {
		checkNotSpliced()
		val snap = redoStack.removeLastOrNull() ?: return
		undoStack.addLast(Snapshot(marks.toList(), segmentButtons.toList()))
		restoreSnapshot(snap)
	}

	private fun restoreSnapshot(snap: Snapshot) {
		marks.clear(); marks.addAll(snap.marks)
		segmentButtons.clear(); segmentButtons.addAll(snap.buttons)
	}

	// ---- Phase 3: splice ----------------------------------------------------

	sealed class SpliceResult {
		data class Success(val clips: List<CommittedClip>) : SpliceResult()
		data class Blocked(val overCapSegmentIndices: List<Int>) : SpliceResult()
	}

	/** Locks the session and returns the final ordered clip list, or reports which segments still need fixing. */
	fun splice(): SpliceResult {
		checkNotSpliced()
		val overCap = segments().withIndex().filter { it.value.durationMs > MAX_SEGMENT_MS }.map { it.index }
		if (overCap.isNotEmpty()) {
			return SpliceResult.Blocked(overCap)
		}
		isSpliced = true
		val clips = segments().mapIndexed { i, seg ->
			CommittedClip(index = i + 1, startMs = seg.startMs, endMs = seg.endMs, button = seg.button)
		}
		return SpliceResult.Success(clips)
	}

	private fun checkNotSpliced() {
		check(!isSpliced) { "Session already spliced -- marks are locked" }
	}
}
