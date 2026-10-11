# Fork enhancement roadmap

Each step requires user approval before work begins. Pause after each step and report completed work, verification results, and remaining steps. No live deployment or server shutdown/restart without separate approval.

## Priorities and status

1. **Local coordinates and reliable transformations** - complete; implementation and isolated Paper runtime acceptance tests passed.
2. **Reusable blueprints and components** - complete; approved implementation, unit/native tests and reload-persistence checks passed.
3. **Preflight validation and dry-run previews** - complete; approved implementation, unit/native tests and image parity checks passed. Steps 1-3 merged into main via PR #1.
4. **Exact verification and targeted repairs** - complete; approved implementation, exact state/sign checks and guarded sparse native repairs passed. Merged via PR #2; lightweight policy merged via PR #3.
5. **Shape-aware isometric and perspective rendering** - complete and merged via PR #4.
6. **Architectural generators** - complete and merged via PR #5.
7. **Terrain-aware site planning** - complete and merged via PR #6.
8. **Design constraints and alternative concepts** - skipped by user decision.
9. **Photo-reference reconstruction workflow** - complete and merged via PR #7.
10. **Safe project revisions and selective undo** - deferred by user decision; no implementation.

## Step 1 implementation

Branch: `feat/local-coordinate-transforms`.

- Optional `mc_build.transform` with required world `origin`, clockwise `rotation` (0/90/180/270), and local `mirror` (none/x/z).
- Mirror local coordinates first, rotate about local zero second, then translate into world space.
- Transforms fills, individual blocks, expanded wall/floor lettering, lettering backgrounds, and snapshot bounds.
- Native Paper `BlockData.mirror`/`rotate` handle directional properties and unspecified defaults, in batches of at most 64 distinct states per main-thread callback. Explicitly correct mirrored stair handedness, because native mirroring alone is geometrically wrong for some facing/axis combinations.
- Partial filters retain wildcard properties after transformation; cardinal property names are transformed too.
- Sign strings and metadata are preserved. Mirrored block lettering intentionally mirrors glyph geometry. Callers still provide both door/bed halves.
- Local-coordinate integer and overflow validation runs before snapshots or writes. Local lettering expands around zero and uses long intermediate coordinates to avoid wrap.
- Existing calls without `transform` remain unchanged. MCP tool catalog and English usage documentation updated.

### Verification performed

- 16 new JUnit tests pass, covering all 12 rotation/mirror combinations, stair reflection chirality, paired-block positional invariants, cuboid geometry, exact glyph pixels, partial filters, backward compatibility, overflow, handler ordering, world-space snapshot bounds and tool schema.
- Plugin full suite: 549 tests, 548 pass. The sole failure is upstream `AwtGlyphsTest.anUncoveredCodePointFailsInsteadOfDrawingTheMissingGlyphBox`: this host's font covers U+E000, contrary to the test's assumption. Reproduced on untouched upstream commit `73026c7`; unrelated code was not changed.
- Plugin build passes with only that reproduced upstream test excluded via an external temporary Gradle init script. Built artifact: `plugin/build/libs/ashlar-0.4.9-dev.jar`.
- MCP TypeScript build passes; all 8 existing adapter tests pass.
- `git diff --check` passes; new Java sources are ASCII and carry SPDX headers.
- No production plugin, world or server configuration changed. All runtime work used the approved isolated Paper server.

### Completed runtime acceptance

- Paper 26.2 build 132, Java 25; bound Minecraft to `127.0.0.1:25585` and plugin WebSocket to `127.0.0.1:18765`. Used a temporary flat world and a development jar only; no connected players.
- Tested through a real MCP stdio client and adapter, with exact palette/RLE world readback. An independent geometric oracle checked all 12 rotation/mirror combinations, both `connect:false` and the normal default connection pass.
- Verified all stair shapes and facings, top/bottom stairs, both door halves and hinges, bed parts and offsets, automatic double-chest pairing, standing/wall sign orientation and preserved front/back text, log axes, rail shapes, panes/fences, hoppers, levers, trapdoors and jigsaw orientation. Omitted default states were included.
- Verified wall and floor lettering/background geometry, mirrored shape filters, cardinal-property filters, wildcard preservation, world-space auto-snapshot bounds and exact restoration.
- Exercised 160 distinct stair states, each used twice (320 placements), to cross native-transform batch boundaries and test caching.
- Verified a late invalid block state rejects the entire transformed request without earlier writes or an auto-snapshot.
- Final acceptance run: **116,844 checks, zero failures**, no unexplained support warnings and no plugin runtime errors.
- Runtime testing exposed incorrect native corner-stair mirroring. Added an explicit geometric-handedness correction and regression test, then repeated the expanded suite successfully.
- Shut down the isolated server after verified vanilla in-game announcements at 30/20/10/0 seconds, allowing the full countdown. Confirmed both test ports closed. The live server remained untouched.
- Reusable acceptance script: `mcp-server/tools/e2e-transforms.mjs`; requires explicit disposable-server opt-in, loopback connectivity and no players. It restores reserved test regions in cleanup and does not manage server lifecycle.

### Out-of-scope observation

Rebuilding an already paired chest fixture in place produced unpaired baseline chests under the existing chest-pairing behavior. Fresh builds pair correctly across all transforms. The acceptance test uses identical pristine starting states for source and target rather than conflating this upstream rebuild behavior with coordinate transformation. Revisit idempotent repairs in Step 4; Step 1 does not change the chest-pairing engine.

## Step 2 implementation and verification

Branch: `feat/reusable-blueprints`, based on the completed Step 1 branch.

