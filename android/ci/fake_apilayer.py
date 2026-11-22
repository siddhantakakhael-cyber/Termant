#!/usr/bin/env python3
"""A pretend APILayer on one port, for the checks of `apilayer` (android/assets/guest/kit).

  fake_apilayer.py PORT_FILE [LOG_FILE] [ADDRESS] [PAGES_FOLDER]

Point the tool at it with
  TERMANT_APILAYER_MARKET=http://ADDRESS:PORT   the marketplace: the list of APIs, an API's page and documentation
  TERMANT_APILAYER=http://ADDRESS:PORT/gw       the gateway that the key opens
Put /down in front of either path (http://ADDRESS:PORT/down, .../down/gw) for a service that answers HTTP 503.

The marketplace's pages are real ones, saved from marketplace.apilayer.com on 2026-11-22 and kept in
android/ci/apilayer-pages/ (the stars, styles and drawings taken out, the list cut to 32 of its 178 APIs,
each API's page cut to its title, plans and description):
  /get-api-data?q=WORDS            list.html, only the cards with these words when q is given
  /NAME-api                        NAME.page.html; for a name with no such file, none.page.html,
                                   as the real site shows its front page for an unknown name
  /NAME-api/tabs/api_docs          NAME.docs.html, or nothing at all (HTTP 200, empty), as the real site
A caller that names itself Python-urllib gets HTTP 403 "error code: 1010", as from the real site.

The gateway answers as the real one did without a key and with a wrong one (also 2026-11-22):
  no apikey header                 401 {"message":"No API key found in request"}
  a key not listed below           401 {"message":"Invalid authentication credentials"}
  an API not listed below          404 {"message":"no Route matched with those values"}
What it says to a key that works is made up here to have something to check; nobody at Termant has
called the real gateway with a real key:
  key goodkey0123456789goodkey01234567      answers, and counts down x-ratelimit-remaining-month from 100
  key nosubscription0123456789abcdefgh      403 {"message":"You cannot consume this service"}
  key usedup0123456789usedup0123456789      429, the sentence APILayer's documentation gives
  GET  /gw/exchangerates_data/latest        rates; without base: 400 with a message
  GET  /gw/exchangerates_data/symbols       a long answer (the tool cuts it)
  GET  /gw/screenshot?url=...               a PNG picture
  GET  /gw/url_to_pdf?url=...               a PDF
  POST /gw/image_to_text/upload             says how many bytes of which kind arrived
  anything under /gw/echo/                  says back the method, path, query, body and headers it got
Each request is written to LOG_FILE as: METHOD PATH key=... type=... bytes=N
"""
import json
import os
import re
import struct
import sys
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

PORT_FILE = sys.argv[1]
LOG_FILE = sys.argv[2] if len(sys.argv) > 2 else None
ADDRESS = sys.argv[3] if len(sys.argv) > 3 else "127.0.0.1"
PAGES = sys.argv[4] if len(sys.argv) > 4 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "apilayer-pages")
GOOD, NO_SUBSCRIPTION, USED_UP = "goodkey0123456789goodkey01234567", "nosubscription0123456789abcdefgh", "usedup0123456789usedup0123456789"
SERVICES = ("exchangerates_data", "screenshot", "url_to_pdf", "image_to_text", "echo")
left = {"month": 100}


def png(width, height, colour):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    row = b"\x00" + bytes(colour) * width
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(row * height)) + chunk(b"IEND", b""))


