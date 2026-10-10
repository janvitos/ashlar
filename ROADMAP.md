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

## Fork activation

User-approved deployment uses Paper 26.1.2 build 74, without a Paper upgrade. The fork now compiles against that exact API and declares minimum API 26.1. One build passed; an approved restart used a verified full 30-second in-game countdown before a graceful stop/install/start. Original artifact retained for rollback; plugin configuration/credentials unchanged. Pi points at the locally built fork adapter through the existing Docker network arrangement. Basic read-only deployment check confirmed plugin 0.4.9-dev, authenticated MCP, 13 tools and photo-reference instructions. No construction/terrain test writes or exhaustive regressions were performed on production. Pi needs `/reload` to refresh an existing session's connection and catalog.

### Separate upstream dependency finding

`npm ci` reported a high-severity advisory in the existing development-only `@modelcontextprotocol/client` dependency: GHSA-6qxp-vccf-f47h (OAuth credential disclosure). Dependencies and lockfile were not changed in Step 1; review separately rather than applying an unrelated automatic upgrade.