- Added plugin-owned `mc_blueprint` save/get/list/delete and durable atomic JSON storage. IDs are path-safe, symlink reads are rejected, replacement requires explicit overwrite, and document/count caps apply. Read/list actions do not mutate documents; delete does not modify placed blocks.
- Version-1 projects hold flat named raw components, ordered instances, palettes, description, advisory dimensions and advisory constraints. Components support the existing fills/blocks/text entries. No nested references or cross-document component links yet.
- Instances support rotation, mirroring and parent/project-frame count/step repetition, including negative and vertical steps. Closed-form frame composition is verified for all 144 parent/child rotation/mirror pairs.
- Material roles use `$name` with optional inline property overrides. Precedence: component defaults < document palette < build overrides < instance overrides. Recursive bindings and malformed property suffixes are rejected. Sign/lettering content stays literal.
- `mc_build.blueprint` is exclusive with direct operations, expands into existing global fills -> text -> blocks passes, and snapshots union world-space bounds once. The MCP adapter needs no feature-specific implementation; it forwards the plugin's tenth tool and updated build schema.
- Expansion guards 128 components, 10,000 raw operations, 4,096 expanded instances and 100,000 expanded operations. Aggregate requested volume, flow targets and union chunk footprint honor current server limits. All compiled native states are validated before snapshots/writes and share a request-local orientation-keyed cache.
- **25 new JUnit tests pass.** Plugin build passes with the single reproduced upstream font-dependent test excluded: 573 tests pass. The unfiltered suite still has the known unrelated Private Use Area font assumption failure. MCP build and all 8 adapter tests pass.
- Native blueprint acceptance: **5,553 checks, zero failures** against independently repeated Step 1 direct-build references. Covers all 12 project frames, mixed component frames, repetitions, palette overrides, property merging, filter roles, paired beds/doors, signs, lettering, CRUD, unresolved roles, invalid-state rejection and exact auto-snapshot restoration.
- Verified a document and advisory metadata persisted across a real isolated plugin reload, built that saved document after reload, deleted it while blocks existed, and confirmed those blocks remained. Then restored the reserved region and removed the test document.
- Re-ran Step 1 native regression on the Step 2 jar: **116,844 checks, zero failures**. No unexpected plugin runtime errors or unexplained support warnings.
- The isolated development server remains running on loopback with the **same PID 35309**. Loaded code using `bukkit:reload confirm` only on this empty disposable instance; no server restart/shutdown or production modifications. This development-only reload procedure is not recommended for production.
- Acceptance scripts restore their regions and close their own clients; they do not manage server lifecycle. Use fresh snapshots because retention remains bounded.

Step 3 was subsequently approved and completed; see below.

## Step 3 implementation and verification

Branch: `feat/build-preflight`, based on Step 2.

- New readonly `mc_plan` shares direct/blueprint compilation and the authoritative build schema. Never calls writing handlers, temporarily places blocks, creates snapshots, saves blueprints or executes console commands. Reads can load chunks.
- Production `mc_build` validates all fill/text/sparse phases and sign metadata before its first snapshot/write. Guards allowed world/build region, Y, strict integer coordinates/scale/spacing, conservative horizontal bounds, aggregate block/flow volume, snapshot capacity and full executor envelope chunk tickets. Hot-reloaded limits apply.
- Tick-budgeted virtual overlay matches fill modes, partial filters and fills -> text -> blocks ordering. Reports bounds/dimensions, eligible material counts, predicted changes/clearing, overlaps and capped collision/overlap coordinates.
- Checks door/bed pairs and common advisory support rules against the final virtual scene, including later-added supports and existing neighbors losing support. Truncation and bounded neighbor coverage are explicit. Terrain cannot be reliably distinguished from structures; collisions remain advisory.
- `dryRun:true` returns report-only analysis. `preflight:true` additionally rejects pairing/support problems or incomplete neighbor checks before snapshot/write. Complete structural validation is always on in production wiring.
- Existing map-color top/facade/slice previews render off-main-thread with image/read caps; slices read only their plane. Include unchanged world cells inside preview bounds. Connections/chest pairing, fluids, entities, NBT edits and concurrent changes are not simulated. No atomic reservation guarantee. Shape-aware perspective remains Step 5; constraints remain advisory.
- **17 new JUnit tests pass; 590 plugin tests pass** with the one reproduced upstream font assumption excluded. MCP build and all 8 adapter tests pass.
- **202 native assertions, zero failures**: full-region blocks/signs and snapshot IDs unchanged after planning; exact collision coordinates; all fill modes and keep/filter sequencing; late invalid states/signs/colors/Y and aggregate/envelope failures before any partial writes; missing pairs, support correction/loss, strict failure/success, dry run and bounded large slices.
- All **12 transformed repeated-blueprint previews match actual unconnected build PNGs byte-for-byte**. Top/north/west/slice samples visually inspected. Predicted change/non-air/clear counts match execution.
- Verified a temporarily lowered, hot-reloaded aggregate block limit rejects before snapshots/writes. Restored the exact original isolated config and reloaded it; no secret backup persisted.
- Step 1 regression: **116,844 checks, zero failures**. Step 2 regression: **5,553 checks, zero failures**. No unexpected runtime errors or unexplained support warnings in acceptance runs. An early helper omitted its timeout and disconnected immediately, causing an explained response-delivery WebsocketNotConnectedException; corrected before acceptance.
- Test regions restored, temporary documents removed, development server still running with the same PID 35309. Only isolated development code/config reloads; no restart/shutdown or production modification.

Step 4 was subsequently approved and completed; see below.

## Step 4 implementation and verification

Branch: `feat/exact-build-verification`, based on merged main `db1afbe`.

