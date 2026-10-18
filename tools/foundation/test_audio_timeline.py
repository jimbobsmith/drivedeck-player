import unittest
from audio_timeline import analyze


def row(t, audio=10, video=None, pictures=None):
    return dict(epochMs=100000+t,monotonicMs=t,clockMs=t,decodedAudio=audio*2,audioBuffers=audio,
                audioLost=0,decodedVideo=t if video is None else video,
                pictures=t if pictures is None else pictures,inputBytes=9,demuxBytes=8)


class AudioTimelineTests(unittest.TestCase):
    def test_audio_hold_does_not_become_a_whole_snapshot_hold_when_video_advances(self):
        result=analyze([row(t) for t in range(0,1201,200)],[])
        hold=result['audioCounterPlateaus'][0]
        self.assertEqual(1200,hold['observedHeldMs'])
        self.assertEqual(1200,hold['decodedVideoDelta'])
        self.assertFalse(hold['entireMeasuredSnapshotHeld'])
        self.assertFalse(result['outputContinuityProven'])
        self.assertFalse(result['rootCauseProven'])

    def test_detects_whole_snapshot_hold_despite_advancing_clock(self):
        hold=analyze([row(t,video=3,pictures=4) for t in range(0,1201,200)],[])['audioCounterPlateaus'][0]
        self.assertTrue(hold['entireMeasuredSnapshotHeld'])
        self.assertEqual(1200,hold['clockDeltaMs'])

    def test_blocking_restart_gap_cannot_be_counted_as_observed_hold(self):
        result=analyze([row(0),row(200),row(5000),row(5200)],[])
        self.assertEqual([],result['audioCounterPlateaus'])
        self.assertEqual(4800,result['samplingGaps'][0]['gapMs'])

    def test_reset_or_progress_breaks_plateau(self):
        self.assertEqual([],analyze([row(0),row(200),row(400,9),row(600,10)],[])['audioCounterPlateaus'])

    def test_nearby_native_events_are_bounded_and_signed(self):
        events=[dict(epochMs=100000+t,monotonicMs=t,event='early_silence',value=-1234) for t in (-251,0,1450,1451)]
        hold=analyze([row(t) for t in range(0,1201,200)],events)['audioCounterPlateaus'][0]
        self.assertEqual([100000,101450],[e['epochMs'] for e in hold['nearbyNativeEvents']])
        self.assertEqual(-1234,hold['nearbyNativeEvents'][0]['value'])

    def test_empty_trace_and_monotonic_order(self):
        self.assertEqual(0,analyze([],[])['sampleCount'])
        with self.assertRaises(ValueError): analyze([row(200),row(100)],[])


if __name__=='__main__': unittest.main()
