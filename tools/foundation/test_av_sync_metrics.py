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
    def test_long_run_cannot_pass_with_short_data(self):
        self.assertEqual("failed", measure(*self.events(25), minimum_span_seconds=170)["result"])
    def test_temporary_drift_cannot_hide_behind_matching_start_and_end(self):
        video, audio = self.events(25)
        for event in audio[8:16]: event["timeMs"] += 60
        self.assertEqual("failed", measure(video, audio)["result"])
    def test_rare_large_excursion_is_not_hidden_by_percentile(self):
        video = [{"id": i, "timeMs": i * 4000} for i in range(1, 51)]
        audio = [dict(event) for event in video]
        audio[25]["timeMs"] += 200
        self.assertEqual("failed", measure(video, audio)["result"])
    def test_long_run_bounded_timing_passes(self):
        video = [{"id": i, "timeMs": i * 4000} for i in range(1, 51)]
        audio = [{"id": event["id"], "timeMs": event["timeMs"] + 30} for event in video]
        self.assertEqual("passed", measure(video, audio, 170, 43)["result"])
    def test_audio_pulse_cannot_be_reused(self):
        video = [{"id": 1, "timeMs": i * 10} for i in range(20)]
        audio = [{"id": 1, "timeMs": 100}]
        self.assertEqual(1, measure(video, audio)["matchedPulses"])

if __name__ == "__main__": unittest.main()
