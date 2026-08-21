"""Validates WAV header math + ms-to-sample-index slicing before writing the real Kotlin."""
import struct, wave, io

def write_wav_header(num_channels, sample_rate, bits_per_sample, num_frames):
    byte_rate = sample_rate * num_channels * bits_per_sample // 8
    block_align = num_channels * bits_per_sample // 8
    data_size = num_frames * block_align
    header = b"RIFF"
    header += struct.pack("<I", 36 + data_size)
    header += b"WAVE"
    header += b"fmt "
    header += struct.pack("<I", 16)               # fmt chunk size
    header += struct.pack("<H", 1)                 # PCM format
    header += struct.pack("<H", num_channels)
    header += struct.pack("<I", sample_rate)
    header += struct.pack("<I", byte_rate)
    header += struct.pack("<H", block_align)
    header += struct.pack("<H", bits_per_sample)
    header += b"data"
    header += struct.pack("<I", data_size)
    return header

def ms_to_sample_index(ms, sample_rate):
    return round(ms / 1000.0 * sample_rate)

# --- Test: slice a synthetic PCM buffer and confirm the WAV round-trips correctly ---
sample_rate = 44100
channels = 2
bits = 16
total_ms = 12000
total_frames = ms_to_sample_index(total_ms, sample_rate)

# synthetic PCM: frame index encoded into the sample value so we can verify exact slicing
pcm = bytearray()
for frame in range(total_frames):
    for ch in range(channels):
        val = (frame % 32000)  # arbitrary but frame-derived
        pcm += struct.pack("<h", val)

def slice_clip(pcm_bytes, start_ms, end_ms, sample_rate, channels, bits):
    bytes_per_frame = channels * bits // 8
    start_frame = ms_to_sample_index(start_ms, sample_rate)
    end_frame = ms_to_sample_index(end_ms, sample_rate)
    start_byte = start_frame * bytes_per_frame
    end_byte = end_frame * bytes_per_frame
    return pcm_bytes[start_byte:end_byte], end_frame - start_frame

# Clip: 3000ms -> 8500ms (5500ms, under the 8s cap)
clip_pcm, n_frames = slice_clip(pcm, 3000, 8500, sample_rate, channels, bits)
header = write_wav_header(channels, sample_rate, bits, n_frames)
wav_bytes = header + clip_pcm

# Round-trip: parse it back with Python's own `wave` module (independent implementation)
w = wave.open(io.BytesIO(wav_bytes), 'rb')
print("Parsed back -> channels:", w.getnchannels(), "rate:", w.getframerate(),
      "sampwidth:", w.getsampwidth(), "nframes:", w.getnframes())
assert w.getnchannels() == channels
assert w.getframerate() == sample_rate
assert w.getsampwidth() == 2
expected_frames = ms_to_sample_index(8500, sample_rate) - ms_to_sample_index(3000, sample_rate)
assert w.getnframes() == expected_frames, f"{w.getnframes()} != {expected_frames}"

# Verify actual sample content matches what we expect at the clip boundary (first frame of the clip)
raw = w.readframes(1)
first_sample_val = struct.unpack("<h", raw[0:2])[0]
expected_start_frame_index = ms_to_sample_index(3000, sample_rate)
expected_val = expected_start_frame_index % 32000
print("First sample of clip:", first_sample_val, "expected:", expected_val)
assert first_sample_val == expected_val

# Duration sanity check: does the clip's real duration (from frame count) match what MarkingSession thinks?
actual_duration_ms = w.getnframes() / sample_rate * 1000
print(f"Clip nominal duration: {8500-3000}ms, actual from real PCM: {actual_duration_ms:.2f}ms")

print("\nWAV HEADER + SLICING LOGIC VALIDATED")
