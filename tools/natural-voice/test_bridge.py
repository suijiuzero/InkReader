import http.client
import json
import threading
import unittest

from bridge import BridgeServer, private_peer


class FakeWorker:
    def synthesize(self, text, rate, pitch):
        return b"RIFF" + bytes(4) + b"WAVE" + bytes(40)


class BridgeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = BridgeServer(("127.0.0.1", 0), {"key": "0123456789ABCDEF", "id": "a" * 32}, FakeWorker())
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def request(self, path, body=None, key="0123456789ABCDEF"):
        client = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        try:
            client.request("POST" if body is not None else "GET", path, body=body,
                           headers={"Authorization": "Bearer " + key})
            result = client.getresponse()
            return result.status, result.read()
        finally:
            client.close()

    def test_pairing_required(self):
        for path, body in [("/v1/health", None), ("/v1/speak", '{"text":"hello"}')]:
            self.assertEqual(self.request(path, body, "wrong")[0], 403)

    def test_health_identity(self):
        status, body = self.request("/v1/health")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["id"], "a" * 32)
        self.assertNotIn(b"0123456789ABCDEF", body)

    def test_invalid_payloads(self):
        for body in ["{", "[]", '{"text":null}', '{"text":""}', json.dumps({"text": "x" * 1201}),
                     json.dumps({"text": "hello", "rate": 99}), '{"text":"hi","pitch":NaN}']:
            self.assertEqual(self.request("/v1/speak", body)[0], 400, body[:80])

    def test_audio(self):
        status, body = self.request("/v1/speak", json.dumps({"text": "hello", "rate": 1.2, "pitch": 1}))
        self.assertEqual(status, 200)
        self.assertTrue(body.startswith(b"RIFF"))

    def test_private_networks_only(self):
        for host in ["127.0.0.1", "10.1.1.2", "192.168.2.3", "172.31.1.2"]:
            self.assertTrue(private_peer(host))
        for host in ["8.8.8.8", "172.32.1.2", "169.254.1.1", "example.com", "::1"]:
            self.assertFalse(private_peer(host))


if __name__ == "__main__":
    unittest.main()
