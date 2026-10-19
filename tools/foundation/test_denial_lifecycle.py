import http.client
from pathlib import Path
import tempfile
import unittest
from denial_lifecycle import origin

class DenialOriginTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(dir="D:/tmp/dd")
        self.media = Path(self.scratch.name) / "synthetic.bin"
        self.media.write_bytes(bytes(range(256)) * 16)
        self.fixtures = {"video": self.media, "audio": self.media}

    def tearDown(self):
        self.scratch.cleanup()

    def fetch(self, server, method, target, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
        try:
            connection.request(method, target, headers=headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_ranges_head_and_invalid_ranges_match_a_real_seekable_origin(self):
        with origin(self.fixtures) as server:
            status, headers, body = self.fetch(server, "GET", "/video", {"Range": "bytes=4000-9999"})
            self.assertEqual((206, "bytes 4000-4095/4096", self.media.read_bytes()[4000:]),
                             (status, headers["Content-Range"], body))
            status, headers, body = self.fetch(server, "HEAD", "/audio")
            self.assertEqual((200, "4096", b""), (status, headers["Content-Length"], body))
            for value in ("bytes=4096-", "bytes=20-10", "bytes=1-2,3-4", "invalid"):
                status, _, body = self.fetch(server, "GET", "/video", {"Range": value})
                self.assertEqual((416, b""), (status, body))

    def test_denial_targets_one_track_and_only_the_explicit_control_header(self):
        with origin(self.fixtures, "audio") as server:
            self.assertEqual(200, self.fetch(server, "GET", "/video")[0])
            for method in ("GET", "HEAD"):
                self.assertEqual(403, self.fetch(server, method, "/audio?c=VISIONOS")[0])
                headers = {"User-Agent": "com.google.visionos.youtube/control", "Range": "bytes=4-1027"}
                status, _, body = self.fetch(server, method, "/audio?c=VISIONOS", headers)
                self.assertEqual(206, status)
                self.assertEqual(1024 if method == "GET" else 0, len(body))
            self.assertNotIn("http://", str(server.rows))
            self.assertEqual(0, server.dropped)
