# Changelog

All notable changes to this project are documented in this file.

## 0.5.9 (fork)

- Building guidance from a review of seven large builds, whose mistakes all passed the existing physics checks. `mc_build`'s description gains a short "DESIGN DEFAULTS" paragraph: one module for openings, lamps and rows; visible lamps before hidden light; nothing hanging into walkways; straight railings closed along stairs; remove what a revision replaces; a door-reach `mc_walk` over the whole building; local terrain edits. It is only read when an agent is about to build. The server instructions now ask for the final render to be first-person at eye height from where players arrive, and the in-game agent prompt gets a one-sentence version.
- Composition advice after every `mc_build`. The build box plus 6 blocks is read under the tick budget and checked for the review's recurring mistakes: diagonal or stepped railings players slip through (`diagonal-rail`, `rail-gap`), fences, panes and bars left unjoined beside a wall or rail, 2x2 fence clumps, solid blocks at head height across a walkway, hanging lamps too low over floors, beds, seats or tables, doors with a blocked side, furnished rooms with no way in, flowing liquid against the build, floating leaves, and invisible `light` blocks among built blocks with no visible lamp within 6 blocks (light over bare terrain is anti-spawn lighting and is not reported). The reply gains one line per rule with a count and one example position; it never fails the build, and `liquids:"flow"` skips the liquid rule. Boxes over 250,000 cells (about 2.5 s of reading) are not checked automatically: the reply says so and points to `mc_diff`. On the seven reviewed builds it found every high-severity issue the review had found by hand. When one rule reports many findings, the agent is asked to inspect a few and report the pattern rather than edit them all.
- `mc_diff` takes `"composition"` in `anomalies`, and `against` is now optional when anomalies are requested, so an existing build can be checked read-only in parts.
- Added 19 unit tests.

## 0.5.8 (fork)

- First-person `mc_render` and `mc_sightline` read unloaded chunks. Before, they only copied chunks that were already loaded, so a view of a build nobody stood near came back as purple "unknown" and had to be force-loaded by hand. Unloaded chunks in view now load in the background with `getChunkAtAsync` without generation: nearest first, at most 8 at a time, each copied under the tick budget in the tick after it loads, and no chunk tickets, so nothing ticks or stays loaded. Never-generated chunks and chunks beyond `limits.max-view-chunk-loads` (default 256, `0` turns loading off, applied by `/ashlar reload`) stay unknown. The first-person reply's `chunks` adds `loadedForView`, `notGenerated`, `overLoadCap` and `failed` when they are not zero.

## 0.5.7 (fork)

- The `mc_command` overrides moved from the call to `config.yml`: `run-command.allow-locate` and `run-command.allow-save` (both `false`, applied by `/ashlar reload`) are the only way to run `/locate` and the save commands. The `allowLocate` / `allowSave` arguments are gone, and the refusal no longer tells the caller how to get past it, so an agent cannot turn the block off by itself.

## 0.5.6 (fork)

- `mc_command` refuses `/save-all`, `/save-on` and `/save-off` (also namespaced, with or without a leading "/", and after `execute ... run`), alone or anywhere in a `commands` batch, before anything runs. They change saving for every loaded world, including worlds a plugin deliberately keeps unsaved: on 2026-10-10 a `save-all flush`, sent so region files could be read offline, wrote a pooled duel arena to disk mid-match, and the pool quarantined it until it was rebuilt. Read live blocks with `mc_inspect`, `mc_survey saveAs` or `mc_export` instead.
- The `mc_command` refusals are now defaults rather than hard blocks: `allowSave:true` runs the save commands and `allowLocate:true` runs `/locate`, for every command in the call. The refusal message names the override and when it is safe to use, so an agent opts in only after reading the risk.
- Added 2 unit tests.

## 0.5.5 (fork)

- `mc_command` refuses `/locate` (also `minecraft:locate`, with or without a leading "/", and after `execute ... run`), alone or anywhere in a `commands` batch, before anything runs. Locate searches on the main thread and cannot be interrupted: on 2026-10-12 one far `/locate biome` ran past Paper's 60 s watchdog and crashed the server. Find biomes and structures offline from the world's region files instead.
- Added 1 unit test.
- `tools/findsite.py`: finds the largest untouched areas of one biome by reading the world's region files offline, read-only and at low priority, so it never touches the running server. Samples one chunk every 64 blocks; options for the biome, minimum biome sections, maximum player time per chunk, a centre point and maximum distance. The `mc_command` locate refusal and README point to it. On the test server's superflat world it read 13 regions (480 sampled chunks) in about a second.

## 0.5.4 (fork)

- `mc_build heightfield` reshapes terrain from one number per column instead of a block per cell: `{from:[x,z], width, heights:[y or null, ...], top, under, underDepth (default 3), fill, steep, steepAt (default 3), clearAbove (default true)}`. `heights` is the new surface block y, row-major like an `mc_survey saveAs` file, so a script can read a survey, edit the heights and send them back; `null` leaves a column alone, which makes ragged, natural edges easy. Ashlar reads each column's live ground (below plants, trees and liquids, as `saveAs` does), fills up to the new surface or digs down to it, and repaints the top: the top block, then `underDepth` under blocks, then fill. With `steep`, columns at least `steepAt` blocks above or below a side neighbour use that block for the top layers (cliffs, crater walls). `clearAbove` turns everything above the new surface up to the column's old top into air, plants and trees included; under water or lava the cleared space is that liquid, so a lowered lakebed stays wet. The cells go through the existing cells build: every part is validated and protection-checked before the first write, the whole call is one journal, and `dryRun` reports the counts without writing. Up to 200,000 columns and 2,000,000 cells per call; a call over that is refused before anything is written, with a tile size to use. After a failed part, repeating the same call finishes it, because columns already at their height come out unchanged. On the test server a 40x40 hill (1,128 columns, a tree and a ditch) built in 0.46 s as 5,054 cells; the repeat changed 0 cells, and undoing the journal gave an export identical to the one taken before.
- Added 3 unit tests.

## 0.5.3 (fork)

- `mc_walk` accepts a start or end point on a bottom slab given as the slab's own block y. Two standing heights were within half a block of that y: on the block under the slab (blocked by the slab itself) and on top of the slab (64.5 for a slab at 64). The closer, blocked one won, so the call failed with "not a standing place: headroom" naming the slab. A valid standing place now wins over a closer blocked one.
- Added 1 unit test.
- When a call changes too many cells to keep a journal, the reply no longer suggests snapshots (capped at 200,000 blocks, so no help at that size). It says mc_restore cannot undo the call, and that next time the work can be split into calls under `journal.max-cells-per-entry`, or the area exported with `mc_export` first and rebuilt with `mc_build cellsFile` to undo.
- Large builds keep their undo. `journal.max-cells-per-entry` now defaults to 4,000,000 (was 1,000,000), so a whole `cells`/`cellsFile` build (up to 2,000,000 cells) fits in one journal. A 1.32M-cell build used to finish with no journal and needed a hand-made undo file. `journal.max-total-cells` doubles to 40,000,000 so one big entry does not push out the others. The journal holds about 40 bytes per cell in memory while a call runs (about 160 MB at the new cap), then goes to disk. Both keys hot-reload; a config that sets them keeps its values.
- A journalled `mc_build` that declares more cells than one journal keeps is refused before anything is written, instead of building and then reporting it cannot be undone. The error names the limit and the ways forward: split the call, or pass `journal:false` (export the area first to keep a way back). Only calls whose size is not known up front can still go past the limit, and their reply says so.
- Added 1 unit test.
- `mc_survey saveAs:"name"` writes the surveyed area to `plugins/Ashlar/exports/<name>.json.gz` (format `surface`) and replies with the one-line summary only (height range, materials, flat zone) plus the file's layout. Each column has two readings with their y: `top`, the first non-air block from above, and `ground`, the block below plants, trees (logs, wood, stems, mushroom caps, mangrove roots), leaves and liquids. A forest reads as the soil under it and a lake as its bed; a building or portal reads as its own top block. Blocks index one palette, row-major z then x. A script can read terrain, trees and water over a 400x400 area from one call instead of decoding region files: on the test server that reply is 576 characters against 47,132 for the image survey, and the volcano build's image survey was about 315,000. Same area limit (200,000 columns) and tick-budgeted read as an ordinary survey; it cannot be combined with `format`, `matrix` or `walkHeight`, and `overwrite:true` replaces an existing export. Using a surface file as `cellsFile` is refused with a clear message.
- Added 3 unit tests.
- `mc_walk` takes `waypoints` (up to 32) instead of `box`: a route from `from` through each waypoint, the last being the destination. Each leg is searched in its own box around its two ends (`margin` sideways, default 6, plus 4 blocks below and above), so a long diagonal trail no longer needs one rectangle over the whole area: a 300x300 diagonal was an 881,721-block box against the 800,000 limit, and a 60x60 leg is about 46,000. Every leg box is checked before anything is read; one over the limit is refused, naming the leg and a midpoint to add. The reply has one line per leg ("reachable in N moves", or the usual diagnosis) and the total, and stops at the first leg that is not walkable. The `box` modes are unchanged.
- Added 2 unit tests.