- `mc_verify prepare` freezes immutable eligible final cells before construction using shared build compilation/preflight and tick-budgeted virtual replay. Conditional keep/filter eligibility, phase ordering, all transforms/blueprints/palettes/text and sequential supported sign patches are retained. Rechecking never reevaluates predicates and cannot hide missing cells by skipping them. Empty eligibility rejects.
- `mc_verify check` scans every expected cell and reports missing/unexpected blocks, wrong materials/properties and supported sign text/color/glow/wax differences, exact absolute coordinates, complete counts and bounded diagnostics. Exact default includes all canonical properties; explicit placement mode excludes only narrow generated connection properties for connected builds, never orientation/chest types. Untouched/skipped cells, rich sign styling, inventories/arbitrary NBT and entities are outside scope.
- Owner-scoped memory receipts: 32 plans, 1,000,000 aggregate cells, 2-hour expiry; lost on plugin reload/restart. Source document deletion does not invalidate captured cells. list/delete metadata do not change blocks. Reload persistence/project revisions remain Step 10 work.
- `mc_repair` requires latest comparison ID, accepts explicit unique mismatch subsets, rejects oversized selections (default 1000, maximum 10000), revalidates current allowed world/region/Y/aggregate/read/chunk/snapshot limits and observed values before snapshot/write. Stale/protected selections reject; late changed values are skipped/stopped. Per-plan operations are exclusive. Default block-entity replacement/deletion guard, explicit opt-in for destructive material replacement.
- Only mismatching selected cells are written, with physics disabled, no connection pass or neighboring refresh. Excluded live properties are retained for same-material placement repairs. Matching cells and supported unchanged sign fields remain untouched. Full post-repair verification yields a new comparison ID. No-op repair makes no writes/snapshots. Flowing-fluid receipts cannot be auto-repaired.
- Optional default-true delta-envelope snapshot. Explicitly documented: existing snapshot payload stores block states only, not sign text/colors, inventory or arbitrary NBT. No atomic world-lock/transaction/rollback guarantee. Wider safe revision/undo remains Step 10, not claimed here.
- **28 new unit tests; 618 plugin tests pass** with only the reproduced upstream font assumption excluded. MCP build and all **8 adapter tests pass**.
- Native acceptance: **233 assertions, zero failures**, full-region/snapshot non-mutation checks, all five difference classes, truncated diagnostics with complete scan, exact stair-property reports, partial and full repairs, stale-ID/value rejection before snapshots/writes, keep/filter freezing and ignored-cell preservation, sequential sign metadata, default block-entity deletion guard, clean no-op repairs and snapshot opt-out.
- Paired chest types remain exact and inventory survives both untouched-chest repairs elsewhere and same-material chest orientation repair. Inventory test uses item/temporary forced-chunk administration only; forced chunk removed. Initial command test found chunk unloading between long fingerprints; fixture now explicitly forces its chunk and checks item command success before asserting inventory.
- All **12 transformed repeated-blueprint** captures/builds/damage/repairs pass. Receipts still repair after source blueprint deletion. Connected placement mode exclusions are visible, while wrong orientation is still detected/repaired.
- Separate hot-limit checks: reduced aggregate block budget and snapshot capacity each reject before any writes or snapshot creation. Original isolated config restored/reloaded and fixture cleaned up.
- Step 1 regression **116,844**, Step 2 **5,553**, Step 3 **202** assertions/checks: zero failures. No unexpected runtime errors.
- Native regions restored, temporary blueprints/receipts removed. Isolated PID **35309** remains running. Development-only plugin/config reloads; no shutdown/restart or production changes.

Step 5 was subsequently approved and completed; see below.

## Step 5 implementation and verification

Branch: `feat/shape-aware-rendering`, based on merged main `95e542c`.

- Added schematic `isometric` (orthographic) and `perspective` views to `mc_render` and optional `mc_plan.preview`, sharing a pure-Java off-main ray renderer. Tight inclusive 3D bounds, validated compass azimuth/elevation and perspective-only FOV; grids must be 0. Existing flat views remain separate.
- Cuboids model slab heights, all stair facings/halves/corners, door facing/hinge/open state, trapdoors, stored fence/wall/pane/bar connections and glass blending. Gates, signs, beds/chests/liquids and similar approximations are labeled; unsupported states explicitly report cube fallback. Map colors remain lossy; missing colors and capped samples are reported. No textures/resource packs, entities, sign glyphs, fluid/waterlogging simulation, native lighting or shadows.
- Bukkit state/color reads remain tick-budgeted on main; region encoding, ray tracing and PNG encoding run off-main. Captured world names prevent off-main Bukkit getter calls. Read-only: no placement, snapshots, physics or neighbor updates.
- Bounded 200,000 cells/current read limits, 1,000,000 pixels, estimated 64,000,000 intersections and 3 MiB PNG. Adaptive resolution is reported; impossible bounds reject. Eight transparent layers per ray with capped-ray counts. Native angled legends cap at 50; fidelity samples at 30 with truncation flags.
- **34 new unit/schema tests; 654 filtered plugin tests and 8 adapter tests pass.** Unfiltered run: 655 tests, sole failure is the known upstream host-font assumption at `AwtGlyphsTest.java:46`; no tracked exclusion or dependency change.
- Opt-in `e2e-shapes.mjs`: **71 checks, zero failures**, including read-only state/sign/snapshot fingerprints, cameras/bounds/limits, native RPC validation and byte-for-byte virtual/live static house renders. Gallery and front/rear house PNGs were visually inspected. These are developer acceptance tests, not routine agent calls.
- Temporary read-only native geometry probe compared **75 stair/slab/door/trapdoor states at 4,096 interior sample points each** against actual server collision boxes: **zero mismatches**. Probe removed; fixture restored.
- Previous native regressions: Step 1 **116,844**, Step 2 **5,553**, Step 3 **202**, Step 4 **233** checks/assertions, zero failures. All fixture regions restored, documents/receipts/items/forced chunk cleaned up. No unexpected new runtime errors.
- Default workflow remains one relevant survey -> build -> one appearance render. An angled image can replace that one render; planning and exact verification stay optional/opt-in. Production untouched; same isolated process retained, no restart/shutdown.

Step 6 was subsequently approved and completed; see below.

## Step 6 implementation and verification

Branch: `feat/architectural-generators`, based on merged main `9a6b048`.

