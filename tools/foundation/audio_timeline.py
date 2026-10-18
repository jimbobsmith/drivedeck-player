"""Correlate opt-in native audio events with bounded high-frequency statistics snapshots.

Counters measure submission/publication, not endpoint consumption or source A/V alignment.
"""
import argparse
import csv
import json
from collections import Counter
from pathlib import Path


def analyze(rows, events, minimum_hold_ms=1000):
    rows = [{key: int(value) for key, value in row.items()} for row in rows]
    events = [{**row, 'epochMs': int(row['epochMs']), 'monotonicMs': int(row['monotonicMs']),
               'value': int(row['value'])} for row in events]
    plateaus = []
    start = 0

    def finish(end):
        if end <= start:
            return
        first, last = rows[start], rows[end]
        duration = last['monotonicMs'] - first['monotonicMs']
        if duration < minimum_hold_ms:
            return
        video_delta = last['decodedVideo'] - first['decodedVideo']
        picture_delta = last['pictures'] - first['pictures']
        decoded_delta = last['decodedAudio'] - first['decodedAudio']
        plateaus.append(dict(startEpochMs=first['epochMs'], endEpochMs=last['epochMs'],
            startMonotonicMs=first['monotonicMs'], endMonotonicMs=last['monotonicMs'],
            observedHeldMs=duration, snapshots=end-start+1,
            audioBuffers=first['audioBuffers'], decodedAudioDelta=decoded_delta,
            audioLostDelta=last['audioLost']-first['audioLost'],
            clockDeltaMs=last['clockMs']-first['clockMs'], decodedVideoDelta=video_delta,
            pictureDelta=picture_delta,
            entireMeasuredSnapshotHeld=all(rows[i][key] == first[key]
                for i in range(start+1,end+1)
                for key in ('decodedAudio','audioLost','decodedVideo','pictures','inputBytes','demuxBytes')),
            nearbyNativeEvents=[dict(epochMs=e['epochMs'], event=e['event'], value=e['value']) for e in events
                if first['monotonicMs']-250 <= e['monotonicMs'] <= last['monotonicMs']+250]))

    gaps = []
    for i in range(1,len(rows)):
        gap = rows[i]['monotonicMs']-rows[i-1]['monotonicMs']
        if gap <= 0:
            raise ValueError('NonIncreasingTimeline')
        if gap > 500 or rows[i]['audioBuffers'] != rows[i-1]['audioBuffers']:
            finish(i-1)
            if gap > 500:
                gaps.append(dict(beforeMonotonicMs=rows[i-1]['monotonicMs'], afterMonotonicMs=rows[i]['monotonicMs'], gapMs=gap))
            start = i
    if rows:
        finish(len(rows)-1)
    return dict(schemaVersion=1, sampleCount=len(rows), nativeEventCount=len(events),
                nativeEventCounts=dict(Counter(e['event'] for e in events)), minimumHoldMs=minimum_hold_ms,
                samplingGaps=gaps, audioCounterPlateaus=plateaus,
                outputContinuityProven=False, sourceAvSyncProven=False, rootCauseProven=False)


def read_csv(path):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        return list(csv.DictReader(stream))


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    args=parser.parse_args()
    print(json.dumps(analyze(read_csv(args.directory/'audio-timeline.csv'),
                             read_csv(args.directory/'audio-native-events.csv')),indent=2))
