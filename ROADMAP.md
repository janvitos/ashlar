# Fork enhancement roadmap

Each step requires user approval before work begins. Pause after each step and report completed work, verification results, and remaining steps. No live deployment or server shutdown/restart without separate approval.

## Priorities and status

1. **Local coordinates and reliable transformations** - complete; implementation and isolated Paper runtime acceptance tests passed. Paused for user review before Step 2.
2. **Reusable blueprints and components** - not started, approval required.
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

### Separate upstream dependency finding

`npm ci` reported a high-severity advisory in the existing development-only `@modelcontextprotocol/client` dependency: GHSA-6qxp-vccf-f47h (OAuth credential disclosure). Dependencies and lockfile were not changed in Step 1; review separately rather than applying an unrelated automatic upgrade.
