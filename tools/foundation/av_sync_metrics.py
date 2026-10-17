"""Pair known flash/beep onsets; positive offsets mean audio is later than painted video."""
from statistics import median

def measure(video_events, audio_events):
    offsets, matched_video, used = [], [], set()
    for video in sorted(video_events, key=lambda event: event["timeMs"]):
        candidates = [(i, event) for i, event in enumerate(audio_events)
                      if i not in used and event["id"] == video["id"]]
        if candidates:
            index, closest = min(candidates, key=lambda item: abs(item[1]["timeMs"] - video["timeMs"]))
            delta = closest["timeMs"] - video["timeMs"]
            if abs(delta) <= 450:
                used.add(index)
                offsets.append(delta)
                matched_video.append(video["timeMs"])
    if len(offsets) < 12:
        return {"result": "failed", "reason": "insufficient_matched_pulses", "matchedPulses": len(offsets)}
    absolute = sorted(abs(value) for value in offsets)
    p95 = absolute[min(len(absolute) - 1, int(len(absolute) * .95))]
    drift = median(offsets[-5:]) - median(offsets[:5])
    span = (matched_video[-1] - matched_video[0]) / 1000
    coverage = len(offsets) / max(1, len(video_events))
    passed = abs(median(offsets)) <= 80 and p95 <= 120 and abs(drift) <= 40 and span >= 15 and coverage >= .8
    return {"result": "passed" if passed else "failed", "matchedPulses": len(offsets),
        "medianAudioMinusVideoMs": round(median(offsets), 2), "p95AbsoluteOffsetMs": round(p95, 2),
        "maxAbsoluteOffsetMs": round(max(absolute), 2), "endMinusStartDriftMs": round(drift, 2),
        "measuredSpanSeconds": round(span, 2), "matchCoverage": round(coverage, 3),
        "medianBudgetMs": 80, "p95BudgetMs": 120, "driftBudgetMs": 40}
