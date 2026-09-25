#!/usr/bin/env python3
"""Exercise Foundation transport against isolated loopback servers and synthetic credentials."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import os
import subprocess
import tempfile
import threading
import time


def main():
    servers = []
    redirected = []
    rejected = []

    def server(mode, token, redirect_port=None):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                if mode == "sink":
                    redirected.append(True)
                if (self.path != "/api/live?watch=1"
                        or self.headers.get("Authorization") != "Bearer " + token
                        or self.headers.get("Host") != f"127.0.0.1:{self.server.server_port}"
                        or self.headers.get("Accept") != "application/json"):
                    rejected.append(mode)
                    self.send_response(403)
                    self.end_headers()
                    return
                if mode == "redirect":
                    self.send_response(302)
                    self.send_header("Location", f"http://127.0.0.1:{redirect_port}/api/live?watch=1")
                    self.end_headers()
                    return
                if mode == "unauthorized":
                    self.send_response(403)
                    self.end_headers()
                    return
                if mode == "slow":
                    time.sleep(1)
                body = json.dumps({"schemaVersion": 2 if mode == "first" else 3,
                                   "sequence": mode, "status": "WARMING"}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        instance = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        servers.append(instance)
        threading.Thread(target=instance.serve_forever, daemon=True).start()
        return instance.server_port

    try:
        with tempfile.TemporaryDirectory(prefix="harmon-native-contract-") as directory:
            token_a, token_b = "a" * 64, "b" * 64
            sink = server("sink", token_a)
            for filename, mode, token in [("current", "first", token_a), ("rotated", "rotated", token_b),
                                           ("redirect", "redirect", token_a), ("unauthorized", "unauthorized", token_a),
                                           ("slow", "slow", token_a)]:
                port = server(mode, token, sink)
                path = Path(directory, filename + ".endpoint")
                path.write_text(f"port={port}\ntoken={token}\n")
                path.chmod(0o600)
            env = dict(os.environ, HARMON_NATIVE_TEST_ENDPOINTS=directory)
            subprocess.run(["./kotlin", "test", "--platform", "macosArm64", "-m", "harmon-native", "--include-test",
                            "io.heapy.kinetica.samples.harmon.LocalLiveSourceTest.foundationTransportContract"],
                           cwd=Path(__file__).resolve().parent.parent, env=env, check=True)
            assert not redirected, "The client followed an HTTP redirect"
            assert not rejected, f"Malformed local requests: {rejected}"
            print("Harmon transport passed: bearer auth, token/port rotation, errors, redirect rejection, cancellation")
    finally:
        for instance in servers:
            instance.shutdown()
            instance.server_close()


if __name__ == "__main__":
    main()
