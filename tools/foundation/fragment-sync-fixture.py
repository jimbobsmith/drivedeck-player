"""Remux a generated fixture into fragmented MP4 audio, retaining AAC packets and the video."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import av

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source", type=Path)
parser.add_argument("destination", type=Path)
parser.add_argument("--fragment-ms", type=int, default=10000)
parser.add_argument("--video", action="store_true", help="Also fragment the master video, without changing its packets")
args = parser.parse_args()
if Path("D:/tmp/dd").resolve() not in args.destination.resolve().parents:
    parser.error("Fixture destination must be inside D:/tmp/dd")
if not 100 <= args.fragment_ms <= 30000:
    parser.error("Fragment duration must be 100..30000ms")
def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024*1024), b""):
            digest.update(block)
    return digest.hexdigest()
def packets(path, kind="audio"):
    payload = hashlib.sha256(); timeline = hashlib.sha256(); count = 0; first = last = None
    with av.open(str(path)) as container:
        for packet in container.demux(**{kind:0}):
            if packet.dts is None:
                continue
            payload.update(bytes(packet));timeline.update(str((packet.pts,packet.dts,packet.duration,str(packet.time_base))).encode())
            value = [packet.pts,packet.dts,packet.duration,str(packet.time_base)]
            if first is None:first=value
            last=value;count+=1
    return {"payloadSha256":payload.hexdigest(),"timelineSha256":timeline.hexdigest(),"packetCount":count,"first":first,"last":last}
recipe = json.loads((args.source/"recipe.json").read_text(encoding="utf-8-sig"))
for name,key in (("sync-video.mp4","videoSha256"),("sync-audio.m4a","audioSha256")):
    if sha(args.source/name) != recipe[key]:parser.error("Source fixture hash mismatch")
args.destination.mkdir(parents=True,exist_ok=False)
options={"movflags":"delay_moov+default_base_moof+global_sidx","frag_duration":str(args.fragment_ms*1000)}
if args.video:
    with av.open(str(args.source/"sync-video.mp4")) as source, av.open(str(args.destination/"sync-video.mp4"),"w",format="mp4",options=options) as target:
        video=target.add_stream_from_template(source.streams.video[0])
        for packet in source.demux(video=0):
            if packet.dts is not None:
                packet.stream=video;target.mux(packet)
    video_identity=packets(args.source/"sync-video.mp4","video")
    if video_identity != packets(args.destination/"sync-video.mp4","video"):
        raise ValueError("RemuxChangedVideoPacketsOrTimeline")
    recipe.update(unfragmentedVideoSha256=recipe["videoSha256"],videoPacketIdentity=video_identity)
else:
    shutil.copyfile(args.source/"sync-video.mp4",args.destination/"sync-video.mp4")
with av.open(str(args.source/"sync-audio.m4a")) as source, av.open(str(args.destination/"sync-audio.m4a"),"w",format="mp4",options=options) as target:
    audio=target.add_stream_from_template(source.streams.audio[0])
    for packet in source.demux(audio=0):
        if packet.dts is not None:
            packet.stream=audio;target.mux(packet)
original=packets(args.source/"sync-audio.m4a");fragmented=packets(args.destination/"sync-audio.m4a")
if original != fragmented:raise ValueError("RemuxChangedAACPacketsOrTimeline")
recipe.update(unfragmentedAudioSha256=recipe["audioSha256"],audioSha256=sha(args.destination/"sync-audio.m4a"),
              generator="fragment-sync-fixture.py",pyavVersion=av.__version__,fragmentDurationMs=args.fragment_ms,
              videoSha256=sha(args.destination/"sync-video.mp4"),fragmentVideo=args.video,
              muxOptions=options,packetIdentity=original,encodedPacketsAndTimelineIdentical=True)
(args.destination/"recipe.json").write_text(json.dumps(recipe,indent=2)+"\n",encoding="utf-8")
print(json.dumps(recipe,indent=2))
