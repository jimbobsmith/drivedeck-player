import unittest
from recovery_diagnostics import analyze, replay_batches

COLUMNS = ["elapsedMs", "positionMs", "clockDeltaMs", "audioBufferDelta", "pictureDelta", "paintDelta", "relayByteDelta", "wallDeltaMs"]
def worker(rows):
    return {"result": "failed", "networkScenario": {"recoverySampleColumns": COLUMNS, "recoverySamples": rows}}

class RecoveryDiagnosticsTest(unittest.TestCase):
    def test_batch_replay_confirms_hold_and_retains_original_failure(self):
        rows=[[i*1000,i*1000,1000,0 if i%2 else 86,60,60,100,1000] for i in range(1,33)]
        batch=replay_batches(COLUMNS,rows)
        self.assertEqual(32000,batch["terminalConfirmedMs"]);self.assertTrue(batch["qualified"])
        data=worker(rows)
        self.assertIsNone(analyze(data)["counterBatchAudit"])
        data["networkScenario"].update(recoveryValidation="confirmed_audio_batches_v2",confirmedRecoveredMs=32000,
                                      longestConfirmedRecoveredMs=32000,pendingAudioBatchMs=0)
        report=analyze(data)
        self.assertTrue(report["counterBatchAudit"]["matchesWorkerDurations"])
        self.assertEqual("failed",report["workerResult"])
        self.assertTrue(report["strictPerTickAuditOnly"])
        rows.append([33000,33000,1000,0,60,60,100,1000])
        self.assertFalse(replay_batches(COLUMNS,rows)["qualified"])
    def test_batched_validation_rejects_real_stalls_resets_and_late_catchup(self):
        good=[[i*1000,i*1000,1000,43,60,60,100,1000] for i in range(1,31)]
        for bad in ([31000,31000,0,86,60,60,100,1000],[31000,31000,1000,-1,60,60,100,1000],
                    [31000,31000,1000,86,0,60,100,1000],[31000,31000,1000,86,60,0,100,1000],
                    [31000,31000,1000,86,60,60,100,1251]):
            self.assertFalse(replay_batches(COLUMNS,good+[bad])["qualified"])
        held=[31000,31000,1000,0,60,60,100,1000]
        self.assertFalse(replay_batches(COLUMNS,good+[held,held])["qualified"])
        self.assertFalse(replay_batches(COLUMNS,good+[held,[32000,32000,1000,86,60,60,100,1251]])["qualified"])
    def test_unknown_gate_version_is_not_silently_audited_as_legacy(self):
        with self.assertRaisesRegex(ValueError,"UnknownRecoveryValidation"):
            analyze({"networkScenario":{"recoveryValidation":"future"}})
    def test_distinguishes_audio_hold_from_clock_video_and_paint(self):
        report = analyze(worker([[1000, 2000, 1000, 0, 60, 60, 0, 1000]]))
        self.assertEqual(["audio_not_advanced"], report["failures"][0]["failedComponents"])
        self.assertFalse(report["rootCauseProven"])
    def test_counter_reset_and_clock_shortfall_are_not_good_samples(self):
        report = analyze(worker([[1000, 2000, 499, -2, 60, 60, 0, 1000]]))
        self.assertEqual(["clock_below_500ms", "audio_counter_reset"], report["failures"][0]["failedComponents"])
    def test_recomputes_consecutive_intervals_without_overriding_worker_result(self):
        rows = [[i*1000, i*1000, 1000, 43, 60, 60, 100, 1000] for i in range(1, 7)]
        rows[2][5] = 0
        report = analyze(worker(rows))
        self.assertEqual(3, report["recomputedLongestStableSamples"])
        self.assertEqual(3, report["recomputedTerminalStableSamples"])
        self.assertEqual("failed", report["workerResult"])
        self.assertEqual(["paint_not_advanced"], report["failures"][0]["failedComponents"])
    def test_five_second_native_summaries_are_insufficient(self):
        report = analyze({"result": "failed", "observationSamples": [{"wallElapsedMs": 5000, "audioBuffersPlayed": 217}]})
        self.assertEqual("no_per_decision_evidence", report["source"])
        self.assertIsNone(report["failedDecisionCount"])
    def test_historical_paint_reference_cannot_exclude_a_predicate_read_race(self):
        row = {"elapsedMs": "1000", "clockDelta": "1000", "audioDelta": "43", "pictureDelta": "60", "paintDeltaAtReference": "1"}
        report = analyze({}, [row])
        self.assertEqual(["unresolved_paint_read_timing"], report["failures"][0]["failedComponents"])
        self.assertTrue(report["failures"][0]["paintWasReadAfterPredicate"])
    def test_accepts_the_failure_only_historical_counter_format(self):
        row = {"elapsedMs": "1000", "clockDelta": "1000", "audioDelta": "0", "pictureDelta": "60", "paintDeltaAfterDecision": "60"}
        report = analyze({}, [row])
        self.assertEqual(["audio_not_advanced"], report["failures"][0]["failedComponents"])
    def test_aligns_transport_and_callback_context_by_exact_tick(self):
        keys = ["decodedVideo", "decodedAudio", "callbacks", "painted", "videoBytes", "audioBytes", "videoPendingReadMs", "audioPendingReadMs", "videoPendingWriteMs", "audioPendingWriteMs"]
        before = dict.fromkeys(keys, 0); before["elapsedMs"] = 1000
        after = dict(before); after.update(elapsedMs=2000, callbacks=60, painted=59, videoPendingReadMs=900)
        report = analyze(worker([[2000, 2000, 0, 0, 0, 59, 0, 1000]]), track_rows=[before, after])
        failure = report["failures"][0]
        self.assertEqual(60, failure["previousTraceDeltas"]["callbacks"])
        self.assertEqual(900, failure["sameTickTrackContext"]["videoPendingReadMs"])
        self.assertEqual(1000, failure["traceWindowMs"])
        self.assertFalse(report["rootCauseProven"])
    def test_audits_a_reported_pass_that_ends_with_no_stability(self):
        data = {"result": "passed", "networkScenario": {"longestConsecutiveRecoveredSeconds": 150, "steadyRecoveredSeconds": 0}}
        report = analyze(data)
        self.assertEqual("passed", report["workerResult"])
        self.assertFalse(report["terminalStabilityQualified"])
        self.assertEqual(0, report["reportedTerminalStableSamples"])
    def test_only_correlates_a_nearby_window_sample(self):
        failure = {"epochMs": "10000", "elapsedMs": "1000", "clockDelta": "766", "audioDelta": "0", "pictureDelta": "46", "paintDeltaAfterDecision": "60"}
        fields = ["windowFound", "windowVisible", "windowMinimized", "windowCloaked", "foregroundOwned", "foregroundCoveragePercent", "displayState"]
        context = dict.fromkeys(fields, "0"); context.update(epochMs="10400", windowFound="1", displayState="1")
        report = analyze({}, [failure], window_rows=[context])
        self.assertEqual(400, report["failures"][0]["windowSampleOffsetMs"])
        self.assertEqual(1, report["failures"][0]["windowContext"]["displayState"])
        context["epochMs"] = "11000"
        self.assertNotIn("windowContext", analyze({}, [failure], window_rows=[context])["failures"][0])
    def test_endpoint_energy_is_context_and_does_not_override_failure(self):
        failure = {"epochMs": "10000", "elapsedMs": "1000", "clockDelta": "766", "audioDelta": "0", "pictureDelta": "46", "paintDeltaAfterDecision": "60"}
        bins = [{"epochSecond": second, "rmsAbove0005PacketFrames": 48000} for second in [8, 9, 10, 11, 12]]
        report = analyze({}, [failure], audio_activity=bins)
        row = report["failures"][0]
        self.assertEqual([9, 10, 11], [x["epochSecond"] for x in row["nearbyEndpointEnergyBins"]])
        self.assertEqual(["audio_not_advanced"], row["failedComponents"])
        self.assertTrue(row["endpointEnergyIsMixedAndBinsPartiallyOverlap"])
        self.assertFalse(report["rootCauseProven"])
    def test_rejects_incomplete_decisions_instead_of_inventing_values(self):
        with self.assertRaisesRegex(ValueError, "RecoveryDecisionArity"):
            analyze(worker([[1, 2, 3]]))
        with self.assertRaisesRegex(ValueError, "MissingRecoveryDecisionColumns"):
            analyze({"networkScenario": {"recoverySamples": [[1]], "recoverySampleColumns": ["elapsedMs"]}})

if __name__ == "__main__":
    unittest.main()