## 0.5.2 (fork)

- `mc_build` no longer reports false support warnings when a later part of the same call supplies the support, for example a hanging lantern in `fills` under a ceiling in `blocks`. Each phase (fills, shapes, text, blocks, and each `cells` part) still checks support right after its own writes, but the warned positions are now checked again once the whole call has written, and only the ones still unsupported are reported. Agents are told to fix every warning, so a false one cost extra calls.
- `mc_restore` takes `journals`: up to 50 journal ids undone newest first in one call, with one combined reply. Every id is looked up before anything is written, and the first failure stops the rest (the ones undone before it stay undone). To undo everything under a label, list it with `mc_snapshot journal-list` and pass the ids: two calls instead of one per journal, and the agent sees exactly what will be undone. `mode`, `dryRun` and the other undo options apply to every journal.
- Added 1 unit test.
- Over-limit errors from `mc_inspect`, `mc_render`, `mc_survey` and `mc_snapshot` name the exact boxes to use instead of only saying "split it": "mc_inspect region volume 270000 exceeds the 200,000-block limit. Reduce the from/to range, or split it into 2 calls: [0,40,0]..[110,69,59], [111,40,0]..[149,69,59]." Pieces are slabs along the longest side (the fewest calls), up to four are listed, and a box over 64 times the limit only gets "reduce the range". Reads are not split automatically: replies are already summaries, and the limits exist to bound server work.
- Added 3 unit tests.
- `mc_inspect format:"exterior"`: what a finished build shows outside. Air is flooded in from the box's sides and top (the bottom layer counts as ground). The reply counts the non-air blocks next to that air by material, the air sealed off from outside, and detached pieces: groups of blocks not connected to the bottom layer, smallest first, which catches stray blocks and leftover scaffolding. A test house with one stray plank: "242 of 290 non-air blocks are seen from outside. Exterior materials: stone_bricks 168 (69.4%), stone 73 (30.2%), oak_planks 1 (0.4%)" and "1x oak_planks [453,47,404]..[453,47,404]". A 200,000-block box takes about 0.3 s. It is an option on an existing tool, so the tool count stays at 19.
- Added 3 unit tests.
- `mc_command` takes `commands`: up to 64 console commands run in order in one call, such as one `/item` per chest when furnishing a build (32 chests: 1 call instead of 32). Every command is routed before any runs, so a bad `vanilla:true` name rejects the call with nothing run. The reply has one entry per command (up to 3 feedback lines each; failures in full), counts failed or undispatched commands, and lists plugin-override notes once. A failing command does not stop the rest. Container contents stay outside the journal and snapshots, as before.
- Added 1 unit test.
- Isometric and perspective `mc_render` views take boxes up to 2,000,000 blocks (was 200,000), set by the new hot-reloadable `limits.max-render-volume`. A whole district fits in one picture instead of ten. The image keeps its detail: the ray-tracing budget doubles with each doubling of volume above 200,000 blocks, so renders up to 200,000 blocks are unchanged (405x506 before and after). On the test server (5 ms tick budget, 1.5 GB heap) a 160x78x160 city rendered at 922x794 in 2.3 s (isometric) and 977x780 in 3.3 s (perspective), with no "Can't keep up" warnings. Flat maps, facades, slices, heightmaps and `mc_plan` previews keep their limits.
- Added 3 unit tests.

## 0.5.1 (fork)

- The MCP server no longer stays disconnected after the plugin restarts. A connection attempt the server accepted but never answered (for example while Paper was stopping or starting) used to wait forever: Node's WebSocket has no handshake timeout, so no retry was ever scheduled. An attempt not authenticated within 15 seconds is now abandoned and retried with the usual backoff.
- Added 1 unit test.

- The server instructions now fit Claude Code's 4,096-character limit (6,211 -> 3,902 characters). Claude Code silently cut off everything past that point, which included the protected-region `override` rule, the warning that `mc_command` ignores protected regions, the coordinate conventions and the opt-in verification policy. The safety and coordinate rules now come first, the tool list is grouped into one line per kind, and repeated wording is trimmed. A test keeps the text at or under 4,000 characters with the safety rules ahead of the workflow.

- Region reads, snapshots, mc_diff and mc_plan previews convert each distinct block state to text once instead of once per block (Paper builds that text fresh on every call). On a 195,112-block area of mixed stairs on the test server, a read dropped from 0.9-1.2 s to 0.2-0.3 s, a snapshot from 1.3 s to 0.3 s and an mc_diff from 1.2-1.4 s to 0.14-0.35 s.

- Smaller tool replies:
  - mc_survey `format:"text"` no longer adds the per-block height matrix by default (it was on up to 40x40); pass `matrix:true` as with images. A 36x36 text survey drops from 7,346 to 1,920 characters.
  - mc_diff reports enclosed air as pockets (bounds and cell count per sealed space) instead of sample cells: "enclosedAir 28 cells in 2 pockets".
  - mc_players takes an optional `name` to report one player.
- Builds with stairs, fences, panes, walls, iron bars or redstone are about 3x faster. After placing them, the connection pass re-wrote each one and then each of its four side neighbours, so in a solid area most blocks were re-written about five times. Neighbours that the build itself placed, and neighbours already re-written, are now skipped. A 97,556-block oak stairs fill on the test server (5 ms tick budget) dropped from 25-39 s to 8.7-11.4 s; a scene of stairs, fences, panes, walls, iron bars and redstone next to existing blocks came out identical (0 of 9,245 cells differ).
- The MCP usage log line no longer repeats a single reply's token estimate ("~67 tokens = ~67 tokens").
- Added 1 unit test.

## 0.5.0 (fork)

- `mc_build` takes `shapes`: cylinders, spheres, domes, cones and pyramids, each inscribed in an inclusive `from`/`to` box (odd or even sizes; unequal sides give ellipses). `axis` (`y` default, `x`, `z`, or `-y`/`-x`/`-z`) orients them, so `-y` gives a bowl or an upside-down cone. `thickness` (1-16) makes a watertight shell with open base faces: a hollow cylinder is a ring wall, a hollow dome has no floor. The plugin computes the cells off the main thread and merges them into as few fill runs as possible. Shapes go through the same validation, protection, snapshots, journal, `transform`, `dryRun`/`mc_plan` and blueprint components (with `$role` blocks) as fills. Execution order is now fills -> shapes -> text -> blocks -> entities. Example: a 9-wide round tower with a dome and a spire is one call with three short entries instead of hundreds of hand-listed cells.
- Added 9 unit tests and a new `e2e-curves.mjs` (21 checks).

