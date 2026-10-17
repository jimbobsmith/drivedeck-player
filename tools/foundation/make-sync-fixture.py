"""Generate known simultaneous flashes/tones as independent H264/AAC tracks. No third-party media."""
import argparse
import hashlib
import json
from fractions import Fraction
from pathlib import Path
import av
import numpy as np

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output-directory", type=Path, required=True)
parser.add_argument("--audio-delay-ms", type=int, default=0)
args = parser.parse_args()
args.output_directory.mkdir(parents=True, exist_ok=True)
video_path = args.output_directory / "sync-video.mp4"
audio_path = args.output_directory / "sync-audio.m4a"
with av.open(str(video_path), "w") as vc, av.open(str(audio_path), "w") as ac:
    video = vc.add_stream("libx264", rate=30)
    video.width, video.height, video.pix_fmt = 320, 180, "yuv420p"
    audio = ac.add_stream("aac", rate=48000)
    audio.layout = "stereo"
    for i in range(45 * 30):
        pixels = np.full((180, 320, 3), 20, dtype=np.uint8)
        if i >= 30 and i % 30 < 2:
            pixels[30:150, 40:280] = 255
        pixels[160:175, :int((i % 30) / 30 * 320), 2] = 180
        for bit in range(6):
            if (i // 30) & (1 << bit):
                pixels[5:20, 10 + bit * 40:35 + bit * 40, 1] = 180
        frame = av.VideoFrame.from_ndarray(pixels, format="rgb24")
        frame.pts, frame.time_base = i, Fraction(1, 30)
        for packet in video.encode(frame): vc.mux(packet)
        n = np.arange(1600) + i * 1600
        shifted = n - args.audio_delay_ms * 48
        pulse = (shifted >= 48000) & ((shifted % 48000) < 960)
        frequency = 1000 + (shifted // 48000) * 100
        # A quiet continuous carrier keeps the render endpoint active between calibration pulses.
        samples = (np.sin(2 * np.pi * frequency * shifted / 48000) * 0.15 * pulse
                   + .005 * np.sin(2 * np.pi * 300 * n / 48000)).astype(np.float32)
        frame = av.AudioFrame.from_ndarray(np.stack([samples, samples]), format="fltp", layout="stereo")
        frame.sample_rate, frame.pts, frame.time_base = 48000, i * 1600, Fraction(1, 48000)
        for packet in audio.encode(frame): ac.mux(packet)
    for packet in video.encode(): vc.mux(packet)
    for packet in audio.encode(): ac.mux(packet)
print(json.dumps({"durationSeconds": 45, "fps": 30, "sampleRate": 48000,
    "pulsePeriodMs": 1000, "flashDurationMs": 2000 / 30, "toneDurationMs": 20,
    "continuousCarrierHz": 300, "continuousCarrierAmplitude": .005, "toneBaseHz": 1000, "toneStepPerSecondHz": 100, "audioDelayMs": args.audio_delay_ms,
    "videoSha256": hashlib.sha256(video_path.read_bytes()).hexdigest(),
    "audioSha256": hashlib.sha256(audio_path.read_bytes()).hexdigest()}, indent=2))
