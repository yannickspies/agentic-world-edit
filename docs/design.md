# AWE (Agentic World Edit): a Fabric mod that lets Claude read and edit a Minecraft build

AWE is a server-side Fabric mod with its own small edit engine and a built-in tool server for Claude. It requires only Fabric API, and it does not require WorldEdit.

The player marks a box in the world. Claude reads the blocks in that box as compact text and changes them with batched operations. Claude then looks at a rendered image and can undo each change. The code lives in a new public repository, `agentic-world-edit`. The monorepo fleet builds and tests it, after two changes to the fleet.

One review round by a second model is done. The last two sections list the unproven assumptions, what the review changed, and the questions for round two.

## The mod owns its edit engine, because WorldEdit trails the newest game version

The owner always plays the newest stable Minecraft version. WorldEdit does not always exist for that version. On 2026-11-23 the newest WorldEdit build found was 7.4.4 for Minecraft 26.2 ([Modrinth](https://modrinth.com/plugin/worldedit/version/7.4.2)), while Minecraft 26.3 was at release candidate 3 ([minecraft.net](https://www.minecraft.net/article/minecraft-26-3-release-candidate-1)).

| Option                            | Result   | Reason                                                                                                                                                                                                                                  |
| --------------------------------- | -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Require WorldEdit                 | Rejected | The mod cannot ship before WorldEdit does. Undo history mixes agent edits with player edits.                                                                                                                                            |
| Require a FastAsyncWorldEdit port | Rejected | The official project supports Spigot, Paper and Purpur only ([Modrinth](https://modrinth.com/plugin/fastasyncworldedit)). The Fabric port is third-party, and listings stop at 1.21.11 ([Modrinth](https://modrinth.com/mod/AckY0G9h)). |
| Own engine                        | Chosen   | No release dependency, an undo journal per agent edit, free license choice, and full control of speed.                                                                                                                                  |

The engine is not a new WorldEdit. It has no brushes, no expressions and no masks. It has three jobs: read a box, apply a planned list of block changes, and keep an undo journal.

Seven existing mods already connect Claude to Minecraft, for example [mcpfabric](https://github.com/denfry/mcpfabric) with more than 70 tools and [minecraft-java-fabric-mcp-server](https://github.com/chapmanjw/minecraft-java-fabric-mcp-server) with 183 tools. AWE differs in scope. It has six tools and does one job: build editing inside a marked box.

## Three parts, and only one of them touches the world

| Part      | Thread           | Job                                                                                                                |
| --------- | ---------------- | ------------------------------------------------------------------------------------------------------------------ |
| Transport | Own HTTP threads | Speaks the Model Context Protocol (MCP), the standard that lets Claude call tools. Receives and serves files.      |
| Planner   | Worker thread    | Turns a tool call into plain data: resolved block states and a change list per chunk section. Reads no world data. |
| Engine    | Server thread    | Reads blocks, applies change lists under a time budget per tick, and records the undo journal.                     |

World access happens only on the server thread. The transport and the planner hand work to the engine through one queue. When the queue is empty, the mod does one queue test per tick and nothing else.

The transport uses the HTTP server that is part of Java (`com.sun.net.httpserver`) and the JSON library that Minecraft already bundles (Gson). The mod bundles no libraries.

The transport answers each request with one JSON body. The MCP specification allows a plain `application/json` answer ([specification](https://modelcontextprotocol.io/specification/latest/basic/transports/streamable-http)). The mod implements `initialize`, `tools/list`, `tools/call` and `ping`.

## The workspace is the only place that the agent can touch

A workspace is one box in one dimension, set by the player in game. Every read and every write outside the workspace is refused. The first version supports one workspace and one agent connection.

All tool coordinates are local to the workspace. The corner with the lowest x, y and z is `[0,0,0]`. X grows to the east, y grows up, and z grows to the south. Small positive numbers are easier for a model than world coordinates such as `-18342`.

Each time a corner moves, the workspace gets a new id. The `workspace` tool returns the id, and every other tool call must carry it. If the id is old, the call fails and tells Claude to read again. A coordinate from before the move can therefore never reach the world.

| Command                     | Effect                                                                                       |
| --------------------------- | -------------------------------------------------------------------------------------------- |
| `/awe pos1`, `/awe pos2`    | Set a corner at the block that the player looks at.                                          |
| `/awe box <from> <to>`      | Set both corners from coordinates. The server console can run it, so a test needs no player. |
| `/awe show`                 | Draw the box outline with particles, one time per second, while the player is near.          |
| `/awe status`               | Show the box, the connection state and the last edits.                                       |
| `/awe undo [count]`         | Revert the newest agent edits, newest first.                                                 |
| `/awe pause`, `/awe resume` | Refuse or allow all agent writes.                                                            |
| `/awe connect`              | Print the command that connects Claude Code, with address and token.                         |

Commands need operator rights. The host of a singleplayer world always has them. After each agent edit, the player sees one line above the hotbar with the block count and the undo command.

## Six tools cover reading, editing and looking

| Tool        | Input                                                              | Output                                                                                               |
| ----------- | ------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------- |
| `workspace` | none                                                               | Size, dimension, player position and facing in local coordinates, limits, pause state, game version. |
| `read`      | optional sub-box, `detail`: `summary` or `layers`, optional `file` | Block data as text. With `file`, the tool reads an uploaded schematic and not the world.             |
| `edit`      | list of operations, short `label`, optional `dry_run`              | Edit report and an edit id.                                                                          |
| `undo`      | `count`, default 1                                                 | Count of restored and skipped cells for each reverted edit.                                          |
| `render`    | `view`: `top`, `north`, `south`, `east`, `west` or `iso`           | A PNG image as MCP image content.                                                                    |
| `blocks`    | search text                                                        | Matching block ids with their properties and allowed values.                                         |

The mod has no tool that runs game commands. It has no tool for entities, players, time or weather.

## Reads return a palette and text layers, trimmed and deduplicated

`summary` returns the box size, the count per block, the bounding box of all non-air cells and a height map. Claude starts with `summary` and then asks for `layers` on the part that matters.

`layers` returns a palette and one text grid per y level. Each character is one block. Rows run from north to south, and characters in a row run from west to east. Layers are listed from bottom to top.

```
box: [0,0,0] to [4,3,3]
palette:
  . air
  a stone_bricks
  b oak_stairs[facing=north]
  c chest[facing=south] *
y=0..2  (3 equal layers)
  z=0 aaaaa
  z=1 a...a
  z=2 a.c.a
  z=3 aaaaa
y=3
  z=0 bbbbb
  z=1 .....
  z=2 .....
  z=3 .....
```

- Properties that have their default value are left out.
- A `*` marks a block that carries extra data, such as a chest. The first version does not expose that data.
- Equal layers that follow each other are printed one time.
- The grid is trimmed to the bounding box of non-air cells. The first line states that box, and each row starts with its z value.
- If the box has more distinct states than single characters, the read fails with a hint to read a smaller box.
- A read that exceeds 60,000 characters fails with the size and a hint to read a smaller box. This limit is set by this spec and has no measurement behind it.

The layer format follows two sources: the interlayer in the Text to Building in Minecraft paper ([arXiv 2406.08751](https://arxiv.org/html/2406.08751)) and the `layers` format in [mc-agent-mcp](https://github.com/ArchitektSpeer/mc-agent-mcp).

One character per cell is not proven to be the best form. Step 2 compares it with a run-length form per row, for example `12a 3. 8b`. The test uses three real builds and measures the token count and the position errors that Claude makes. The winner becomes the only format for `read` and `grid`.

## Edits are batches of eight operations, applied all or nothing

| Operation | Fields                                               | Effect                                                                                           |
| --------- | ---------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| `set`     | `at` (list of positions), `block`                    | Set single cells.                                                                                |
| `fill`    | `from`, `to`, `block`, `mode`                        | Fill a box. Modes: `solid`, `hollow`, `walls`, `frame`.                                          |
| `line`    | `from`, `to`, `block`                                | Set the cells on a straight line.                                                                |
| `shape`   | `kind`, `from`, `to`, `block`, `hollow`              | Set an `ellipsoid` or an upright `cylinder` that fits the box.                                   |
| `replace` | `from`, `to`, `match`, `block`                       | Replace one block with another inside a box.                                                     |
| `grid`    | `origin`, `palette`, `layers`                        | Write text layers in the read format. The character `~` keeps the cell that is there.            |
| `clone`   | `from`, `to`, `dest`, `rotate`, `mirror`, `skip_air` | Copy a box, with rotation in steps of 90 degrees and mirroring. Block states turn with the copy. |
| `paste`   | `file`, `at`, `rotate`, `mirror`, `skip_air`         | Place an uploaded schematic.                                                                     |

Operations in one batch apply in order, and a later operation overwrites an earlier one. Blocks use the same text form as the `/setblock` command, for example `oak_stairs[facing=north,half=top]`.

Primitives replace raw coordinate lists because raw lists do not scale. MineBench added box and line primitives for the same reason ([MineBench](https://github.com/Ammaar-Alam/minebench/)). The `grid` operation uses the read format, so Claude can read layers, change characters and write them back.

The `shape` operation exists for towers and domes, which cost too many tokens as grids. Claude Desktop has no shell and cannot upload a generated schematic, so it depends on these eight operations. Free curves and terrain stay out of reach for Claude Desktop.

The planner resolves the full batch before the engine changes one block. If one block name is unknown or one cell lies outside the workspace, the whole batch fails. The error lists each bad item with its operation index, the reason and the valid values. Models often invent block names, as the same paper reports. With `dry_run`, the planner validates the batch and the engine changes nothing.

Blocks are placed frozen. The engine sends no neighbor updates, so the edit itself makes no sand fall and no water flow. Frozen is a goal that step 3 must prove per block family, because the game can still run ticks that were scheduled before the edit.

Two rules keep frozen placement from producing broken blocks.

- Partner cells: a door, a bed and a tall plant have two parts. If the batch sets one part and not the partner cell, the planner adds the partner. The report lists each added cell.
- Connection pass: fences, walls, panes, bars, stairs and redstone wire take their shape from their neighbors. After placement, the engine recomputes the shape of those blocks in changed cells and in the cells next to them. The game's own structure placement works the same way. If the batch states a connection property for a changed cell, the engine keeps the stated value.

The edit report contains the edit id, the changed cell count per block and the bounding box of the change. It also lists the added partner cells and the cells that the connection pass changed.

## Undo runs newest first and skips cells that the player changed

Each edit stores one journal entry: for each changed cell the world position, the old state, the old extra data and the new state. Undo always reverts the newest edit that is not yet reverted. An older edit cannot go first, because a later undo of the newer edit then puts back a block that the older edit placed.

If a cell still holds the state that the agent wrote, undo restores the cell. Undo skips each cell that the player changed afterward and counts it in the result.

The journal keeps the newest entries in memory, up to 4,000,000 cells in total. After each edit, a worker thread also writes the entry to the world folder under `awe/journal/`. Undo therefore survives a restart, and it survives a crash that comes after the write. The disk journal keeps 256 megabytes at most and drops the oldest entries first. Both limits are set by this spec.

## Files travel over HTTP and never through the model

Schematic bytes inside a tool call waste context and are slow. The transport has a second endpoint next to the MCP endpoint: `PUT /files/<name>` stores a file, and `GET /files/<name>` returns one. Both need the token.

Claude Code downloads a schematic link to disk, uploads it with one `curl` command, and then names the file in `read` or `paste`. If Claude Code runs inside WSL (Windows Subsystem for Linux) and the game runs on Windows, the upload still works. The upload needs no shared file path.

The mod parses two formats itself: Sponge schematics (`.schem`, the WorldEdit format) and Litematica schematics (`.litematic`). Both are compressed files in Named Binary Tag (NBT), the data format of Minecraft, and the game includes a reader for it. A block state that the running game does not know is listed in the report and is not placed.

Files have a size limit of 32 megabytes each and 256 megabytes in total. If the total is reached, an upload fails. Names allow only letters, digits, `-`, `_` and `.`, so a name cannot contain a path. Reserved Windows device names are refused.

A small compressed file can expand to a very large one. The parser stops at 512 megabytes of expanded data and at the cell limit of one edit, and it runs on a worker thread. A bad file therefore fails with an error and cannot use up the server memory.

## Claude checks its work with the edit report and a rendered image

The `render` tool draws the workspace on the server from the map color of each block, with shading by height. It needs no game client and no graphics card. The `iso` view shows the shape of a build, and the five flat views show plans and faces. The image is at most 1024 pixels wide.

The engine copies the block states on the server thread, inside the same time budget per tick. A large box takes several ticks. Only the drawing and the PNG encoding run on a worker thread.

The idea comes from `block_render_region` in [minecraft-java-fabric-mcp-server](https://github.com/chapmanjw/minecraft-java-fabric-mcp-server). A screenshot from the real game camera shows textures and light, but it needs client code. That screenshot is planned after the first version.

## Speed targets are gates, and the write path changes only if a gate fails

No measured numbers exist yet. Each target below is set by this spec and is proven or changed in the build step that is named.

| Target                                                    | Value                                                                | Proven in |
| --------------------------------------------------------- | -------------------------------------------------------------------- | --------- |
| Work per tick with an empty queue                         | One queue test                                                       | Step 1    |
| Read of 262,144 cells (64 x 64 x 64) on the server thread | Under 10 ms                                                          | Step 2    |
| Fill of 1,000,000 cells                                   | Under 10 s, no full server tick over 50 ms                           | Step 3    |
| Engine time per tick during an edit                       | 8 ms at most, configurable                                           | Step 3    |
| Render of a 128 x 128 x 128 workspace                     | Copy inside the tick budget, drawing under 200 ms on a worker thread | Step 4    |

Tick time means the full server tick as the game reports it, not the time inside the engine. The game does light and network work after the engine returns.

Reads go straight to chunk sections (the 16 x 16 x 16 block storage units) and not through a position lookup per block. Reads and writes never load or generate a chunk. A box with an unloaded chunk fails with a clear error.

The write path has two candidates.

- Path A uses the game's own `setBlock` call with flags that turn off neighbor updates and placement callbacks. The game then handles light, height maps, extra block data and the update to clients. NeoForge documents the flags `UPDATE_KNOWN_SHAPE` and `UPDATE_SKIP_ON_PLACE` for this ([NeoForge docs](https://docs.neoforged.net/docs/blocks/states)).
- Path B writes block states directly into chunk sections, then queues light updates and resends each changed chunk one time. This is the method of FastAsyncWorldEdit. Path B is faster, but it depends on game internals that change between versions.

The mod ships Path A. If Path A fails the 1,000,000-cell gate, Path B is built. Both paths sit behind the same change-list input, so the planner and the tools do not change.

## The server listens on the local machine only and writes only inside the workspace

| Rule        | Detail                                                                                                                                                                                                                                                                                       |
| ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Address     | `127.0.0.1` by default. Another address needs `allowRemote: true` in the configuration file. The mod then warns at start and in `/awe status` that the token travels unencrypted.                                                                                                            |
| Token       | A random token is created on first start and stored in `config/awe/connection.json`. Every request needs it.                                                                                                                                                                                 |
| Origin      | A request with an `Origin` header that is not local gets status 403. The MCP specification requires this ([specification](https://modelcontextprotocol.io/specification/latest/basic/transports/streamable-http)). A request with no `Origin` header passes, because Claude Code sends none. |
| Host        | A request with a `Host` header that is not the bind address gets status 403. This stops DNS rebinding, an attack in which a web page points its own domain name at the local machine.                                                                                                        |
| Scope       | Writes stay inside the workspace. No tool runs commands.                                                                                                                                                                                                                                     |
| Limits      | 2,000,000 cells per workspace and 1,000,000 changed cells per edit, both configurable.                                                                                                                                                                                                       |
| Pause       | `/awe pause` refuses all writes at once.                                                                                                                                                                                                                                                     |
| Not covered | Another program of the same user can read the token file. The mod does not defend against that.                                                                                                                                                                                              |

## The first version leaves out six things on purpose

| Left out                                       | Reason                                                                                                                       |
| ---------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| In-game prompt (`/claude ...`)                 | The prompt is typed in Claude Code or Claude Desktop. A later version can start Claude Code without a window from a command. |
| Script engine inside the mod                   | Claude Code can run its own code and upload the result as a schematic. A script engine is a large bundled library.           |
| Preview as a hologram before the edit          | It needs client rendering code. Undo covers the first version.                                                               |
| Entities, such as item frames and armor stands | They are not blocks. They are not read, written or restored.                                                                 |
| Editing extra block data, such as sign text    | The data is kept by clone, paste and undo, but it is not exposed.                                                            |
| WorldEdit selection import                     | It is optional comfort, and it brings a license question. See the last section.                                              |

## The new repository holds only the mod, its tests and its rules

The repository is `github.com/yannickspies/agentic-world-edit`, public from the first commit. The owner creates it, because a box has no GitHub access.

| Path                       | Content                                                                                           |
| -------------------------- | ------------------------------------------------------------------------------------------------- |
| `src/main/`                | The mod: transport, planner, engine, tools, commands.                                             |
| `src/gametest/`            | Test layer 1.                                                                                     |
| `test/protocol_test.py`    | Test layer 2. Python standard library only, so the test needs no install. |
| `test/agent_test.py`       | Test layer 3: task prompts, fixtures, the scorer and the run script. `test/harness.py` starts the server. |
| `docs/design.md`           | This spec. It moves here from the monorepo on the first commit, and the monorepo copy is deleted. |
| `CLAUDE.md`                | Rules for agents: thread rule, test command, commit format, no libraries.                         |
| `.sandbox/profile`         | The setup and verify commands that a fleet box runs.                                              |
| `.github/workflows/ci.yml` | Runs `./gradlew verify` without layer 3 on each push.                                             |

The repository takes four things from the monorepo, copied and not linked:

- the commit format and the commitlint limits of 72 characters per subject and 100 per body line,
- the document linter `scripts/lint-doc.mjs`, for `docs/`,
- the brief template for Trello cards from `docs/runbooks/remote-work-runbook.md`,
- the `/commit` and `/land` habits, written as plain rules in `CLAUDE.md`.

Work is tracked on a new Trello board, AWE, with a Fleet queue list and the run id prefix `a`. The board is created before the first card.

## The monorepo fleet builds AWE after two changes

The fleet runs each Trello card in a throwaway Hetzner VM (a box) with `deploy/sandbox/fleet.py`. Today the fleet assumes the monorepo in three places.

| Place                         | Assumption                                                                                        |
| ----------------------------- | ------------------------------------------------------------------------------------------------- |
| `deploy/sandbox/host.sh`      | One `REPO_URL` for the whole host.                                                                |
| `deploy/sandbox/box.sh`       | Setup runs `pnpm install`, Playwright and `pnpm db:migrate`. Verify runs `pnpm db:migrate` first. |
| `deploy/sandbox/provision.sh` | The box image has Node and pnpm, and no Java.                                                     |

The fleet needs two changes, made as cards on the yspies-monorepo board.

1. Repository per board: the board table maps each board to a repository URL. The fleet clones that repository on the box, and the CI link follows the repository.
2. Profile per repository: if the repository has `.sandbox/profile`, `box.sh` runs its setup and verify commands. If not, `box.sh` keeps the monorepo steps. The box image gets the Java version that the target Minecraft release needs.

AWE uses the fleet only for test layers 1 and 2 at first. A box can run layer 3 only if three things are true. Each one is checked in step 1 and is not proven yet.

| Condition                                                                                   | Why it is open                                                                                                            |
| ------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| The Claude Code on the box can connect to an MCP server on the same box.                    | The runbook says that a box cannot reach any MCP server. That rule targets remote servers, but a local one is not tested. |
| The box can download the Minecraft server and accept the Minecraft end user license (EULA). | The owner accepts the EULA once, as a configuration value of the fleet.                                                   |
| A box has enough memory for a Minecraft server next to the build.                           | The largest box type that fits the quota is named by `sbx.sh capacity`.                                                   |

If one condition fails, layer 3 runs on the owner's machine with the same run script, each night by a timer.

## Build order: each step has a proof

The code lives in `agentic-world-edit`, in Java with Gradle and Fabric Loom, under the MIT license. The target is the newest full Minecraft release on the day that step 1 starts. Test layers 1 and 2 grow with each step, and a step is done only if `./gradlew verify` passes.

| Step | Work                                                                               | Proof                                                                                                                                                                                                            |
| ---- | ---------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 0    | Repository, board, the two fleet changes                                           | A box clones `agentic-world-edit` and runs `./gradlew verify` on an empty mod.                                                                                                                                   |
| 1    | Mod skeleton, transport, token, `workspace` tool, `/awe` commands                  | Claude Code lists six tools and reports the box that was set in game.                                                                                                                                            |
| 2    | `read` with `summary` and `layers`, `blocks`                                       | Claude describes a hand-built house correctly. The read target is met. The format test names a winner.                                                                                                           |
| 3    | Planner, engine, `edit`, partner cells, connection pass, journal in memory, `undo` | A game test places each operation and compares every cell. Frozen tests cover sand, water, lava, redstone dust, observers, leaves, pistons and a waterlogged block. The fill gate is measured on full tick time. |
| 4    | `render`                                                                           | Claude finds and repairs a hole that was cut into its own wall, with no hint from the player.                                                                                                                    |
| 5    | File endpoint, `.schem` and `.litematic` parsing, `paste`, `read` of a file        | A Litematica file from the owner's collection pastes and matches the original cell for cell.                                                                                                                     |
| 6    | Journal on disk                                                                    | Claude edits, the server restarts, and `/awe undo` restores every cell.                                                                                                                                          |
| 7    | Test layer 3                                                                       | All five agent tasks reach their pass rule with no human action.                                                                                                                                                 |

## One command tests the mod with no human, in three layers

The command `./gradlew verify` starts a real server with no window and no player, in an empty flat world. Each layer answers a different question, and a later layer runs only if the earlier one passes.

| Layer       | Question                                            | Who acts                     | Runs                             |
| ----------- | --------------------------------------------------- | ---------------------------- | -------------------------------- |
| 1. Engine   | Does each operation change exactly the right cells? | Game tests inside the server | On each push                     |
| 2. Protocol | Does a tool client get correct answers over HTTP?   | A script that speaks MCP     | On each push                     |
| 3. Agent    | Can Claude finish a build task alone?               | Claude Code with no window   | Each night, and before a release |

Layer 1 uses the Fabric game test framework. Each test places a fixture, runs one operation, and compares every cell with the expected cells. The frozen tests, the partner cells, the connection pass and undo are tested here.

Layer 2 sets a workspace with `/awe box` from the console. The script then runs this loop: read, edit, read again, compare, undo, compare with the first read. It also sends requests that must fail.

| Request that must fail                         | Expected result                 |
| ---------------------------------------------- | ------------------------------- |
| No token, or a wrong token                     | Status 401                      |
| An `Origin` or `Host` header that is not local | Status 403                      |
| A file name that contains a path               | Refused                         |
| A tool call with an old workspace id           | Tool error                      |
| An edit with one cell outside the workspace    | Tool error, and no cell changes |

Layer 3 is the proof that the mod works on its own. Claude Code runs in headless mode (one prompt, no window) with only the six tools allowed. A scorer program then reads the world itself and compares cells. The scorer never trusts what Claude reports.

| Task    | Prompt in short                                                                      | Pass rule                                                |
| ------- | ------------------------------------------------------------------------------------ | -------------------------------------------------------- |
| Repair  | "This wall has a hole. Repair it." A fixture wall misses 7 cells.                    | Every cell equals the wall before the hole.              |
| Build   | "Build a hollow stone brick box of 7 x 5 x 7 with a door opening on the south side." | Every cell equals the expected box.                      |
| Replace | "Replace all oak planks with spruce planks."                                         | No oak planks remain, and all other cells are unchanged. |
| Paste   | "Place the uploaded schematic at `[2,0,2]`."                                         | Every cell equals the schematic.                         |
| Undo    | "Revert your last change." This follows one of the tasks above.                      | Every cell equals the state before that task.            |

Each task runs three times in a fresh workspace. Repair, Replace, Paste and Undo must pass three times of three. Build must pass two times of three, because a model does not give the same answer each time. These pass rates are set by this spec.

Each run has a limit of 20 model turns. The harness records the turns, the tokens and the time for each task. The format test of step 2 uses the same harness and the same numbers.

Layers 1 and 2 need no model and no account, so they run in continuous integration. Layer 3 needs a Claude Code login and costs tokens, so it does not run on each push.

## A dedicated server with no players stops ticking, and the mod keeps it awake

A dedicated server pauses after `pause-when-empty-seconds` with no players, 60 seconds by default. A paused server runs no ticks, and the engine applies edits in ticks. The first agent test found this: an undo waited until Claude gave up.

The mod patches `DedicatedServer.pauseWhenEmptySeconds()` with a mixin. While an edit is queued, or during five minutes after the last MCP request, the method returns 0, and the server does not pause.

## Eight assumptions are not proven yet

| Assumption                                                                                        | If it is false                                                                | Proven in |
| ------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- | --------- |
| The Java runtime that ships with Minecraft contains the `jdk.httpserver` module.                  | The transport gets a small hand-written HTTP handler on a plain socket.       | Step 1    |
| That runtime contains `javax.imageio` for PNG output.                                             | The mod writes PNG with the `Deflater` class, about 60 lines.                 | Step 4    |
| The flag names for Path A exist in the target version with the documented behavior.               | Path B becomes the only write path.                                           | Step 3    |
| Claude Code accepts a server that answers with plain JSON and keeps no session. Proven on 2026-11-23 by the agent test. Claude Desktop is not tested yet. | The transport adds a session id header. | Step 1 |
| Claude Code inside WSL can reach `127.0.0.1` on Windows. This depends on the WSL network mode.    | The owner sets the bind address, or runs Claude Code on Windows.              | Step 1    |
| A model edits well with text layers at sizes up to 60,000 characters.                             | The limit drops, and `summary` gains more detail.                             | Step 2    |
| The game's shape function for connecting blocks schedules no ticks and removes no blocks.         | The engine computes connections with its own code for the six block families. | Step 3    |
| A tick that was scheduled before the edit does not move a block that the edit placed.             | The engine clears scheduled ticks in changed cells.                           | Step 3    |

## Review round one changed six decisions and confirmed four

| Decision                    | Result            | Change                                                                                                                                            |
| --------------------------- | ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. Own engine, no WorldEdit | Holds             | Partner cells and the connection pass replace the warnings. No `force` option is added, because stated block states are already written as given. |
| 2. Path A first             | Holds, with proof | Frozen tests per block family. The gate measures the full server tick.                                                                            |
| 3. Thread model             | Changed           | The block copy for `render` runs inside the tick budget.                                                                                          |
| 4. Read format              | Open until step 2 | Bounds and row labels are added. Two-character cells are removed. A test decides on run-length rows.                                              |
| 5. Local coordinates        | Changed           | Each corner move gives a new workspace id. The journal stores world positions.                                                                    |
| 6. All-or-nothing batches   | Holds             | Errors carry the operation index. `dry_run` is added.                                                                                             |
| 7. Undo rule                | Changed           | Undo runs newest first only.                                                                                                                      |
| 8. Journal in memory        | Changed           | The journal is also written to disk.                                                                                                              |
| 9. No script engine         | Holds             | The `shape` operation is added. The limit for Claude Desktop is stated.                                                                           |
| 10. Security                | Changed in part   | `Host` rule, `allowRemote`, total file limit.                                                                                                     |

One suggestion is rejected: to refuse each request that has no `Origin` header. Claude Code sends no such header, so the rule locks out the main client.

## Round two must attack these six decisions

The reviewer has no game access and judges the design only. For each point, state three things: does the decision hold, what breaks, and what to change.

1. Connection pass: it recomputes cells next to an edit. That can destroy a shape that a builder set by hand. Is that acceptable?
2. Partner cells: the added partner overwrites the block that is in that cell. Is a failed batch the better result?
3. Newest-first undo: the player cannot revert an old edit and keep the newer ones. Is that acceptable for a build tool?
4. Disk journal: a crash between the edit and the write loses one entry. Must the write come before the edit?
5. `shape`: are an ellipsoid and a cylinder enough for Claude Desktop, or is a third kind needed?
6. Workspace id: is there a path on which a coordinate from before a corner move still reaches the world?

One open point needs a decision from the owner and not from the reviewer.

- WorldEdit selection import: if WorldEdit is present, the mod can read the WorldEdit selection. WorldEdit is under GPLv3, and a mod under MIT that calls its API needs a license review first.
