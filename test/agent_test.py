"""Test layer 3: Claude Code, with no window, does build tasks through the AWE tools.

The scorer never trusts what Claude reports. It builds the expected result in a reference area
outside the workspace and compares the two areas with the game's own `execute if blocks`.

Run: python3 test/agent_test.py [--runs 3] [--task repair] [--model sonnet]
"""

import argparse
import json
import os
import subprocess
import sys
import time
from harness import RUN, Server

# Workspace and reference area, world coordinates. The reference is 32 blocks east.
X0, Y0, Z0, X1, Y1, Z1 = 0, -60, 0, 19, -46, 19
DX = 32


def w(x, y, z, ref=False):
    """Local workspace cell to world coordinates text, in the workspace or in the reference area."""
    return f"{X0 + x + (DX if ref else 0)} {Y0 + y} {Z0 + z}"


def fill(r, a, b, block, ref=False):
    r.cmd(f"fill {w(*a, ref=ref)} {w(*b, ref=ref)} {block}")


def both(r, a, b, block):
    fill(r, a, b, block)
    fill(r, a, b, block, ref=True)


def hut(r, ref):
    fill(r, (3, 0, 3), (9, 0, 9), "oak_planks", ref)
    for x, z in ((3, 3), (3, 9), (9, 3), (9, 9)):
        fill(r, (x, 1, z), (x, 3, z), "oak_log", ref)
    fill(r, (3, 4, 3), (9, 4, 9), "oak_planks", ref)
    fill(r, (5, 1, 5), (5, 1, 5), "crafting_table", ref)


def setup_repair(r):
    both(r, (3, 0, 5), (13, 4, 5), "stone_bricks")
    both(r, (3, 5, 5), (13, 5, 5), "polished_andesite")
    fill(r, (7, 1, 5), (9, 2, 5), "air")
    fill(r, (8, 3, 5), (8, 3, 5), "air")


def setup_build(r):
    fill(r, (2, 0, 2), (8, 4, 8), "stone_bricks hollow", ref=True)
    fill(r, (5, 1, 8), (5, 2, 8), "air", ref=True)


def setup_replace(r):
    hut(r, ref=False)
    hut(r, ref=True)
    r.cmd(f"fill {w(0, 0, 0, True)} {w(19, 14, 19, True)} spruce_planks replace oak_planks")


def setup_undo(r):
    # Runs after the replace task in the same workspace: the expected result is the hut before it.
    r.cmd(f"fill {w(0, 0, 0, True)} {w(19, 14, 19, True)} air")
    hut(r, ref=True)


TASKS = {
    "repair": (setup_repair,
               "The stone brick wall in the workspace has a hole. Repair the hole so the wall is whole again, "
               "with the same blocks as the wall around it. Change nothing else."),
    "build": (setup_build,
              "In the workspace, build a hollow box of stone_bricks: 7 blocks along x, 5 along y, 7 along z, with its "
              "lowest north-west corner at local [2,0,2]. Floor, walls and roof are all stone_bricks, one block thick, "
              "and the inside is air. Then make a doorway in the middle of the south wall: set the two cells at local "
              "[5,1,8] and [5,2,8] to air. Change nothing else."),
    "replace": (setup_replace,
                "Replace every oak_planks block in the workspace with spruce_planks. Change nothing else."),
    "undo": (setup_undo, "Revert the last edit that was made in the workspace."),
}
SEQUENCES = [["repair"], ["build"], ["replace", "undo"]]


def run_claude(mcp, prompt, model, timeout):
    config = RUN / "agent-mcp.json"
    config.write_text(json.dumps(mcp.mcp_config()))
    env = {k: v for k, v in os.environ.items() if not k.startswith("CLAUDE_CODE") and k != "CLAUDECODE"}
    command = ["claude", "-p", prompt, "--output-format", "json", "--no-session-persistence",
               "--mcp-config", str(config), "--strict-mcp-config", "--tools", "",
               "--allowedTools", "mcp__awe__workspace,mcp__awe__read,mcp__awe__edit,mcp__awe__undo,mcp__awe__blocks",
               "--setting-sources", "project"]
    if model:
        command += ["--model", model]
    started = time.time()
    proc = subprocess.run(command, cwd=RUN, env=env, capture_output=True, text=True, timeout=timeout)
    seconds = round(time.time() - started)
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError:
        return {"error": (proc.stdout + proc.stderr)[-800:], "seconds": seconds}
    usage = data.get("usage", {})
    return {"turns": data.get("num_turns"), "seconds": seconds, "cost_usd": data.get("total_cost_usd"),
            "tokens_in": usage.get("input_tokens", 0) + usage.get("cache_read_input_tokens", 0) + usage.get("cache_creation_input_tokens", 0),
            "tokens_out": usage.get("output_tokens"), "answer": (data.get("result") or "")[-400:], "is_error": data.get("is_error")}


def score(r):
    out = r.cmd(f"execute if blocks {w(0, 0, 0)} {w(19, 14, 19)} {w(0, 0, 0, True)} all")
    return "Test passed" in out, out.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=int, default=1)
    parser.add_argument("--task", choices=["repair", "build", "replace"], help="run one sequence only")
    parser.add_argument("--model")
    parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    sequences = [seq for seq in SEQUENCES if args.task is None or seq[0] == args.task]
    results = []
    with Server() as s:
        r = s.rcon
        r.cmd(f"forceload add {X0} {Z0} {X1 + DX + 31} {Z1 + 31}")
        for run in range(1, args.runs + 1):
            for seq in sequences:
                r.cmd(f"fill {w(0, 0, 0)} {w(19, 14, 19)} air")
                r.cmd(f"fill {w(0, 0, 0, True)} {w(19, 14, 19, True)} air")
                r.cmd(f"awe box {X0} {Y0} {Z0} {X1} {Y1} {Z1}")
                for name in seq:
                    setup, prompt = TASKS[name]
                    setup(r)
                    info = run_claude(s.mcp, prompt, args.model, args.timeout)
                    passed, detail = score(r)
                    info.update(task=name, run=run, passed=passed, detail=detail)
                    results.append(info)
                    print(f"{'PASS' if passed else 'FAIL'} {name} run {run}: {info.get('turns')} turns, {info['seconds']} s, "
                          f"{info.get('tokens_out')} output tokens. {detail}", flush=True)
                    if not passed:
                        print("  claude said: " + (info.get("answer") or info.get("error") or "").replace("\n", " ")[:400], flush=True)
    (RUN / "agent-results.json").write_text(json.dumps(results, indent=2))
    failed = [x for x in results if not x["passed"]]
    print(f"\n{len(results) - len(failed)} of {len(results)} tasks passed. Details: {RUN / 'agent-results.json'}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
