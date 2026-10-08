# Fork enhancement roadmap

Each step requires user approval before work begins. Pause after each step and report completed work, verification results, and remaining steps. No live deployment or server shutdown/restart without separate approval.

## Priorities and status

1. **Local coordinates and reliable transformations** - complete; implementation and isolated Paper runtime acceptance tests passed.
2. **Reusable blueprints and components** - complete; approved implementation, unit/native tests and reload-persistence checks passed. Paused for user review before Step 3.
3. **Preflight validation and dry-run previews** - not started, approval required.
4. **Exact verification and targeted repairs** - not started, approval required.
5. **Shape-aware isometric and perspective rendering** - not started, approval required.
6. **Architectural generators** - not started, approval required.
7. **Terrain-aware site planning** - not started, approval required.
8. **Design constraints and alternative concepts** - not started, approval required.
9. **Photo-reference reconstruction workflow** - not started, approval required.
10. **Safe project revisions and selective undo** - not started, approval required.

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

Next: user approval is required before Step 3 (preflight validation and previews). No Step 3 work has begun.

### Separate upstream dependency finding

`npm ci` reported a high-severity advisory in the existing development-only `@modelcontextprotocol/client` dependency: GHSA-6qxp-vccf-f47h (OAuth credential disclosure). Dependencies and lockfile were not changed in Step 1; review separately rather than applying an unrelated automatic upgrade.
