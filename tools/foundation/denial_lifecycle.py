"""Local synthetic native denial controls; never a real YouTube reproduction.

Run with --java-home, --distribution, --fixture-directory and a fresh
--output-directory under D:/tmp/dd. Uses dummy outputs and the normal supervisor.
"""
import argparse
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import subprocess
import threading


class FixtureOrigin(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, fixtures, denied_track):
        self.fixtures = fixtures
        self.denied_track = denied_track
        self.rows = []
        self.row_lock = threading.Lock()
        self.dropped = 0
        super().__init__(("127.0.0.1", 0), FixtureHandler)

    def record(self, row):
        with self.row_lock:
            if len(self.rows) < 128:
                self.rows.append(row)
            else:
                self.dropped += 1


class FixtureHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass  # Request targets stay private.

    def do_HEAD(self):
        self.serve()

    def do_GET(self):
        self.serve()

    def serve(self):
        track = self.path.split("?", 1)[0].removeprefix("/")
        source = self.server.fixtures.get(track)
        if source is None:
            self.send_error(404)
            return
        vision = self.headers.get("User-Agent", "").startswith("com.google.visionos.youtube/")
        if track == self.server.denied_track and not vision:
            status, start, end = 403, 0, -1
        else:
            length = source.stat().st_size
            start, end, status = 0, length - 1, 200
            value = self.headers.get("Range")
            if value:
                match = re.fullmatch(r"bytes=(\d+)-(\d*)", value)
                if not match:
                    status, end = 416, -1
                else:
                    start = int(match[1])
                    end = min(int(match[2]) if match[2] else end, end)
                    status = 206 if 0 <= start <= end else 416
                    if status == 416:
                        start, end = 0, -1
        size = max(0, end - start + 1)
        self.server.record(dict(track=track, method=self.command, visionosUserAgent=vision,
                                status=status, responseBytes=size))
        self.send_response(status)
        self.send_header("Content-Length", str(size))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Type", "video/mp4" if track == "video" else "audio/mp4")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{source.stat().st_size}")
        self.end_headers()
        if self.command == "HEAD" or not size:
            return
        try:
            with source.open("rb") as stream:
                stream.seek(start)
                remaining = size
                while remaining:
                    chunk = stream.read(min(65536, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass  # Native seek and bounded witness reads deliberately close early.


@contextmanager
def origin(fixtures, denied_track=None):
    server = FixtureOrigin(fixtures, denied_track)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(3)
        if thread.is_alive():
            raise RuntimeError("FixtureOriginDidNotStop")


def check(case, worker, exit_code, rows, dropped):
    if dropped or not worker.get("cleanShutdown") or not worker.get("nativeProcessIsolated"):
        raise RuntimeError("ControlCleanupOrAccountingFailed")
    witness = worker["providerDenialWitness"]
    if not witness["relayClosedBeforeDiagnostic"] or not witness["nativeCleanupPrecededDiagnostic"]:
        raise RuntimeError("ControlDiagnosticOrderingFailed")
    if case == "baseline":
        if exit_code or worker["result"] != "passed" or witness["result"] != "not_triggered" or witness["requests"]:
            raise RuntimeError("ControlBaselineFailed")
    else:
        if exit_code == 0 or worker["result"] != "failed" or worker["stage"] != "decode":
            raise RuntimeError("DenialDidNotRemainNativeFailure")
        if witness["track"] != case or witness["originalDenial"]["status"] != 403:
            raise RuntimeError("WrongDenialCaptured")
        diagnostic = witness["requests"]
        if witness["result"] != "completed" or [r.get("status") for r in diagnostic] != [403, 206, 403]:
            raise RuntimeError("ControlDiagnosticPatternFailed")
        if any(r["networkExchangeAttempts"] != 1 or r["bytesRead"] !=
               (1024 if r["method"] == "GET" and r["status"] == 206 else 0) for r in diagnostic):
            raise RuntimeError("ControlDiagnosticBoundFailed")
        if not any(r["status"] == 403 and r["track"] == case for r in rows):
            raise RuntimeError("MissingControlledDenial")
    if len(rows) != worker["relay"]["requests"] + len(witness["requests"]):
        raise RuntimeError("ControlRequestAccountingFailed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("java-home", "distribution", "fixture-directory", "output-directory"):
        parser.add_argument("--" + flag, type=Path, required=True)
    args = parser.parse_args()
    output = args.output_directory.resolve()
    if not output.is_relative_to(Path("D:/tmp/dd").resolve()):
        parser.error("Output must be inside D:/tmp/dd")
    fixtures = {"video": args.fixture_directory / "sync-video.mp4", "audio": args.fixture_directory / "sync-audio.m4a"}
    if not all(p.is_file() and p.stat().st_size > 1024 for p in fixtures.values()):
        parser.error("Paired synthetic fixtures required")
    output.mkdir(parents=True, exist_ok=False)
    cases = []
    for case in ("baseline", "video", "audio"):
        scratch = output / case
        scratch.mkdir()
        env = dict(os.environ, DD_PLAYER_SCRATCH=str(scratch), DD_PLAYER_NETWORK_FAULT="relay",
                   DD_PLAYER_NETWORK_DOWN_SECONDS="25", DD_PLAYER_PROVIDER_DENIAL_WITNESS="1",
                   DD_PLAYER_OBSERVE_SECONDS="20", DD_PLAYER_PROBE_TIMEOUT_SECONDS="100", DD_PLAYER_PROFILE="baseline")
        env.pop("DD_PLAYER_AUDIO_TIMELINE", None)
        command = [str(args.java_home / "bin/java.exe"), "--enable-native-access=ALL-UNNAMED", "-cp",
                   str(args.distribution / "lib/*"), "com.drivedeck.player.spike.DesktopSpike", "--fixture-http-paired"]
        with origin(fixtures, None if case == "baseline" else case) as server:
            base = f"http://127.0.0.1:{server.server_port}/"
            with (scratch / "supervisor.log").open("w", encoding="utf-8") as log:
                process = subprocess.Popen(command + [base + "video?c=VISIONOS", base + "audio?c=VISIONOS"],
                                           env=env, stdout=subprocess.PIPE, stderr=log)
                try:
                    stdout, _ = process.communicate(timeout=115)
                except subprocess.TimeoutExpired:
                    subprocess.run(["taskkill.exe", "/PID", str(process.pid), "/T", "/F"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10, check=False)
                    process.wait(timeout=5)
                    raise RuntimeError("NativeControlDeadline") from None
            worker = json.loads(stdout.decode("utf-8-sig"))
            (scratch / "worker.json").write_text(json.dumps(worker, indent=2), encoding="utf-8")
            # Server handlers finish before the origin closes; worker/relay accounting is already final.
        check(case, worker, process.returncode, server.rows, server.dropped)
        cases.append(dict(control=case, processExit=process.returncode, worker=worker,
                          originRequests=server.rows, droppedOriginRequests=server.dropped))
    result = dict(schemaVersion=1, result="passed", syntheticLoopbackOnly=True, nativePlaybackAttempted=True,
                  output="dummy_decode_only", realYouTube403Reproduced=False, providerRootCauseProven=False, cases=cases)
    text = json.dumps(result, indent=2)
    if "http://" in text or "127.0.0.1" in text:
        raise RuntimeError("PrivateFixtureAddressInReport")
    (output / "result.json").write_text(text, encoding="utf-8")
    print(json.dumps(dict(result="passed", cases=len(cases), realYouTube403Reproduced=False)))


if __name__ == "__main__":
    main()