- Invalid block states now explain themselves in one line, on every path that parses them (`mc_build` blocks/fills/text/cells/cellsFile, blueprints at build time, `mc_plan`, `mc_verify`, `mc_repair`):
  - `oak_stairs facing=up is invalid; facing: north|east|south|west`;
  - `oak_stairs has no property 'facin'; properties: facing, half, shape, waterlogged`;
  - `stone has no properties; use plain stone`;
  - `unknown block 'oak_stair'; did you mean oak_stairs, dark_oak_stairs, pale_oak_stairs?`;
  - syntax errors such as `missing ]`.
- Errors for `blocks`, `fills` and `text` no longer start with a misleading `transform:` when no transform was given.
- Added 5 unit tests and 3 `e2e-usability.mjs` checks.

- Token efficiency: the full tool catalogue (19 schemas plus instructions) went from about 175 KB to 85 KB. `mc_plan` and `mc_verify` no longer embed a copy of the whole `mc_build` schema in their `build` parameter (about 25 KB each); it is now a short object that refers to mc_build, and the arguments are still validated exactly as mc_build validates them. Tool descriptions are about 30% shorter and keep the four sections. The instructions dropped details that the tool descriptions already carry (10.8 KB to 6.2 KB).
- `mc_plan` and `mc_build dryRun` replies are compact: collisions grouped as `collisions: [{change: "a -> b", n, at}]`, `materials` with canonical states, issues without `severity` (at most 20, plus `issuesMore`), and a preview legend `{state: color}`. Internal and derived fields and zero/false/empty optional counters are left out. Example: one house plan went from 1,378 to 586 characters; with `image:false` it went from 1,059 to 382. The internal plan report used by preflight and verification is unchanged.
- Flat renders automatically scale small regions to at most 16 px per block (for example, a 9-block plan preview is 144 px instead of 1,017 px). Flat and first-person legends list at most 20 blocks with canonical states. `mc_players lookingAt` uses canonical states.
- Added 4 unit tests. Rewrote the 3 nested-schema tests. Updated the stale tool counts in 5 e2e scripts.

- `mc_render` isometric/perspective replies now include a colour legend (`legend: {state: "#rrggbb"}`, the 20 most visible blocks, `legendMore` counts the rest), so colours in angled images can be matched to blocks. The reply is shorter: block states are canonical (no `minecraft:`, no default properties), at most 10 fallback samples are listed, and the fixed assumptions text, unit vectors, internal work estimates and zero/false/empty fields are left out. The same air-only render went from 1,150 to 197 characters. Added 2 unit tests and one `e2e-render.mjs` check.

- `mc_command` / `run_command`: a vanilla command syntax error (for example `time query daytime` on 26.2) is returned as `Syntax error: <message>` (`syntaxError` in the RPC result) instead of failing with an internal error and a logged stack trace. Added 2 unit tests and one `e2e-usability.mjs` check.

- Render fidelity: angled `mc_render` views model lanterns (hanging/standing), chains, end and lightning rods, candles, bells, campfires, pointed dripstone, buttons, levers, flower pots and heads, and recognise copper bars. Pillars (logs, stems, basalt, quartz and purpur pillars, hay, bone, deepslate, froglights...) colour end faces apart from sides by `axis` (vanilla log colours, or lightened ends when both match); grass, podzol, mycelium, dirt paths and nylium use their side block's colour on the sides. Paintings and item frames are drawn as placeholder panels.
- `mc_render compareTo:"<snapshot id>"` (angled views): cells that differ from the snapshot are tinted (changed orange, added green, removed as translucent red cubes) and counted in `geometry.diff`; the diff runs off the main thread. `zoom:{pos, radius}` replaces `from`/`to` with a close-up cube and defaults to 32 px per block; angled views accept `scale` up to 32.
- Added 22 focused unit tests and disposable `e2e-render.mjs` (16 checks).

- `mc_command`: when the first word is a plugin command that has taken over a vanilla name (EssentialsX `tp`, `give`, `kill`...), the reply ends with a note naming the plugin and the `minecraft:` form; `vanilla:true` sends `minecraft:<name>` instead and is rejected when no vanilla command has that name. The description now says the console is not a player (`@s`, `~`).
- Blueprint tags: documents may hold `tags: {module, status: draft|built|approved, undo: <blueprint id>, note}`. `mc_blueprint list {tags}` filters on them, `tag {id, tags}` merges changes (null removes one) without resending the document, and `touches {from, to}` lists blueprints whose bounds intersect a box. Bounds are compiled without a project transform at save time and cached in `<id>.bounds.json` next to the document.
- A blueprint `mc_build` reply ends with `Blueprint: <id> (module, status, undo); journal <id>.`
- Added 10 focused unit tests and disposable `e2e-usability.mjs` (23 checks).

- New read-only `mc_light`: counts the hostile-mob spawn spaces in a box (open feet and head cells over a floor with a full solid top; carpet, rails, pressure plates, water and bottom slabs prevent spawning) and the dark ones among them (block light at most `threshold`, default 0; sky light only with `includeSky`), with dark bounds, counts per feet y and samples; `spawnable:false` counts every open cell. `suggest` returns a greedy list of light positions ready for `mc_build cells` (`block` default the invisible light block, `level` default 11, `spacing`, `placement` hidden/floor/ceiling), estimated by spreading light inside the box. The main thread only copies chunk snapshots a few per tick; rules and planning run off-main.
- `mc_survey` gains `type:"TOP_SOLID_IGNORE_DECOR"` (the ground under carpet, plants, torches, lanterns, rails, signs, heads and snow up to `snowLayers`, default 3) and `walkHeight:true` (per-column feet height a player stands at, e.g. 64.5 on a bottom slab; at most 4,096 columns).
- Added 24 focused unit tests and disposable `e2e-light.mjs` (27 checks).

- Decorative entities: `mc_build entities` (up to 256 per call) places paintings (`facing`, `variant`; `pos` is the lower-left cell as the viewer sees it, the vanilla anchor is computed per size), item frames and glow item frames (`facing` incl. up/down, `item`, `rotation` 0-7, `fixed` default true, `invisible`) and armor stands (`yaw`, `equipment`, `pose`, `small`, `invisible`, `noGravity`). They run after every block write; an identical entity already there is reported unchanged, a different one or a missing wall is reported per entity while the rest are placed. `entityMode:"replace-in-box"` with `entityBox` first removes Ashlar-placed entities of the requested types (mobs, players, hand-placed and marker armor stands are never touched). `transform` turns facings and yaw; a mirror keeps paintings on the same wall cells. Variant and item ids are checked before anything is written; `dryRun` lists the entities. Entities cannot be combined with `blueprint` or `cells`.
- Ashlar-placed entities carry the PDC tag `ashlar:placed`. Journals record the entities a call placed, removed or changed and the sign text it changed (including text-only rewrites); `mc_restore {journal}` removes, respawns or resets them and writes the text back, in safe mode only where they still match. Snapshots store sign text and decorative entities; a snapshot restore respawns or resets them and removes Ashlar-placed ones that were not there.
- `mc_inspect entities:true` lists paintings, item frames and armor stands in the box.
- Added 15 focused unit tests and disposable `e2e-entities.mjs` (40 checks).

