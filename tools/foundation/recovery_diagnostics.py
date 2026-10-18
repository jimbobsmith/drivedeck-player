"""Offline, numeric-only recovery decision analysis. Never changes the worker gate."""
import argparse
import csv
import json
from pathlib import Path


def failed_components(clock, audio, pictures, paint):
    failed = []
    if clock < 500:
        failed.append("clock_below_500ms")
    for label, value in (("audio", audio), ("pictures", pictures), ("paint", paint)):
        if value <= 0:
            failed.append(label + ("_counter_reset" if value < 0 else "_not_advanced"))
    return failed


def replay_batches(columns, samples):
    """Versioned publication audit; never upgrades a historical worker result."""
    confirmed = pending = longest = holds = resets = 0
    for values in samples:
        if len(values)!=len(columns): raise ValueError("RecoveryDecisionArity")
        row=dict(zip(columns, values))
        wall,clock,audio,pictures,paint=(row[k] for k in ("wallDeltaMs","clockDeltaMs","audioBufferDelta","pictureDelta","paintDelta"))
        if wall<500 or wall>1250 or clock<500 or clock>wall+500 or audio<0 or pictures<=0 or paint<=0:
            confirmed=pending=0;resets+=1
        elif audio==0:
            pending+=wall
            if pending>1250: confirmed=pending=0;resets+=1
        elif pending+wall>2250:
            confirmed=pending=0;resets+=1
        else:
            holds+=bool(pending);confirmed+=pending+wall;pending=0;longest=max(longest,confirmed)
    terminal=0 if pending else confirmed
    return dict(longestConfirmedMs=longest,terminalConfirmedMs=terminal,pendingMs=pending,
                confirmedHolds=holds,resetDecisions=resets,qualified=longest>=30000 and terminal>=30000,
                audibleContinuityProven=False)


