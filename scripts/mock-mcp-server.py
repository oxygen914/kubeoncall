#!/usr/bin/env python3
"""Authenticated MCP discovery/invocation server used by the dynamic MCP acceptance test."""

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def handler(api_key: str, log_path: str):
    class McpHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != "/health":
                self.send_error(404)
                return
            self._respond(200, {"status": "up"})

        def do_POST(self):
            if self.headers.get("Authorization") != f"Bearer {api_key}":
                self._respond(401, {"error": "unauthorized"})
                return
            payload = self._read_json()
            self._record({"path": self.path, "request": payload})
            if self.path == "/tools/list":
                self._respond(
                    200,
                    {
                        "tools": [
                            {
                                "name": "inventory.lookupOwner",
                                "description": "Lookup service ownership metadata",
                                "readOnly": True,
                                "requiresApproval": False,
                                "requiredParameters": ["serviceName"],
                                "targetSystems": ["inventory"],
                            }
                        ]
                    },
                )
                return
            if self.path == "/mcp/call":
                tool_name = payload.get("toolName")
                parameters = payload.get("payload") or {}
                if tool_name == "inventory.lookupOwner":
                    self._respond(
                        200,
                        {
                            "owner": "payments-platform",
                            "service": parameters.get("serviceName"),
                            "source": "dynamic-mcp-e2e",
                        },
                    )
                    return
                self._respond(200, {"tool": tool_name, "source": "fixed-evidence-mock"})
                return
            self._respond(404, {"error": "not_found"})

        def _read_json(self):
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length).decode("utf-8")
            try:
                return json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                return {"raw": raw}

        def _record(self, payload):
            with open(log_path, "a", encoding="utf-8") as stream:
                stream.write(json.dumps(payload, ensure_ascii=False) + "\n")

        def _respond(self, status, payload):
            encoded = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def log_message(self, format_, *args):
            return

    return McpHandler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", default=18089, type=int)
    parser.add_argument("--api-key", default="mcp-e2e-token")
    parser.add_argument("--log", required=True)
    args = parser.parse_args()
    ThreadingHTTPServer((args.host, args.port), handler(args.api_key, args.log)).serve_forever()


if __name__ == "__main__":
    main()