def page(name):
    try:
        with open(os.path.join(PAGES, name), "rb") as f:
            return f.read()
    except OSError:
        return None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *args):
        pass

    def send(self, code, body, kind="application/json; charset=utf-8", headers=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def answer(self):
        url = urlsplit(self.path)
        path, query = url.path, parse_qs(url.query, keep_blank_values=True)
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        if LOG_FILE:
            with open(LOG_FILE, "a") as f:
                f.write("%s %s key=%s type=%s bytes=%d\n" % (self.command, self.path, self.headers.get("apikey", "-"),
                                                            self.headers.get("Content-Type", "-"), len(body)))
        if path.startswith("/down/") or path == "/down":
            return self.send(503, "<html><body>503 Service Temporarily Unavailable</body></html>", "text/html")
        if path.startswith("/gw/") or path == "/gw":
            return self.gateway(path[3:], query, body)
        if self.headers.get("User-Agent", "").startswith("Python-urllib"):
            return self.send(403, "error code: 1010\n", "text/plain")
        if path == "/get-api-data":
            words = " ".join(query.get("q", [""])).lower().split()
            whole = (page("list.html") or b"").decode("utf-8")
            parts = re.split(r"(?=<!-- Card: API)", whole)
            head, cards = parts[0], parts[1:]
            tail = ""
            if cards:
                end = cards[-1].rfind("<!-- End Card -->") + len("<!-- End Card -->")
                cards[-1], tail = cards[-1][:end], cards[-1][end:]
            kept = [c for c in cards if all(w in re.sub(r"<[^>]+>", " ", c).lower() for w in words)]
            return self.send(200, head + "".join(kept) + tail, "text/html; charset=utf-8")
        found = re.fullmatch(r"/([A-Za-z0-9_]+)-api(/tabs/api_docs)?", path)
        if found and found.group(2):
            return self.send(200, page(found.group(1) + ".docs.html") or b"", "text/html; charset=utf-8")
        return self.send(200, (found and page(found.group(1) + ".page.html")) or page("none.page.html") or b"",
                         "text/html; charset=utf-8")

    def gateway(self, path, query, body):
        service = path.strip("/").split("/")[0]
        rest = path.strip("/")[len(service):]
        if service not in SERVICES:
            return self.send(404, {"message": "no Route matched with those values"})
        key = self.headers.get("apikey")
        if not key:
            return self.send(401, {"message": "No API key found in request"})
        if key == NO_SUBSCRIPTION:
            return self.send(403, {"message": "You cannot consume this service"})
        if key == USED_UP:
            return self.send(429, {"message": "You have exceeded your daily/monthly API rate limit. Please review and upgrade "
                                              "your subscription plan at https://apilayer.com/subscriptions to continue."},
                             headers={"x-ratelimit-limit-month": "100", "x-ratelimit-remaining-month": "0"})
        if key != GOOD:
            return self.send(401, {"message": "Invalid authentication credentials"})
        left["month"] -= 1
        limits = {"x-ratelimit-limit-month": "100", "x-ratelimit-remaining-month": str(left["month"]),
                  "x-ratelimit-limit-day": "100", "x-ratelimit-remaining-day": str(left["month"])}
        if service == "exchangerates_data" and rest == "/latest":
            if "base" not in query:
                return self.send(400, {"message": "The base currency is missing."}, headers=limits)
            wanted = query.get("symbols", ["INR,EUR"])[0].split(",")
            rates = {"INR": 83.512345, "EUR": 0.9213, "GBP": 0.7901, "JPY": 151.23}
            return self.send(200, {"base": query["base"][0], "date": "2026-11-22", "rates": {s: rates.get(s, 1.0) for s in wanted},
                                   "success": True, "timestamp": 1795320000}, headers=limits)
        if service == "exchangerates_data" and rest == "/symbols":
            return self.send(200, {"success": True, "symbols": {"C%03d" % i: "The money of country number %d" % i for i in range(400)}},
                             headers=limits)
        if service == "screenshot":
            return self.send(200, png(64, 40, (40, 120, 200)), "image/png", limits)
        if service == "url_to_pdf":
            return self.send(200, b"%PDF-1.4\n% a pretend page\n%%EOF\n", "application/pdf", limits)
        if service == "image_to_text" and rest == "/upload" and self.command == "POST":
            return self.send(200, {"lang": "en", "all_text": "HELLO FROM THE PICTURE", "got_bytes": len(body),
                                   "got_type": self.headers.get("Content-Type", "")}, headers=limits)
        if service == "echo":
            return self.send(200, {"method": self.command, "path": rest, "query": {k: v[0] for k, v in query.items()},
                                   "type": self.headers.get("Content-Type", ""), "body": body.decode("utf-8", "replace"),
                                   "encryption_key": self.headers.get("encryption_key", ""),
                                   "agent": self.headers.get("User-Agent", "")}, headers=limits)
        return self.send(404, {"message": "no Route matched with those values"})

    do_GET = do_POST = do_PUT = do_DELETE = answer


server = ThreadingHTTPServer((ADDRESS, 0), Handler)
with open(PORT_FILE + ".new", "w") as f:
    f.write(str(server.server_address[1]))
os.replace(PORT_FILE + ".new", PORT_FILE)
server.serve_forever()
