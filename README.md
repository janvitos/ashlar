<p align="center"><img src="docs/images/icon.png" width="160" alt="Ashlar icon"></p>

# Ashlar

**English** | [简体中文](README.zh-CN.md)

AI building tools for Minecraft Paper servers - no SSH, no LAN world: one jar plus one URL.

![Temple built by Claude through this MCP](docs/images/showcase-temple.jpg)

*Built by Claude through this MCP.*

Ashlar is a Paper plugin plus a Node MCP server. Point an AI client - Claude Desktop, Claude Code, OpenCode, Cursor, or anything else that speaks MCP - at the MCP server, and it gets fourteen tools to survey terrain, render images of the world, build in bulk, inspect exact block data, snapshot/restore regions, and run console commands. No mods, no SSH access to the host, no need to run the world on your own machine: the plugin runs inside your existing Paper server (a panel-hosted one works fine) and talks to the MCP server over a WebSocket. Players who have no MCP client at all can instead just type `/ashlar <request>` in chat and get an answer from the plugin's own built-in assistant - no Node process or inbound port needed for that path; see [In-game assistant](#in-game-assistant-no-ai-client-needed) below.

## How it works

```
AI client                MCP server                 Paper plugin
(Claude/ChatGPT/...)     (Node, mcp-server/,         (Java, plugin/ - owns the
                          a protocol adapter)         tool layer: descriptions,
                                                       schemas, result text,
                                                       execution)

   mc_* tool call  --->  tool_catalog/tool_call  --->   main-thread block
   (stdio or HTTP)       over WebSocket RPC             writes, tick-budgeted
                         (ws:// / wss://)
        <---  text/image result  <---  JSON result / progress events

player's /ashlar  --->  plugin's built-in assistant  --->  model API (DeepSeek by default)  --->  same tools above
```

The tool layer lives entirely in the plugin, not in the MCP server: `ashlar-mcp` fetches the tool catalog (descriptions, JSON schemas, and the server-level instructions text) from the plugin at startup and just forwards calls, so there is one definition of every tool, reused by any MCP client and by the in-game assistant alike. The `/ashlar` path needs no Node process and no inbound port at all: the plugin calls the model API directly (an outbound HTTPS connection) and runs the same in-process tool layer the MCP adapter forwards to.

- Coarse-grained tools: one `mc_build` call places up to 500,000 blocks, instead of the AI placing blocks one at a time.
- All block edits run on the server's main thread, spread across ticks under a per-tick time budget, so a large build does not freeze the server or lag players.
- Physics is off while writing (sand does not fall, water does not flow); a connection pass afterward lets fences/panes/walls/stairs connect to their neighbours, and any block left without support is reported back as a warning instead of silently popping off.
- Every writing `mc_build`, `mc_repair` and `mc_restore` is journalled: `mc_restore {journal}` undoes just that call cell by cell, keeping anything changed since (reported as conflicts). `mc_build` can also snapshot the affected region before writing, so a whole region can be rolled back with `mc_restore`.

## The tools

| Tool | What it does |
|---|---|
| `mc_status` | Server/plugin health and queue length. |
| `mc_players` | Online players with position and facing ("here", "in front of me", "at my feet"). |
| `mc_survey` | Terrain survey of an x/z area: heightmap image plus exact numbers (min/max/median height, surface mix, largest flat zone); `format:"text"` for an ASCII map. |
| `mc_render` | One visual PNG check: shape-aware isometric/perspective depth, or flat top/facade/slice/heightmap views. Angled views need tight 3D bounds; top/heightmap stay area-priced. |
| `mc_build` | Places blocks in bulk (cuboid fills with modes replace/keep/outline/hollow/walls, individual blocks and sign text, plus lettering rendered by the plugin via `text`). |
| `mc_blueprint` | Optionally generate architectural parts or fit foundations/entrances to terrain; save/get/list/delete reusable designs. No world writes. |
| `mc_plan` | Read-only preflight and virtual-scene previews for direct builds or saved blueprints; collision/support/pairing diagnostics without placement. |
| `mc_verify` | Freeze expected cells before building; compare actual states/sign values afterward with exact coordinate/property differences. |
| `mc_repair` | Guarded repairs of mismatches from a fresh comparison, followed by full verification; no matching-cell or neighbor refresh writes. |
| `mc_inspect` | Exact block contents of a region (statistics, ASCII slice, sign text). |
| `mc_diff` | Read-only comparison of a live box against a snapshot, expected cells or a blueprint, with anomaly checks and a repairable diffId. |
| `mc_snapshot` | Save a region before changing it, list snapshots, or list/delete build journal entries (`touches` finds the calls that changed an area). |
| `mc_restore` | Roll a region back to a snapshot, or undo one journalled call selectively (safe/force, dry run). |
| `mc_command` | Run a server console command and return its output (escape hatch). |

Default lightweight flow for substantial builds: `mc_players` only for player context/safety -> one `mc_survey` (reuse a recent relevant survey) -> `mc_build` -> one `mc_render` for appearance. Tiny edits can rely on build feedback. If a concrete issue appears, inspect/correct only that area and recheck it if needed; no repeated whole-build verification loops. Keep rollback protection when editing existing terrain/structures, preferably `mc_build snapshot:true` rather than a redundant separate call; snapshots do not cover NBT. Coordinates: X grows east, Z grows south, Y grows up; `from`/`to` corners are inclusive.

## Local coordinates, rotation and mirroring (fork enhancement)

`mc_build` accepts an optional `transform` for designing in a local coordinate frame:

```json
{
  "transform": { "origin": [100, 64, 200], "rotation": 90, "mirror": "none" },
  "fills": [
    { "from": [0, 0, 0], "to": [8, 0, 6], "block": "minecraft:stone_bricks" }
  ],
  "blocks": [
    { "pos": [4, 1, 0], "block": "minecraft:oak_stairs[facing=north,half=bottom]" }
  ]
}
```

- `origin` is required when `transform` is present. Every fill corner, block position and text position is then local to that world origin.
- `mirror` defaults to `none`: `x` negates local X (reflect across YZ); `z` negates local Z (reflect across XY). Y is never flipped.
- `rotation` defaults to `0`: `0`, `90`, `180` or `270` degrees clockwise viewed from above. At `90`, east becomes south and north becomes east.
- Order is **mirror -> rotate -> translate**. Rotation and mirroring pivot around local `[0,0,0]`, not the building's center. Negative local positions are allowed.
- Paper's native block-data transformations handle facing, axes, rail shapes, door hinges and standing-sign rotation, including omitted default properties. Ashlar additionally corrects corner-stair handedness on reflection: native mirroring alone preserves the wrong left/right shape for some facing/axis combinations. Provide BOTH halves of doors and beds; no new blocks are synthesized.
- Fill filters rotate/mirror too, while omitted properties remain wildcards. Expanded lettering and backgrounds transform geometrically; mirroring reverses block-letter glyphs. Sign strings are not reversed.
- Transformed states are cached per request and processed in bounded main-thread batches before snapshots or writes. Existing connection updates can subsequently recompute stair/fence/pane shapes; use `connect:false` when testing exact state transforms.
- Local coordinates and origins must be signed 32-bit integers; fractional inputs and overflowing world results are rejected. Existing calls without `transform` retain their absolute-coordinate behavior.
- Build reports, snapshots, surveys and inspections use **world coordinates**. This is a plugin-side feature; the MCP adapter forwards the updated schema without changes.

### Transformation acceptance test

After building the plugin and running `npm ci && npm run build` in `mcp-server/`, the opt-in test is:

```sh
ASHLAR_DISPOSABLE_TEST=1 \
  MC_PLUGIN_URL=ws://127.0.0.1:<test-port> \
  MC_PLUGIN_TOKEN=<test-token> \
  node tools/e2e-transforms.mjs
```

Run this **only on an isolated disposable Paper server** with no connected players. It writes fixed regions around `[0,100,0]` and `[64,100,0]`, snapshots and restores them, and requires loopback connectivity and explicit opt-in. It checks all 12 rotation/mirror combinations with and without the connection pass, partial filters, paired blocks, sign content, rendered lettering, snapshot restoration, invalid-state rejection and multi-batch state transforms. It does not start or stop the server.

## Reusable blueprints and components (fork enhancement)

Use `mc_blueprint` to optionally **generate** architectural parts, or **save**, **get**, **list** or **delete** persistent project documents. These operations do not place blocks. Documents are shared in the plugin's `blueprints/` directory and survive plugin reloads and server restarts. Replacing an existing ID requires `overwrite:true`; deletion never removes existing world structures.

Example arguments to `mc_blueprint`:

```json
{
  "action": "save",
  "id": "window_row",
  "document": {
    "version": 1,
    "description": "Three matching windows",
    "dimensions": [21, 5, 1],
    "constraints": { "style": "stone cottage" },
    "palette": { "frame": "minecraft:stone_bricks", "glass": "minecraft:light_blue_stained_glass" },
    "components": {
      "window": {
        "fills": [
          { "from": [0, 0, 0], "to": [4, 0, 0], "block": "$frame" },
          { "from": [0, 4, 0], "to": [4, 4, 0], "block": "$frame" },
          { "from": [0, 1, 0], "to": [0, 3, 0], "block": "$frame" },
          { "from": [4, 1, 0], "to": [4, 3, 0], "block": "$frame" },
          { "from": [1, 1, 0], "to": [3, 3, 0], "block": "$glass" }
        ]
      }
    },
    "instances": [
      { "component": "window", "pos": [0, 0, 0], "repeat": { "count": 3, "step": [8, 0, 0] } }
    ]
  }
}
```

Then build with `mc_build`, choosing a surveyed world origin:

```json
{
  "blueprint": { "id": "window_row", "palette": { "frame": "minecraft:sandstone" } },
  "transform": { "origin": [100, 64, 200], "rotation": 90 },
  "snapshot": true
}
```

- Version 1 has **flat named components**, each containing the same local `fills`, `blocks` and/or `text` entries as `mc_build`. No nested references or cross-document component links yet.
- Every instance names a component and supplies a local `pos`. Optional `rotation`/`mirror` orient that component. `repeat:{count,step}` repeats it in the **project frame**, not its own rotated frame; negative and vertical steps are allowed.
- Order: component mirror/rotation -> instance position plus repeated offset -> project mirror/rotation -> world origin. Without a project transform, the project origin is world `[0,0,0]`.
- Materials use `$role` or `$role[property=value]`. Inline properties override bound properties; for example `$trim[facing=north]` can bind to `minecraft:oak_stairs[half=top]`. Palette precedence is **component defaults < document palette < build overrides < instance overrides**. Recursive palette bindings are rejected. Literal block states still work.
- Sign strings and lettering content are not substituted. Mirrored lettering follows Step 1's geometric rules; sign strings remain readable.
- Compilation uses the existing **all fills -> all text -> all blocks** passes, preserving instance order within each pass. Overlaps follow those passes, not whole-component sequential writes. One optional snapshot covers the union of final world-space bounds.
- The compiler bounds expansion and checks aggregate requested volume, flow targets and union chunk footprint against current server limits. All compiled block states are prepared before snapshots/writes, with a request-local orientation-aware cache. Read-only previews and collision/support preflight are now available through `mc_plan` (see below).
- `description`, `dimensions` and `constraints` are retained as **advisory metadata**, not enforced constraints. Missing material-role bindings may be saved and supplied later; they must resolve before building.
- Limits: 256 saved documents, 2 MiB per document, 128 components, 10,000 raw operations, 4,096 expanded instances and 100,000 expanded operations, plus existing server block/chunk/liquid limits. IDs and component/role names use a lowercase letter followed by letters, digits, `_` or `-`, at most 64 characters.

The opt-in `mcp-server/tools/e2e-blueprints.mjs` acceptance test uses the same disposable-server environment variables as the transformation test. It reserves regions around `[128,100,0]` and `[256,100,0]`, restores them in cleanup, and removes its generated document by default. `ASHLAR_TEST_KEEP_BLUEPRINT=1` retains that document for a separate reload-persistence check. It never starts, stops or reloads the server.

## Architectural generators (fork enhancement)

Optional generators save ordinary reusable version-1 blueprints. They do **not** place blocks, read terrain or clear interiors. For example:

```json
{
  "action": "generate",
  "id": "stone_hip_roof",
  "generator": {
    "kind": "roof", "style": "hip", "width": 13, "depth": 17,
    "materials": {
      "full": "minecraft:stone_bricks",
      "stairs": "minecraft:stone_brick_stairs",
      "slab": "minecraft:stone_brick_slab"
    }
  }
}
```

Then use the existing placement path:

```json
{
  "blueprint": { "id": "stone_hip_roof" },
  "transform": { "origin": [100, 70, 200], "rotation": 90 },
  "connect": false,
  "snapshot": true
}
```

- **Roofs:** `gable` (default), `hip`, `shed`; width/depth 3-64, defaults 9/11. Slope 1:1; footprint includes desired eaves. Gable ridge along Z, shed uphill east. Optional `gableInfill:true` fills gable-end triangles only. Odd ridges use bottom slabs; hip corners use explicit outer stairs. `connect:false` preserves requested corner shapes.
- **Arches:** `round` / `pointed`, odd width 3-63 (default 9), pier/spring-line `height` 1-32 (3), `depth` 1-16 (1), `thickness` 1-8 and <= (width-1)/2. Pointed-only `rise` 2..(width+1)/2, default maximum. Arch spans XY and extrudes Z. Full-block stepped curves with bridges for face connectivity; opening is omitted, not erased.
- **Towers:** circular shell, `diameter` 5-64 (13), wall `height` 3-64 (12), nominal radial `thickness` 1-8 and <= (diameter-3)/2. Thin corner bridges keep walls face-connected. `floor` / `battlements` default true; `merlons` 4-32 (8) selects alternating angular sectors with voxel approximations. Height excludes the merlon layer. No entrance, roof or implicit interior clearing.
- **Stairs:** `straight` / `switchback`, per-flight `width` 1-16 (3), `steps` 1-64 (8), `landing` depth 1-16 (2), switchback-only `gap` 1-8 (1). First flight south; return flight north on the east side, with turn/exit landings. One-block rise/run, lower-half stairs. `supports:true` adds solid plinths; false omits them. No railings or terrain adaptation.
- Optional `materials:{full,stairs,slab}` overrides defaults (dark oak roofs, stone-brick arches/towers, oak staircases). Concrete native states and role types are checked **before saving**. Generated orientation/half/corner properties override material defaults. Normal build palette precedence and project transforms still apply.
- Generation caps 200,000 requested cells and 10,000 raw operations; adjacent identical runs are compressed. Invalid/inapplicable parameters, budgets and native states reject without replacing existing documents. Existing IDs require `overwrite:true`; regular persistence quotas apply. Response contains saved metadata, bounds, counts, palettes and assumptions, not a giant geometry dump; `get` retrieves the document. `mc_build` still validates live world/write/snapshot limits before placement.
- Compose generated components through existing version-1 blueprints. Explicitly clear an opening/interior only when appropriate, with rollback protection for existing terrain/structures. Planning and exact verification remain optional/opt-in; generator calls are not mandatory for ordinary builds.

Disposable `mcp-server/tools/e2e-generators.mjs` reserves `[896,99,-40]` through `[976,125,40]`, restores it and deletes generated documents by default. It checks native states, all 12 hip-roof frames, rejection atomicity and virtual/live gallery parity; saves PNGs under `/tmp/ashlar-step6-previews` (`ASHLAR_GENERATOR_OUTPUT` override). Diagnostic `ASHLAR_TEST_KEEP_BLUEPRINT=1` retains documents; `ASHLAR_TEST_KEEP_SCENE=1` retains the world fixture for a read-only probe and must be explicitly restored afterward. No lifecycle management. These acceptance tests are not per-build agent steps.

## Terrain-aware foundations and entrances (fork enhancement)

`mc_blueprint action:"fit"` is an **optional authoring helper**, not an extra mandatory site audit. It takes one bounded, tick-budgeted terrain capture and saves an additive, site-specific blueprint. Select `floorY` (walking level) using a recent relevant survey or design; it does not guess a safe global height under trees/buildings.

```json
{
  "action": "fit", "id": "hill_foundation",
  "site": {
    "from": [100, 200], "to": [112, 214],
    "floorY": 75, "maxDepth": 16,
    "mode": "piers", "spacing": 4,
    "entrance": { "facing": "south", "width": 3, "maxRun": 12 }
  }
}
```

- Inclusive footprint `[x,z]` corners, 1-64 cells per axis; optional `world`. Deck blocks are at `floorY-1`; two blocks of footprint/entrance headroom must be empty. `maxDepth` 1-64 (32) bounds searches below the deck. Read envelope includes that depth, headroom and the optional entire entry run; cap 200,000/current `max-read-volume`, world/build-region/Y/chunk restrictions apply before capture.
- `solid` fills each column above its observed anchor through the deck. `piers` uses grid intersections plus far edges (`spacing` 1-16, default 4), with a full deck between supports. It can span interior gaps but all selected piers need anchors. This is schematic spacing, not structural engineering or survival/environment simulation.
- A conservative whitelist accepts natural full-block anchors (stone/dirt/grass, deepslate, clay, sandstone and similar). Sand/gravel, plants/leaves/logs, liquids, block entities, constructed/unknown anchors and unanchored selected columns reject instead of guessing or excavating. Existing natural deck cells are preserved. A completely satisfied plane produces no empty saved blueprint.
- Optional entrance: north/south/east/west (south), width 1-16 fitting its side (default min(3,span)), centered `offset` unless supplied, `maxRun` 1-32 and <=maxDepth (min(16,maxDepth)). Runs straight outward, descends one block per row with stairs facing back toward the deck, then finishes on a level filled/existing terrain landing. Uneven lanes are supported to that common level. Uphill/obstructed routes or missing bounded landings reject; no arbitrary rerouting, ascent or clearing.
- `materials:{full,stairs}` defaults to stone bricks/stone brick stairs. Full material must be occluding and non-gravity; stairs must be a native stair state. Generated orientation/half/shape overrides binding defaults. Shared compiled build validation and current write limits run **before saving**. Existing IDs require `overwrite:true`.
- All emitted fills have **observed air-type filters** (`air`, `cave_air` or `void_air`), never excavation or terrain/NBT replacement. Adjacent identical support runs compress. Capture reads no inventories and writes no world blocks, signs, snapshots or console commands. Persistent files are the only modification.
- Result includes absolute generated bounds, original world/origin, anchor/count/entry summaries and a recommended ordinary `mc_build` request with `connect:false` and `snapshot:true`. Keep rollback precautions; snapshots still do not back up NBT. Fit metadata is advisory. **No world lock or reservation**: later non-air edits cause filters to skip rather than overwrite, possibly leaving gaps; later anchor/headroom changes can invalidate the design. Inspect concrete changes if needed, not repeated blanket scans. Moving/rotating this site-specific document does not refit terrain.