- Bulk cells: `mc_build` accepts `cells: [[x,y,z,"state"],...]` (up to 50,000 inline) or `cellsFile` (a finalized staged upload or an export, up to 2,000,000 cells). Cells are sorted and deduplicated (conflicting duplicates are rejected), merged greedily into one-state boxes (x, then z rows, then y layers; at most 128 wide), ordered solids, air, then late blocks that need support, and packed into parts within `max-blocks-per-operation`, the chunk cap (at most 256 chunks per part), flowing-liquid and snapshot limits. Every part is validated and protection-checked before the first write; parts run in order under one journal id. A failed part returns a PARTIAL report with a `resume` token for the same call; `dryRun` reports the merge and parts.
- `mc_blueprint` gains `stage` (append up to 50,000 cells per call to `plugins/Ashlar/staging/<name>`, `reset` to start over) and `finalize` (checks every cell and writes `<name>.json.gz`). Unfinished uploads expire after 24 hours; staging and exports keep at most 20 files each (oldest pruned) of at most 256 MiB.
- New read-only `mc_export`: writes a box to `plugins/Ashlar/exports/<name>.json.gz` as `cells` (optional `skipAir`) or `palette-rle`, canonical states by default, through the tick-budgeted read path; capped by new `limits.max-export-volume` (default 4,000,000). `mc_diff expectedFile` also finds staged uploads and exports and now streams the file.
- Added 9 focused unit tests and disposable `e2e-bulk.mjs` (28 checks).

- New read-only `mc_walk`: player-movement reachability inside a box (at most 4 x `limits.max-read-volume`, read in tick-budgeted tiles; the search runs off the main thread). Mode `path` returns move count and waypoints; mode `reach` counts reachable standing places and checks `targets` (`doors` on both sides, `beds`, `stairs`, or up to 256 feet positions). Rules: `stepHeight` 0.6, `jump` (1.25), `maxDrop` 3, `allowLadders`, `allowWater`, `doors` open-only/any. Collision heights for snow, carpet, slabs, beds, fences, walls and gates; bottom stairs are a step only head-on. Failures name the nearest reachable place and the reason with the blocking cell.
- Added 9 focused unit tests and disposable `e2e-walk.mjs` (20 checks, including armor-stand drops that confirm snow, carpet, slab, stair and open-door collision on the server).

- First-person views and sightlines: `mc_render` gains `view:"first-person"` (`eye`+`yaw`/`pitch` or `player`, `fov` 10-110, `distance` 8-256, up to 1,000,000 pixels). New read-only `mc_sightline`: up to 256 targets (visible/blocked/unknown with the first blocking block and distance; a target counts as visible when its center or a face turned toward the eye is in view), or a cone grid of first hits; `ignore` patterns such as `*_leaves` or `snow[layers<=3]`. Voxel traversal against the schematic block shapes; glass, panes and water do not block, leaves do. The main thread only copies already-loaded chunks (`ChunkSnapshot`, tick-budgeted, no loads or tickets, at most `limits.max-chunks-per-operation`); all tracing runs off-main. `mc_players` adds `eye` and optional `lookRange` 16-256.
- Added 18 focused unit tests and disposable `e2e-view.mjs` (20 checks).

- Load-aware build budget: the executor subtracts its own work from Paper's average tick time and gives itself the full `limits.tick-budget-ms` only while the rest of the server needs at most 30 ms per tick, scaling down linearly to 1 ms at 45 ms and pausing beyond that apart from one 1 ms slice per second. The default `tick-budget-ms` drops from 20 to 5 (existing configs keep their value; the key still needs a restart). `mc_status` and `health` report the current budget and the load without Ashlar.
- Added 5 focused unit tests.

- Protected regions: new `mc_protect` (`add`/`list`/`remove`) keeps named boxes in `plugins/Ashlar/protected.json` (atomic writes, re-read by `/ashlar reload`). `mc_build`, `mc_repair` (plan and diff), `mc_restore` (snapshot box and journal cells) check every write target before any snapshot or write: `deny` rejects with the region, cell count and bounds; `warn` adds a warning line. `override:["name",...]` names each region explicitly (no wildcards) and is logged to `operations.log` and the server log. `mc_plan`, `dryRun`, journal dry runs and `mc_blueprint fit` report overlaps without enforcing. `outline`/`walls` fills only target their shell. Only the creator, an operator or the console/MCP token may remove a region. `mc_command`, players and other plugins are not checked.
- Added 16 focused unit tests and disposable `e2e-protect.mjs` (30 checks).

- Build journal: every writing `mc_build`, `mc_repair` and `mc_restore` records the original state of each cell it changed, including neighbour shapes changed by the connection pass and chest pairing, and returns a journal id. `mc_build` takes `label` and `journal:false`. Entries live under `plugins/Ashlar/journal/` (config section `journal`, all keys hot: 200 entries, 20M total cells, 30 days, 1M cells per entry).
- `mc_restore {journal}` undoes one call cell by cell. Safe mode (default) skips cells changed since and reports them with bounds and samples; `mode:"force"`, `dryRun` and `allowBlockEntityReplacement` are optional. The undo is itself journalled, so it can be undone.
- `mc_snapshot` gains `journal-list` (filters `since`, `label`, `world`, exact `touches`, `limit`) and `journal-delete`. Journals store block states only: sign text, inventories, entities and liquid flow are not undone.
- Added 14 focused unit tests and disposable `e2e-journal.mjs` (21 checks).

- New read-only `mc_diff` compares a live box against a snapshot, inline expected cells, a server-side expected file (`plugins/Ashlar/expected/<name>.json[.gz]`) or a saved blueprint. Canonical comparison (default-valued properties dropped, air variants equal), optional material-only compare, ignore globs, declared/box scopes and a baseline snapshot for undeclared cells. Summary/cells/columns reports with exact counts, transitions, bbox and samples. Boxes up to `limits.max-diff-volume` (default 2,000,000, hot-reloadable) are split into tick-budgeted reads of at most `limits.max-read-volume`.
- Optional `mc_diff` anomaly checks: floating (snow, carpet, plates, rails, torches, lanterns, plants), stacked partial snow, unsupported (existing support rules) and enclosed air pockets.
- `mc_diff` issues an owner-scoped, expiring `diffId` receipt; `mc_repair {diffId}` writes reference states back to those cells only, behind the same stale-value and block-entity guards, with an optional delta snapshot.
- `mc_inspect` reports canonical block states by default (`canonical:false` returns raw full states).
- Added 27 focused unit tests and disposable `e2e-diff.mjs` (18 checks).

- `mc_build` defaults to compact phase totals (changed and unchanged/skipped), retaining snapshot IDs, chest pairing and support warnings. `detailed:true` restores per-fill/per-text-entry results; placement, errors and safety checks are unchanged.
- Clarified global fills -> text -> blocks ordering and final-air opening corrections. Agent guidance reserves strict preflight for concrete risks, discourages redundant checks/backups, and explicitly warns that snapshots cannot restore sign text or inventories before deletion. No new audit or overlap-warning system.

- Lightweight photo-reference reconstruction workflow in public documentation and plugin-served agent instructions: scale/proportions, distinctive features, observed/inferred/unknown evidence and interior uncertainty. Uses existing tools; no automatic image-to-3D conversion or photographic similarity scoring. Visual fidelity requests no longer imply exact block-state auditing.
- Repository `AGENTS.md` persists the approved basic-testing policy: focused checks, no exhaustive sweeps or repeated native regressions without explicit approval, runtime safety protections unchanged.

