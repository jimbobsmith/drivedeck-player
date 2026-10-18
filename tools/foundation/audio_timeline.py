"""Correlate opt-in native audio events with bounded high-frequency statistics snapshots.

Counters measure submission/publication, not endpoint consumption or source A/V alignment.
"""
import argparse
import csv
import json
from collections import Counter
from pathlib import Path
from statistics import median


def counter_cadence(rows):
    changes, intervals = [], []
    last_change = None
    resets = 0
    for before, after in zip(rows, rows[1:]):
        gap = after['monotonicMs']-before['monotonicMs']
        delta = after['audioBuffers']-before['audioBuffers']
        if gap > 500 or delta < 0:
            last_change = None
            resets += delta < 0
        if 0 < gap <= 500 and delta > 0:
            changes.append(delta)
            if last_change is not None:
                intervals.append(after['monotonicMs']-last_change)
            last_change = after['monotonicMs']
    return dict(positiveChangeCount=len(changes), positiveBufferDeltaHistogram=dict(Counter(changes)),
                counterResets=resets, intervalsBetweenPositiveUpdatesMs=intervals,
                medianPositiveUpdateIntervalMs=median(intervals) if intervals else None,
                maximumPositiveUpdateIntervalMs=max(intervals) if intervals else None)


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
                samplingGaps=gaps, audioCounterPlateaus=plateaus, counterCadence=counter_cadence(rows),
                outputContinuityProven=False, sourceAvSyncProven=False, rootCauseProven=False)


def phase_segments(rows, events, phases):
    allowed={'start','pause','resume','seek','steady_after_seek','network_down','network_up','network_recovered'}
    phases=[(int(stamp),name) for stamp,name in phases]
    if any(name not in allowed for _,name in phases):
        raise ValueError('UnknownPresentationPhase')
    if any(a[0]>=b[0] for a,b in zip(phases,phases[1:])):
        raise ValueError('NonIncreasingPhaseTimes')
    segments=[]
    for i,(start,name) in enumerate(phases):
        end=phases[i+1][0] if i+1<len(phases) else float('inf')
        selected=[row for row in rows if start<=int(row['epochMs'])<end]
        if selected:
            native=[event for event in events if start<=int(event['epochMs'])<end]
            segments.append(dict(phase=name,startEpochMs=start,
                                 analysis=analyze(selected,native)))
    return segments


def read_csv(path):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        return list(csv.DictReader(stream))


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    args=parser.parse_args()
    rows=read_csv(args.directory/'audio-timeline.csv')
    events=read_csv(args.directory/'audio-native-events.csv')
    result=analyze(rows,events)
    phase_file=args.directory/'phases.csv'
    if phase_file.exists():
        with phase_file.open(encoding='utf-8-sig',newline='') as stream:
            result['phaseSegments']=phase_segments(rows,events,list(csv.reader(stream)))
    print(json.dumps(result,indent=2))