Use the returned build request to place it, then the usual one appearance render. Planning/exact verification remain optional/opt-in. Explicit excavation, liquid displacement, vegetation clearing, upward/curved routes and automatic alternative selection are outside this step.

Disposable `mcp-server/tools/e2e-terrain-fit.mjs` reserves `[1024,99,-40]` through `[1104,125,40]`, restores it and deletes generated documents. It checks hill/pier/entry geometry, read-only fitting, late protected edits, all entry directions, hazard/atomic rejection and virtual/live image parity. PNGs go to `/tmp/ashlar-step7-previews` (`ASHLAR_TERRAIN_OUTPUT` override); no lifecycle management. Developer tests are not per-build agent verification steps.

## Photo-reference reconstruction (fork enhancement)

See [Photo-reference workflow](PHOTO_REFERENCE_WORKFLOW.md). The plugin's agent instructions also carry its essential guidance, so MCP and in-game agents receive it without needing to read repository files. This is agent guidance using existing tools, not automatic image-to-3D conversion: image/web access belongs to the calling agent.

Use a compact reference/scale/proportion brief, distinguish observed features from inferred and unknown details, prioritize silhouette and distinctive architecture, and disclose invented interiors or material approximations. Ordinary blueprint descriptions/advisory constraints can preserve reference notes. Keep the usual one relevant survey -> build -> one appearance render; no additional mandatory verification tools. Exact block-state comparison cannot establish photographic similarity.

## Verification policy: lightweight by default

Mandatory structural safety checks run inside `mc_build` without extra agent calls. Site simulation (`mc_plan`, `dryRun`, strict `preflight`) is optional, for requested previews/checks or concrete placement risks. Exact cell-by-cell verification (`mc_verify`) is opt-in when the user explicitly asks for exact block-state verification. Visual fidelity requests, photo reconstruction and build size alone do not trigger it; this tool compares against a frozen build specification, not against photographs. Do not stack equivalent analyses or launch full scans to fix a small visible defect. Stop when the result is satisfactory and no concrete issue remains; explain unresolved issues instead of looping indefinitely.

The comprehensive unit/native tests used to develop Ashlar are **not** run for each agent build. Advanced tools remain available; this is an agent instruction policy, not a hard runtime call-budget or removal of safety guards.

## Check and preview before building (fork enhancement)

`mc_plan` accepts a `build` object with the **same schema as `mc_build`**, including saved blueprints, palettes, transforms, fills, text and sparse blocks. It checks the full request and simulates placement without temporary world edits:

```json
{
  "build": {
    "blueprint": { "id": "window_row" },
    "transform": { "origin": [100, 64, 200], "rotation": 90 },
    "connect": false
  },
  "preview": { "view": "south", "grid": 0 }
}
```

The report includes bounds/dimensions, requested volume, accepted/skipped visits, unique planned cells, final material counts for eligible planned cells, predicted block-state changes, existing non-air changes/clearing, overlapping cells, capped collision/overlap positions, and door/bed pairing and common support diagnostics. `valid:false` identifies pairing errors; `strictSitePass` additionally requires no support warnings or incomplete neighbor checking. Missing halves are **not automatically synthesized**. Collision samples distinguish clearing from replacement; neither is automatically forbidden because terrain and existing structures cannot be reliably classified without provenance.

- **No placement, snapshot creation, blueprint writes or console commands.** Reading can load chunks. World data is observed over ticks, not locked or reserved.
- Simulation follows actual **fills -> text -> blocks** ordering, including `keep`, `outline`, `hollow`, `walls`, partial-state filters, repeated components and palette/property overrides.
- Images use existing **map colors**, not textures. Choose flat `top`/compass facade/`slice` with `{axis,at}`, or shape-aware `isometric`/`perspective` with optional `camera` (see below). Unchanged cells inside preview bounds are included. Flat views retain legends/axes; angled views report geometry fidelity and adaptive resolution.
- Full top/facade previews must fit `limits.max-read-volume` by envelope volume. Slice previews read only their plane, so a larger design may still be previewed. Use `image:false` for site analysis without image reads. Existing image scale/grid/pixel caps and the 3 MiB PNG cap apply.
- Common support rules are advisory and not an exhaustive vanilla survival/voxel-face solver. Final-scene checks include supports supplied later in the request and existing neighboring blocks losing support. Neighbor candidates are capped at the smaller of 200,000 and `max-read-volume`; omitted checks and capped issue/sample lists are explicitly flagged.
- **Not simulated:** automatic connection shapes/chest pairing, flowing fluids, entities, block-entity/NBT edits, or later concurrent changes. Counts describe planned block states before those effects. Use `connect:false` when exact static-state/image comparison is intended. State-change counts exclude sign text edits; requested sign edits are reported separately.

Every production `mc_build` now validates **all three phases before its first snapshot/write**, including states, sign target/color data, allowed world/build region, Y range, signed integer coordinates, conservative horizontal safety bounds, aggregate requested block/flow volume, envelope chunk tickets and optional snapshot capacity. Invalid fractional/overflow coordinates, text scales and spacing are rejected rather than silently truncated. Current hot-reloaded limits are honored. The executor acquires an entire bounding envelope, so widely separated small fills are bounded by that envelope, not just the union of occupied chunks.

For report-only analysis, use the same build request with **`dryRun:true`**. For placement only after a fresh clean site analysis, set **`preflight:true`**. This rejects pairing/support problems or incomplete neighbor coverage before snapshotting/placing; clearing/collision/overlap counts remain advisory. Without that flag, complete structural validation still runs, but site warnings remain the existing post-build diagnostics. Preflight is not transactional execution: runtime failures or concurrent edits are still possible.

The opt-in `mcp-server/tools/e2e-preflight.mjs` reserves `[344,99,-40]` through `[424,118,40]`, restores it and removes its generated blueprint in cleanup. It also performs a separately fingerprinted **read-only** large-slice test around `[384,100,0]` through `[463,139,79]`. Use the disposable-server environment variables described above. Optional `ASHLAR_PREVIEW_OUTPUT` saves PNGs to that directory. The test does not manage server lifecycle.

## Shape-aware angled appearance views (fork enhancement)

Use one angled render when it helps inspect the finished exterior; this replaces, rather than adds to, the default single appearance check:

```json
{
  "from": [100, 63, 200],
  "to": [120, 80, 220],
  "view": "isometric",
  "camera": { "azimuth": 135, "elevation": 35 },
  "scale": 16,
  "grid": 0
}
```

- `isometric` is orthographic; `perspective` adds distance-dependent projection. Both automatically frame the requested **inclusive 3D bounds**. Compass azimuth: 0 north, 90 east, 180 south, 270 west; range [0,360). Elevation: 5-85 degrees. Perspective-only `fov`: 20-90 degrees. Defaults: southeast azimuth 135; elevations 35.264 (orthographic) / 30 (perspective); perspective FOV 50. Angled grids must be 0.
- Schematic cuboids show slab heights, stair facing/half/corners, door facing/hinge/open state, trapdoors and stored fence/wall/pane/bar connections. Glass blends with geometry behind it. Signs, beds, chests, gates, liquids and other approximations are reported; unknown models explicitly use cube fallback. Colors remain lossy Minecraft map colors, with labeled fallback for missing colors. This is **not an in-game screenshot**, texture/resource-pack renderer, exhaustive voxel model, or exact fidelity audit.
- Read-only, tick-budgeted block/color capture; ray tracing and PNG encoding run off-main-thread. No neighbor updates or state/sign/inventory writes. Connections and world cells are observed as stored, not simulated or refreshed.
- Volume cap 200,000 cells (also current read/world/chunk limits), 1,000,000 pixels, estimated 64,000,000 intersection work and 3 MiB PNG cap. Resolution is lowered and reported when necessary; impossible scenes reject instead of allocating unbounded buffers. `geometry` reports projection/camera, effective scale, budget estimates, fallback/approximation samples and truncation flags. Up to eight transparent layers per ray; capped rays are explicitly counted. Tight bounds improve detail.
- Optional `mc_plan.preview` accepts the same angled views/camera. It renders the virtual final scene with the same engine; static `connect:false` previews match actual render pixels. This does **not** make planning or exact verification mandatory. Existing flat map/facade/slice/heightmap behavior remains separate.