- Optional `mc_blueprint fit` creates site-specific additive solid/pier foundations and bounded descending entrances from one tick-budgeted terrain capture. Explicit walking plane, conservative natural anchors, headroom/hazard/landing rejection, air-type filters, compressed columns, original world/origin recommendation and shared live build validation before persistence. No world placement, excavation or new mandatory verification stages; later-world/relocation limitations explicit.
- Added terrain geometry/schema tests and disposable `e2e-terrain-fit.mjs`: 354,449 native checks, late protected edits preserved, four entry directions, rejected-fit atomicity, static preview/live parity and read/write cap rejection. Ordinary build/snapshot safeguards unchanged.
- Optional `mc_blueprint generate` saves reusable gable/hip/shed roofs, rounded/pointed arches, circular towers and straight/switchback stairs. Pure deterministic bounded geometry, face-connected thin profiles, compressed fills, material/expanded-native-state validation before persistence, explicit overwrite and bounded summaries. Existing compiler/transforms/build safety remain authoritative; no world changes or implicit clearing and no extra mandatory verification calls.
- Added generator geometry/schema tests and disposable `e2e-generators.mjs`: 9,933 native checks, 12 hip-roof frames, generation/overwrite rejection atomicity, static preview/live parity; 45 native staircase surface samples and reload persistence verified. Terrain integration remains a later step.
- Shape-aware schematic `mc_render` isometric/perspective views and optional `mc_plan.preview`: stair/slab/door/trapdoor orientation, stored connection geometry, glass blending and camera/depth framing. Main-thread tick-budgeted capture, off-main bounded rendering; explicit approximations/fallbacks, adaptive resolution and transparency caps. Existing flat views remain separate. An angled render replaces, rather than adds to, the default single appearance check; no mandatory planning/exact audit.
- Added geometry/camera/renderer/schema tests and disposable `e2e-shapes.mjs`, with static virtual/live image parity and read-only sign/state/snapshot checks. Native geometry probe confirms 75 stair/slab/door/trapdoor states against server shapes; not a texture/resource-pack renderer.
- Lightweight default agent policy: one relevant survey and one finished-build render for substantial builds, targeted inspection/correction only for concrete issues, no routine stacked/repeated full checks. Site analysis and exact verification remain optional/opt-in. Mandatory plugin-side structural safety and rollback precautions are unchanged; development acceptance tests are not per-build agent steps.

- New `mc_verify` freezes eligible final cells before placement and completely checks actual canonical states and supported sign values afterward. Bounded expiring owner-scoped in-memory receipts retain keep/filter eligibility and survive source blueprint deletion. Exact coordinate/property diagnostics, full counts and fresh comparison IDs; no world writes or snapshot creation.
- New `mc_repair` repairs only reviewed mismatches from the latest comparison, with explicit subsets, strict selection caps, stale-value guards, block-entity replacement protection and optional delta snapshots, then verifies the full receipt again. Matching cells and neighbor refreshes are never written. Static physics-disabled writes only; flowing plans cannot be auto-repaired. Existing snapshots do not back up sign text, inventories or arbitrary NBT.
- Optional placement comparison explicitly excludes a narrow set of automatically generated connection properties; exact remains the default. Native tests cover all transformed blueprints, sign patches, conditional eligibility, paired chest/inventory preservation, stale/oversized selections and idempotent no-op repairs.

- New readonly `mc_plan` tool validates direct builds/saved blueprints, simulates fills/text/blocks with a virtual overlay in the tick-budgeted executor, and returns bounds, material/change/collision/overlap counts and samples, door/bed pairing errors and common support warnings, including neighboring support removal. Never places temporary blocks or creates snapshots.
- Optional map-color top/facade/slice previews render off the main thread. Bounded slices read only their plane; large-envelope image requests reject safely. These previews explicitly exclude automatic connections, fluid/entity simulation and block-entity/NBT edits; shape-aware angled views were subsequently added as a separate renderer.
- Production `mc_build` validates every phase, sign metadata and aggregate/current server limits before any snapshot/write. `dryRun:true` returns readonly diagnostics; `preflight:true` adds strict final-site pairing/support checks. Signed coordinates and text scale/spacing reject fractional/overflow values rather than truncating. Executor bounding-envelope chunk tickets are capped, preventing widely separated fills from bypassing footprint guards.

- New `mc_blueprint` tool saves/gets/lists/deletes persistent version-1 designs with named flat components, project metadata, material palettes and repeated instances. Storage uses safe names, explicit overwrite, bounded files/counts and atomic replacement; these actions never modify world blocks.
- `mc_build` accepts a saved `blueprint:{id,palette}` instead of direct operations. Component orientation, project-frame repetition and project transforms compose correctly. `$role[property=value]` supports material substitution with explicit precedence and property overrides. All components compile into the existing global fills/text/blocks passes with one union snapshot.
- Blueprint expansion enforces operation/instance caps, aggregate requested block/flow volume and union chunk footprint against current server limits. All compiled states use a request-local orientation-aware native cache and are validated before snapshots or writes.
- Added compiler/storage tests and `e2e-blueprints.mjs`; verified 5,553 native blueprint checks, persistence across reload, non-destructive document deletion, and the full Step 1 transformation regression suite. Nested components, previews and enforced design constraints are not included in version 1.

- `mc_build` gains an optional local coordinate `transform`: required world `origin`, clockwise quarter-turn `rotation`, and local `mirror` (`none`, `x`, `z`). Applies mirror, rotation and translation to fills, sparse blocks and expanded text before snapshots or writes. Calls without a transform are unchanged.
- Native Paper block-data transforms preserve directional states and omitted defaults; partial fill filters retain wildcard properties. Sign content is preserved, mirrored block lettering mirrors its glyphs, and paired blocks must still be supplied explicitly.
- Local inputs reject fractional/out-of-range integers and transformed coordinate overflow. Added coordinate, geometry, filter and compatibility tests.
- Correct mirrored corner-stair handedness independently of facing: Paper's native mirror leaves some inner/outer left/right shapes unchanged. Verified the fix against a geometric oracle on Paper 26.2 build 132.
- Added an opt-in disposable-server MCP acceptance test (`mcp-server/tools/e2e-transforms.mjs`): 116,844 checks across exact and default connection modes, partial filters, paired structures, signs, lettering, restoration and multi-batch transforms.

## 0.4.9

- New `engine.text-font-file` config key (hot, `/ashlar reload` applies it): points `mc_build`'s `text` entries at a `.ttf`/`.otf`/`.ttc` file for non-ASCII (CJK) lettering instead of this JVM's system font - the fix for a Docker container with no CJK font installed, since a mounted file needs no image rebuild or restart. Empty (the default) keeps the previous behaviour; a path that does not exist, is a directory, or fails to load as a font logs one warning and falls back to the system font rather than stopping the server.
- `/ashlar undo`: rolls back the player's own last assistant build with no model call - restores the newest snapshot that player's own requests created (through the same validation/executor/connect-support passes `mc_restore` uses) and marks it undone, so a second `/ashlar undo` goes one step further back. An MCP client's snapshots are never touched. A reply that created a snapshot now hints at this in its usage footer.
- `mc_build` `text`: a character the server's Java has no font for now fails the call with a message naming the fix (install a CJK font, or use ASCII) instead of rendering the JVM's missing-glyph box, which at block scale looked like deliberate rectangles.
- `mc_build` `text` gains `align`: `"left"` (default, unchanged) or `"center"`, where `pos` is the bottom-CENTRE block of the first line instead of its bottom-left (the text extends half its width to each side, with any odd leftover block on the advance-positive side). The in-game assistant now anchors free-standing lettering on the requesting player alone (their own position, facing and lookingAt) and never surveys to find a place for it - fixing a real session where a request to write four characters "in front of me" triggered six blind `mc_survey` calls over six different areas and never placed a block. The engine itself now backs this up: within one request, the 2nd+ call to `mc_survey`/`mc_render` gets a note nudging the model to stop searching, and from the 4th call on the image is withheld from the model entirely (the call still succeeds) so no amount of prompt-wording drift can cause an unbounded search loop.

