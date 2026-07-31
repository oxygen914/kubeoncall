#!/usr/bin/env python3
"""Minimal local receiver used only by the alerting end-to-end acceptance script."""

import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def handler(log_path: str):
    class NotificationHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != "/health":
                self.send_error(404)
                return
            self._respond({"status": "up"})

        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length).decode("utf-8")
            try:
                payload = json.loads(body)
            except json.JSONDecodeError:
                payload = {"raw": body}
            if self.path == "/slow-rerank":
                time.sleep(2)
                self._respond({"results": [{"index": 0, "relevance_score": 0.9}]})
                return
            if self.path == "/embedding-dimension-mismatch":
                self._respond({"data": [{"embedding": [0.1, 0.2]}]})
                return
            with open(log_path, "a", encoding="utf-8") as stream:
                stream.write(json.dumps(payload, ensure_ascii=False) + "\n")
            self._respond({"status": "success", "deliveryMode": "e2e-test-receiver"})

        def log_message(self, format_, *args):
            return

        def _respond(self, payload):
            encoded = json.dumps(payload).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            try:
                self.wfile.write(encoded)
            except BrokenPipeError:
                pass

    return NotificationHandler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", default=18083, type=int)
    parser.add_argument("--log", required=True)
    args = parser.parse_args()
    ThreadingHTTPServer((args.host, args.port), handler(args.log)).serve_forever()


if __name__ == "__main__":
    main()
