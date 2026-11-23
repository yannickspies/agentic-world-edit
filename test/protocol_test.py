"""Test layer 2: a plain MCP client checks the tools and the security rules. No model involved.

Run: python3 test/protocol_test.py
"""

import sys
from harness import Server

WS = (0, -60, 0, 19, -46, 19)
failures = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail and not ok else ""))
    if not ok:
        failures.append(name)


def main():
    with Server() as s:
        r = s.rcon
        r.cmd("forceload add 0 0 63 63")
        r.cmd("fill 0 -60 0 19 -46 19 air")
        r.cmd("fill 3 -60 3 6 -58 6 stone_bricks")
        print(r.cmd(f"awe box {WS[0]} {WS[1]} {WS[2]} {WS[3]} {WS[4]} {WS[5]}").strip())

        m = s.mcp
        init = m.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "awe-test", "version": "0"}})
        check("initialize names the server", init["serverInfo"]["name"] == "awe")
        status, _ = m.raw({"jsonrpc": "2.0", "method": "notifications/initialized"})
        check("notification gets 202", status == 202, status)
        names = [t["name"] for t in m.rpc("tools/list")["tools"]]
        check("five tools listed", names == ["workspace", "read", "edit", "undo", "blocks"], names)

        err, text = m.tool("workspace")
        ws_id = text.split("workspace_id: ")[1].split()[0]
        check("workspace reports size 20 x 15 x 20", "size: x 20, y 15, z 20" in text, text)

        err, before = m.tool("read", workspace_id=ws_id, detail="layers")
        check("read finds the stone brick cube", "stone_bricks" in before and "box: [3,0,3] to [6,2,6]" in before, before)
        err, summary = m.tool("read", workspace_id=ws_id, detail="summary")
        check("summary counts 48 stone bricks", "48 stone_bricks" in summary, summary)

        ops = [
            {"op": "fill", "from": [10, 0, 10], "to": [14, 3, 14], "block": "oak_planks", "mode": "hollow"},
            {"op": "set", "at": [[12, 6, 12]], "block": "sand"},
            {"op": "replace", "from": [3, 0, 3], "to": [6, 2, 6], "match": "stone_bricks", "block": "mossy_stone_bricks"},
            {"op": "line", "from": [0, 5, 0], "to": [9, 5, 0], "block": "oak_stairs[facing=east]"},
            {"op": "grid", "origin": [0, 8, 0], "palette": {"g": "glass"}, "layers": [["g~g", "z=1 ggg"]]},
        ]
        err, text = m.tool("edit", workspace_id=ws_id, label="protocol test", operations=ops, dry_run=True)
        check("dry run changes nothing", not err and "No block changed" in text, text)
        err, text = m.tool("edit", workspace_id=ws_id, label="protocol test", operations=ops)
        check("edit succeeds", not err and "cells changed" in text, text)
        check("hollow box is air inside", "Test passed" in r.cmd("execute if block 12 -59 12 air"))
        check("sand placed in air stays (frozen)", "Test passed" in r.cmd("execute if block 12 -54 12 sand"))
        check("stairs keep their facing", "Test passed" in r.cmd("execute if block 4 -55 0 oak_stairs[facing=east]"))
        check("grid '~' kept the cell", "Test passed" in r.cmd("execute if block 1 -52 0 air"))
        check("replace changed the cube", "Test passed" in r.cmd("execute if block 3 -60 3 mossy_stone_bricks"))

        err, text = m.tool("undo", workspace_id=ws_id)
        check("undo reports restored cells", not err and "restored" in text, text)
        err, after = m.tool("read", workspace_id=ws_id, detail="layers")
        check("undo restores the world exactly", after == before, after)

        err, text = m.tool("edit", workspace_id=ws_id, label="bad", operations=[
            {"op": "set", "at": [[1, 1, 1]], "block": "stone_brick"},
            {"op": "set", "at": [[99, 1, 1]], "block": "stone"}])
        check("bad batch is refused with index and suggestion", err and "operations[0]" in text and "stone_bricks" in text and "operations[1]" in text, text)
        check("bad batch changed nothing", "Test passed" in r.cmd("execute if block 1 -59 1 air"))

        r.cmd(f"awe box {WS[0]} {WS[1]} {WS[2]} {WS[3]} {WS[4] - 1} {WS[5]}")
        err, text = m.tool("read", workspace_id=ws_id, detail="summary")
        check("old workspace id is refused", err and "old or wrong" in text, text)

        err, text = m.tool("blocks", search="oak_stairs")
        check("blocks lists properties", "facing=north|south|west|east" in text, text)

        status, _ = m.raw({"jsonrpc": "2.0", "id": 99, "method": "ping"}, {"Authorization": None})
        check("no token gives 401", status == 401, status)
        status, _ = m.raw({"jsonrpc": "2.0", "id": 99, "method": "ping"}, {"Authorization": "Bearer wrong"})
        check("wrong token gives 401", status == 401, status)
        status, _ = m.raw({"jsonrpc": "2.0", "id": 99, "method": "ping"}, {"Origin": "https://evil.example"})
        check("foreign Origin gives 403", status == 403, status)
        status, _ = m.raw({"jsonrpc": "2.0", "id": 99, "method": "ping"}, {"Host": "evil.example:25599"})
        check("foreign Host gives 403", status == 403, status)

    print(f"\n{len(failures)} failed" if failures else "\nall protocol checks passed")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
