"""Windows-only presentation probe. Render-loopback only: never opens a physical microphone.
Transient PCM is reduced to pulse/peak statistics and never saved. Close unrelated audio first.
SoundCard 0.4.6 private COM bindings are used to retain IAudioCaptureClient QPC timestamps.
"""
import argparse
import ctypes
from ctypes import wintypes
import json
import hashlib
import os
from pathlib import Path
import subprocess
import time
import numpy as np
import soundcard as sc
from soundcard import mediafoundation as mf
from av_sync_metrics import measure

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--java-home", type=Path, required=True)
parser.add_argument("--distribution", type=Path, required=True)
parser.add_argument("--output-directory", type=Path, required=True)
parser.add_argument("--fixture-directory", type=Path)
parser.add_argument("--video-id")
parser.add_argument("--observe-seconds", type=int, default=20)
parser.add_argument("--network-fault", choices=("none","relay","hold","disconnect","permanent","cancel"), default="none")
parser.add_argument("--network-down-seconds", type=int, default=25)
parser.add_argument("--profile", choices=("baseline", "hd720", "hd1080"), default="baseline")
args = parser.parse_args()
if not 20 <= args.observe_seconds <= 300: parser.error("Observation must be 20..300 seconds")
pulse_period_seconds = 1
if args.fixture_directory:
    recipe_path = args.fixture_directory / "recipe.json"
    if recipe_path.exists():
        recipe = json.loads(recipe_path.read_text())
        for filename, key in (("sync-video.mp4", "videoSha256"), ("sync-audio.m4a", "audioSha256")):
            if hashlib.sha256((args.fixture_directory / filename).read_bytes()).hexdigest() != recipe[key]:
                parser.error("Fixture hash does not match recipe")
        pulse_period_seconds = recipe["pulsePeriodMs"] / 1000
        if recipe["durationSeconds"] < 15 + args.observe_seconds + 5:
            parser.error("Fixture needs seek + observation + five seconds of headroom")
if bool(args.video_id) == bool(args.fixture_directory): parser.error("Choose one public video ID or sync fixture directory")
if os.name != "nt": parser.error("This probe uses Windows WASAPI")
args.output_directory.mkdir(parents=True, exist_ok=True)
speaker = sc.default_speaker()
channels = speaker.channels
loopback = sc.get_microphone(speaker.id, include_loopback=True)
if not loopback.isloopback: raise RuntimeError("RefusingNonLoopbackCapture")
report = {"schemaVersion": 1, "capture": "WASAPI_default_render_loopback", "physicalMicrophoneOpened": False,
    "rawAudioSaved": False, "sampleRate": 48000, "silentPacketFlagsChecked": True, "physicalSpeakerConfirmation": "not_measured_by_harness"}
audio_events = []
discontinuity_events = []
timestamp_error_events = []
peak = 0.0
last_pulse = -1e9
packet_count = timestamp_errors = discontinuities = 0
clock_offset = time.time_ns() - time.perf_counter_ns()

def packet(recorder, detect):
    global peak, last_pulse, packet_count, timestamp_errors, discontinuities
    if recorder._capture_available_frames() == 0: return 0.0
    ffi = mf._ffi
    data, size, flag_pointer = ffi.new("BYTE**"), ffi.new("UINT32*"), ffi.new("DWORD*")
    qpc = ffi.new("UINT64*")
    client = recorder._ppCaptureClient[0]
    status = client[0].lpVtbl.GetBuffer(client, data, size, flag_pointer, ffi.NULL, qpc)
    mf._com.check_error(status)
    try:
        count, flags, timestamp = int(size[0]), int(flag_pointer[0]), int(qpc[0])
        if count and not flags & 2:
            # Copy and promptly release the native packet before FFT/analysis or any device query.
            samples = np.frombuffer(ffi.buffer(data[0], count * channels * 4), dtype=np.float32).reshape(-1, channels).copy()
        else:
            samples = None
    finally:
        recorder._capture_release(int(size[0]))
    if not count: return 0.0
    if detect:
        # Flags on silent packets count too. The first packet's startup discontinuity is expected.
        if flags & 4:
            timestamp_errors += 1
            timestamp_error_events.append((timestamp * 100 + clock_offset) / 1e6)
        if packet_count > 0 and flags & 1:
            discontinuities += 1
            discontinuity_events.append((timestamp * 100 + clock_offset) / 1e6)
        packet_count += 1
    if samples is None: return 0.0
    mono = samples.mean(axis=1)
    value = float(np.max(np.abs(samples)))
    if detect:
        peak = max(peak, value)
        if value > .0005:
            # 20ms tones encode unique pulse ID in 100Hz steps; IDs prevent whole-period aliasing.
            spectrum = np.abs(np.fft.rfft(mono * np.hanning(count), n=8192))
            frequencies = np.fft.rfftfreq(8192, 1 / 48000)
            band = (frequencies >= 1050) & (frequencies <= 7350)
            frequency = frequencies[np.flatnonzero(band)[np.argmax(spectrum[band])]]
            identity = round((frequency - 1000) / 100)
            basis = np.exp(-2j * np.pi * (1000 + identity * 100) * np.arange(count) / 48000)
            tone = 2 * abs(np.dot(mono, basis)) / count
            rms = float(np.sqrt(np.mean(mono * mono)))
            stamp_ms = (timestamp * 100 + clock_offset) / 1e6
            if 1 <= identity <= 63 and tone > .001 and tone > rms * .8 and stamp_ms - last_pulse > 250:
                crossings = np.flatnonzero(np.abs(mono) > max(.0005, value * .08))
                if len(crossings):
                    onset = stamp_ms + int(crossings[0]) * 1000 / 48000
                    audio_events.append({"timeMs": onset, "id": identity})
                    last_pulse = onset
    return value

