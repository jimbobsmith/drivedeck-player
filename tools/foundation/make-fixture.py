import argparse, hashlib
from pathlib import Path
import av, numpy as np
parser=argparse.ArgumentParser(description='Generate a disposable 20s decoder test fixture')
parser.add_argument('--output', type=Path, required=True)
out=parser.parse_args().output
out.parent.mkdir(parents=True, exist_ok=True)
with av.open(str(out),'w') as container:
 video=container.add_stream('libx264', rate=30);video.width=320;video.height=180;video.pix_fmt='yuv420p'
 audio=container.add_stream('aac', rate=48000);audio.layout='stereo'
 for i in range(600):
  pixels=np.zeros((180,320,3),dtype=np.uint8);pixels[:,:,0]=(i*3)%256;pixels[:,:,1]=np.arange(320,dtype=np.uint8);pixels[:,:,2]=128
  frame=av.VideoFrame.from_ndarray(pixels,format='rgb24');frame.pts=i
  for packet in video.encode(frame):container.mux(packet)
  samples=(np.sin(2*np.pi*440*(np.arange(1600)+i*1600)/48000)*.1).astype(np.float32)
  aframe=av.AudioFrame.from_ndarray(np.stack([samples,samples]),format='fltp',layout='stereo');aframe.sample_rate=48000;aframe.pts=i*1600
  for packet in audio.encode(aframe):container.mux(packet)
 for packet in video.encode():container.mux(packet)
 for packet in audio.encode():container.mux(packet)
print('Synthetic 20s H264/AAC fixture:',hashlib.sha256(out.read_bytes()).hexdigest())