## 0.4.8

- `mc_build` gains `text` entries: lettering rendered by the plugin instead of guessed block-by-block by the model. A built-in 5x7 bitmap font covers printable ASCII; any other character (CJK etc.) is rendered through this server's Java (`java.awt`) at 12 rows (up to 12 columns) per glyph, and fails the whole call with a clear error if this JVM has no fonts for it. `facing` (`south`/`north`/`east`/`west`/`up`) picks which way the text reads, `scale` (1-4) and `spacing` (0-3) control size, an optional `background` paints a solid plaque behind the letters - omit it and only the ink is written, the gaps stay untouched. Executes via the same `fill_batch` machinery as `fills` (one run per row of pixels), so it counts against the same block/chunk limits and gets the same connect/support-warning handling; the response gets a new "Text:" section reporting each entry's exact bounding box.
- `/ashlar reload` (also `ashlar reload` from the console): re-reads `config.yml` and applies most keys immediately - model/limits/pricing/cooldown/language/world/snapshot/engine/logging and more - without a restart. Every key in `config.yml` is now commented `Reload` or `Restart`; a changed `Restart` key (`mode`, `server.host/port/token`, `limits.tick-budget-ms`/`max-queued-operations`, `agent.limits.max-concurrent`) is listed in the reply but still needs one. An invalid file is rejected with the error, leaving the running config untouched.
- One top-level `mode` key replaces `agent.mode`: `both` (default - WebSocket server for MCP clients plus the in-game assistant), `mcp` (WebSocket only, `/ashlar` off), `ingame` (`/ashlar` only: no WebSocket server, no port, and `server.token` is not required any more) or `external` (WebSocket plus `/ashlar` forwarded to a connected external process). A config without `mode` keeps working: it is derived from the old `agent.mode` and the log says which value to add. Fixes a latent bug where an unquoted `agent.mode: off` was read as YAML `false` and ignored.
- Java package renamed from `net.rcwalter.ashlar` to `cc.wujm.ashlar` (Gradle group `cc.wujm`). No user-visible change: the plugin name, data folder (`plugins/Ashlar/`), config, snapshots and permission nodes are unaffected - drop in the new jar.

## 0.4.7

- New top-level `language` config key (`en`/`zh_CN`/`auto`, default `en`): everything the plugin itself says in chat - `/ashlar` usage/help lines, permission/cooldown/"not configured" messages, progress lines, the usage footer, and every `/ashlar usage`/`limit`/`credit`/`pause`/`resume`/`cancel` reply - can now be shown in Simplified Chinese instead of English. `auto` follows each player's own client language (falling back to English for anything not shipped) with the console always in English; `ashlar simulate` uses the configured language like a player would. The AI's own replies were already following the player's request language and are unaffected; logs, RPC errors, tool descriptions/results and config.yml comments stay English-only. Ships a Chinese README (`README.zh-CN.md`, linked from the top of both READMEs).

## 0.4.6

- `mc_inspect` gains `format: "columns"`: an exact bottom-to-top block-run list for every column in the region, one line per column (`x,z: y1-y2 block | y3-y4 block | ...`, ids without the `minecraft:` prefix), capped at 1024 columns and exclusive with `slice`. Lets the in-game assistant read a damaged area (a crater, a hole, a gap in a wall) in one call instead of one `mc_inspect` slice per layer plus repeated single-column probes.
- Assistant prompt: repairing terrain now calls for exactly one read over the damaged area (`mc_survey` with `matrix: true` for surface-only damage, `mc_inspect format: "columns"` when there may be overhangs or the damage is inside a structure) followed by one `mc_build`, instead of inspecting layer by layer or widening the search area.
- Paper 26.3 support (tested on build 5). The plugin loaded fine but crashed the server on the first read: 26.3's alpha builds run the vanilla chunk system, which throws when a chunk ticket is removed in the same tick the chunk was loaded. Finished tasks now keep their chunk tickets for at least one second (`TickBudgetExecutor.MIN_TICKET_HOLD_TICKS`); 26.2 is unaffected, and the new poplar/wool-stairs/straw-bed blocks need no plugin changes.
- Connection pass no longer knocks down sand: refreshing a fence/pane/wall next to an unsupported gravity block, or next to water that had somewhere to flow, scheduled the physics tick that "no physics" was supposed to avoid - it showed up whenever a player stood nearby (the chunk ticked), and the longer ticket hold above made it show up always. Such a cell is now left unrefreshed (verified on the test server: unsupported sand next to a fence stays; fences next to supported sand still connect).
- Unsupported gravity blocks (sand, gravel, concrete powder, anvils with nothing solid below) are listed in `mc_build`'s support warnings so the model fixes them; `mc_restore` does not report them (natural terrain is full of gravel over cave air).

## 0.4.4

- `/ashlar usage [player|all] <days>` / `/ashlar usage [player|all] <from> <to>`: a per-day usage report (last N days, N in 1-31, or an explicit inclusive UTC date range, also capped at 31 days) alongside the existing today/total summary, for a player or server-wide. A range date may be written as `YYYY-MM-DD`, `YYYYMMDD`, or `MM-DD`/`M-D` (current UTC year); a reversed `from`/`to` is swapped and a future date clamps to today. The caller's own usage with a range needs only `ashlar.use`; naming another player or `all` still needs `ashlar.monitor`. `UsageStore` keeps a rolling 90-day per-day history per player (pruned on save); an old usage file without it gains one day of history, backfilled from its own last-saved "today" on load.

## 0.4.3

- Assistant prompt: a request about an existing build (rebuild it, change the roof, use another material) is anchored on that build's own location, never on where the player stands now; only new things or an explicit "here" use the player's position.
- Locally built jars are versioned `<version>-dev`; only the release workflow produces the bare version number.

## 0.4.2

- Prepaid credit: `/ashlar credit <player> [add|set|off] [amount]` gives operators a way to fund the assistant for a player out of someone else's money rather than the server's own daily budget. Checked before a request starts (like the daily limits) and again after every model turn; when a balance hits zero mid-request the current tool call finishes and the model gets one final, tool-free turn to summarise what was done, what is left, and the snapshot id - the reply gains a line asking the player to get topped up and say "continue". The usage footer and `/ashlar usage` show the balance once it is enabled; players without credit are unaffected.
- `mc_build` gains `liquids: "flow"`: water and lava targets are placed with physics so a single source at the top of a fountain or waterfall actually flows (default `"static"` is unchanged). Capped by `limits.max-flowing-liquids-per-operation` (default 2000). The engine keeps the affected chunks ticking for 10 s after such a build so the fluid spreads even with no player nearby.
- Players' descriptions (`mc_players` and the assistant's request context) include `lookingAt`: the block the player's line of sight hits, its state and the face seen, so "this wall"/"on the house" resolves without searching; the assistant's prompt scales its survey/verify effort to the size of the request.
- Adjacent chests with the same facing written by `mc_build` are paired into double chests (`chestsPaired` in the result); trapped and normal chests never pair with each other.

## 0.4.1

- History between requests keeps only the player's text and the final reply (no tool traffic), keeping follow-up context small; `/ashlar reset` clears it; the system prompt asks the model to make final replies self-sufficient (bounding box, materials, snapshot id).

## 0.4.0