The disposable opt-in `mcp-server/tools/e2e-shapes.mjs` reserves pristine `[720,99,-40]` through `[816,119,40]`, restores it by default, and saves gallery/house images under `/tmp/ashlar-step5-previews` (override `ASHLAR_SHAPE_OUTPUT`). It checks read-only state/sign/snapshot fingerprints, cameras, limits and planned/live parity. It uses the same disposable environment variables as earlier tests and never manages server lifecycle. Developer tests are not per-build agent steps.

## Verify what was built and repair only differences (fork enhancement)

Step 4 adds **`mc_verify`** and **`mc_repair`**. These are **opt-in**, not the default build workflow. When exact verification is requested, use:

1. `mc_verify {"action":"prepare","build": <your mc_build request>}` -> `planId`.
2. Run `mc_build` with that same request.
3. `mc_verify {"action":"check","planId":"plan-..."}` -> exact differences and `comparisonId`.
4. Review them, then `mc_repair {"planId":"plan-...","comparisonId":"comparison-..."}` -> repair report, full recheck and a new comparison ID.

**Prepare before placing.** Expectations freeze final eligible cells through the same transforms, blueprints, palettes, lettering, keep/filters and phase ordering as planning. Re-running a filter afterward may skip the very cells that went wrong; a frozen receipt does not. Skipped/untouched cells and gaps outside the write set are not audited. A zero-eligible-cell capture rejects rather than reporting a misleading empty success.

Verification scans **every** frozen cell and reports missing/unexpected blocks, wrong materials/properties, and supported sign differences. Returned absolute coordinates include expected/actual states and differing property values. `limit` (1-1000, default 100) caps displayed diagnostics, **not the scan or stored repair selection**. `matched`, complete counts and `differencesTruncated` remain accurate.

- Default **`mode:"exact"`** includes every canonical native property. For static literal state parity, build with `connect:false` and static liquids. Automatic shapes/chest pairing can legitimately differ from raw requested states.
- Optional **`mode:"placement"`** explicitly excludes generated fence/pane/bar/wall/wire/tripwire connections and stair `shape` only for captured connected builds. Exclusions and ignored-cell counts are reported. Orientation, half, waterlogging, power and **chest type stay checked**. This mode is not shape verification; use exact mode and explicit final chest halves when paired-type parity is required.
- Sign expectations cover both faces' **plain text, color/glow and waxed state**, preserving prebuild values not overwritten and merging sequential patches. Rich component styling and arbitrary NBT/inventories are not compared. Repair changes only differing managed sign fields, retaining unchanged rich-text lines.
- Owner-scoped receipts are **in-memory**, expire after two hours and disappear on plugin reload/restart. Quotas are 32 plans and 1,000,000 total cells; current read/block/chunk/build-region limits also apply. `action:"list"` lists your receipts; `action:"delete"` frees one without changing the world or blueprint files. Preparing/checking never snapshots or places temporary blocks. Source blueprint deletion does not invalidate a frozen receipt.

Repairs require the **latest** comparison ID. `positions:[[x,y,z],...]` selects unique mismatches explicitly; omitted means all mismatches. `maxChanges` (1-10000, default 1000) rejects oversized selections rather than silently repairing a prefix. Matching cells are not written. Repairs do not replay fill modes or run a connection/neighbor refresh pass; all block writes use physics disabled. Placement-mode repairs preserve excluded live properties when the material matches. Flowing-fluid receipts can be checked but cannot be auto-repaired.

All selected observed block/sign values are rechecked before the first snapshot/write. A stale or protected selection rejects entirely at this guard. Each actual write rechecks its observation, so later edits are skipped/stopped, not blindly overwritten. A full recheck afterward reports what still differs; **there is no transactional lock, atomic reservation or guaranteed rollback**. Runtime failures/concurrent changes remain possible. A clean repeated repair creates no snapshot and performs no writes.

**Block-entity safety:** material replacement/deletion of an existing sign/chest/etc. is blocked unless `allowBlockEntityReplacement:true` is explicitly supplied. Same-material state changes retain their entity data. **`snapshot:true` is the default**, covering only the selected delta's bounding envelope and subject to current snapshot capacity. Existing snapshots store **block states only, not sign text/colors, inventories or arbitrary NBT**. Do not treat that snapshot as an NBT backup. `snapshot:false` explicitly opts out; Step 10's richer project revision/undo workflow is still future work.

The opt-in `mcp-server/tools/e2e-verification.mjs` reserves pristine air `[536,99,-40]` through `[616,120,40]`, restores it, deletes all generated receipts/documents and removes its temporary forced chunk. It requires the same disposable-server variables described above and zero online players. Item/forced-chunk administration is used only for inventory-preservation assertions; it never manages server lifecycle.

## Install (three steps)

### 1. Install the plugin

