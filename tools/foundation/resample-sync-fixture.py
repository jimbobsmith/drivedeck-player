"""Resample only a generated sync fixture's audio for a controlled counter-cadence comparison.
Requires PyAV 19 in the disposable test runtime. No provider media or credentials.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import av

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source", type=Path)
parser.add_argument("destination", type=Path)
parser.add_argument("--rate", type=int, choices=(44100, 48000), default=44100)
args = parser.parse_args()
if Path("D:/tmp/dd").resolve() not in args.destination.resolve().parents:
    parser.error("Fixture destination must be inside D:/tmp/dd")
recipe = json.loads((args.source / "recipe.json").read_text(encoding="utf-8-sig"))
def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024*1024), b""):
            digest.update(block)
    return digest.hexdigest()
for name, key in (("sync-video.mp4", "videoSha256"), ("sync-audio.m4a", "audioSha256")):
    if sha(args.source / name) != recipe[key]:
        parser.error("Source fixture hash mismatch")
args.destination.mkdir(parents=True, exist_ok=False)
shutil.copyfile(args.source / "sync-video.mp4", args.destination / "sync-video.mp4")
with av.open(str(args.source / "sync-audio.m4a")) as source, av.open(str(args.destination / "sync-audio.m4a"), "w") as target:
    audio = target.add_stream("aac", rate=args.rate)
    audio.layout = "stereo"
    audio.bit_rate = 128000
    resampler = av.AudioResampler(format="fltp", layout="stereo", rate=args.rate)
    for frame in source.decode(audio=0):
        for converted in resampler.resample(frame):
            for packet in audio.encode(converted):
                target.mux(packet)
    for converted in resampler.resample(None):
        for packet in audio.encode(converted):
            target.mux(packet)
    for packet in audio.encode():
        target.mux(packet)
recipe.update(sourceAudioSha256=recipe["audioSha256"], sourceSampleRate=recipe["sampleRate"], sampleRate=args.rate,
              generator="resample-sync-fixture.py", pyavVersion=av.__version__, audioEncoderBitrate=128000,
              videoSha256=sha(args.destination / "sync-video.mp4"), audioSha256=sha(args.destination / "sync-audio.m4a"))
(args.destination / "recipe.json").write_text(json.dumps(recipe, indent=2)+"\n", encoding="utf-8")
print(json.dumps(recipe, indent=2))
