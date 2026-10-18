import unittest
import numpy as np
from pilot_continuity import PilotPackets,assess_interval,assess_terminal
class PilotTests(unittest.TestCase):
    def test_terminal_gate_rejects_silent_audio_even_with_qualified_counters(self):
        rows=[dict(startEpochMs=t,durationMs=10,pilotAmplitude=.0002,flags=0) for t in range(0,100000,10)]
        phases=[(0,"steady_after_seek"),(10000,"network_recovered")]
        worker={"observationWallMs":100000,"result":"passed"}
        self.assertTrue(assess_terminal(rows,phases,worker)["continuousPilotObserved"])
        for row in rows:
            if 80000<=row["startEpochMs"]<84000: row["pilotAmplitude"]=0
        self.assertFalse(assess_terminal(rows,phases,worker)["continuousPilotObserved"])
        with self.assertRaisesRegex(ValueError,"InsufficientTerminalPilotWindow"):
            assess_terminal(rows,phases,{"observationWallMs":30000})
    def test_known_tone_phase_and_amplitude(self):
        pilot=PilotPackets(300)
        for phase in (0,.7,2):
            tone=.0002*np.sin(2*np.pi*300*np.arange(480)/48000+phase)
            pilot.observe(len(pilot.rows)*10,480,np.column_stack((tone,tone)),0)
        self.assertTrue(all(abs(r['pilotAmplitude']-.0002)<1e-7 for r in pilot.rows))
        self.assertTrue(assess_interval(pilot.rows,0,30)['continuousPilotObserved'])
    def test_silent_packet_is_retained_and_rejected(self):
        p=PilotPackets(300);p.observe(0,480,None,2)
        self.assertFalse(assess_interval(p.rows,0,10)['continuousPilotObserved'])
    def test_missing_packets_cannot_be_hidden_by_good_tone(self):
        rows=[dict(startEpochMs=t,durationMs=10,pilotAmplitude=.0002,flags=0) for t in (0,20)]
        r=assess_interval(rows,0,30)
        self.assertEqual(10,r['maximumGapMs']);self.assertFalse(r['continuousPilotObserved'])
    def test_empty_and_flagged_intervals_cannot_prove_continuity(self):
        self.assertFalse(assess_interval([],0,10)['continuousPilotObserved'])
        rows=[dict(startEpochMs=0,durationMs=10,pilotAmplitude=.0002,flags=1)]
        self.assertFalse(assess_interval(rows,0,10)['continuousPilotObserved'])
        with self.assertRaises(ValueError):assess_interval([],1,1)
    def test_invalid_numbers_cannot_prove_pilot_presence(self):
        with self.assertRaises(ValueError):PilotPackets(float('nan'))
        with self.assertRaises(ValueError):PilotPackets(24000)
        rows=[dict(startEpochMs=0,durationMs=10,pilotAmplitude=float('nan'),flags=0)]
        self.assertFalse(assess_interval(rows,0,10)['continuousPilotObserved'])
    def test_packet_storage_is_bounded(self):
        p=PilotPackets(300,limit=1);p.observe(0,480,None,2);p.observe(10,480,None,2)
        self.assertEqual(1,len(p.rows));self.assertEqual(1,p.dropped)
if __name__=='__main__':unittest.main()
