"""Insert a declared silent interval in generated test audio; video and audio timeline continue."""
import argparse, hashlib, json, shutil
from pathlib import Path
import av
import numpy as np
p=argparse.ArgumentParser(description=__doc__)
p.add_argument("source",type=Path);p.add_argument("destination",type=Path)
p.add_argument("--start",type=float,required=True);p.add_argument("--end",type=float,required=True)
a=p.parse_args()
if Path("D:/tmp/dd").resolve() not in a.destination.resolve().parents: p.error("Destination must be inside D:/tmp/dd")
recipe=json.loads((a.source/"recipe.json").read_text(encoding="utf-8-sig"))
if not 0<=a.start<a.end<recipe["durationSeconds"]: p.error("Invalid silence interval")
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
for name,key in (("sync-video.mp4","videoSha256"),("sync-audio.m4a","audioSha256")):
    if sha(a.source/name)!=recipe[key]: p.error("Source fixture hash mismatch")
a.destination.mkdir(parents=True,exist_ok=False)
shutil.copyfile(a.source/"sync-video.mp4",a.destination/"sync-video.mp4")
changed=0
with av.open(str(a.source/"sync-audio.m4a")) as source, av.open(str(a.destination/"sync-audio.m4a"),"w",format="mp4",
        options={"movflags":"delay_moov+default_base_moof+global_sidx","frag_duration":"10000000"}) as target:
    audio=target.add_stream("aac",rate=recipe["sampleRate"]);audio.layout="stereo";audio.bit_rate=128000
    for frame in source.decode(audio=0):
        samples=frame.to_ndarray()
        times=float(frame.pts*frame.time_base)+np.arange(frame.samples)/frame.sample_rate
        mask=(times>=a.start)&(times<a.end)
        changed+=int(mask.sum());samples[:,mask]=0
        replacement=av.AudioFrame.from_ndarray(samples,format=frame.format.name,layout=frame.layout.name)
        replacement.sample_rate=frame.sample_rate;replacement.pts=frame.pts;replacement.time_base=frame.time_base
        for packet in audio.encode(replacement): target.mux(packet)
    for packet in audio.encode(): target.mux(packet)
for key in ("encodedPacketsAndTimelineIdentical","packetIdentity","unfragmentedAudioSha256"):
    recipe.pop(key,None)
recipe.update(generator="silence-sync-fixture.py",pyavVersion=av.__version__,sourceAudioSha256=recipe["audioSha256"],
              silenceStartSeconds=a.start,silenceEndSeconds=a.end,silentSampleFrames=changed,audioSha256=sha(a.destination/"sync-audio.m4a"))
(a.destination/"recipe.json").write_text(json.dumps(recipe,indent=2)+"\n",encoding="utf-8")
print(json.dumps(recipe,indent=2))
