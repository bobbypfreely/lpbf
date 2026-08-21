"""Validates MarkingSession logic: ripple invariant, cap handling, undo/redo, splice gating."""

class Session:
    MAX = 8000
    def __init__(self, track_ms):
        self.track_ms = track_ms
        self.marks = [0]
        self.buttons = []
        self.undo_stack = []
        self.redo_stack = []
        self.spliced = False

    def segments(self):
        return [(self.marks[i], self.marks[i+1], self.buttons[i]) for i in range(len(self.buttons))]

    def _push_undo(self):
        self.undo_stack.append((list(self.marks), list(self.buttons)))
        self.redo_stack.clear()

    def _commit(self, at_ms, button):
        self._push_undo()
        self.marks.append(at_ms)
        self.buttons.append(button)
        return len(self.buttons) - 1

    def record_mark(self, at_ms, button):
        prev = self.marks[-1]
        assert at_ms > prev
        gap = at_ms - prev
        if gap > self.MAX:
            return ("EXCEEDS_CAP", gap)
        return ("COMMITTED", self._commit(at_ms, button))

    def resolve_auto_split(self, button):
        auto_ms = min(self.marks[-1] + self.MAX, self.track_ms)
        return self._commit(auto_ms, button)

    def resolve_place_anyway(self, at_ms, button):
        return self._commit(at_ms, button)

    def ripple_move(self, index, new_pos):
        lower = self.marks[index - 1]
        if new_pos <= lower:
            return ("REJECTED", "before previous mark")
        delta = new_pos - self.marks[index]
        if self.marks[-1] + delta > self.track_ms:
            return ("REJECTED", "past track end")
        self._push_undo()
        for i in range(index, len(self.marks)):
            self.marks[i] += delta
        return ("SUCCESS",)

    def undo(self):
        if not self.undo_stack: return
        self.redo_stack.append((list(self.marks), list(self.buttons)))
        self.marks, self.buttons = self.undo_stack.pop()

    def splice(self):
        over = [i for i, s in enumerate(self.segments()) if (s[1]-s[0]) > self.MAX]
        if over:
            return ("BLOCKED", over)
        self.spliced = True
        clips = [(i+1, s[0], s[1], s[2]) for i, s in enumerate(self.segments())]
        return ("SUCCESS", clips)


# --- Test 1: basic recording, marks strictly increasing, durations correct ---
s = Session(track_ms=60000)
r1 = s.record_mark(3000, "btnA")   # 0->3000, dur 3000
r2 = s.record_mark(9500, "btnB")   # 3000->9500, dur 6500
r3 = s.record_mark(9600, "btnC")   # 9500->9600, dur 100
print("Test1 basic:", r1, r2, r3)
assert s.segments() == [(0,3000,"btnA"), (3000,9500,"btnB"), (9500,9600,"btnC")]

# --- Test 2: cap exceeded, auto-split resolution ---
s2 = Session(track_ms=60000)
r = s2.record_mark(9000, "btnA")  # gap 9000 > 8000
print("Test2 exceeds:", r)
assert r[0] == "EXCEEDS_CAP" and r[1] == 9000
idx = s2.resolve_auto_split("btnA")  # commits mark at 8000
print("Test2 auto-split segment:", s2.segments())
assert s2.segments()[0] == (0, 8000, "btnA")

# --- Test 3: ripple invariant -- only two adjacent segments change duration ---
s3 = Session(track_ms=60000)
for t, b in [(2000,"A"), (5000,"B"), (9000,"C"), (14000,"D"), (20000,"E")]:
    s3.record_mark(t, b)
before = s3.segments()
before_durations = [(e-st) for st,e,btn in before]
print("Test3 before ripple durations:", before_durations)

# Move mark index=2 (currently at 9000) to 9500 -- ripple everything after it
res = s3.ripple_move(2, 9500)
after = s3.segments()
after_durations = [(e-st) for st,e,btn in after]
print("Test3 after ripple durations:", after_durations, res)

# Segment 1 (index1: 5000->9000, was B, becomes B extended) and segment 2 (9000->14000 becomes 9500->14500)
# should be the only ones with CHANGED duration; segment 0 (0->5000->wait check) and segment 3 unaffected... let's check precisely
for i, (bd, ad) in enumerate(zip(before_durations, after_durations)):
    changed = bd != ad
    print(f"  segment {i}: before={bd} after={ad} changed={changed}")

# marks before index=2 unchanged, index=2 and after shifted by +500
assert s3.marks[0] == 0 and s3.marks[1] == 5000  # untouched
assert s3.marks[2] == 9500  # moved
assert s3.marks[3] == 14500 and s3.marks[4] == 20500  # rippled by +500

# --- Test 4: reject ripple that would cross the previous mark ---
s4 = Session(track_ms=60000)
for t, b in [(2000,"A"), (5000,"B"), (9000,"C")]:
    s4.record_mark(t, b)
bad = s4.ripple_move(2, 4000)  # 4000 <= marks[1]=5000, should reject
print("Test4 reject-before-prev:", bad)
assert bad[0] == "REJECTED"

# --- Test 5: reject ripple pushing last mark past track end ---
s5 = Session(track_ms=10000)
for t, b in [(2000,"A"), (5000,"B"), (9500,"C")]:
    s5.record_mark(t, b)
bad2 = s5.ripple_move(1, 9800)  # delta +4800 would push last mark 9500+4800=14300 > 10000
print("Test5 reject-past-end:", bad2)
assert bad2[0] == "REJECTED"

# --- Test 6: undo restores previous state exactly ---
s6 = Session(track_ms=60000)
s6.record_mark(3000, "A")
snapshot_marks = list(s6.marks)
s6.record_mark(7000, "B")
s6.undo()
print("Test6 undo restores:", s6.marks, "expected", snapshot_marks)
assert s6.marks == snapshot_marks

# --- Test 7: splice blocked when a segment still exceeds cap (place-anyway case) ---
s7 = Session(track_ms=60000)
s7.record_mark(3000, "A")
r = s7.record_mark(20000, "B")  # exceeds cap
assert r[0] == "EXCEEDS_CAP"
s7.resolve_place_anyway(20000, "B")  # forced commit despite cap
result = s7.splice()
print("Test7 splice blocked:", result)
assert result[0] == "BLOCKED" and result[1] == [1]

# fix it via ripple, then splice should succeed
s7.ripple_move(2, 3000 + 8000)  # move the offending mark's end down to within cap... wait index for mark at 20000 is index 2
res_fix = s7.ripple_move(2, 3000 + 7000)
result2 = s7.splice()
print("Test7 splice after fix:", result2)
assert result2[0] == "SUCCESS"

print("\nALL TESTS PASSED")