1. Download `ashlar-0.4.9.jar` from the [Releases](../../releases) page into your server's `plugins/` folder.
2. Start the server once, then stop it. The plugin refuses to fully start on this first run - it writes a default `plugins/Ashlar/config.yml` and disables itself because the token is empty.
3. Edit `plugins/Ashlar/config.yml`:
   - `mode`: how this server is used - `both` (default: MCP clients and `/ashlar`), `mcp` (MCP clients only), `ingame` (`/ashlar` only) or `external` (see [In-game assistant](#in-game-assistant-no-ai-client-needed)). **Only using `/ashlar` in game, no AI client?** Set `mode: ingame`, skip the rest of this list and go straight to that section: no port is opened and no token is needed.
   - `server.token`: a long random value, e.g. `openssl rand -hex 24`. **Unless `mode` is `ingame`, the plugin refuses to start if this is missing or shorter than 16 characters.**
   - `server.port`: an idle TCP port your host/panel exposes.
   - `server.allowed-ips`: optional. If the MCP server runs somewhere with a fixed public IP (a VPS), put that IP here. If it runs on your own PC behind a typical home connection, your IP changes and an allow-list would lock you out - leave it empty and rely on the token, which is the real authentication. See [Security](#security) for what an empty list means and how to tighten it anyway.
4. Restart the server.

### 2. Have Node on the client machine

There is nothing to install by hand. The client configs below start the MCP server with `npx -y ashlar-mcp`, which downloads it from npm on first launch and caches it; the only requirement is **Node >= 22** on the machine that runs your AI client. (`ashlar-mcp` 0.4 needs plugin 0.3 or newer and exits with a clear message otherwise. If you would rather have a fixed path, `npm install -g ashlar-mcp` once and point the configs at the resulting `ashlar-mcp` binary.)

### 3. Connect a client

#### Claude Desktop

Edit `claude_desktop_config.json` (Settings -> Developer -> Edit Config) and add:

```json
{
  "mcpServers": {
    "ashlar": {
      "command": "/absolute/path/to/npx",
      "args": ["-y", "ashlar-mcp", "--stdio"],
      "env": {
        "MC_PLUGIN_URL": "ws://<your-server-ip>:8765",
        "MC_PLUGIN_TOKEN": "<the token from config.yml>"
      }
    }
  }
}
```

Use an absolute path to `npx` (`which npx`) - Claude Desktop does not inherit your shell's PATH. After adding the server, open its "Tool access" settings and pick **"Tools already loaded"**; the alternative, "Load tools when needed", is unreliable in practice (the model can end up seeing only one or two `mc_*` tools). Claude Desktop starts two instances of the MCP server per configured connector - this is normal and harmless.

#### Claude Code

```sh
claude mcp add --scope user --transport stdio ashlar \
  -e MC_PLUGIN_URL=ws://<your-server-ip>:8765 \
  -e MC_PLUGIN_TOKEN=<the token from config.yml> \
  -- npx -y ashlar-mcp --stdio
```

#### OpenCode

Add to `opencode.json` (in the project, or `~/.config/opencode/opencode.json` for every project):

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "ashlar": {
      "type": "local",
      "command": ["npx", "-y", "ashlar-mcp", "--stdio"],
      "environment": {
        "MC_PLUGIN_URL": "ws://<your-server-ip>:8765",
        "MC_PLUGIN_TOKEN": "<the token from config.yml>"
      },
      "enabled": true
    }
  }
}
```

A project-level `opencode.json` contains the token, so keep it out of git (or use the global file). For an HTTP-mode server (below) use `"type": "remote"` with `"url": "http://<host>:3000/mcp"` and `"headers": {"Authorization": "Bearer <MCP_HTTP_TOKEN>"}` instead.

#### Remote/HTTP mode (for a VPS-hosted MCP server)

Run the MCP server itself over HTTP instead of stdio, e.g. on the same VPS as a panel-hosted Paper server:

```sh
MC_PLUGIN_URL=ws://127.0.0.1:8765 \
MC_PLUGIN_TOKEN=<plugin token> \
MCP_HTTP_TOKEN=<a second, separate long random token> \
npx -y ashlar-mcp --http
```

Clients that can send custom headers authenticate with `Authorization: Bearer <MCP_HTTP_TOKEN>` against `POST /mcp`. Clients that cannot set headers (such as a remote MCP connector configured with only a URL) can instead use `POST /mcp/<MCP_HTTP_TOKEN>`, which puts the token in the path. `GET /healthz` is unauthenticated and reports whether the MCP server currently has a live connection to the plugin.

Put a TLS-terminating reverse proxy in front of the HTTP port - the server itself only speaks plain HTTP. Claude Desktop's remote connector setup requires HTTPS. A minimal [Caddy](https://caddyserver.com/) config does this in one line:

```
mcp.example.com {
    reverse_proxy 127.0.0.1:3000
}
```

## First build

A worked example. The user says:

> Survey the area around me and build a small stone cottage with glass windows and a sign over the door saying "Home". Snapshot first.

The model's tool calls, in order:

1. **`mc_players`** `{}` - finds the caller's block position, e.g. `pos: [104, 65, -212]`, and facing.
2. **`mc_survey`** `{ "from": [84, -232], "to": [124, -192] }` - a 40x40 area centered on the player; returns a heightmap image plus text such as `Largest flat zone (+/-1 block): 12x9 at x=98..109 z=-220..-211, y=65.`
3. **`mc_snapshot`** `{ "action": "create", "from": [98, 64, -220], "to": [109, 71, -211] }` - captures the build region; returns a snapshot id like `snap-20260914-101532-7c2a`.
4. **`mc_build`** with a handful of `fills` (floor, walls via `mode: "walls"`, roof) using `minecraft:stone_bricks`, window openings via a second `fills` entry with `minecraft:glass`, and one `blocks` entry for `minecraft:oak_door` plus one for a sign (`minecraft:oak_wall_sign[facing=south]` with `sign.front: ["Home"]`) placed in the air block above the door frame.
5. **`mc_render`** `{ "from": [98, 64, -220], "to": [109, 71, -211], "view": "south" }` - a facade image to check the result.

If the door or sign ended up without solid support behind it, `mc_build`'s response text ends with a block like:

```
WARNINGS (blocks that would fall or pop off in vanilla, including ones next to something you just removed; physics is disabled so they stay - fix them):
  1x minecraft:oak_wall_sign[facing=south] at 103,68,-215: no solid block behind it
```

The model is expected to read this and fix the flagged blocks (or explain the trade-off) before telling the user the build is done - physics being off means nothing falls on its own.

![Stone cottage built by Claude through this MCP](docs/images/showcase-cottage.jpg)

## In-game assistant (no AI client needed)

Everything above needs an AI client on the player's own machine. `/ashlar <request>` is the alternative: the plugin runs the assistant itself, inside the same process as everything else, through the same fourteen tools - without anyone needing Claude Desktop, Claude Code, Cursor, any other MCP client, or a separate Node process. This is for the players and friends on your server who do not run an AI client at all - the server owner sets one API key and pays for the model API; everyone else just types in chat.

### Setup

1. **Plugin only.** Set `agent.model.api-key` in `plugins/Ashlar/config.yml` to a DeepSeek key from [platform.deepseek.com](https://platform.deepseek.com) (the defaults already point at `deepseek-flash`), restart the server, and `/ashlar` works - no other process to run:

   ```yaml
   mode: both                # default: MCP clients and /ashlar; "ingame" = /ashlar only, no port, no token
   agent:
     model:
       api-key: "sk-..."     # required; leave empty and /ashlar replies "not configured"
   ```

   Whether the assistant runs is decided by the top-level `mode` key:
   - `both` (default) - the WebSocket server for MCP clients **and** the assistant, run by the plugin itself using `agent.model.*` below; no Node process needed for `/ashlar`.
   - `ingame` - the assistant only: no WebSocket server, no port, no `server.token`.
   - `mcp` - the WebSocket server only; `/ashlar` is disabled.
   - `external` - the WebSocket server, with `/ashlar` requests forwarded to a connected `ashlar-mcp`-style process over the plugin's chat events, for integrators; this is the previous 0.2 layout.
2. **Any OpenAI-compatible endpoint** works by setting `agent.model.base-url` and `agent.model.model` instead of the DeepSeek defaults (OpenAI, OpenRouter, a local Ollama), as long as the model supports tool calling. Vision is recommended: without it the assistant cannot look at the images `mc_render`/`mc_survey` return, only their text.
3. **Who may use it** is decided in this order (unchanged from 0.2):
   - Operators always can.
   - A permissions plugin that has explicitly granted or denied `ashlar.use` wins (e.g. `/lp user <name> permission set ashlar.use true` with LuckPerms; an explicit `false` blocks the player even if they are on the allow list below).
   - Otherwise the plugin's own allow list decides: `/ashlar allow <player>`, `/ashlar deny <player>`, `/ashlar allowed` (operators only; stored in `plugins/Ashlar/allowed-players.yml`). This is enough for a friends' server with no permissions plugin at all.
   - `agent.everyone-can-use: true` opens `/ashlar` to every player (the daily limits and cooldown still apply). Default `false`.

### Using it

```
/ashlar build a small stone cottage in front of me
```

`/ashlar ask <request>` is the same thing spelled out - use it when a request happens to start with one of the command words (`usage`, `cancel`, `limit`, ...). Tab completion lists the subcommands the player may use and fills in player names.

The player sees `[Ashlar]`-prefixed progress lines as the assistant works (`> mc_survey ...`, `> mc_build ...`) followed by its final reply. `/ashlar cancel` stops a request in progress (it takes effect between tool calls, not inside one). `/ashlar undo` rolls back the player's own last assistant build with no model call at all - it restores the newest snapshot the caller's own requests created (an MCP client's snapshots are never touched) and marks it undone, so a second `/ashlar undo` goes one step further back; a reply that made a snapshot hints at it in its footer. Follow-up requests remember the recent conversation - only what the player asked and what the assistant finally answered (coordinates, materials, snapshot id), never the tool traffic in between - so "make the roof taller" works without repeating the whole description while the context stays small; `/ashlar reset` forgets it and starts fresh. Replies come back in whatever language the request was written in. Players with the `ashlar.monitor` permission (default op) see a compact echo of every other player's request and final reply - `"<name> asked: ..."` and `[Ashlar -> <name>]`-prefixed replies, but none of the progress lines; turn it off with `agent.echo-to-monitors: false`.

From the server console (no player needed), `ashlar simulate <x> <y> <z> [facing] <request>` drives one request through the same code path, with progress and the final reply printed to the console instead of chat - the way to test the assistant without a player online:

```
ashlar simulate 100 64 -200 south build a small stone cottage
```

### Usage, cost and limits

Every final reply ends with a footer line, e.g. `(this request: 21.9k tokens, $0.0061 | today: $0.04 of $1.00)` - `of $1.00` is omitted when there is no cost limit, and it shows tokens instead of cost when every `agent.pricing.*` price is `0` (a free/local model). Usage, per-player limit overrides and a global pause flag are persisted to `plugins/Ashlar/usage.json` so they survive a restart.

Players with the `ashlar.admin` permission (default op) get these in-game commands (`/ashlar help` lists the ones the caller may use):

- **`/ashlar usage [player|all]`** - with no argument, the caller's own usage; a player name, theirs; `all` lists every player who has used the assistant, sorted by today's cost (top 20, with a note if more exist). Each report shows today's and all-time requests/tokens/cost, plus the effective per-day limits and which are overrides.
- **`/ashlar usage [player|all] <days>`** / **`/ashlar usage [player|all] <from> <to>`** - a per-day report instead of the today/total summary: the last *N* days (1-31, ending today) or an explicit inclusive date range (also capped at 31 days; a reversed `from`/`to` is swapped, and a future date is clamped to today). A bare range with no player is the caller's own usage - unlike the target form above, this needs no `ashlar.monitor`, only `ashlar.use`. Dates may be written as `YYYY-MM-DD`, `YYYYMMDD`, or `MM-DD`/`M-D` (day/month in the current UTC year); the two ends of a range may mix spellings. For example, `/ashlar usage 7`:
  ```
  Usage for Steve, 2026-09-08..2026-09-14:
  2026-09-08  0 req  0 tok  $0.00
  2026-09-09  0 req  0 tok  $0.00
  2026-09-10  3 req  41.2k tok  $0.02
  2026-09-11  0 req  0 tok  $0.00
  2026-09-12  5 req  102.4k tok  $0.05
  2026-09-13  0 req  0 tok  $0.00
  2026-09-14  1 req  9.8k tok  $0.01
  total: 9 req, 153.4k tok, $0.08
  ```
- **`/ashlar limit [player] <cost|tokens|requests> <value|off>`** / **`/ashlar limit [player] reset`** - sets (or clears) a per-day cap. With no player, it sets the server default; `off` means unlimited. Precedence: a player's own override, then the server default, then the `agent.limits.*` config value.
- **`/ashlar pause`** / **`/ashlar resume`** - a global switch; while paused, every new `/ashlar` request is rejected with a message, without touching one already running.
- **`/ashlar cancel <player>`** - cancels another player's running or queued request (their own `/ashlar cancel` still works too); the target is told who cancelled it.
- **`/ashlar allow <player>`** / **`/ashlar deny <player>`** / **`/ashlar allowed`** - the plugin's own allow list (see Setup).
- **`/ashlar credit <player>`** / **`/ashlar credit <player> <add|set> <amount>`** / **`/ashlar credit <player> off`** - manage a player's prepaid credit (see "Prepaid credit" below).
- **`/ashlar reload`** (also `ashlar reload` from the console) - re-reads `config.yml` and applies most keys immediately, no restart; config.yml marks each key `Reload` or `Restart`, and a changed `Restart` key is listed in the reply but still needs one. An invalid file is rejected with the error and the running config is left untouched.

#### Prepaid credit

Daily limits (above) are the operator's own safety valve and are checked only before a request starts. Credit is different: it is someone else's money, so it is checked before a request *and* enforced while one is running. Give a player credit with `/ashlar credit <player> add <amount>` (tops up, enabling credit if it was off) or `/ashlar credit <player> set <amount>` (replaces the balance outright); `/ashlar credit <player> off` disables it again; `/ashlar credit <player>` with no further arguments shows the balance. A player without credit enabled is completely unaffected by any of this - `/ashlar usage` and the reply footer only show a credit line once they have some.

The balance is deducted after every model turn, the same per-turn accounting the usage counters already use. If it reaches zero while a request is still running, the request is wrapped up gracefully rather than cut off mid-build: the tool call already in flight finishes, then the model gets one final turn with no tools to say what it completed, what is left, and the snapshot id - the same mechanism used when a request hits `agent.model.max-tool-calls`. The final reply gets an extra line telling the player to ask an operator to top up and then say "continue". Starting a *new* request with a balance already at or below zero is rejected up front, like any other limit. Daily limits still apply on top of credit - both are checked. Balances persist in the same `plugins/Ashlar/usage.json` as everything else above.

`agent.limits.*` and `agent.pricing.*` in `plugins/Ashlar/config.yml` (in-game assistant only):

| Key | Default | Meaning |
|---|---|---|
| `agent.model.max-tool-calls` | `25` | Max tool calls per player request before forcing a final answer. |
| `agent.limits.max-requests-per-player-per-day` | `40` | Per-player daily request cap, reset at UTC midnight; `0` = unlimited. Overridable per-player via `/ashlar limit`. |
| `agent.limits.max-tokens-per-player-per-day` | `0` | Per-player daily token cap (input + cached + output); `0` = unlimited. Overridable per-player. |
| `agent.limits.max-cost-per-player-per-day` | `0` | Per-player daily cost cap, in `agent.pricing.currency`; `0` = unlimited. Overridable per-player. |
| `agent.model.allow-command` | `false` | Whether `mc_command` is included in the assistant's tool list. |
| `agent.limits.max-concurrent` | `2` | Requests running at once across all players. |
| `agent.limits.history-turns` | `6` | User/assistant exchanges remembered per player. |
| `agent.limits.history-ttl-minutes` | `30` | Idle minutes after which a player's history is dropped. |
| `agent.model.image-detail` | `"high"` | Image detail passed through on image parts: `low`/`high`/`auto`. |
| `agent.model.system-prompt-file` | `""` (none) | Optional path to a text file appended to the built-in system prompt. |
| `agent.model.request-timeout-ms` | `120000` | Per model call timeout, in milliseconds. |
| `agent.pricing.input` | `0.30` | Price per 1M uncached input tokens, at peak price (verified DeepSeek `deepseek-flash` rate, 2026-09-14). |
| `agent.pricing.cached-input` | `0.006` | Price per 1M cached input tokens, at peak price. |
| `agent.pricing.output` | `1.20` | Price per 1M output tokens, at peak price. |
| `agent.pricing.currency` | `"USD"` | Label only: `USD` shows as `$`, anything else as a `<code> ` prefix. |
| `agent.pricing.peak-hours` | `"mon-fri 01:00-04:00,06:00-10:00"` | UTC windows in which `agent.pricing.*` prices apply in full - DeepSeek's own peak schedule. `always` disables the off-peak discount, for providers that do not offer one. |
| `agent.pricing.off-peak-multiplier` | `0.5` | Price multiplier applied outside `agent.pricing.peak-hours`. |

A small hut (survey, snapshot, build, a couple of renders, a final reply - about a dozen model calls) ran roughly 320k prompt tokens, dominated by the tool descriptions and the images sent back on each call, for about $0.05 at DeepSeek's peak price (half that off-peak); budget `agent.limits.max-cost-per-player-per-day` accordingly - a cap of `1` (one dollar) covers roughly 20 such requests at peak price.

### Safety

- `mc_command` is never offered to the model unless the operator sets `agent.model.allow-command: true`.
- `agent.limits.max-requests-per-player-per-day`, `agent.limits.max-tokens-per-player-per-day` and `agent.limits.max-cost-per-player-per-day` cap what one player can spend per day; operators (`ashlar.admin`) can tighten or loosen any of them per player from in-game chat, or pause the assistant entirely.
- The plugin's `agent.cooldown-seconds` and `agent.max-message-length` throttle and bound individual `/ashlar` requests before they even reach the assistant.
- Use `world.build-region` (see [Configuration reference](#configuration-reference)) to fence off where the assistant is allowed to build, the same way you would for a human builder.
- The assistant snapshots the region before building, so a bad result can be rolled back with `mc_restore` - ask it to restore, or use `mc_restore` yourself.
- `agent.model.system-prompt-file` adds house rules to the built-in system prompt, e.g. a file containing a line like `Never build within 50 blocks of spawn.`

### Upgrading from 0.2

- Stop the old Node-based assistant process - the flag that used to start it no longer exists and now exits immediately with a message pointing at `agent.mode: embedded`.
- If you want to keep your usage counters, copy the old usage file (next to wherever that process used to run) to `plugins/Ashlar/usage.json` (same format).
- Move the old process's provider/limit/pricing environment-variable values into the matching `agent.model.*`/`agent.limits.*`/`agent.pricing.*` keys in `plugins/Ashlar/config.yml` - see the table above and [Configuration reference](#configuration-reference) for the exact key names.
- If you still want a separate process driving `/ashlar` (e.g. a custom integration), set `mode: external` - that is the old 0.2 behaviour.

## Compatibility

| Component | Status |
|---|---|
| Paper 26.1.2 | Compiles against build 74 API; minimum `api-version: 26.1`. Deployment smoke check passed: startup, authenticated MCP, 13-tool catalog and photo instructions. |
| Paper 26.2 | Fork development runtime tested on build 132 |
| Paper 26.3 | Upstream reported build 5 (alpha); not independently tested for this fork |
| Paper 26.x | Other builds unverified; check compatibility before deployment |
| Java | 25 required (Paper 26.x's hard requirement) |
| Node | >= 22 required (MCP server uses the built-in `WebSocket` global) |
| MCP clients | Any MCP SDK v2 client: Claude Desktop, Claude Code, OpenCode, Cursor, etc. |
| `ashlar-mcp` <-> plugin | `ashlar-mcp` 0.4 requires plugin >= 0.3.0 (fetches the tool catalog via `tool_catalog` at startup; exits with a clear message otherwise); plugin 0.3+ still serves every RPC an `ashlar-mcp` 0.2 client uses, so an older `ashlar-mcp` keeps working against a newer plugin. |
| In-game assistant | Plugin only (no Node) once `agent.model.api-key` is set. |
| Language | Plugin chat text: English or Simplified Chinese (`language` in `config.yml`), or each player's own client language (`auto`). The AI's own replies always follow whatever language the request was written in, regardless of this setting. |

**Not supported:** Minecraft 1.21.x and older (different Paper API version), Folia (single main-thread scheduling model assumed throughout), Bedrock Edition.

## Security

**The plugin's WebSocket port is a remote console with full build and (optionally) command-execution privileges.** Treat the token like a root password.

- Set a long, random `server.token` (>= 16 characters; the plugin enforces this and refuses to start otherwise). `openssl rand -hex 24` is a good source.
- `server.allowed-ips` is a second layer, not the first: the token is what actually authenticates a client (a failed or missing handshake is closed within 5 seconds). Set the allow-list when the MCP server has a fixed IP (a VPS). When it runs on a home PC with a dynamic IP, leave it empty rather than pinning today's address; if you want to lock it down anyway, use the host's firewall or panel rules, or put both machines on a private overlay network (Tailscale, WireGuard) and allow only that address range.
- The plugin does **not** provide TLS. Plaintext `ws://` across the open internet exposes the token to anyone on the path - acceptable only for local/LAN testing. For anything crossing an untrusted network, put a reverse proxy (Caddy, Nginx, Cloudflare Tunnel, ...) in front of it to terminate TLS (`wss://`), and do the same for the MCP server's own HTTP mode.
- Disable `run-command.enabled` if you do not need the `mc_command` escape hatch - it runs arbitrary console commands with full operator privileges.
- Every executed operation is appended to `plugins/Ashlar/operations.log` (IP, method, summary, blocks changed) when `logging.log-operations` is on, as an audit trail.
- `limits.*` bound how much a single call can touch (blocks, chunks, read volume); `world.allowed-worlds` and the optional `world.build-region` bound where it can happen. Configure these to match what you actually want an AI to be able to do.
- The built-in assistant turns chat into a control channel for whoever may use `/ashlar`: anyone with the `ashlar.use` permission can make it call every tool the assistant has, including `mc_command` if `agent.model.allow-command: true`. Grant `ashlar.use` (or an allow-list entry) deliberately, the same way you would grant an operator permission, and keep `agent.everyone-can-use` off on a public server. The model API key in `config.yml` (`agent.model.api-key`) should be treated like the server token - anyone who can read it can run up your model API bill.

## Configuration reference

### Plugin (`plugins/Ashlar/config.yml`)

| Key | Default | Meaning |
|---|---|---|
| `language` | `"en"` | Language for everything the plugin itself says in chat (usage/help lines, progress lines, the usage footer, limit/credit/pause messages); does not affect the AI's own replies. `en`, `zh_CN`, or `auto` (each player's own client language, console always English). |
| `mode` | `"both"` | How this server is used: `both` = WebSocket server (MCP clients) and the in-game assistant; `mcp` = WebSocket server only, `/ashlar` disabled; `ingame` = `/ashlar` only, no WebSocket server, no port, no token; `external` = WebSocket server with `/ashlar` forwarded to a connected external process (0.2 layout). Replaces `agent.mode` (still read when `mode` is absent). |
| `server.host` | `"0.0.0.0"` | Interface the WebSocket server binds to. |
| `server.port` | `8765` | TCP port for the WebSocket server. |
| `server.token` | `""` | Auth token for MCP clients; must be >= 16 characters or the plugin refuses to start (not checked under `mode: ingame`). |
| `server.allowed-ips` | `[]` | Allow-list of exact client IPs (IPv4/IPv6, no CIDR/hostnames in v1). Empty = allow any IP. |
| `limits.max-blocks-per-operation` | `500000` | Max blocks a single `fill_batch`/`set_blocks` request may touch. |
| `limits.max-read-volume` | `200000` | Max region volume `read_region`/`heightmap` may return in one call. |
| `limits.max-diff-volume` | `2000000` | Max box volume of one `mc_diff` call (read in parts of at most `max-read-volume`). |
| `limits.tick-budget-ms` | `20` | Max milliseconds of work per server tick for build tasks. |
| `limits.max-queued-operations` | `16` | Max operations that may be queued at once before new ones are rejected. |
| `limits.max-chunks-per-operation` | `1024` | Max 16x16 chunk columns a single operation's bounding box may force-load (a 1024-chunk cap covers a 512x512 block footprint). |
| `world.default` | `"world"` | World used when a request omits `world`. |
| `world.allowed-worlds` | `["world"]` | Whitelist of world names operations may touch. |
| `world.build-region.enabled` | `false` | Whether to further restrict builds to a bounding box. |
| `world.build-region.min` / `.max` | `{x:-1000,z:-1000}` / `{x:1000,z:1000}` | The bounding box, when enabled. |
| `snapshot.enabled` | `true` | Whether `mc_snapshot`/`mc_restore` are available. |
| `snapshot.max-snapshots` | `20` | Snapshots kept on disk; oldest is evicted first. |
| `snapshot.max-volume` | `200000` | Max region volume a single snapshot may capture. |
| `journal.enabled` | `true` | Journal every writing `mc_build`/`mc_repair`/`mc_restore` for selective undo (block states only). |
| `journal.max-entries` | `200` | Journal entries kept on disk (`plugins/Ashlar/journal/`); oldest idle entry is evicted first. |
| `journal.max-total-cells` | `20000000` | Total changed cells across all entries. |
| `journal.max-age-days` | `30` | Older entries are evicted. |
| `journal.max-cells-per-entry` | `1000000` | A call changing more cells keeps no journal (the result says so). |
| `logging.log-operations` | `true` | Whether executed operations are appended to `operations.log`. |
| `run-command.enabled` | `true` | Whether the `run_command`/`mc_command` escape hatch is available at all. |
| `engine.connect-blocks` | `true` | Whether writes get a shape-only connection pass (panes/fences/walls/bars/stairs connect to neighbours). Overridable per-request via `mc_build`'s `connect` field. |
| `engine.support-warnings` | `true` | Whether writes are checked afterward for unsupported attached blocks (reported as warnings, nothing is fixed automatically). No per-request override. |
| `engine.text-font-file` | `""` | Path to a `.ttf`/`.otf`/`.ttc` file `mc_build`'s `text` entries use for non-ASCII (CJK) lettering instead of this JVM's system font. Empty keeps today's behaviour; relative paths resolve against the plugin data folder. A bad path only falls back to the system font with a logged warning - it never stops the server. |
| `agent.model.base-url` | `"https://api.deepseek.com"` | OpenAI-compatible base URL; `/chat/completions` is appended. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.model.api-key` | `""` | Bearer token for the model API. Required for the in-game assistant - empty means `/ashlar` replies "not configured" instead of the plugin refusing to start. |
| `agent.model.model` | `"deepseek-flash"` | Model name sent in each request. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.model.max-tool-calls` | `25` | Max tool calls per player request before forcing a final answer. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.model.request-timeout-ms` | `120000` | Per model API call timeout, in milliseconds. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.model.image-detail` | `"high"` | Image detail passed through on image parts: `low`/`high`/`auto`. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.model.system-prompt-file` | `""` | Optional path to a text file appended to the built-in system prompt. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.model.allow-command` | `false` | Whether `mc_command` is offered to the model as a tool. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.limits.max-requests-per-player-per-day` | `40` | Per-player daily request cap, reset at UTC midnight; `0` = unlimited. Overridable per-player via `/ashlar limit`. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.limits.max-tokens-per-player-per-day` | `0` | Per-player daily token cap (input + cached + output); `0` = unlimited. Overridable per-player. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.limits.max-cost-per-player-per-day` | `0` | Per-player daily cost cap, in `agent.pricing.currency`; `0` = unlimited. Overridable per-player. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.limits.max-concurrent` | `2` | Requests running at once across all players. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.limits.history-turns` | `6` | User/assistant exchanges remembered per player. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.limits.history-ttl-minutes` | `30` | Idle minutes after which a player's history is dropped. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.pricing.input` | `0.30` | Price per 1M uncached input tokens, at peak price. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.pricing.cached-input` | `0.006` | Price per 1M cached input tokens, at peak price. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.pricing.output` | `1.20` | Price per 1M output tokens, at peak price. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.pricing.currency` | `"USD"` | Label only: `USD` shows as `$`, anything else as a `<code> ` prefix. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.pricing.peak-hours` | `"mon-fri 01:00-04:00,06:00-10:00"` | UTC windows in which `agent.pricing.*` prices apply in full; `always` disables the off-peak discount. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.pricing.off-peak-multiplier` | `0.5` | Price multiplier applied outside `agent.pricing.peak-hours`. Only with the in-game assistant (`mode: both`/`ingame`). |
| `agent.cooldown-seconds` | `5` | Minimum seconds between two `/ashlar` requests from the same player. |
| `agent.max-message-length` | `500` | Longest `/ashlar` request text accepted, in characters. |
| `agent.everyone-can-use` | `false` | Whether every player may use `/ashlar` without being an operator, permission-granted, or on the allow list. |
| `agent.echo-to-monitors` | `true` | Whether players with `ashlar.monitor` see a compact echo of other players' `/ashlar` requests and final replies (no progress lines). |

