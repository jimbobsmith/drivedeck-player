import unittest
from audio_timeline import analyze, counter_cadence, phase_segments


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

    def test_batched_counter_cadence_does_not_imply_output_silence(self):
        rows=[row(t,audio=10+43*(t//1000)) for t in range(0,3201,200)]
        cadence=counter_cadence(rows)
        self.assertEqual({43:3},cadence['positiveBufferDeltaHistogram'])
        self.assertEqual(1000,cadence['medianPositiveUpdateIntervalMs'])
        self.assertEqual([1000,1000],cadence['intervalsBetweenPositiveUpdatesMs'])

    def test_cadence_resets_or_sampling_gaps_do_not_create_false_update_intervals(self):
        rows=[row(0,1),row(200,2),row(400,1),row(600,2),row(5000,3),row(5200,4)]
        cadence=counter_cadence(rows)
        self.assertEqual(1,cadence['counterResets'])
        self.assertEqual([],cadence['intervalsBetweenPositiveUpdatesMs'])

    def test_holds_are_not_merged_across_fault_phase_boundaries(self):
        rows=[row(t) for t in range(0,1401,200)]
        segments=phase_segments(rows,[],[(100000,'steady_after_seek'),(100800,'network_down')])
        self.assertEqual(['steady_after_seek','network_down'],[s['phase'] for s in segments])
        self.assertTrue(all(not s['analysis']['audioCounterPlateaus'] for s in segments))
        self.assertEqual(1,len(analyze(rows,[])['audioCounterPlateaus']))

    def test_phase_labels_cannot_import_arbitrary_log_content(self):
        with self.assertRaises(ValueError): phase_segments([],[],[(100000,'https://private')])
        with self.assertRaises(ValueError): phase_segments([],[],[(100000,'seek'),(100000,'resume')])

    def test_empty_trace_and_monotonic_order(self):
        self.assertEqual(0,analyze([],[])['sampleCount'])
        with self.assertRaises(ValueError): analyze([row(200),row(100)],[])


if __name__=='__main__': unittest.main()
