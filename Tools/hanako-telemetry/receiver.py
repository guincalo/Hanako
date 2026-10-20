#!/usr/bin/env python3
"""Hanako telemetry receiver: appends POSTed JSON to ~/.local/share/hanako-telemetry/YYYY-MM-DD.jsonl.

Python stdlib only. Listens on 127.0.0.1 (HANAKO_TELEMETRY_ADDR / _PORT); put TLS in front of it.
Every request needs the header X-Hanako-Key equal to the secret in
~/.config/hanako-telemetry/secret (one line, mode 600). Bodies over 256 KiB, non-JSON bodies and
unknown schemas are refused. Only known fields are stored; the client's IP is never logged or
stored. A day file stops growing at 20 MiB and the directory at 200 MiB.
"""
import hmac
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = os.path.expanduser("~")
DATA = os.environ.get("HANAKO_TELEMETRY_DATA", os.path.join(HOME, ".local/share/hanako-telemetry"))
SECRET_FILE = os.environ.get("HANAKO_TELEMETRY_SECRET_FILE", os.path.join(HOME, ".config/hanako-telemetry/secret"))
ADDR = os.environ.get("HANAKO_TELEMETRY_ADDR", "127.0.0.1")
PORT = int(os.environ.get("HANAKO_TELEMETRY_PORT", "2590"))
PATHS = ("/", "/hanako-telemetry", "/hanako-telemetry/")
MAX_BODY = 256 * 1024
MAX_DAY_FILE = 20 * 1024 * 1024
MAX_DIR = 200 * 1024 * 1024

TOP_FIELDS = ("schema", "install_id", "app_version", "android", "device", "sent_day", "days", "errors")
ERROR_FIELDS = ("date", "kind", "class", "message", "stack", "app_version", "android", "device", "count")

lock = threading.Lock()


def read_secret():
    with open(SECRET_FILE, encoding="utf-8") as f:
        s = f.read().strip()
    if len(s) < 16:
        raise SystemExit("secret too short (need 16+ chars) in " + SECRET_FILE)
    return s.encode()


SECRET = read_secret()


def short(v, n):
    return v[:n] if isinstance(v, str) else ""


def clean(p):
    """Known fields only, with sane types and sizes; None if it isn't a Hanako payload."""
    if not isinstance(p, dict) or p.get("schema") != 1:
        return None
    out = {k: p[k] for k in TOP_FIELDS if k in p}
    for k in ("install_id", "app_version", "android", "device", "sent_day"):
        out[k] = short(out.get(k), 80)
    days = []
    for d in out.get("days") or []:
        if not isinstance(d, dict) or not isinstance(d.get("counts"), dict):
            continue
        counts = {short(k, 64): v for k, v in d["counts"].items()
                  if isinstance(v, int) and 0 <= v < 10**7}
        days.append({"date": short(d.get("date"), 10), "counts": counts})
    out["days"] = days[:60]
    errors = []
    for e in out.get("errors") or []:
        if not isinstance(e, dict):
            continue
        e = {k: e[k] for k in ERROR_FIELDS if k in e}
        e["stack"] = [short(s, 300) for s in (e.get("stack") or [])][:40]
        for k in ("date", "kind", "class", "message", "app_version", "android", "device"):
            e[k] = short(e.get(k), 400)
        errors.append(e)
    out["errors"] = errors[:100]
    return out


def dir_size():
    total = 0
    for name in os.listdir(DATA):
        try:
            total += os.path.getsize(os.path.join(DATA, name))
        except OSError:
            pass
    return total


class Handler(BaseHTTPRequestHandler):
    server_version = "hanako-telemetry"
    sys_version = ""

    def log_message(self, fmt, *args):
        # no client address: only the request line and the status
        sys.stderr.write("%s %s\n" % (time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), fmt % args))

    def reply(self, code, text):
        body = (text + "\n").encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.reply(405, "POST only")

    def do_POST(self):
        if self.path not in PATHS:
            return self.reply(404, "not found")
        key = (self.headers.get("X-Hanako-Key") or "").encode()
        if not hmac.compare_digest(key, SECRET):
            return self.reply(401, "bad key")
        try:
            length = int(self.headers.get("Content-Length") or "-1")
        except ValueError:
            length = -1
        if length < 0:
            return self.reply(411, "length required")
        if length > MAX_BODY:
            return self.reply(413, "too large")
        try:
            payload = clean(json.loads(self.rfile.read(length).decode("utf-8")))
        except (ValueError, UnicodeDecodeError):
            payload = None
        if payload is None:
            return self.reply(400, "not a Hanako payload")
        payload["received"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        line = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n"
        path = os.path.join(DATA, time.strftime("%Y-%m-%d", time.gmtime()) + ".jsonl")
        with lock:
            if dir_size() + len(line) > MAX_DIR:
                return self.reply(507, "storage full")
            if os.path.exists(path) and os.path.getsize(path) + len(line) > MAX_DAY_FILE:
                return self.reply(429, "day limit reached")
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
            with os.fdopen(fd, "a", encoding="utf-8") as f:
                f.write(line)
        self.reply(200, "ok")


def main():
    os.makedirs(DATA, mode=0o700, exist_ok=True)
    ThreadingHTTPServer.daemon_threads = True
    srv = ThreadingHTTPServer((ADDR, PORT), Handler)
    srv.timeout = 10
    sys.stderr.write("hanako-telemetry listening on %s:%d, data in %s\n" % (ADDR, PORT, DATA))
    srv.serve_forever()


if __name__ == "__main__":
    main()