### MCP server (environment variables)

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `MC_PLUGIN_URL` | Always | - | WebSocket URL of the Paper plugin, e.g. `ws://127.0.0.1:8765`. Must start with `ws://` or `wss://`. |
| `MC_PLUGIN_TOKEN` | Always | - | Must match `server.token` in the plugin's `config.yml`. |
| `MC_REQUEST_TIMEOUT_MS` | No | `600000` | Per-request timeout waiting on the plugin, in milliseconds. |
| `MC_LOG_USAGE` | No | on | Set to `0` to stop logging per-call size/token estimates to stderr. |
| `MC_USAGE_LOG` | No | off | Path of a JSONL file to append one line per tool call to (consumed by `tools/overlay.mjs`). |
| `MCP_HTTP_HOST` | `--http` only | `127.0.0.1` | Interface to bind. |
| `MCP_HTTP_PORT` | `--http` only | `3000` | Port to bind. |
| `MCP_HTTP_TOKEN` | `--http` only | - | Bearer token MCP clients must present. Required, >= 16 characters. |
| `MCP_ALLOWED_HOSTS` | `--http` only, when not bound to localhost | - | Comma-separated hostnames accepted in Host/Origin headers. Required once `MCP_HTTP_HOST` is not `localhost`/`127.0.0.1`/`::1`. |

