# AWE (Agentic World Edit)

A server-side Fabric mod that lets an AI agent read and edit one marked box of a Minecraft world through MCP tools. The design is `docs/design.md`.

## Build and test

You need JDK 25. If `java` is not on the path, set `JAVA_HOME` to the JDK under `~/.local/jdks/`.

```bash
./gradlew --no-daemon build                 # compile the mod
python3 test/protocol_test.py               # layer 2: a real server and a plain MCP client
python3 test/agent_test.py --runs 3         # layer 3: Claude Code does build tasks alone
```

Both tests start a headless server in `run/` and need `run/eula.txt` with `eula=true`. Only the owner accepts the Minecraft EULA. A test never writes that file.

If the protocol test fails, the change is not done. Run the agent test after a change to a tool description, the read format or the edit operations.

## Rules

- World access happens on the server thread only. HTTP threads plan, and the engine applies.
- The mod bundles no libraries. It uses the JDK, Minecraft and Fabric API.
- Every tool error says what is wrong and what the agent must do next.
- Game names come from the 26.x jar, which has no obfuscation. Check a method with `javap` on the Loom cache jar before you use it.
- Commits use the conventional format, with a subject of 72 characters at most and body lines of 100 characters at most.