def analyze(worker, original_failures=None, track_rows=None, window_rows=None, audio_activity=None, decision_rows=None):
    network = worker.get("networkScenario", {})
    columns = network.get("recoverySampleColumns", [])
    samples = network.get("recoverySamples", [])
    decision_source="inline_json"
    if decision_rows is not None:
        try: full=[[int(row[key]) for key in columns] for row in decision_rows]
        except (KeyError,ValueError) as failure: raise ValueError("InvalidRecoveryDecisionSidecar") from failure
        if not columns or len(full)!=network.get("totalRecoveryDecisionSamples",len(full)):
            raise ValueError("RecoveryDecisionSidecarCount")
        if full[:len(samples)]!=samples: raise ValueError("RecoveryDecisionPreviewMismatch")
        samples=full;decision_source="full_numeric_sidecar"
    elif (network.get("recoverySamplesComplete") is False
          or network.get("totalRecoveryDecisionSamples",len(samples))!=len(samples)):
        raise ValueError("FullRecoveryDecisionSidecarRequired")
    required = ["elapsedMs", "clockDeltaMs", "audioBufferDelta", "pictureDelta", "paintDelta"]
    ordered_tracks = sorted(track_rows or [], key=lambda row: int(row["elapsedMs"]))
    trace = {int(row["elapsedMs"]): row for row in ordered_tracks}
    previous = {int(row["elapsedMs"]): before for before, row in zip(ordered_tracks, ordered_tracks[1:])}
    failures = []
    longest = steady = 0
    if samples:
        if not all(key in columns for key in required):
            raise ValueError("MissingRecoveryDecisionColumns")
        for sample in samples:
            if len(sample) != len(columns):
                raise ValueError("RecoveryDecisionArity")
            row = dict(zip(columns, sample))
            failed = failed_components(*(row[key] for key in required[1:]))
            if failed:
                steady = 0
                detail = {"elapsedMs": row["elapsedMs"], "failedComponents": failed,
                          "clockDeltaMs": row["clockDeltaMs"], "audioDelta": row["audioBufferDelta"],
                          "pictureDelta": row["pictureDelta"], "paintDelta": row["paintDelta"]}
                context = trace.get(int(row["elapsedMs"]))
                if context:
                    keys = ["decodedVideo", "decodedAudio", "callbacks", "painted", "videoBytes", "audioBytes",
                            "videoPendingReadMs", "audioPendingReadMs", "videoPendingWriteMs", "audioPendingWriteMs"]
                    detail["epochMs"] = int(context["epochMs"]) if "epochMs" in context else None
                    detail["sameTickTrackContext"] = {key: int(context[key]) for key in keys}
                    before = previous.get(int(row["elapsedMs"]))
                    if before:
                        delta_keys = ["decodedVideo", "decodedAudio", "callbacks", "painted", "videoBytes", "audioBytes"]
                        detail["previousTraceDeltas"] = {key: int(context[key])-int(before[key]) for key in delta_keys}
                        detail["traceWindowMs"] = int(context["elapsedMs"])-int(before["elapsedMs"])
                failures.append(detail)
            else:
                steady += 1
                longest = max(longest, steady)
        source = "worker_recovery_decisions"
    elif original_failures is not None:
        for row in original_failures:
            paint = int(row.get("paintDeltaAtReference", row.get("paintDeltaAfterDecision")))
            failed = failed_components(int(row["clockDelta"]), int(row["audioDelta"]),
                                       int(row["pictureDelta"]), paint)
            failures.append({"elapsedMs": int(row["elapsedMs"]), "epochMs": int(row["epochMs"]) if "epochMs" in row else None,
                             "failedComponents": failed or ["unresolved_paint_read_timing"],
                             "clockDeltaMs": int(row["clockDelta"]), "audioDelta": int(row["audioDelta"]),
                             "pictureDelta": int(row["pictureDelta"]), "paintDeltaAfterPredicate": paint,
                             "paintWasReadAfterPredicate": True})
        source = "historical_failed_decisions"
    else:
        source = "no_per_decision_evidence"
    for detail in failures:
        epoch = detail.get("epochMs")
        if epoch is None:
            continue
        if window_rows:
            nearest = min(window_rows, key=lambda row: abs(int(row["epochMs"])-epoch))
            if abs(int(nearest["epochMs"])-epoch) <= 750:
                keys = ["windowFound", "windowVisible", "windowMinimized", "windowCloaked", "foregroundOwned", "foregroundCoveragePercent", "displayState"]
                detail["windowContext"] = {key: int(nearest[key]) for key in keys}
                detail["windowSampleOffsetMs"] = int(nearest["epochMs"])-epoch
        if audio_activity is not None:
            second = epoch//1000
            detail["nearbyEndpointEnergyBins"] = [row for row in audio_activity if second-1 <= row["epochSecond"] <= second+1]
            detail["endpointEnergyIsMixedAndBinsPartiallyOverlap"] = True
    version=network.get("recoveryValidation","strict_per_tick_v1")
    if version not in ("strict_per_tick_v1","confirmed_audio_batches_v2"):
        raise ValueError("UnknownRecoveryValidation")
    batch=replay_batches(columns,samples) if samples and version=="confirmed_audio_batches_v2" else None
    if batch is not None:
        batch["matchesWorkerDurations"]=(batch["longestConfirmedMs"]==network.get("longestConfirmedRecoveredMs")
                                         and batch["terminalConfirmedMs"]==network.get("confirmedRecoveredMs")
                                         and batch["pendingMs"]==network.get("pendingAudioBatchMs"))
    return {"schemaVersion": 2, "recoveryValidation":version,"counterBatchAudit":batch,
            "strictPerTickAuditOnly":version!="strict_per_tick_v1", "source": source, "workerResult": worker.get("result"),
            "terminalReason": network.get("terminalReason"), "decisionSampleCount": len(samples), "decisionSource":decision_source,
            "failedDecisionCount": len(failures) if source != "no_per_decision_evidence" else None,
            "recomputedLongestStableSamples": longest if samples else None,
            "recomputedTerminalStableSamples": steady if samples else None,
            "reportedLongestStableSamples": network.get("longestConsecutiveRecoveredSeconds"),
            "reportedTerminalStableSamples": network.get("steadyRecoveredSeconds"),
            "terminalStabilityQualified": (network.get("longestConsecutiveRecoveredSeconds", 0)>=30
                                            and network.get("steadyRecoveredSeconds", 0)>=30)
                                           if "steadyRecoveredSeconds" in network else None,
            "failures": failures, "rootCauseProven": False,
            "interpretation": "Strict per-tick failures are legacy comparison only for version 2; its counterBatchAudit identifies the active publication gate. Neither audit proves native cause or audible continuity/source alignment."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    worker = json.loads((args.directory / "worker.json").read_text(encoding="utf-8-sig"))
    legacy = next((args.directory / name for name in ("original-gate-failures.csv", "historical-failed-decisions.csv")
                   if (args.directory / name).exists()), None)
    tracks = args.directory / "recovery-diagnostics.csv"
    def rows(path):
        with path.open(encoding="utf-8-sig", newline="") as stream:
            return list(csv.DictReader(stream))
    windows = args.directory / "window-state.csv"
    energy = args.directory / "audio-activity.json"
    decisions = args.directory / "recovery-decisions.csv"
    print(json.dumps(analyze(worker, rows(legacy) if legacy is not None else None,
                             rows(tracks) if tracks.exists() else None,
                             rows(windows) if windows.exists() else None,
                             json.loads(energy.read_text(encoding="utf-8-sig")) if energy.exists() else None,
                             rows(decisions) if decisions.exists() else None), indent=2))


if __name__ == "__main__":
    main()