The in-game assistant has no environment variables of its own any more - see the `agent.*` keys in the plugin table above.

## Troubleshooting

**Symptom:** connection closes with code 1006, and running `curl` against the plugin's port returns an HTML "domain not whitelisted" page.
**Cause:** some hosting providers filter plain HTTP by the `Host` header and reject anything that is not a recognized domain, including a raw WebSocket upgrade request sent to an IP.
**Fix:** set `MC_PLUGIN_URL` to the server's raw IP address, not a domain name.

**Symptom:** Claude only sees one or two `mc_*` tools instead of fourteen.
**Cause:** Claude Desktop's "Load tools when needed" setting loads tool definitions lazily and unreliably.
**Fix:** switch the connector's tool access setting to "Tools already loaded", or start a new chat.

**Symptom:** the plugin's log says the token is empty (or too short) and the plugin does not start.
**Cause:** `server.token` in `config.yml` is blank, whitespace, or shorter than 16 characters.
**Fix:** set a real token (`openssl rand -hex 24`) and restart - or, if nothing but `/ashlar` will be used, set `mode: ingame`.

**Symptom:** `mc_render` fails with `VOLUME_EXCEEDED`.
**Cause:** a facade/slice view is volume-priced (<= 200,000 blocks); a tall or deep `from`/`to` range can exceed that quickly.
**Fix:** use the `top` or `heightmap` views (area-priced, any y range) when you only need a footprint, or shrink the `y` range to the structure's actual height.