process = None
avrt = ctypes.WinDLL("avrt", use_last_error=True)
avrt.AvSetMmThreadCharacteristicsW.argtypes = [wintypes.LPCWSTR, ctypes.POINTER(wintypes.DWORD)]
avrt.AvSetMmThreadCharacteristicsW.restype = wintypes.HANDLE
avrt.AvRevertMmThreadCharacteristics.argtypes = [wintypes.HANDLE]
avrt.AvRevertMmThreadCharacteristics.restype = wintypes.BOOL
task_index = wintypes.DWORD()
scheduling = avrt.AvSetMmThreadCharacteristicsW("Audio", ctypes.byref(task_index))
report["multimediaCaptureScheduling"] = bool(scheduling)
try:
    with loopback.recorder(samplerate=48000, blocksize=4800, channels=channels) as recorder:
        baseline = 0.0
        preflight_started = quiet_since = time.monotonic()
        while time.monotonic() - quiet_since < 1:
            value = packet(recorder, False)
            if value > .001:
                quiet_since = time.monotonic()
                baseline = 0.0
            else:
                baseline = max(baseline, value)
            if time.monotonic() - preflight_started > 10: raise RuntimeError("OutputNotQuietForIsolatedMeasurement")
            time.sleep(.001)
        report["baselinePeak"] = round(baseline, 6)
        report["preflightWaitSeconds"] = round(time.monotonic() - preflight_started, 2)
        command = [str(args.java_home / "bin" / "java.exe"), "--enable-native-access=ALL-UNNAMED", "-cp",
                   str(args.distribution / "lib" / "*"), "com.drivedeck.player.spike.DesktopSpike", "--present"]
        if args.video_id: command += ["--youtube", args.video_id]
        else: command += ["--fixture-paired", str(args.fixture_directory / "sync-video.mp4"), str(args.fixture_directory / "sync-audio.m4a")]
        env = dict(os.environ, DD_PLAYER_PRESENTATION_DIR=str(args.output_directory),
                   DD_PLAYER_OBSERVE_SECONDS=str(args.observe_seconds), DD_PLAYER_PROFILE=args.profile,
                   DD_PLAYER_PROBE_TIMEOUT_SECONDS=str(args.observe_seconds + 80),
                   DD_PLAYER_NETWORK_FAULT=args.network_fault, DD_PLAYER_NETWORK_DOWN_SECONDS=str(args.network_down_seconds))
        with (args.output_directory / "worker.json").open("wb") as output, (args.output_directory / "launcher.log").open("wb") as errors:
            process = subprocess.Popen(command, stdout=output, stderr=errors, env=env)
            deadline = time.monotonic() + args.observe_seconds + 90
            while process.poll() is None and time.monotonic() < deadline:
                packet(recorder, True); time.sleep(.001)
            if process.poll() is None: raise TimeoutError("PresentationSupervisorDeadline")
    worker = json.loads((args.output_directory / "worker.json").read_text())
    report["worker"] = worker
    report.update(peakLoopbackAmplitude=round(peak, 6), timestampErrors=timestamp_errors,
                  captureDiscontinuities=discontinuities, capturedPackets=packet_count)
    clock_change_ms = abs(time.time_ns() - time.perf_counter_ns() - clock_offset) / 1e6
    report["wallClockMappingChangeMs"] = round(clock_change_ms, 3)
    if process.returncode or worker.get("result") != "passed": raise RuntimeError("PlaybackProbeFailed")
    if peak < .001: raise RuntimeError("NoRenderedAudioInLoopback")
    if args.fixture_directory:
        video_events = [{"timeMs": float(parts[0]), "id": int(parts[2])} for parts in
                        (line.split(",") for line in (args.output_directory / "flash-events.csv").read_text().splitlines())]
        phases = [line.split(",") for line in (args.output_directory / "phases.csv").read_text().splitlines()]
        steady = next(float(stamp) for stamp, name in phases if name == "steady_after_seek")
        changes = [float(stamp) for stamp, name in phases if name in ("pause", "resume", "seek")]
        # Classifications are diagnostic only; every discontinuity still fails qualification.
        started = next(float(stamp) for stamp, name in phases if name == "start")
        first_event = min(event["timeMs"] for event in video_events)
        startup = [stamp for stamp in discontinuity_events if started <= stamp < min(started + 2000, first_event)]
        transitions = [stamp for stamp in discontinuity_events if stamp not in startup
                       and any(-100 <= stamp - change <= 1500 for change in changes)]
        unexpected = [stamp for stamp in discontinuity_events if stamp not in startup and stamp not in transitions]
        report["startupDiscontinuitiesBeforeMeasurement"] = len(startup)
        report["transitionDiscontinuities"] = len(transitions)
        report["unexpectedCaptureDiscontinuities"] = len(unexpected)
        if args.network_fault in ("none","relay"):
            minimum_span = max(15, args.observe_seconds - 2 * pulse_period_seconds - 2)
            minimum_pulses = max(12, int(args.observe_seconds / pulse_period_seconds) - 2)
            report["sync"] = measure(video_events, audio_events, minimum_span, minimum_pulses)
            report["steadyPostSeekSync"] = measure([event for event in video_events if event["timeMs"] >= steady], audio_events, minimum_span, minimum_pulses)
            if timestamp_errors or discontinuities or clock_change_ms > 5: raise RuntimeError("CaptureClockOrContinuityFailure")
            if report["sync"]["result"] != "passed" or report["steadyPostSeekSync"]["result"] != "passed": raise RuntimeError("AVSyncBudgetFailed")
            report["calibratedFixtureSyncPassed"] = True
        else:
            recovered = next(float(stamp) for stamp, name in phases if name == "network_recovered")
            segment = recovered + 2000
            post_video = [event for event in video_events if event["timeMs"] >= segment]
            post_audio = [event for event in audio_events if event["timeMs"] >= segment]
            report["postRecoverySync"] = measure(post_video,post_audio,30,12)
            report["wholeCaptureContinuityPassed"] = not (timestamp_errors or discontinuities)
            report["postRecoveryCaptureDiscontinuities"] = sum(stamp>=segment for stamp in discontinuity_events)
            report["postRecoveryTimestampErrors"] = sum(stamp>=segment for stamp in timestamp_error_events)
            report["postRecoveryMeasurementStartsMsAfterRecovery"] = 2000
            if report["postRecoveryCaptureDiscontinuities"] or report["postRecoveryTimestampErrors"] or clock_change_ms>5:
                raise RuntimeError("PostRecoveryCaptureFailure")
            if report["postRecoverySync"]["result"]!="passed": raise RuntimeError("PostRecoveryAVSyncBudgetFailed")
            report["calibratedPostRecoverySyncPassed"] = True
    else:
        report["providerAVSyncProven"] = False
    report["result"] = "passed"
except Exception as failure:
    report.update(result="failed", errorType=type(failure).__name__, reason=str(failure) if str(failure).isidentifier() else "ProbeFailure")
finally:
    if scheduling:
        restored = bool(avrt.AvRevertMmThreadCharacteristics(scheduling))
        report["multimediaSchedulingRestored"] = restored
        if not restored: report.update(result="failed", reason="MultimediaSchedulingRestoreFailed")
    if process is not None and process.poll() is None:
        # The child Java supervisor owns native-worker lifetime. Kill the owned tree on harness failure.
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True)
    (args.output_directory / "audio-pulse-events.json").write_text(json.dumps(audio_events))
    (args.output_directory / "capture-gaps.json").write_text(json.dumps(discontinuity_events))
    (args.output_directory / "timestamp-error-events.json").write_text(json.dumps(timestamp_error_events))
    (args.output_directory / "presentation-result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
raise SystemExit(0 if report["result"] == "passed" else 1)
