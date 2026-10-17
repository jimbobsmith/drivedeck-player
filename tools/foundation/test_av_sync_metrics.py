import unittest
from av_sync_metrics import measure

class SyncMetricsTest(unittest.TestCase):
    def events(self, delay=0, drift=0):
        video = [{"id": i, "timeMs": i * 1000} for i in range(1, 25)]
        audio = [{"id": i, "timeMs": i * 1000 + delay + drift * i} for i in range(1, 25)]
        return video, audio
    def test_small_bounded_offset_passes(self):
        self.assertEqual("passed", measure(*self.events(35))["result"])
    def test_quarter_second_delay_fails(self):
        self.assertEqual("failed", measure(*self.events(250))["result"])
    def test_whole_second_delay_cannot_alias_next_pulse(self):
        self.assertEqual("failed", measure(*self.events(1000))["result"])
    def test_drift_fails(self):
        self.assertEqual("failed", measure(*self.events(0, 6))["result"])
    def test_wrong_ids_do_not_match(self):
        video, audio = self.events()
        for event in audio: event["id"] += 100
        self.assertEqual("failed", measure(video, audio)["result"])
    def test_insufficient_data_fails(self):
        self.assertEqual("failed", measure([], [])["result"])
    def test_audio_pulse_cannot_be_reused(self):
        video = [{"id": 1, "timeMs": i * 10} for i in range(20)]
        audio = [{"id": 1, "timeMs": 100}]
        self.assertEqual(1, measure(video, audio)["matchedPulses"])

if __name__ == "__main__": unittest.main()