**Symptom:** sand, torches, ladders, signs or carpets end up floating or missing after a build.
**Cause:** physics is off by design (so intentional overhangs and floating platforms are possible); unsupported blocks are not auto-corrected.
**Fix:** read the `WARNINGS` block in `mc_build`'s response - it lists exactly which blocks lack support and why.

**Symptom:** `/ashlar` says the assistant is not configured.
**Cause:** `mode` is `both` (the default) or `ingame`, but `agent.model.api-key` in `config.yml` is empty.
**Fix:** set `agent.model.api-key` to a real key and restart.

**Symptom:** the console says `Ashlar agent: mode=external` but nothing answers `/ashlar`.
**Cause:** `mode: external` forwards requests to a connected external process instead of running the assistant inside the plugin; none is connected.
**Fix:** either connect an `ashlar-mcp`-style external process that subscribes to the plugin's chat events, or set `mode: both` (the normal setup for most servers).

**Symptom:** `mc_build`'s `text` fails saying this server's Java has no font for a character (typically CJK), especially in a Docker container.
**Cause:** the JVM reads the system font list once at startup, and a container image usually ships with no CJK font at all.
**Fix:** the quick one - mount a `.ttf` you already have into the container, set `engine.text-font-file` to its path, and run `/ashlar reload` (no restart needed). Otherwise install a system font (Debian/Ubuntu: `apt install fonts-noto-cjk`) and restart the server, or a Docker image needs the font baked into the image.

**Symptom:** the model's reply says it cannot see the image, or answers as if it never looked at the survey/render.
**Cause:** `agent.model.model` does not support vision, so the images sent alongside `mc_render`/`mc_survey` results are effectively invisible to it.
**Fix:** switch to a vision-capable model, or accept that the assistant is working from the text-only numbers in `mc_survey`'s response.

**Symptom:** need to see the MCP server's logs for any of the above.
**Fix:** Claude Desktop's MCP server logs live at:
  - macOS: `~/Library/Logs/Claude/mcp-server-ashlar.log`
  - Windows: `%APPDATA%\Claude\logs\mcp-server-ashlar.log`
  - Linux: `~/.config/Claude/logs/mcp-server-ashlar.log`

## Measuring token usage

Every tool call logs one line to stderr with its size and a rough token estimate (text at ~4 characters/token, images at `width*height/750`):

```
[tool 60695] mc_survey: 812 ms, image 1024x1024 (~1398 tokens) + 240 chars (~60 tokens) = ~1458 tokens
```

Set `MC_LOG_USAGE=0` to silence these lines. Set `MC_USAGE_LOG=<path>` to also append one JSON line per call to a file, independent of whether your client keeps stderr around (useful for Claude Code, or for building a spreadsheet).

For a live on-screen counter while recording or streaming, run the zero-dependency OBS overlay:

```sh
node mcp-server/tools/overlay.mjs
```

It auto-detects Claude Desktop's log file per OS (or reads `MC_USAGE_LOG`/`--file`), and serves a transparent page at `http://127.0.0.1:4545/` to add as an OBS Browser Source. Visit `.../?reset=1` once to zero the running total for a new take.

## Development

```sh
# plugin (JDK 25 required)
cd plugin && JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew build --no-daemon   # produces build/libs/ashlar-<version>-dev.jar; the release workflow builds the bare version
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew runServer --no-daemon   # local Paper test server in plugin/run

# mcp-server
cd mcp-server && env -u HTTP_PROXY -u HTTPS_PROXY npm install --omit=optional
env -u HTTP_PROXY -u HTTPS_PROXY npm run build && env -u HTTP_PROXY -u HTTPS_PROXY npm test
node tools/e2e.mjs        # end-to-end check against a running plugin test server
```

Project layout: `plugin/` is an independent Gradle project (Paper plugin, Java 25); `mcp-server/` is an independent npm project (TypeScript, MCP SDK v2). Both sides follow the same hard rules: Bukkit API only on the main thread inside the tick-budgeted executor, block writes only via `setBlockData(data, false)`, requests fully validated on the network thread before being queued, no NMS/reflection, and pure-ASCII sources.

The tool layer lives entirely under `plugin/`: each `mc_*` tool is a Java class in `plugin/src/main/java/cc/wujm/ashlar/tool/mc/`, its description and JSON Schema are a resource file in `plugin/src/main/resources/tools/<name>.json`, and the server-level instructions text sent to the model is `plugin/src/main/resources/tools/instructions.txt`. To change a tool's description, schema, or the instructions text, edit the matching resource - both MCP clients and the in-game assistant pick it up automatically, since both go through the plugin's `tool_catalog`/`tool_call` RPCs and neither hardcodes any tool knowledge of its own. Result text formatting (headers, warnings, ASCII maps, error text) lives in `plugin/src/main/java/cc/wujm/ashlar/tool/text/`; the golden files under `plugin/src/test/resources/goldens/` are the reference for that formatting and should be updated deliberately, not silently, when it changes.

## Roadmap

**v0.4:**
- Mid-build cancellation: today `/ashlar cancel` only takes effect between tool calls, not inside a single `mc_build` fill.
- A native Anthropic-format provider for the embedded assistant, alongside the current OpenAI-compatible chat-completions one.

**v0.5:**
- Event bus for world/player events, and per-token scopes (a token can be limited to a subset of tools/worlds instead of all-or-nothing).

**v1.1:**
- Deterministic color tinting so blocks sharing a Minecraft map color (e.g. stone/stone bricks/cobblestone) are distinguishable in `mc_render`/`mc_survey` images.
- `mc_inspect` slice: merge block types beyond the current 47-distinct-type limit, plus an optional `focus` parameter to highlight one block type.
- Snapshots capture block entity contents (sign text, container items) so `mc_restore` does not lose them.
- CIDR ranges in `server.allowed-ips` (exact IPs only today).

**v2 (candidates, not committed):**
- Block entity content in `set_blocks`/`read_region`: container contents (chest/hopper/dispenser/furnace), command block text.
- WorldEdit-compatible `.schem` import/export as a soft dependency.
- Parametric structure generators (sphere, column, roof, spiral staircase).
- An optional "redstone domain" (`mc_interact` to toggle levers/buttons and sample block state over several ticks, `mc_entities` to list moving parts) for actually testing redstone builds, not just placing them.
- MCP endpoint inside the plugin (the Paper plugin speaks MCP directly, so a local/single-player setup would not need `mcp-server` at all) - under evaluation, not committed.

## License

AGPL-3.0-or-later (see `LICENSE`). In short: if you run a modified version of this project on a server that other people interact with over a network, you must make that modified source available to them - the same copyleft as the GPL, extended to cover network use instead of only distribution. This matters if you fork the MCP server or plugin to run as part of a hosted service.

Contributions are welcome. A CLA will be added before external pull requests are accepted.
