# Agent instructions

## Project rules

Read `CLAUDE.md` before working in this repository for language, licensing, architecture, threading, and block-write rules. The basic-testing policy below governs verification scope; interpret any older instructions to verify engine assumptions as a targeted smoke check, not exhaustive testing.

## Basic testing and verification

These instructions apply on new sessions, resumed sessions, and after context compaction. Default to basic, proportionate testing, not exhaustive reassurance.

1. Run one compile/build check for the affected component. Existing fast tests that naturally run with that build are fine.
2. Add or run a few focused tests for changed behavior and obvious invalid inputs. Do not build an extensive acceptance harness by default.
3. Run one small isolated-server smoke test only when actual runtime behavior or a new engine assumption requires it. Reuse the running disposable server.
4. For visual changes, inspect one relevant appearance render.
5. Stop when these checks pass. If a check fails, fix the concrete issue and rerun only the affected check.

Do not run exhaustive whole-region comparisons, combinatorial sweeps, custom native probes, every earlier feature's native regression suite, or repeated successful build/test runs for reassurance. Broader verification requires explicit user approval. If a concrete risk warrants more testing, explain it and ask first.

During review/merge, inspect the diff and existing results. Do not automatically repeat successful checks for an unchanged commit; rerun only when a concrete change or missing result makes it necessary.

Keep mandatory runtime safety validation, permission/resource bounds, safe block writes, and rollback precautions intact. Reducing development verification does not disable those protections.

Report new tests separately from the existing suite. Describe runtime scenarios, not inflated totals of individual block comparisons. Clearly distinguish measured command duration from estimated development/testing time.

## Operational boundaries and step approvals

- Keep production servers, plugins, credentials, and worlds untouched unless the user explicitly authorizes deployment or modification.
- Obtain separate user approval before restarting or shutting down a server, proxy, or host; follow applicable operational instructions and verified in-game countdown requirements. Plugin/script reloads do not require restart approval.
- Complete the approved implementation step, report its results, and pause. Review/merge approval is not approval to start the next step.

## Routine Minecraft build verification

Use one relevant survey (reuse a recent one), build, and one appearance render. Query players only for player context/safety. Tiny edits can rely on build feedback. Investigate and correct concrete defects locally; no repeated whole-build scans. Exact auditing remains opt-in. Preserve rollback protection when modifying existing terrain or structures; snapshots do not preserve arbitrary NBT.
