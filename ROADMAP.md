# Fork enhancement roadmap

Each step requires user approval before work begins. Pause after each step and report completed work, verification results, and remaining steps. No live deployment or server shutdown/restart without separate approval.

## Priorities and status

1. **Local coordinates and reliable transformations** - approved; implementation ready, Paper runtime acceptance testing pending approval to start and stop an isolated disposable test server.
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
- Native Paper `BlockData.mirror`/`rotate` handle directional properties and unspecified defaults, in batches of at most 64 distinct states per main-thread callback.
- Partial filters retain wildcard properties after transformation; cardinal property names are transformed too.
- Sign strings and metadata are preserved. Mirrored block lettering intentionally mirrors glyph geometry. Callers still provide both door/bed halves.
- Local-coordinate integer and overflow validation runs before snapshots or writes. Local lettering expands around zero and uses long intermediate coordinates to avoid wrap.
- Existing calls without `transform` remain unchanged. MCP tool catalog and English usage documentation updated.

### Verification performed

- 15 new JUnit tests pass, covering all 12 rotation/mirror combinations, paired-block positional invariants, cuboid geometry, exact glyph pixels, partial filters, backward compatibility, overflow, handler ordering, world-space snapshot bounds and tool schema.
- Plugin full suite: 548 tests, 547 pass. The sole failure is upstream `AwtGlyphsTest.anUncoveredCodePointFailsInsteadOfDrawingTheMissingGlyphBox`: this host's font covers U+E000, contrary to the test's assumption. Reproduced on untouched upstream commit `73026c7`; unrelated code was not changed.
- Plugin build passes with only that reproduced upstream test excluded via an external temporary Gradle init script. Built artifact: `plugin/build/libs/ashlar-0.4.9-dev.jar`.
- MCP TypeScript build passes; all 8 existing adapter tests pass.
- `git diff --check` passes; new Java sources are ASCII and carry SPDX headers.
- No production plugin, world or server configuration changed. No server started, stopped or restarted.

### Remaining Step 1 acceptance work

With explicit approval to start and stop a separate disposable Paper test server:

1. Load the development jar only on that isolated instance; use loopback-only ports and a temporary world.
2. Exercise the actual `mc_build` tool path for all rotations and mirrors, then read exact world states back.
3. Verify stair shape/facing, both door halves and hinges, bed offsets, standing/wall sign orientations and text, log axes, rail shapes, and cardinal connection properties. Test omitted directional defaults too.
4. Verify transformed partial filters, lettering/background geometry, snapshot bounds and restoration end-to-end.
5. Require no unexplained support warnings or runtime errors; fix discrepancies before marking Step 1 accepted.
6. Shut down only the approved test instance, using the required verified in-game countdown. Do not touch the live server.

### Separate upstream dependency finding

`npm ci` reported a high-severity advisory in the existing development-only `@modelcontextprotocol/client` dependency: GHSA-6qxp-vccf-f47h (OAuth credential disclosure). Dependencies and lockfile were not changed in Step 1; review separately rather than applying an unrelated automatic upgrade.