- Optional `mc_blueprint action:generate` saves ordinary flat version-1 components, preserving existing transforms/palettes/repetition, planning and build validation. No new tool or mandatory workflow stage. Explicit overwrite required; rejection happens before replacing a document.
- Deterministic pure-Java gable/hip/shed roofs (1:1 slopes, slab ridges, explicit hip corners, optional gable infill), rounded/pointed arches, hollow circular towers with optional floor/merlons, straight/switchback stairs with turn/exit landings and optional solid plinths. Bounds/counts/palettes/assumptions returned without dumping geometry.
- Checked kind-specific parameters, native concrete materials/expanded states before persistence, 200,000 requested-cell / 10,000-operation caps and ordinary storage quotas. Adjacent identical fills compress without overlaps. Thin arches and circular-wall corner bridges are face-connected. No implicit clearing, terrain adaptation, railings, spiral stairs, arbitrary roof slopes or revision/undo additions.
- **26 new unit/schema tests; 680 filtered plugin tests and 8 adapter tests pass.** Unfiltered run: 681 tests, sole failure is the known upstream host-font assumption at `AwtGlyphsTest.java:46`; no tracked exclusion/dependency changes.
- Native generator acceptance: **9,933 checks, zero failures**, covering every variant, canonical states, role-property overrides, all 12 hip-roof frames, invalid/duplicate/overwrite atomicity, generation without world mutation and byte-for-byte virtual/live gallery PNG parity. Gallery and targeted hip close-up visually reviewed.
- Temporary read-only native probe: **45 staircase surface samples, zero failures**, validating half-step progression and both switchback landings against actual server collision boxes. **9 document persistence comparisons** after reload matched exactly. Probe, generated documents and world fixture removed/restored.
- Previous native regressions: transforms **116,844**, blueprints **5,553**, preflight **202**, exact verification **233**, shapes **71**; zero failures. No unexpected new runtime errors. Same isolated PID **35309** retained; production untouched, no restart/shutdown.
- Routine agent verification stays lightweight. Generators are optional authoring helpers, not extra site/verification loops. Existing `mc_build` performs mandatory safety validation and snapshot checks; appearance remains one relevant render.

Step 7 was subsequently approved and completed; see below.

## Step 7 implementation and verification

Branch: `feat/terrain-aware-foundations`, based on merged main `5aac02a`.

- Optional `mc_blueprint action:fit` takes an explicit footprint/world/walking `floorY` selected from a recent survey/design, one bounded tick-budgeted state capture, pure terrain fitting and shared compiled build validation before saving. No new tool, mandatory survey/plan/exact scan or world placement.
- Additive solid foundations or spaced piers with full decks, plus optional straight outward descending stairs to a common level terrain landing. Conservative natural full-block anchors; rejects unknown/constructed/protected/vegetation/liquid/gravity anchors, occupied decks/headroom, missing bounded anchors, uphill routes or absent landings. No excavation, implicit clearing, upward/curved routing or automatic design alternatives.
- Explicit observed-air-type filters preserve terrain/NBT and later non-air edits. Adjacent identical columns compress. Site-specific world/origin/build recommendation, anchor/count/entry metadata and limitations returned; persisted provenance is advisory, not a world reservation. Relocating/rotating does not refit; skipped supports or changed anchors/headroom are explicitly possible after later edits.
- Network-side parameter/native-state validation, main-thread material/world-height checks, tick-budgeted reads; immutable region encoding/pure solver/storage run off-main. Read envelope <=200,000/current read caps, dimensions/depth/run bounded, world/build-region/Y/chunk checks before enqueue. Existing live compiled write limits checked before persistence; normal placement/snapshot safety unchanged.
- **20 new unit/schema tests; 700 filtered plugin tests and 8 adapter tests pass.** Unfiltered run: 701 tests, sole failure is the known upstream host-font assumption at `AwtGlyphsTest.java:46`; no tracked exclusion/dependency changes.
- Native terrain acceptance: **354,449 checks, zero failures**, including whole-fixture preservation outside emitted matching-air cells, hill/pier geometry and entry counts, no-world/snapshot mutation during fitting, late protected edits, invalid overwrite/file atomicity, hazardous anchors/headroom, all four entry directions and byte-for-byte static planned/live PNG parity. Solid/pier appearance image visually reviewed.
- Hot isolated read/write caps: **8 checks, zero failures**, rejecting before document/snapshot/world changes; original limits restored. Previous native regressions: transforms **116,844**, blueprints **5,553**, preflight **202**, exact verification **233**, shape rendering **71**, generators **9,933**; zero failures.
- Fixtures/documents/items/forced chunk cleaned/restored. Same isolated PID **35309** retained; no new runtime errors, production untouched, no restart/shutdown.
- Routine workflow stays one relevant survey -> build -> one appearance render. fit is optional adaptation, not a per-build verification requirement; exact auditing remains opt-in.

Step 8 was subsequently skipped by user decision. Step 9 was approved as a lightweight reference-reconstruction workflow, not a new image-to-3D engine.

## Step 9 implementation

Branch: `feat/photo-reference-workflow`.

- Public `PHOTO_REFERENCE_WORKFLOW.md` and plugin-served agent guidance: reference selection/access limits, explicit scale/proportion brief, observed/inferred/unknown separation, distinctive features, interior uncertainty, local reusable geometry, and honest handoff.
- Existing blueprint descriptions/advisory constraints can retain reference notes; no new tool, schema, runtime geometry engine or automatic fidelity score.
- Corrected visual-fidelity wording: photographic similarity does not trigger exact block-state auditing. `mc_verify` compares a frozen specification, not photographs.
- Basic verification only: one affected plugin build with 10 focused tool/schema tests passed (one newly added instruction test), zero failures. No native/world tests needed for instructions/documentation; no production or disposable-server changes.
- Added repository `AGENTS.md` with the approved basic-testing policy, persistent across new/resumed/compacted contexts.

Merged via PR #7. Step 10 was subsequently deferred by user decision; implementation work is complete for now.

## Step 11 implementation and verification

Branch: `feat/region-diff`.

