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
parser.add_argument("--duration-seconds", type=int, default=45)
parser.add_argument("--pulse-interval-seconds", type=int, default=1)
parser.add_argument("--fps", type=int, choices=(30, 60), default=30)
parser.add_argument("--width", type=int, choices=(320, 1280, 1920), default=320)
args = parser.parse_args()
if not 20 <= args.duration_seconds <= 330 or not 1 <= args.pulse_interval_seconds <= 6:
    parser.error("Bounded fixture duration 20..330s and pulse interval 1..6s required")
if (args.duration_seconds - 1) // args.pulse_interval_seconds > 63:
    parser.error("Six-bit pulse IDs must remain unique (maximum 63)")
if not 0 <= args.audio_delay_ms <= 400: parser.error("Audio delay must be 0..400ms")
fps, interval = args.fps, args.pulse_interval_seconds
height = args.width * 9 // 16
samples_per_frame = 48000 // fps
args.output_directory.mkdir(parents=True, exist_ok=True)
video_path = args.output_directory / "sync-video.mp4"
audio_path = args.output_directory / "sync-audio.m4a"
with av.open(str(video_path), "w") as vc, av.open(str(audio_path), "w") as ac:
    video = vc.add_stream("libx264", rate=fps)
    video.width, video.height, video.pix_fmt = args.width, height, "yuv420p"
    video.options = {"preset": "ultrafast", "crf": "18"}
    audio = ac.add_stream("aac", rate=48000)
    audio.layout = "stereo"
    for i in range(args.duration_seconds * fps):
        pixels = np.full((180, 320, 3), 20, dtype=np.uint8)
        if i >= interval * fps and i % (interval * fps) < fps // 15:
            pixels[30:150, 40:280] = 255
        pixels[160:175, :int((i % fps) / fps * 320), 2] = 180
        for bit in range(6):
            if (i // (interval * fps)) & (1 << bit):
                pixels[5:20, 10 + bit * 40:35 + bit * 40, 1] = 180
        frame = av.VideoFrame.from_ndarray(pixels, format="rgb24")
        frame = frame.reformat(width=args.width, height=height, format="yuv420p")
        frame.pts, frame.time_base = i, Fraction(1, fps)
        for packet in video.encode(frame): vc.mux(packet)
        n = np.arange(samples_per_frame) + i * samples_per_frame
        shifted = n - args.audio_delay_ms * 48
        pulse = (shifted >= interval * 48000) & ((shifted % (interval * 48000)) < 960)
        frequency = 1000 + (shifted // (interval * 48000)) * 100
        # A quiet continuous carrier keeps the render endpoint active between calibration pulses.
        samples = (np.sin(2 * np.pi * frequency * shifted / 48000) * 0.15 * pulse
                   + .005 * np.sin(2 * np.pi * 300 * n / 48000)).astype(np.float32)
        frame = av.AudioFrame.from_ndarray(np.stack([samples, samples]), format="fltp", layout="stereo")
        frame.sample_rate, frame.pts, frame.time_base = 48000, i * samples_per_frame, Fraction(1, 48000)
        for packet in audio.encode(frame): ac.mux(packet)
    for packet in video.encode(): vc.mux(packet)
    for packet in audio.encode(): ac.mux(packet)
recipe = {"durationSeconds": args.duration_seconds, "fps": fps, "width": args.width, "height": height, "sampleRate": 48000,
    "pulsePeriodMs": interval * 1000, "flashDurationMs": 2000 / 30, "toneDurationMs": 20,
    "continuousCarrierHz": 300, "continuousCarrierAmplitude": .005, "toneBaseHz": 1000, "toneStepPerIdHz": 100,
    "maximumPulseId": (args.duration_seconds - 1) // interval, "audioDelayMs": args.audio_delay_ms,
    "videoSha256": hashlib.sha256(video_path.read_bytes()).hexdigest(),
    "audioSha256": hashlib.sha256(audio_path.read_bytes()).hexdigest()}
(args.output_directory / "recipe.json").write_text(json.dumps(recipe, indent=2) + "\n")
print(json.dumps(recipe, indent=2))