0.3.0 was not published separately; this release includes it. The in-game assistant now runs inside the plugin: `/ashlar` needs no Node process and no inbound port, just a model API key in `config.yml`.

### Plugin (`plugin/`)

- Embedded agent: a pure-Java agent core (`cc.wujm.ashlar.agent`, no Bukkit dependency) ported from the Node `--agent` implementation - `ModelClient` (OpenAI-compatible chat-completions over `java.net.http`, retrying 429/5xx with backoff), `Pricing`/`UsageStore` (peak/off-peak pricing, per-player daily limits, atomic debounced persistence), `HistoryStore` (bounded turns, TTL, image redaction) and `AgentRunner` (the tool-calling loop against the plugin's own `ToolRegistry`).
- `AgentService`: per-player serial request queue plus a global concurrency cap on a virtual-thread executor, progress throttling, per-turn usage accounting (every model call is recorded immediately, including on cancellation or failure), and a cost/token footer on every final reply.
- New `agent:` config keys: `agent.mode` (`embedded`/`external`/`off`, default `embedded`), `agent.model.*` (provider, credentials, tool-call budget, timeouts, image detail, system prompt file, `mc_command` opt-in), `agent.limits.*` (daily caps, concurrency, history), `agent.pricing.*` (peak/off-peak pricing). Usage, limit overrides and the pause flag persist to `plugins/Ashlar/usage.json`.
- `AshlarCommand` calls `AgentService` directly in embedded mode (request, cancel, usage, limit, pause, resume) instead of broadcasting chat events; `external` mode keeps the 0.2 event-broadcast path for a connected integrator process; `off` disables `/ashlar` entirely.
- Console-only `ashlar simulate <x> <y> <z> [facing] <request>`: drives one request through the same code path with a synthetic player position, progress and replies printed to the console - the way to test the assistant without a player online (replaces `tools/agent-sim.mjs`).
- Cancellation now reaches a running fill: `/ashlar cancel` stops between tool calls as before, but `onDisable` cancels every in-flight request and gives the executor up to 5 seconds to drain before shutting down.
- `ChatOut`: the assistant's message-sending logic (progress lines, final replies, monitor echo) is shared between the embedded and external paths instead of living only in the `send_message` RPC handler.

### MCP server (`mcp-server/`)

- `--agent` removed: `src/agent/` (the OpenAI-compatible provider, runner, usage/pricing/history stores, admin commands) is deleted along with `tools/agent-sim.mjs`. Running `ashlar-mcp --agent` now prints a message pointing at `agent.mode: embedded` in the plugin's `config.yml` and exits 2, instead of silently doing nothing useful.
- `@modelcontextprotocol/client` moves back to `devDependencies` - only `tools/e2e.mjs` and the test suite use it now that there is no in-process MCP client driving the agent loop.
- `--help` no longer documents any `AI_*` variable or `--agent` mode.

### Upgrading from 0.2.0

See the README's ["Upgrading from 0.2"](README.md#upgrading-from-02) section under "In-game assistant": stop the old Node-based assistant process, optionally carry over its usage file to `plugins/Ashlar/usage.json`, and move its environment-variable settings into the new `agent.*` keys in `config.yml`.

### Known limitations (tracked for later)

- `mc_inspect` slices merge by block type, not full state, once a slice has more than 47 distinct types.
- Blocks that share a Minecraft map color (e.g. stone/stone bricks/cobblestone) can be indistinguishable in `mc_render`/`mc_survey` images.
- No `/ashlar undo` yet - players have to ask the assistant to restore the snapshot it took.
- An in-progress build cannot be cancelled mid-fill; `/ashlar cancel` now stops at the next tick slice of a running `mc_build` fill rather than only between whole tool calls, but not inside the slice itself.
- Snapshots do not capture block entity contents (sign text, container items); `restore` loses them.

## 0.3.0

The tool layer moves into the plugin: tool descriptions, JSON schemas and result formatting are now owned by the Java side, and the MCP server becomes a thin protocol adapter.

### Plugin (`plugin/`)

- `InvocationContext` decouples tool execution from the WebSocket session, so tools can run for in-process callers (the in-game assistant) as well as RPC clients; handlers are split into services.
- A main-thread guard protects in-process callers, and `MainThread.call` now completes its futures off the main thread.
- The nine tools (`mc_status`, `mc_players`, `mc_survey`, `mc_render`, `mc_build`, `mc_inspect`, `mc_snapshot`, `mc_restore`, `mc_command`) are Java classes (`tool/mc/`), each with a description and JSON Schema in a resource file (`resources/tools/*.json`) plus a shared `instructions.txt`.
- Model-facing result text formatting (headers, warnings, ASCII maps, error text) is ported to Java (`tool.text`), with cross-language golden files (`plugin/src/test/resources/goldens/`) as the reference.
- Two new RPCs: `tool_catalog` (returns the tool specs plus the `instructions` text) and `tool_call` (runs one tool by name and returns its formatted result).

### MCP server (`mcp-server/`)

- Generic adapter: `ashlar-mcp` no longer contains any Ashlar-specific tool code. On connect it fetches the catalog via `tool_catalog` and registers every tool exactly as the plugin describes it; each call is forwarded to `tool_call` and the result passed through untouched.
- `mcp-server/src/tools/*` (nine tool modules, schemas, result formatting) deleted along with their tests; only a small `ContentBlock` helper remains.
- `ashlar-mcp` 0.3 requires plugin >= 0.3.0: it fetches the catalog at startup and exits with a clear message if the plugin is too old to answer `tool_catalog`. Plugin 0.3 still serves every RPC from 0.1/0.2, so an older `ashlar-mcp` 0.2 keeps working against it.

### Known limitations (tracked for later)

- Snapshots do not capture block entity contents (sign text, container items); `restore` loses them.
- `mc_inspect` slices merge by block type, not full state, once a slice has more than 47 distinct types.
- Blocks that share a Minecraft map color (e.g. stone/stone bricks/cobblestone) can be indistinguishable in `mc_render`/`mc_survey` images.
- No `/ashlar undo` yet - players have to ask the assistant to restore the snapshot it took.
- An in-progress build cannot be cancelled mid-fill; `/ashlar cancel` takes effect between tool calls, not inside one.
- Daily per-player request/token/cost counters and limit overrides are read at `--agent` startup and saved debounced/on close; a hard crash between saves can lose the last few seconds of counters (the file itself, and the pause flag, do survive a clean restart).
- The Node adapter registers the catalog once at startup (no `tools/list_changed`); a plugin reload with changed tool specs needs `ashlar-mcp` restarted to pick them up.

## 0.2.0

In-game AI assistant: players can now ask for a build directly in chat, without any MCP client on their own machine.

### Plugin (`plugin/`)

- `/ashlar <request>` / `/ashlar cancel` command, gated by the `ashlar.use` permission (default op).
- `agent:` config section: `enabled`, `cooldown-seconds`, `max-message-length`, `echo-to-monitors`.
- New RPCs: `subscribe` (lets an `--agent` process receive `chat`/`chat_cancel` events) and `send_message` (the assistant's replies back to a player, `[Ashlar] `-prefixed; takes an optional `kind: "progress"|"final"`).
- Chat and cancel requests are broadcast as events to the subscribed connection; per-player cooldown and message-length checks happen before broadcasting.
- `ashlar.monitor` permission (default op): players with it see a compact echo of every other player's `/ashlar` request and final reply, but none of the progress lines; off switch is `agent.echo-to-monitors`.

- Plugin-local allow list (`/ashlar allow|deny|allowed`, `allowed-players.yml`) and `agent.everyone-can-use`; explicit permission-plugin grants/denials take precedence over the list.

### MCP server (`mcp-server/`)

- `ashlar-mcp --agent`: a new mode with no MCP transport, driving an OpenAI-compatible chat-completions model (DeepSeek by default) through the same nine tools via an in-process tool bridge (no tool code duplication).
- `AI_*` environment variables: provider (`AI_BASE_URL`, `AI_API_KEY`, `AI_MODEL`), limits (`AI_MAX_TOOL_CALLS`, `AI_MAX_REQUESTS_PER_PLAYER_PER_DAY`, `AI_MAX_CONCURRENT`), `AI_ALLOW_COMMAND` to opt `mc_command` into the assistant's tool list, history (`AI_HISTORY_TURNS`, `AI_HISTORY_TTL_MINUTES`), `AI_IMAGE_DETAIL`, `AI_SYSTEM_PROMPT_FILE`, `AI_REQUEST_TIMEOUT_MS`.
- Per-player serial request queue, a global concurrency cap, and a per-player daily request counter.
- Per-player conversation history (bounded turns, idle TTL, old images redacted to keep token cost down).
- Progress lines (`> mc_build from=... to=...`) and the final reply sent back through `send_message`, chunked to fit chat; final chunks are marked `kind: "final"` so the plugin can echo them to monitors.
- System prompt: interior furniture must stay clear of doors and walkways, verified with an `mc_inspect` floor-level slice before replying.
- `--help` now documents `--agent` and its environment variables alongside `--stdio`/`--http`.
- `tools/agent-sim.mjs`: drives one request through the same runner without a player online or a real model call being required to set up, for local testing.
- Per-player usage and cost accounting: every model call's normalised token usage (`agent/provider.ts`, cached tokens from DeepSeek's `prompt_cache_hit_tokens`/OpenAI's `prompt_tokens_details.cached_tokens`) is summed per request (`runRequest` now resolves `{text, usage, toolCalls}`) and priced by `agent/pricing.ts`'s peak/off-peak schedule (`AI_PEAK_HOURS`, `AI_OFF_PEAK_MULTIPLIER`, default matching DeepSeek's own mon-fri 01:00-04:00/06:00-10:00 UTC peak windows at half price off-peak).
- `agent/usage.ts`'s `UsageStore`: persists per-player today/total token and cost counters, per-day limit overrides (cost/tokens/requests, `off` = unlimited) and a global pause flag to `AI_USAGE_FILE` (default `./ashlar-usage.json`), atomically and debounced; `AI_PRICE_INPUT`/`AI_PRICE_CACHED_INPUT`/`AI_PRICE_OUTPUT`/`AI_CURRENCY` price it, `AI_MAX_TOKENS_PER_PLAYER_PER_DAY`/`AI_MAX_COST_PER_PLAYER_PER_DAY` join `AI_MAX_REQUESTS_PER_PLAYER_PER_DAY` as the env-level daily caps.
- Every final reply gets a usage footer, e.g. `(this request: 21.9k tokens, $0.0061 | today: $0.04 of $1.00)`.
- New `ashlar.admin` in-game commands (via the plugin's `admin` chat-subscription event, `agent/admin.ts`'s `createAdminHandler`): `/ashlar usage [player|all]`, `/ashlar limit [player] <cost|tokens|requests> <value|off>` / `... reset`, `/ashlar pause`/`resume`, `/ashlar cancel <player>`.

### Known limitations (tracked for later)

- Snapshots do not capture block entity contents (sign text, container items); `restore` loses them.
- `mc_inspect` slices merge by block type, not full state, once a slice has more than 47 distinct types.
- Blocks that share a Minecraft map color (e.g. stone/stone bricks/cobblestone) can be indistinguishable in `mc_render`/`mc_survey` images.
- No `/ashlar undo` yet - players have to ask the assistant to restore the snapshot it took.
- An in-progress build cannot be cancelled mid-fill; `/ashlar cancel` takes effect between tool calls, not inside one.
- Daily per-player request/token/cost counters and limit overrides are read at `--agent` startup and saved debounced/on close; a hard crash between saves can lose the last few seconds of counters (the file itself, and the pause flag, do survive a clean restart).

## 0.1.0 - v1

First release. A Paper plugin plus a Node MCP server that give an AI client nine tools to survey, render, build, inspect, snapshot/restore and run commands on a live Minecraft server.

### Plugin (`plugin/`, Paper 26.2, Java 25)

- WebSocket RPC server with token authentication (`MessageDigest.isEqual` comparison), an `allowed-ips` allow-list, and an auth timeout on new connections.
- Bulk block engine: `fill_batch` (modes `replace`/`keep`/`outline`/`hollow`/`walls`, optional `filter`) and `set_blocks` for individual placements, including sign text. All writes use `setBlockData(data, false)` (no physics), execute on the main thread inside a per-tick time budget, and force-load their chunks with a plugin chunk ticket for the duration of the operation.
- Limits enforced before any block is touched: `max-blocks-per-operation`, `max-chunks-per-operation`, `max-read-volume`, world allow-list, optional `build-region` bounding box.
- Read-only RPCs: `heightmap` (four Bukkit heightmap types, surface material breakdown), `read_region` (palette + run-length-encoded block data), `render` (PNG: top-down map-style view, four facade views, an exact-color slice, and a color-banded heightmap view with contour lines).
- `players` RPC: online players with block/exact position, yaw/pitch, facing direction, and the block in front of them.
- Snapshot/restore: `snapshot`, `restore`, `list_snapshots`, gzip-persisted to disk, capped and LRU-evicted by `max-snapshots`.
- `run_command`: runs a console command and captures its feedback text; can be disabled in config.
- Post-write shape/support passes: a connection pass so panes/fences/walls/bars/stairs/redstone wire connect to their placed neighbours, and a support-warning check that reports (without fixing) unsupported attached blocks such as ladders, wall torches, signs and carpets - including neighbours of blocks that were just cleared.
- `operations.log` audit trail of every executed RPC.

### MCP server (`mcp-server/`, TypeScript, MCP SDK v2, Node >= 22)

- Nine tools: `mc_status`, `mc_players`, `mc_survey`, `mc_render`, `mc_build`, `mc_inspect`, `mc_snapshot`, `mc_restore`, `mc_command`.
- stdio transport (default) and Streamable HTTP transport (`--http`), with bearer-token auth or a `/mcp/<token>` path form for clients that cannot set headers, plus Host/Origin header validation and an unauthenticated `/healthz`.
- A single long-lived, auto-reconnecting WebSocket client to the plugin (exponential backoff), shared across every MCP connection.
- `mc_survey`/`mc_render` heightmap view render a color-coded PNG with min/max/median height, surface material mix and the largest flat buildable zone; `format: "text"` keeps an ASCII relief map for text-only clients.
- `mc_inspect` decodes the plugin's RLE-encoded region data into block statistics or an ASCII slice with a legend.
- `mc_build` supports auto-snapshotting the affected region before a build and surfaces the plugin's support warnings in its response text.
- Plugin error codes are translated into model-actionable text (e.g. `VOLUME_EXCEEDED` suggests splitting the call).
- `MC_LOG_USAGE` / `MC_USAGE_LOG` per-call token/size accounting, plus a zero-dependency OBS browser-source overlay (`tools/overlay.mjs`).
- End-to-end test script (`tools/e2e.mjs`) driving every tool against a live plugin instance over both transports.

### Known limitations (tracked for later)

- Snapshots do not capture block entity contents (sign text, container items); `restore` loses them.
- `mc_inspect` slices merge by block type, not full state, once a slice has more than 47 distinct types.
- Blocks that share a Minecraft map color (e.g. stone/stone bricks/cobblestone) can be indistinguishable in `mc_render`/`mc_survey` images.