- New `mc_diff` tool (read-only) with snapshot, inline expected, expected-file and blueprint references; canonical state comparison via `StateCanon` (server defaults from `Bukkit.createBlockData`), ignore globs, material compare, declared/box scopes and baseline snapshot.
- Large boxes are split into y-slabs (or single-layer z-bands) whose reads respect `limits.max-read-volume`; new hot key `limits.max-diff-volume` (default 2,000,000).
- Anomalies: floating, stacked, unsupported (reuses `SupportCheck`) and enclosedAir (box-boundary flood fill).
- `diffId` receipts (32 receipts / 1M cells / 2h, owner-scoped) feed `mc_repair {diffId}`, which reuses `RepairTask` guards with live block-entity detection.
- `mc_inspect` canonical output by default.
- Verification: one plugin build, 732 tests passed (27 new), zero failures; MCP build and 8 tests passed. Isolated-server smoke test `e2e-diff.mjs`: 18 checks (8 exact differences incl. one floating and one stacked snow layer, ignore/material compare, diffId repair to zero, blueprint diff zero after clean build, ~620k-cell box in 4 reads); test region restored to pristine air. No production changes.

Merged via PR #9.

## Step 12 implementation and verification

Branch: `feat/build-journal` (supersedes Step 10's revision/undo item).

- Capture: an `InvocationContext` carries a `JournalCapture`. The executor attaches it to every task, and each write site (fill, sparse, restore, repair, chest pairing, connection pass incl. face neighbours) records the prior state before `setBlockData`. Final states are read in the same tick budget before the task completes.
- Pure core in `cc.wujm.ashlar.journal`:
  - `JournalRecorder`: packed positions in an open-addressing map;
  - `JournalCodec`: gzip binary;
  - `JournalStore`: JSON metadata + cells, eviction, owner visibility, exact `touches`;
  - `UndoRules`.
- Undo: `JournalRestoreTask` writes original states with `setBlockData(old,false)`, without a connection pass, honouring safe/force and block-entity guards. The undo is journalled and the original is marked `undoneBy`.
- Ownership: PLAYER contexts (embedded agent) see their own entries; MCP token, console and system see all.
- Verification:
  - one plugin build: 746 tests passed (14 new), zero failures; MCP build and 8 tests passed;
  - isolated-server smoke test `e2e-journal.mjs`, 21 checks, run twice:
    - build A, overlapping build B, 3 untracked edits;
    - safe undo of A restored 42/75 cells and kept 33 conflicts;
    - dry run changed nothing;
    - undo of the undo restored 42/42;
    - fence neighbour shape journalled (2 cells) and restored;
    - touches/label listing and delete worked;
  - test region restored to pristine air, all test entries deleted. No production changes.

Merged via PR #10.

## Step 13 implementation and verification

Branch: `feat/protected-regions`.

- Pure core in `cc.wujm.ashlar.protect`:
  - `ProtectedRegion`: name, world, box, mode (deny/warn), note, owner, creation time;
  - `ProtectionCheck`: counts the write targets of one call per region; `outline`/`walls` fills are split into disjoint shell slabs; cells are prefiltered by the regions' envelope;
  - `ProtectedRegions`: `protected.json` with atomic writes, swapped as an immutable view, reloaded at enable and by `/ashlar reload` (an invalid file keeps the loaded set).
- `ProtectionGuard` (tool layer) runs after validation and before any snapshot or write: `mc_build`, both `mc_repair` paths (selected cells), snapshot restores (the snapshot box) and journal undos (every journalled cell). `mc_plan`, `dryRun`, journal dry runs and `mc_blueprint fit` only report. Overrides name each region and are logged. Deviation from the plan: sparse cells use an envelope prefilter plus a linear region scan instead of a chunk hash, which is cheap for the expected few dozen regions.
- Not checked: `mc_command`, the raw `fill_batch`/`set_blocks`/`restore` RPCs (the MCP adapter only uses `tool_call`), `/ashlar undo`, players and other plugins. Connection-pass shape updates of protected neighbours just outside a write target are not counted.
- Verification:
  - one plugin build: 762 tests passed (16 new), zero failures; MCP build and 8 tests passed;
  - isolated-server smoke test `e2e-protect.mjs`, 30 checks:
    - a fill crossing a deny region was rejected before any snapshot or write;
    - `dryRun` and `mc_plan` reported the overlap (with override status);
    - walls around the region passed untouched;
    - an unknown override name and a wildcard were rejected;
    - the override build wrote and was logged; a warn region wrote with a warning;
    - a journal undo and a snapshot restore over the region were rejected without an override and succeeded with one;
  - test region restored to pristine air, regions removed, journal entries deleted. No production changes.

Merged via PR #11.

## Step 14 implementation and verification

Branch: `feat/first-person-view`.

- Pure core in `cc.wujm.ashlar.render`:
  - `VoxelRay`: Amanatides-Woo traversal against `BlockShapes` cuboids; unloaded cells stop the ray as unknown;
  - `FirstPersonCamera`: pinhole camera with Minecraft yaw/pitch, plus the chunk columns its rays can reach (x/z hull of a 65x65 ray grid, or the full circle when the view includes straight up or down);
  - `FirstPersonRenderer`: per-pixel trace with the angled views' shading and light haze; unknown cells drawn flat and counted;
  - `Sightline`: target lines (center plus the faces turned toward the eye), cone grids and ignore patterns.
- Engine: `ViewSnapshotTask` copies already-loaded chunk columns into `ChunkSnapshot`s under the tick budget, with no chunk loads or tickets (`BuildTask#needsChunkTickets`); `SnapshotVoxels` converts 16x16x16 sections to ids lazily off-main; `ViewService` resolves player eyes on the main thread and runs all tracing and PNG encoding on the render executor.
- Tools: `mc_render view:"first-person"`, new `mc_sightline`, `mc_players` `eye` and `lookRange`.
- Deviations from the plan:
  - reads use chunk snapshots instead of tick-budgeted per-block reads, to keep main-thread cost low; the cap is `limits.max-chunks-per-operation` chunk columns instead of a new `limits.max-view-read` cell count;
  - sightline targets are also tested at the centers of their faces turned toward the eye, because a ground block's center is usually hidden by its neighbours;
- Not covered: entities, fog, lighting, textures and the client's render distance; chunks that are not loaded answer unknown.
- Verification:
  - one plugin build: 780 tests passed (18 new), zero failures;
  - isolated-server smoke test `e2e-view.mjs`, 20 checks:
    - through a one-block gap visible, beside it blocked by the wall block, over a bottom slab visible, a ground block visible by its top face;
    - an ignore pattern let a line through the wall; a target in an unloaded chunk answered unknown;
    - a cone grid showed the gap in its center ray; argument errors were rejected;
    - one 640x360 first-person render showed the wall, gap, slab row and floor as expected;
  - test region restored to pristine air, forceload removed, journal entries deleted. No production changes.
  - The plan's acceptance case (the Titanic spawn vantage) needs the production world and was not run.

## Step 15 implementation and verification

Branch: `feat/walk-check`.

- Pure core in `cc.wujm.ashlar.engine`:
  - `WalkShapes`: one collision interval per block state (snow `(layers-1)/8`, carpet 1/16, slabs, bottom stairs with facing, beds, fences/walls/closed gates 1.5, closed doors, trapdoors, low blocks), plus ladder, water and hazard kinds; unknown blocks count as full cubes;
  - `Walker`: standing places per column with 1.8 headroom, four-direction moves with step/jump/drop limits, head-on-only stairs (also head-on descent as a half step), climbable ladders and optional swimming, breadth-first search, and a diagnosis of the first failing move from the nearest reached place.
- Tool: new `mc_walk` (path / reach, rules, targets doors/beds/stairs/list, samples). The box is read through the existing `read_region` path in tiles of at most `limits.max-read-volume`; decoding and the search run on the render executor.
- Deviations from the plan:
  - added `rules.maxDrop` (default 3) so drops are bounded too; `maxDrop` 0.6 reproduces the "both directions, no jumps" checks;
  - breadth-first search only (no A*): boxes are small enough and BFS gives shortest move counts;
  - reads stay tick-budgeted per-block reads rather than chunk snapshots, because a walk box needs every cell even where no player is and may need to load chunks.
- Not covered: sprint jumps over gaps, entities, ice, slime, soul-sand slowdown, diagonal squeezes (moves are four-directional).
- Verification:
  - one plugin build: 794 tests passed (9 new), zero failures; adapter 8 tests passed;
  - isolated-server smoke test `e2e-walk.mjs`, 20 checks:
    - a closed door blocks the route and is named; `doors:"any"` and then opening the door in the world both open it;
    - the route onto a one-high ledge climbs the head-on stair; without it the ledge is "step too high (rise 1.00)" unless jumping;
    - doors and stairs targets; argument errors;
    - armor stands dropped onto snow (3 layers), carpet, a bottom slab, a stair's low half and an open door settled at 0.25, 0.0625, 0.5, 0.5 and 0, matching the model;
  - test region restored to pristine air, forceload removed, probe entities killed, journal entries deleted. No production changes.
  - The plan's acceptance case (Titanic Module 9 doors and the iceberg path) needs the production world and was not run.

## Step 16 implementation and verification

Branch: `feat/bulk-io`.

- Pure core in `cc.wujm.ashlar.tool.mc`:
  - `CellSet`: cells in primitive arrays sorted y, z, x with a sorted palette, so the same cells in any order give the same set; equal duplicates collapse, conflicting ones are rejected; states are checked and namespaced;
  - `CellMerge`: phase classification (solid, air, late), greedy box merge (at most 128 in x and z, within the per-call block and snapshot limits), and packing of boxes by phase and 128-block tile into parts within the block, chunk, flowing-liquid and snapshot-envelope limits;
  - `CellFiles`: chunked staging (one gzip member per call), lenient finalize to `<name>.json.gz`, atomic export writes, streamed reading of cells, `{cells}` and palette-rle files, name and symlink checks, retention.
- Tools: `mc_build` `cells`/`cellsFile`/`resume` (`CellBuild`), `mc_blueprint` `stage`/`finalize`, new `mc_export` (tiled read through `read_region`, encoding on the render executor), `mc_diff expectedFile` also reads staged uploads and exports.
- Deviations from the plan:
  - cells cannot be combined with `preflight`, fills, blocks, text, blueprints or transforms: every part still gets the normal structural validation and protection checks;
  - parts are also capped at 256 chunks each so one part never touches more of the main thread than a typical build;
  - at most 2,000,000 cells and 1,000 parts per call; a journal entry over `journal.max-cells-per-entry` is skipped as before;
  - no delete action for staged uploads or exports; retention prunes the oldest beyond 20 files per directory;
  - the smoke patch is 54,701 cells (the isolated test region holds 144,342), and the 500k export round trip uses a 515,200-cell box around it.
- Verification:
  - one plugin build: 803 tests passed (9 new), zero failures;
  - isolated-server smoke test `e2e-bulk.mjs`, 28 checks:
    - a 54,701-cell terrain patch staged in two calls, finalized, dry-run, then built: 2,905 boxes in 3 parts (solid, air, late) in under 1.5 s; `mc_diff` against the same file reports no differences;
    - exports in both formats (91,854 cells), `mc_diff` against each reports no differences; the region was restored to air, rebuilt from the palette-rle export, and matched the sparse export;
    - a 515,200-cell export round trip (export, then diff) reports no differences;
    - inline cells (stone then a torch in a later part), resume-token, conflict, mixing and name errors, the export volume cap and overwrite guard;
  - test region restored to pristine air, forceload removed, journal entries and test files deleted. No production changes.

## Step 17 implementation and verification

Branch: `feat/build-entities`.

- Pure core: `EntitySpec` (one painting, item frame, glow item frame or armor stand as data, with JSON and an equality that ignores the placed tag), `EntityGeometry` (viewer's right, painting footprints and the vanilla anchor per size, lower-left from a mirrored lower-right cell, yaw under mirror and rotation), `EntityArgs` (`mc_build` parsing, per-type keys, transforms) and `JournalExtras` (sign text before/after, spawned, removed and changed entities).
- Main-thread tasks: `EntityPlaceTask` (after the blocks; checks support and clearance per entity, verifies where a painting landed and removes it if vanilla moved it), `SnapshotExtrasTask` (capture after the block read, apply after the block restore) and `JournalExtrasUndoTask` (after the journal cells). Each waits up to 100 ticks for chunk entities to load. Registry lookups for variants and items run off the main thread before anything is queued.
- Sign text is now journalled through `JournalCapture.signBefore`, including the text-only writes of `SparseTask` and `RepairTask`; journal extras live in `<id>.extra.json.gz` next to the cells.
- `mc_inspect entities:true`; schema and instruction text no longer say snapshots and journals keep block states only.
- Deviations from the plan:
  - `entityMode` and `entityBox` are top-level `mc_build` keys instead of `entities.mode`, since `entities` is the array itself;
  - the PDC tag value is `"1"`, not the journal id: the id does not exist yet when the entity is spawned, and journal records keep the entity UUIDs instead;
  - marker armor stands are never read, listed, replaced or restored (they are usually other plugins' holograms);
  - entities cannot be combined with `blueprint` or `cells` in one call;
  - safe-mode undo reverts an entity or sign only while it still matches what the call left.
- Context cost: tool schemas and instructions grow by about 4,700 characters (about 1,200 tokens), mostly the `mc_build` entity schema.
- Verification:
  - one plugin build: 818 tests passed (15 new), zero failures; adapter 8 tests passed;
  - isolated-server smoke test `e2e-entities.mjs`, 40 checks:
    - unknown variant/item and cells+entities rejected before any write; dry run lists 6 entities;
    - one call placed 1x1, 2x1 and 4x3 paintings, a wall item frame with a clock, a floor glow frame and a posed, equipped armor stand; `mc_inspect` lists them, and the paintings' `block_pos` matches the computed vanilla anchors (2x1 on its left cell, 4x3 on the second column, middle row);
    - repeating the call reports 6 unchanged; a painting without a wall is reported while the other entity is placed;
    - journal undo removes the 6, undoing the undo respawns them identically; a text-only sign rewrite is undone;
    - snapshot, kill every entity and change the sign, restore: sign text and all 6 entities are back;
    - replace-in-box removes the Ashlar-placed armor stand and keeps a summoned one; a mirrored 2x1 painting lands on the same wall cells;
    - after the force-load is dropped and the chunk unloads, all 8 entities read back unchanged;
  - test region restored to pristine air, entities killed, forceload removed, journal entries deleted. No production changes.

## Step 18 implementation and verification

Branch: `feat/terrain-light-analysis`.

- Pure core: `SurfaceRules` (what counts as decoration, and the collision top a player stands on), `LightRules` (floor, feet and head cells of a spawn space; which cells let light through) and `LightScan` (the scan, a 6-neighbour light flood that stays in the box, and lazy greedy set cover with a spacing check).
- Main-thread work: `SurfaceReader` reads the decoration-ignoring surface and walk heights inside the existing heightmap tasks; `LightSnapshotTask` copies one `ChunkSnapshot` per chunk under the tick budget, with chunk tickets for the box. Scan and planning run on the render executor.
- `mc_light` is registered as the 19th tool; `mc_survey` and the heightmap RPC take `snowLayers` and `walk`.
- Deviations from the plan:
  - light levels are read from chunk snapshots off the main thread instead of `Block.getLightFromBlocks` on the main thread;
  - `spacing` is the minimum distance between suggested lights (largest axis difference, default 1 = no limit), not a grid step; spaces it leaves unlit are reported as staying dark;
  - `snowLayers` defaults to 3; the decoration-ignoring scan starts at the highest non-air block, because thin snow is not motion-blocking, and scans at most 64 blocks down;
  - `walkHeight` is limited to 4,096 columns (64x64);
  - `placement:"hidden"` needs the light block (it has no collision, so it can sit in the dark space itself); other blocks use `floor` or `ceiling`.
- Context cost: tool schemas and instructions grow by about 5,600 characters (about 1,400 tokens), mostly the new `mc_light` schema. Typical `mc_light` replies are 10-180 tokens.
- Verification:
  - one plugin build: 842 tests passed (24 new), zero failures; adapter 8 tests passed. The full build ran before a one-line fix to `SurfaceReader` (start the decoration scan at the highest non-air block); that fix was compiled and covered by the smoke test;
  - isolated-server smoke test `e2e-light.mjs`, 27 checks:
    - the catalog has 19 tools; `mc_light` is read-only; `mc_survey` has `walkHeight`;
    - hidden placement with a torch, a level not above the threshold and an unknown block are rejected;
    - a closed 9x4x9 stone room reports 81 of 81 dark spawn spaces, bounds and per-y counts; `spawnable:false` reports 324 open cells; `includeSky` changes nothing indoors; ceiling placement of a hanging lantern lands under the roof;
    - after building the suggested light blocks, the room reports 0 dark and "none needed"; a threshold of 3 finds dark corners again;
    - on a row of carpet, 2-layer snow, a lantern, a bottom slab, a fence, 1-layer snow and bare stone, `TOP_SOLID_IGNORE_DECOR` reads the stone under the decoration, and walk heights are 101.063, 101.125, 101.563, 101.5, 102.5, 101, 101; `snowLayers:0` keeps the 2-layer snow; `ANY` sees every decoration;
    - `snowLayers` without the decor type and an over-limit `walkHeight` area are rejected;
  - test region restored to pristine air, forceload removed, journal entries deleted, no non-player entities left. No production changes.

## Step 19 implementation and verification

Branch: `feat/usability-19`.

- `CommandShadow` (pure) checks the first token against a command-map lookup; `McCommand` reads the console's command map on the main thread (`PluginIdentifiableCommand` gives the owning plugin, so Paper's brigadier plugin commands count too), then routes, notes or rewrites before `run_command`.
- `BlueprintCompiler` validates `tags` and computes bounds by compiling the document with every role bound to stone (materials do not move geometry). `BlueprintStore` gains `list(filter)`, `tag`, `tags` and `touches`; `McBuild.traceLine` adds the blueprint line.
- Deviations from the plan:
  - bounds are cached in a `<id>.bounds.json` sidecar keyed by the document revision (recomputed if missing or stale), not inside the document, so `get` still returns exactly what was saved;
  - new `mc_blueprint tag` action, so status changes do not require resending a whole document;
  - bounds are in the frame the document is written in: world bounds for blueprints in absolute coordinates, local bounds for those built with a `transform`;
  - the trace line is a separate last line, only for blueprint builds (not dry runs); it omits the note;
  - the isolated server has no plugin that takes over a vanilla command, so override detection is covered by unit tests with a fake command map; the native check covers `vanilla:true` rewriting and rejection only.
- Found, not changed: `run_command` reports a vanilla command syntax error (for example `time query daytime`, which 26.2 replaced with timelines) as an unhandled `CommandException` instead of the error text.
- Context cost: tool schemas and instructions grow by about 2,800 characters (about 700 tokens), mostly `mc_blueprint`.
- Verification:
  - one plugin build: 852 tests passed (10 new), zero failures; one existing store test now ignores the bounds sidecar when counting files;
  - isolated-server smoke test `e2e-usability.mjs`, 23 checks:
    - `mc_command`: no note for vanilla `time` or Bukkit `plugins`; `vanilla:true` runs `minecraft:time`; `vanilla:true` is rejected for `plugins` (no vanilla command) and for an already namespaced command;
    - two tagged blueprints save; `list {tags}` filters by module and status; `touches` finds the tower by its bounds and not the far wall; an invalid status and an unknown tag are rejected;
    - building the tower ends with `Blueprint: <id> (module=..., status=draft); journal jrn-...` matching the journal line; a dry run has no trace line; after `tag` the line shows `status=built, undo=...` and `no journal` with `journal:false`;
  - test region restored to pristine air, forceload removed, journal entries and test blueprints deleted. No production changes.

## Step 20 implementation and verification

Branch: `feat/render-fidelity`.

- `BlockShapes` gains the new families with two orientation helpers (`rot` for horizontal facings, `toward` for six-way rods), `axis(state)` for pillars, `faceColors(state, base, mapColor)` and `entityPanel(type, facing)`. `ShapeRenderer.render` takes optional `Extras` (per-face colours, diff marks, placeholders); the old signature is unchanged, so `mc_plan` previews behave as before.
- `RenderTask` (angled views only) resolves face colours on the main thread with the palette colours (two extra map-colour lookups per pillar state, one per grass-like state) and reads paintings and item frames once entities are loaded (waits at most 20 ticks, then reports `entitiesUnloaded`). `SnapshotDiff` maps only the snapshot cells inside the render box and runs on the render executor.
- Deviations from the plan:
  - Paper exposes one map colour per state, so "separate top/side colours" are derived: vanilla logs colour by axis (the `axis=y` state is the end colour), and a small table gives grass-like blocks their side block;
  - walls already had post and side heights, and panes/bars were already thin; both are now covered by tests, and `_bars` also matches copper bars;
  - `compareTo` works only for isometric/perspective, which is where before/after checks are useful; flat views reject it;
  - `zoom` needed more than 16 px per block to be a real close-up, so angled views now accept `scale` up to 32 (flat views stay at 16; auto scale still caps at 16); the existing pixel and work budgets still apply;
  - placeholders cover paintings and item frames only; armor stands are not drawn.
- Context cost: tool schemas and instructions grow by about 1,600 characters (about 400 tokens), all in `mc_render`.
- Verification:
  - one plugin build: 874 tests passed (22 new), zero failures;
  - isolated-server smoke test `e2e-render.mjs`, 16 checks: a gallery of every new family plus pillars on three axes, walls, a painting and an item frame renders with no cube fallbacks and 3 placeholder cells (isometric and perspective); `zoom` gives the expected cube at 32 px per block and rejects zoom with `from`/`to`; after one change, one removal and one addition, `compareTo` reports exactly 1/1/1 with nothing outside the snapshot, and every gallery block counts as added against the pristine snapshot; flat-view, unknown-snapshot and non-overlapping `compareTo` calls are rejected;
  - the gallery, compare and zoom images were inspected: new shapes read correctly, the removed lantern shows as a red ghost, the changed basalt orange and the added block green;
  - an earlier run with a grass floor counted 2 extra changes: grass under the gallery decayed to dirt between snapshot and render, which is correct; the test floor is now stone;
  - test region restored to pristine air, forceload removed, no entities left, journal entries deleted. No production changes.

## Fork activation

User-approved deployment uses Paper 26.1.2 build 74, without a Paper upgrade. The fork now compiles against that exact API and declares minimum API 26.1. One build passed; an approved restart used a verified full 30-second in-game countdown before a graceful stop/install/start. Original artifact retained for rollback; plugin configuration/credentials unchanged. Pi points at the locally built fork adapter through the existing Docker network arrangement. Basic read-only deployment check confirmed plugin 0.4.9-dev, authenticated MCP, 13 tools and photo-reference instructions. No construction/terrain test writes or exhaustive regressions were performed on production. Pi needs `/reload` to refresh an existing session's connection and catalog.

### Separate upstream dependency finding

`npm ci` reported a high-severity advisory in the existing development-only `@modelcontextprotocol/client` dependency: GHSA-6qxp-vccf-f47h (OAuth credential disclosure). Dependencies and lockfile were not changed in Step 1; review separately rather than applying an unrelated automatic upgrade.
