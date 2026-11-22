#!/usr/bin/env python3
"""A pretend picture service for the tests of `draw` (android/assets/guest/kit).

It stands in for the two free services `draw` asks, on one port. The first word of the
address chooses how the service behaves, so one server plays every case:

  Pollinations (GET /HOW/prompt/WORDS?width=..&height=..&seed=..)
    /ok/...       a small JPEG (96x64)
    /png/...      a PNG of the size asked for: sky, sun and ground
    /busy/...     HTTP 402, "pay or wait", as the real one answers when it has drawn for this address lately
    /wait/...     HTTP 429 with Retry-After: 1 the first time, a picture the second time
    /html/...     HTTP 200 with a web page instead of a picture
    /down/...     HTTP 503

  AI Horde (POST /HOW/generate/async, GET /HOW/generate/check/ID, GET or DELETE /HOW/generate/status/ID)
    /horde/...    in the queue for two looks, then done: a PNG at an address of this server
    /b64/...      done at once, the picture inside the answer (base64)
    /censored/... done, but held back as not safe
    /nomodel/...  cannot be done while models are named; done when any model may draw
    /faulted/...  the worker failed
    /never/...    never done
    /down/...     HTTP 503

  fake_draw.py PORT_FILE [LOG_FILE] [ADDRESS TO LISTEN ON]

Every request is written to LOG_FILE: the method, the address, the key and agent it named, what was sent.
"""
import base64
import json
import os
import struct
import sys
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

PORT_FILE = sys.argv[1]
LOG_FILE = sys.argv[2] if len(sys.argv) > 2 else ""
ADDRESS = sys.argv[3] if len(sys.argv) > 3 else "127.0.0.1"

JPEG = base64.b64decode(
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDABQODxIPDRQSEBIXFRQYHjIhHhwcHj0sLiQySUBMS0dARkVQWnNiUFVtVkVGZIhlbXd7"
    "gYKBTmCNl4x9lnN+gXz/2wBDARUXFx4aHjshITt8U0ZTfHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8"
    "fHx8fHx8fHz/wAARCABAAGADASIAAhEBAxEB/8QAGAABAQEBAQAAAAAAAAAAAAAAAAQCAQX/xAAhEAEAAQQCAgMBAAAAAAAAAAAA"
    "AQIUY6EDEQQhEjFBgf/EABgBAQEBAQEAAAAAAAAAAAAAAAAEBQID/8QAIBEBAAIBAwUBAAAAAAAAAAAAAAIDARETUQQFEiEiMf/a"
    "AAwDAQACEQMRAD8A9ABeiAAAAAAdppmqeqY7lueHkiO/iq4uOOOnqPv9ltjWdyliekMelkemxp9Z9vOFHk8cR1XHrufadqU24uhi"
    "eEs4ZhLTIA9XIAAAAAC+iuK6YqhpDRXVxz3TLc+TXMfkfxh2dun5fH4uj1MdPr9b8quOooj777lM7Pue5ca1FOKa8Qwksn5y1BLe"
    "49l7j2b9fLhUJb3Hsvcezfr5FQlvcey9x7N+vkVCW9x7L3Hs36+RUJb3Hsvcezfr5FQlvcey9x7N+vkSgMwAAAAAAAAAAf/Z"
)
lock = threading.Lock()
looks = {}   # job id -> how often it was looked at
jobs = {}    # job id -> what was asked
asked = {}   # "wait" requests seen so far
count = [0]


