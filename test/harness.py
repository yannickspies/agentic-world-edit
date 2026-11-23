"""Starts a headless test server, talks to it over RCON and MCP, and stops it.

Python standard library only. The server runs through Loom's runServer task in run/.
"""

import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUN = ROOT / "run"
WORLD = "awe-test-world"
RCON_PORT = 25598
RCON_PASSWORD = "awe-test"
JAVA_HOME = os.environ.get("JAVA_HOME") or str(sorted((Path.home() / ".local/jdks").glob("jdk-25*"))[-1])

SERVER_PROPERTIES = f"""level-name={WORLD}
level-type=minecraft\\:flat
generate-structures=false
online-mode=false
enable-rcon=true
rcon.port={RCON_PORT}
rcon.password={RCON_PASSWORD}
spawn-protection=0
view-distance=4
simulation-distance=4
gamemode=creative
max-tick-time=-1
"""


class Rcon:
    def __init__(self):
        self.sock = socket.create_connection(("127.0.0.1", RCON_PORT), timeout=30)
        self.next_id = 1
        if self._send(3, RCON_PASSWORD)[0] == -1:
            raise RuntimeError("RCON login failed")

    def _send(self, kind, body):
        request_id = self.next_id
        self.next_id += 1
        data = struct.pack("<ii", request_id, kind) + body.encode() + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(data)) + data)
        length = struct.unpack("<i", self._read(4))[0]
        packet = self._read(length)
        response_id, _ = struct.unpack("<ii", packet[:8])
        return response_id, packet[8:-2].decode(errors="replace")

    def _read(self, n):
        out = b""
        while len(out) < n:
            chunk = self.sock.recv(n - len(out))
            if not chunk:
                raise RuntimeError("RCON connection closed")
            out += chunk
        return out

    def cmd(self, command):
        return self._send(2, command)[1]


class Server:
    def __init__(self, fresh_world=True):
        self.fresh_world = fresh_world
        self.proc = None
        self.log = RUN / "awe-test-server.log"

    def __enter__(self):
        RUN.mkdir(exist_ok=True)
        eula = RUN / "eula.txt"
        if "eula=true" not in (eula.read_text() if eula.exists() else ""):
            sys.exit("run/eula.txt does not say eula=true. Read https://aka.ms/MinecraftEULA and accept it in run/eula.txt first.")
        (RUN / "server.properties").write_text(SERVER_PROPERTIES)
        if self.fresh_world and (RUN / WORLD).exists():
            shutil.rmtree(RUN / WORLD)
        env = dict(os.environ, JAVA_HOME=JAVA_HOME, PATH=f"{JAVA_HOME}/bin:{os.environ['PATH']}")
        self.proc = subprocess.Popen(
            ["./gradlew", "--no-daemon", "--console=plain", "runServer"],
            cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=self.log.open("w"), stderr=subprocess.STDOUT)
        deadline = time.time() + 600
        while time.time() < deadline:
            text = self.log.read_text(errors="replace")
            if "AWE MCP server on" in text and "Done (" in text and "RCON running" in text:
                break
            if self.proc.poll() is not None:
                sys.exit(f"the server stopped during start. Log: {self.log}")
            time.sleep(1)
        else:
            sys.exit(f"the server did not start in 600 s. Log: {self.log}")
        self.rcon = Rcon()
        config = json.loads((RUN / "config/awe/connection.json").read_text())
        self.mcp = Mcp(f"http://127.0.0.1:{config['port']}/mcp", config["token"])
        return self

    def __exit__(self, *exc):
        try:
            self.rcon.cmd("stop")
            self.proc.wait(timeout=120)
        except Exception:
            self.proc.kill()


class Mcp:
    def __init__(self, url, token):
        self.url = url
        self.token = token
        self.next_id = 1

    def raw(self, body, headers=None):
        """Returns (status, parsed body or None)."""
        all_headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                       "Authorization": f"Bearer {self.token}"}
        all_headers.update(headers or {})
        all_headers = {k: v for k, v in all_headers.items() if v is not None}
        request = urllib.request.Request(self.url, data=json.dumps(body).encode(), headers=all_headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                data = response.read()
                return response.status, json.loads(data) if data else None
        except urllib.error.HTTPError as e:
            return e.code, None

    def rpc(self, method, params=None):
        body = {"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params or {}}
        self.next_id += 1
        status, data = self.raw(body)
        if status != 200 or "result" not in data:
            raise RuntimeError(f"{method} failed: {status} {data}")
        return data["result"]

    def tool(self, name, **args):
        """Returns (is_error, text)."""
        result = self.rpc("tools/call", {"name": name, "arguments": args})
        return result["isError"], result["content"][0]["text"]

    def mcp_config(self):
        return {"mcpServers": {"awe": {"type": "http", "url": self.url, "headers": {"Authorization": f"Bearer {self.token}"}}}}
