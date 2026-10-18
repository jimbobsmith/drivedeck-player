"""Known synthetic pilot measurements from transient endpoint-loopback packets; no raw PCM is saved."""
import numpy as np
import math


class PilotPackets:
    def __init__(self, frequency, sample_rate=48000, limit=70000):
        if not math.isfinite(frequency) or not 0<frequency<sample_rate/2 or limit<=0:
            raise ValueError("InvalidPilotConfiguration")
        self.frequency=frequency
        self.sample_rate=sample_rate
        self.limit=limit
        self.rows=[]
        self.dropped=0
        self.basis={}

    def observe(self, epoch_ms, frames, samples, flags):
        if len(self.rows)>=self.limit:
            self.dropped+=1
            return
        amplitude=0.0
        if samples is not None and frames:
            if frames not in self.basis and len(self.basis)<32:
                self.basis[frames]=np.exp(-2j*np.pi*self.frequency*np.arange(frames)/self.sample_rate)
            basis=self.basis.get(frames)
            if basis is None:
                basis=np.exp(-2j*np.pi*self.frequency*np.arange(frames)/self.sample_rate)
            amplitude=float(2*abs(np.dot(samples.mean(axis=1),basis))/frames)
        self.rows.append(dict(startEpochMs=round(epoch_ms,3),durationMs=frames*1000/self.sample_rate,
                              frames=frames,pilotAmplitude=round(amplitude,8),flags=int(flags)))


def assess_interval(rows,start_ms,end_ms,threshold=.00002):
    if end_ms<=start_ms:
        raise ValueError('EmptyPilotInterval')
    selected=[r for r in rows if r['startEpochMs']<end_ms and r['startEpochMs']+r['durationMs']>start_ms]
    cursor=start_ms
    coverage=0.0
    maximum_gap=0.0
    for row in sorted(selected,key=lambda r:r['startEpochMs']):
        start=max(start_ms,row['startEpochMs'])
        end=min(end_ms,row['startEpochMs']+row['durationMs'])
        maximum_gap=max(maximum_gap,start-cursor)
        coverage+=max(0,end-max(cursor,start))
        cursor=max(cursor,end)
    maximum_gap=max(maximum_gap,end_ms-cursor)
    below=sum(not math.isfinite(r['pilotAmplitude']) or r['pilotAmplitude']<threshold for r in selected)
    flagged=sum(bool(r['flags'] & (1|2|4)) for r in selected)
    qualified=bool(selected) and below==0 and flagged==0 and maximum_gap<=1 and coverage>=(end_ms-start_ms)*.98
    return dict(startEpochMs=start_ms,endEpochMs=end_ms,packetCount=len(selected),
                coveredMs=round(coverage,3),maximumGapMs=round(maximum_gap,3),
                minimumPilotAmplitude=min((r['pilotAmplitude'] for r in selected),default=None),
                threshold=threshold,belowThresholdPackets=below,flaggedPackets=flagged,
                continuousPilotObserved=qualified,exclusiveSourceContinuityProven=False)


def assess_terminal(rows, phases, worker):
    """Last 30 seconds of observation, with a 100ms terminal boundary trim."""
    stamps={name:float(stamp) for stamp,name in phases}
    end=stamps["steady_after_seek"]+worker["observationWallMs"]-100
    start=end-30000
    if start<stamps.get("network_recovered",stamps["steady_after_seek"])+2000:
        raise ValueError("InsufficientTerminalPilotWindow")
    return assess_interval(rows,start,end)


def analyze_directory(directory):
    import csv,json
    from audio_timeline import read_csv,phase_segments
    rows=read_csv(directory/'audio-timeline.csv')
    events=read_csv(directory/'audio-native-events.csv')
    phases=list(csv.reader((directory/'phases.csv').open(encoding='utf-8-sig')))
    packets=json.loads((directory/'pilot-packets.json').read_text())
    report=json.loads((directory/'presentation-result.json').read_text())
    segments=phase_segments(rows,events,phases)
    assessments=[]
    for segment in segments:
        if segment['phase'] not in ('steady_after_seek','network_recovered'):
            continue
        for hold in segment['analysis']['audioCounterPlateaus']:
            measured=assess_interval(packets,hold['startEpochMs']+100,hold['endEpochMs']-100)
            assessments.append(dict(phase=segment['phase'],counterHold=hold,pilot=measured))
    return dict(schemaVersion=1,workerResult=report['worker']['result'],harnessResult=report['result'],
                pilotMeasurement=report.get('pilotMeasurement'),phaseAnalyses=segments,
                assessments=assessments,qualifiedHolds=sum(a['pilot']['continuousPilotObserved'] for a in assessments),
                mixedEndpoint=True,providerContinuityProven=False,nativeWaitStackCaptured=False)


if __name__=='__main__':
    import argparse,json
    from pathlib import Path
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    print(json.dumps(analyze_directory(parser.parse_args().directory),indent=2))