def png(width, height):
    """Sky, a sun and ground, as PNG."""
    width, height = max(8, min(width, 1024)), max(8, min(height, 1024))
    sun_x, sun_y, sun_r = width * 0.72, height * 0.26, min(width, height) * 0.13
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        if y > height * 0.7:
            rows += bytes((70, 150, 70)) * width
            continue
        sky = bytes((110 + 60 * y // height, 170 + 40 * y // height, 240))
        line = bytearray(sky * width)
        reach = sun_r * sun_r - (y - sun_y) * (y - sun_y)
        if reach > 0:
            half = int(reach ** 0.5)
            a, b = max(0, int(sun_x) - half), min(width, int(sun_x) + half)
            line[a * 3:b * 3] = bytes((250, 220, 60)) * (b - a)
        rows += line

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(rows), 6)) + chunk(b"IEND", b""))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *args):
        pass

    def note(self, sent=""):
        if not LOG_FILE:
            return
        with lock, open(LOG_FILE, "a") as log:
            log.write("%s %s key=%s agent=%s %s\n" % (self.command, self.path, self.headers.get("apikey", "-"),
                                                      self.headers.get("Client-Agent", "-"), sent))

    def give(self, code, body, kind="application/json", more=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (more or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def parts(self):
        url = urlparse(self.path)
        bits = [b for b in url.path.split("/") if b]
        return (bits[0] if bits else ""), bits[1:], parse_qs(url.query)

    def do_GET(self):
        self.note()
        how, rest, query = self.parts()
        if rest[:1] == ["prompt"]:
            return self.pollinations(how, unquote("/".join(rest[1:])), query)
        if rest[:1] == ["img"]:
            w, h = (int(x) for x in rest[1].split(".")[0].split("x"))
            return self.give(200, png(w, h), "image/png")
        if rest[:2] == ["generate", "check"]:
            return self.check(how, rest[2])
        if rest[:2] == ["generate", "status"]:
            return self.status(how, rest[2])
        self.give(404, {"message": "no such address"})

    def do_DELETE(self):
        self.note()
        self.give(200, {"done": False, "generations": []})

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("content-length", 0)))
        self.note(raw.decode("utf-8", "replace"))
        how, rest, _ = self.parts()
        if rest[:2] != ["generate", "async"]:
            return self.give(404, {"message": "no such address"})
        if how == "down":
            return self.give(503, {"message": "The horde is in maintenance mode."})
        if self.headers.get("apikey") != "0000000000" or not self.headers.get("Client-Agent"):
            return self.give(401, {"message": "No valid API key and Client-Agent given"})
        try:
            body = json.loads(raw or b"{}")
        except ValueError:
            return self.give(400, {"message": "not JSON"})
        size = body.get("params", {})
        if max(size.get("width", 512), size.get("height", 512)) > 576:
            return self.give(403, {"message": "This request requires kudos upfront", "rc": "KudosUpfront"})
        with lock:
            count[0] += 1
            job = "job%d" % count[0]
            jobs[job] = body
            looks[job] = 0
        self.give(202, {"id": job, "kudos": 10.0, "message": ""})

    def pollinations(self, how, words, query):
        w = int(query.get("width", ["768"])[0])
        h = int(query.get("height", ["768"])[0])
        if how == "ok":
            return self.give(200, JPEG, "image/jpeg")
        if how == "png":
            return self.give(200, png(w, h), "image/png")
        if how == "wait":
            with lock:
                asked[words] = asked.get(words, 0) + 1
                first = asked[words] == 1
            if first:
                return self.give(429, {"error": "Too Many Requests"}, more={"Retry-After": "1"})
            return self.give(200, png(w, h), "image/png")
        if how == "html":
            return self.give(200, b"<html><body>Sign in to continue</body></html>", "text/html")
        if how == "down":
            return self.give(503, b"<html>Service Unavailable</html>", "text/html")
        self.give(402, {"error": "Payment Required", "message": "Pay 0.01 USDC per image, or wait."})

    def check(self, how, job):
        with lock:
            looks[job] = looks.get(job, 0) + 1
            n = looks[job]
            body = jobs.get(job, {})
        state = {"finished": 0, "processing": 0, "waiting": 1, "done": False, "faulted": False,
                 "wait_time": 6, "queue_position": 3, "is_possible": True}
        if how == "faulted":
            state["faulted"] = True
        elif how == "nomodel" and body.get("models"):
            state["is_possible"] = False
        elif how == "never":
            pass
        elif how in ("b64", "censored", "nomodel") or n >= 3:
            state.update(done=True, finished=1, waiting=0, wait_time=0, queue_position=0)
        self.give(200, state)

    def status(self, how, job):
        body = jobs.get(job, {})
        size = body.get("params", {})
        w, h = size.get("width", 512), size.get("height", 512)
        made = {"worker_name": "pretend", "model": (body.get("models") or ["any"])[0], "state": "ok",
                "seed": str(size.get("seed", "")), "censored": how == "censored", "id": job + "-1",
                "img": "http://%s/%s/img/%dx%d.png" % (self.headers.get("Host"), how, w, h)}
        if how == "b64":
            made["img"] = base64.b64encode(png(w, h)).decode()
        self.give(200, {"done": True, "faulted": False, "generations": [made]})


server = ThreadingHTTPServer((ADDRESS, 0), Handler)
with open(PORT_FILE + ".new", "w") as f:
    f.write(str(server.server_address[1]))
os.replace(PORT_FILE + ".new", PORT_FILE)
server.serve_forever()
