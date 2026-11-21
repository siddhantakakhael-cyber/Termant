#!/usr/bin/env python3
"""A pretend chat model for the tests of the helper agents (OpenAI chat protocol, streamed).

Asked "TEAM ...", it starts the researcher and the helper "poet" in one step; the researcher
runs one command and reports, the poet reports; then the main agent's answer repeats what
the helpers reported. Asked "RUN command", the main agent runs that command and answers with
what it printed. Asked "LOOK ...", it answers how many pictures and other files came with
the message. OpenCode itself is the real one; only the model is this stand-in.

  fake_model.py PORT_FILE [LOG_FILE] [COMMAND THE RESEARCHER RUNS] [ADDRESS TO LISTEN ON]
"""
import json
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT_FILE = sys.argv[1]
LOG_FILE = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] else None
COMMAND = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] else "echo helper-ran > helper-ran.txt"
ADDRESS = sys.argv[4] if len(sys.argv) > 4 else "127.0.0.1"
lock = threading.Lock()
count = [0]


def text_of(message):
    content = message.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return " ".join(p.get("text", "") for p in content if isinstance(p, dict))
    return ""


def answer(body):
    messages = body.get("messages", [])
    system = " ".join(text_of(m) for m in messages if m.get("role") == "system")
    tools = [t.get("function", {}).get("name") for t in body.get("tools", [])]
    users = [text_of(m) for m in messages if m.get("role") == "user"]
    results = [m for m in messages if m.get("role") == "tool"]
    if "researcher of Termant" in system:
        shell = next((name for name in ("shell", "bash") if name in tools), None)
        if not results and shell:
            return {"who": "researcher", "wait": 1.5, "calls": [(shell, {"command": COMMAND})]}
        return {"who": "researcher", "text": "RESEARCH REPORT: chai is tea."}
    if "POET-MARK" in system:
        return {"who": "poet", "wait": 1.5, "text": "POEM REPORT: garam chai, meethi baat."}
    if not tools:
        return {"who": "title", "text": "Helpers"}
    reports = [u for u in users if "<subagent " in u]
    if reports:
        said = [re.sub(r"\s+", " ", re.sub(r"</?subagent[^>]*>", " ", r)).strip() for r in reports]
        return {"who": "main", "text": "TEAM RESULT (%d): %s" % (len(reports), " | ".join(said))}
    asked = users[-1] if users else ""
    wanted = re.search(r"^RUN (.+)$", asked, re.M)
    if wanted:
        shell = next((name for name in ("shell", "bash") if name in tools), None)
        if not results and shell:
            return {"who": "main", "calls": [(shell, {"command": wanted.group(1).strip()})]}
        return {"who": "main", "text": "RAN: " + " ".join(text_of(m) for m in results)[:1500]}
    if "LOOK" in asked:
        last = [m for m in messages if m.get("role") == "user"][-1].get("content")
        parts = last if isinstance(last, list) else []
        return {"who": "main", "text": "SAW pictures=%d files=%d" % (
            sum(1 for p in parts if isinstance(p, dict) and p.get("type") == "image_url"),
            sum(1 for p in parts if isinstance(p, dict) and p.get("type") == "file"))}
    if results:
        return {"who": "main", "text": "I started two helpers."}
    if "subagent" in tools and any("TEAM" in u for u in users):
        return {"who": "main", "calls": [
            ("subagent", {"agent": "researcher", "description": "Find out about chai",
                          "prompt": "What is chai? Run your one command, then report in one line."}),
            ("subagent", {"agent": "poet", "description": "A poem about chai", "prompt": "Write one line about chai."}),
        ]}
    return {"who": "main", "text": "plain answer"}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"data":[{"id":"fake","object":"model"}]}')

    def do_POST(self):
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("content-length", 0))) or b"{}")
        except ValueError:
            body = {}
        a = answer(body)
        with lock:
            count[0] += 1
            number = count[0]
            if LOG_FILE:
                with open(LOG_FILE, "a") as log:
                    log.write("%.3f %s %s\n" % (time.time(), a["who"], a.get("text") or "calls " + " ".join(c[0] for c in a["calls"])))
        time.sleep(a.get("wait", 0))
        self.send_response(200)
        self.send_header("content-type", "text/event-stream")
        self.end_headers()

        def send(delta, finish=None):
            chunk = {"id": "c1", "object": "chat.completion.chunk", "created": 1, "model": "fake",
                     "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
            self.wfile.write(("data: " + json.dumps(chunk) + "\n\n").encode())
            self.wfile.flush()

        send({"role": "assistant", "content": ""})
        if "calls" in a:
            for index, (name, args) in enumerate(a["calls"]):
                send({"tool_calls": [{"index": index, "id": "call_%d_%d" % (number, index), "type": "function",
                                      "function": {"name": name, "arguments": json.dumps(args)}}]})
            send({}, "tool_calls")
        else:
            send({"content": a["text"]})
            send({}, "stop")
        usage = {"id": "c1", "object": "chat.completion.chunk", "created": 1, "model": "fake", "choices": [],
                 "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}}
        self.wfile.write(("data: " + json.dumps(usage) + "\n\ndata: [DONE]\n\n").encode())
        self.wfile.flush()


server = ThreadingHTTPServer((ADDRESS, 0), Handler)
server.daemon_threads = True
with open(PORT_FILE, "w") as f:
    f.write(str(server.server_address[1]))
server.serve_forever()
