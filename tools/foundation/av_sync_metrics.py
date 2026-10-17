"""Pair known flash/beep onsets; positive offsets mean audio is later than painted video."""
from statistics import median

def measure(video_events, audio_events, minimum_span_seconds=15, minimum_pulses=12):
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
    if len(offsets) < minimum_pulses:
        return {"result": "failed", "reason": "insufficient_matched_pulses", "matchedPulses": len(offsets)}
    absolute = sorted(abs(value) for value in offsets)
    p95 = absolute[min(len(absolute) - 1, int(len(absolute) * .95))]
    drift = median(offsets[-5:]) - median(offsets[:5])
    window_medians = [median(offsets[i:i + 5]) for i in range(len(offsets) - 4)]
    excursion = max(window_medians) - min(window_medians)
    span = (matched_video[-1] - matched_video[0]) / 1000
    coverage = len(offsets) / max(1, len(video_events))
    passed = abs(median(offsets)) <= 80 and p95 <= 120 and abs(drift) <= 40 and span >= minimum_span_seconds and coverage >= .8 and max(absolute) <= 150 and excursion <= 40
    return {"result": "passed" if passed else "failed", "matchedPulses": len(offsets),
        "medianAudioMinusVideoMs": round(median(offsets), 2), "p95AbsoluteOffsetMs": round(p95, 2),
        "maxAbsoluteOffsetMs": round(max(absolute), 2), "endMinusStartDriftMs": round(drift, 2),
        "measuredSpanSeconds": round(span, 2), "matchCoverage": round(coverage, 3),
        "medianBudgetMs": 80, "p95BudgetMs": 120, "driftBudgetMs": 40, "maximumOffsetBudgetMs": 150,
        "windowMedianExcursionMs": round(excursion, 2), "windowExcursionBudgetMs": 40,
        "minimumSpanSeconds": minimum_span_seconds, "minimumMatchedPulses": minimum_pulses}
