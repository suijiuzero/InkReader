"""InkReader LAN bridge. Python 3.10+, Windows SAPI5; no third party packages."""
import base64
import hmac
import ipaddress
import json
import math
import os
from pathlib import Path
import queue
import re
import secrets
import socket
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parent
HTTP_PORT = 37262
DISCOVERY_PORT = 37261
MARKER = "INKREADER_VOICE_V1"
MAX_TEXT = 1200
LANGUAGES = ("auto", "zh-CN", "en-US", "ja-JP")
MODELS = ("auto", "xiaoxiao", "nanami")
KANA = re.compile(r"[\u3041-\u3096\u309d\u309e\u30a1-\u30fa\u30fd\u30fe\uff66-\uff9d]")


def speech_language(text, hint="auto"):
    # Kana wins over a stale Chinese hint; chapter hints cover Kanji-only sentences.
    return "ja-JP" if KANA.search(text) else ("zh-CN" if hint == "auto" else hint)


def private_peer(host):
    try:
        addr = ipaddress.ip_address(host)
        return addr.version == 4 and (addr.is_loopback or any(addr in net for net in (
            ipaddress.ip_network("10.0.0.0/8"), ipaddress.ip_network("172.16.0.0/12"),
            ipaddress.ip_network("192.168.0.0/16"))))
    except ValueError:
        return False


def load_config():
    folder = ROOT / ".runtime"
    folder.mkdir(exist_ok=True)
    path = folder / "config.json"
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    config = {"id": secrets.token_hex(16), "key": secrets.token_hex(8).upper()}
    with path.open("x", encoding="utf-8") as stream:
        json.dump(config, stream)
    return config


class SapiWorker:
    def __init__(self):
        self.lock = threading.Lock()
        self.process = None
        self.languages = ["zh-CN", "en-US"]
        self.models = ["auto", "xiaoxiao"]
        self.last_voice = ""

    def close(self):
        if self.process:
            self.process.kill()
            self.process.wait(timeout=5)
            self.process = None

    def _start(self):
        self.close()
        self.process = subprocess.Popen(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
             "-File", str(ROOT / "SapiWorker.ps1")], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, encoding="utf-8",
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        self.lines = queue.Queue()
        output = self.process.stdout
        lines = self.lines

        def read():
            for line in output:
                lines.put(line)
            lines.put("")
        threading.Thread(target=read, daemon=True).start()

    def synthesize(self, text, rate, pitch, language="auto", model="auto"):
        if not self.lock.acquire(timeout=2):
            raise TimeoutError("语音服务忙，请稍后重试")
        try:
            if self.process is None or self.process.poll() is not None:
                self._start()
            self.process.stdin.write(json.dumps({"text": text, "rate": rate, "pitch": pitch,
                                                 "language": speech_language(text, language), "model": model}) + "\n")
            self.process.stdin.flush()
            try:
                result = json.loads(self.lines.get(timeout=45))
                if not result.get("ok"):
                    raise RuntimeError("本地晓晓不可用，请检查 NaturalVoiceSAPIAdapter")
                self.languages = result.get("languages", self.languages)
                self.models = result.get("models", self.models)
                self.last_voice = result.get("voice", "")
                audio = base64.b64decode(result["audio"], validate=True)
                if not 44 < len(audio) <= 12_000_000 or audio[:4] != b"RIFF":
                    raise ValueError("无效音频")
                return audio
            except Exception:
                self.close()
                raise
        finally:
            self.lock.release()


class BridgeServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 8

    def __init__(self, address, config, worker):
        self.config = config
        self.worker = worker
        self.slots = threading.BoundedSemaphore(8)
        super().__init__(address, Handler)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    server_version = "InkReaderVoice/1"

    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def log_message(self, *_):
        pass  # No book contents, credentials, or request paths in logs.

    def reply(self, status, data, mime="application/json; charset=utf-8"):
        if not isinstance(data, bytes):
            data = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)

    def authorized(self):
        return private_peer(self.client_address[0]) and hmac.compare_digest(
            self.headers.get("Authorization", ""), "Bearer " + self.server.config["key"])

    def do_GET(self):
        if self.path == "/" and ipaddress.ip_address(self.client_address[0]).is_loopback and self.headers.get("Host", "") in ("127.0.0.1:37262", "localhost:37262"):
            key = self.server.config["key"]
            html = ("<!doctype html><meta charset=utf-8><title>墨阅 · 电脑晓晓</title>"
                    "<style>body{max-width:680px;margin:70px auto;padding:24px;background:#f8f5ef;"
                    "color:#302e29;font:18px/1.8 system-ui}code{font-size:28px;letter-spacing:3px}"
                    "</style><h1>墨阅 · 电脑晓晓</h1><p>电脑语音服务已启动。</p>"
                    f"<p>配对码：<br><code>{key}</code></p>"
                    "<p>手机与电脑连接同一个 Wi-Fi，在墨阅的听书面板里选择「连接电脑晓晓」，"
                    "输入上面的配对码。以后听书会自动连接，电脑不在线时使用手机语音。</p>"
                    "<p>若自动查找失败，可填电脑的局域网 IPv4 地址。电脑需保持唤醒；"
                    "仅在可信的家庭局域网使用。文字只发送给配对电脑，语音临时文件播放后删除。</p>")
            self.reply(200, html.encode("utf-8"), "text/html; charset=utf-8")
        elif self.path == "/v1/health" and self.authorized():
            self.reply(200, {"service": MARKER, "id": self.server.config["id"], "voice": "电脑语音",
                             "languages": getattr(self.server.worker, "languages", ["zh-CN", "en-US"]),
                             "models": getattr(self.server.worker, "models", ["auto"])})
        else:
            self.reply(403, {"error": "需要配对"})

    def do_POST(self):
        if self.path != "/v1/speak" or not self.authorized():
            self.reply(403, {"error": "需要配对"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 16000:
                raise ValueError()
            body = json.loads(self.rfile.read(length))
            text = body["text"]
            language = body.get("language", "auto")
            model = body.get("model", "auto")
            if language not in LANGUAGES:
                raise ValueError()
            if model not in MODELS:
                raise ValueError()
            rate, pitch = float(body.get("rate", 1)), float(body.get("pitch", 1))
            if not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT:
                raise ValueError()
            if not math.isfinite(rate) or not math.isfinite(pitch) or not .5 <= rate <= 2.5 or not .5 <= pitch <= 2:
                raise ValueError()
        except (ValueError, KeyError, TypeError):
            self.reply(400, {"error": "无效朗读请求"})
            return
        try:
            self.reply(200, self.server.worker.synthesize(text, rate, pitch, speech_language(text, language), model), "audio/wav")
        except Exception:
            self.reply(503, {"error": "电脑语音暂不可用"})


def discover(config, stop):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
        udp.bind(("0.0.0.0", DISCOVERY_PORT))
        udp.settimeout(1)
        while not stop.is_set():
            try:
                data, peer = udp.recvfrom(1024)
                request = json.loads(data)
                if not isinstance(request, dict):
                    continue
                nonce = request.get("nonce", "")
                if not private_peer(peer[0]) or request.get("service") != MARKER or not isinstance(nonce, str) or len(nonce) != 32:
                    continue
                reply = {"service": MARKER, "nonce": nonce, "id": config["id"], "port": HTTP_PORT}
                udp.sendto(json.dumps(reply).encode(), peer)
            except (socket.timeout, ValueError, UnicodeError):
                continue


def main():
    config = load_config()
    worker = SapiWorker()
    # Verify the exact local voice before advertising. No silent use of an online token.
    worker.synthesize("墨阅电脑语音已就绪。", 1, 1)
    server = BridgeServer(("0.0.0.0", HTTP_PORT), config, worker)
    stop = threading.Event()
    threading.Thread(target=discover, args=(config, stop), daemon=True).start()
    try:
        server.serve_forever()
    finally:
        stop.set()
        server.server_close()
        worker.close()


if __name__ == "__main__":
    main()
