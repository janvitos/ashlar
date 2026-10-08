#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in destructive acceptance test. ONLY use on a disposable loopback Paper server.
// ASHLAR_DISPOSABLE_TEST=1 MC_PLUGIN_URL=ws://127.0.0.1:<port> MC_PLUGIN_TOKEN=<token>
// node tools/e2e-transforms.mjs
// Does not start or stop the server. Restores its test regions even after assertion failures.

import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/client";
import { StdioClientTransport, getDefaultEnvironment } from "@modelcontextprotocol/client/stdio";
import { PluginClient } from "../dist/plugin-client.js";

assert.equal(process.env.ASHLAR_DISPOSABLE_TEST, "1", "Explicit disposable-server opt-in required");
assert.ok(process.env.MC_PLUGIN_TOKEN, "MC_PLUGIN_TOKEN required");
const url = new URL(process.env.MC_PLUGIN_URL);
assert.ok(["127.0.0.1", "localhost", "[::1]"].includes(url.hostname), "Loopback server required");
const plugin = new PluginClient({ url: url.href, token: process.env.MC_PLUGIN_TOKEN, defaultTimeoutMs: 60_000 });
const client = new Client({ name: "ashlar-transform-acceptance", version: "1.0.0" });
const transport = new StdioClientTransport({
    command: process.execPath,
    args: [fileURLToPath(new URL("../dist/cli.js", import.meta.url)), "--stdio"],
    env: { ...getDefaultEnvironment(), MC_PLUGIN_URL: url.href, MC_PLUGIN_TOKEN: process.env.MC_PLUGIN_TOKEN }
});
const baseOrigin = [0, 100, 0], targetOrigin = [64, 100, 0];
const extentMin = [-10, 0, -10], extentMax = [10, 10, 10];
const key = p => p.join(",");
const add = (a, b) => a.map((v, i) => v + b[i]);
let checks = 0, failures = 0;
const failureExamples = [];
function check(label, fn) {
    checks++;
    try { fn(); } catch (e) {
        failures++;
        if (failureExamples.length < 30) failureExamples.push(`${label}: ${e.message}`);
    }
}
function content(result) { return result.content.filter(c => c.type === "text").map(c => c.text).join("\n"); }
async function tool(name, args) {
    const result = await client.callTool({ name, arguments: args });
    assert.ok(!result.isError, `${name}: ${content(result)}`);
    if (name === "mc_build") assert.ok(!/WARNINGS/i.test(content(result)), content(result));
    return result;
}
async function snapshot(origin) {
    const result = await plugin.request("snapshot", {
        world: "world", from: add(origin, extentMin), to: add(origin, extentMax), label: "transform acceptance"
    });
    return result.id;
}
async function restore(id) { await tool("mc_restore", { id }); }
async function read(origin) {
    const result = await plugin.request("read_region", { world: "world", from: add(origin, extentMin), to: add(origin, extentMax) });
    const states = new Map();
    let run = 0, remaining = result.runs[0][1];
    for (let y = extentMin[1]; y <= extentMax[1]; y++)
        for (let z = extentMin[2]; z <= extentMax[2]; z++)
            for (let x = extentMin[0]; x <= extentMax[0]; x++) {
                states.set(key([x, y, z]), result.palette[result.runs[run][0]]);
                if (--remaining === 0 && run + 1 < result.runs.length) remaining = result.runs[++run][1];
            }
    return { states, signs: result.signs ?? [] };
}
function position(p, rotation, mirror) {
    let [x, y, z] = p;
    if (mirror === "x") x = -x;
    if (mirror === "z") z = -z;
    for (let r = 0; r < rotation; r += 90) [x, z] = [-z, x];
    return [x, y, z];
}
function direction(d, rotation, mirror) {
    const vectors = { north: [0, 0, -1], east: [1, 0, 0], south: [0, 0, 1], west: [-1, 0, 0] };
    if (!vectors[d]) return d;
    const p = position(vectors[d], rotation, mirror);
    return Object.keys(vectors).find(k => key(vectors[k]) === key(p));
}
function state(raw, rotation = 0, mirror = "none") {
    const bracket = raw.indexOf("[");
    if (bracket < 0) return raw;
    const material = raw.slice(0, bracket), entries = raw.slice(bracket + 1, -1).split(",");
    const mapped = entries.map(entry => {
        let [name, value] = entry.split("=");
        name = direction(name, rotation, mirror);
        if (name === "facing") value = direction(value, rotation, mirror);
        if (name === "orientation") value = value.split("_").map(d => direction(d, rotation, mirror)).join("_");
        if (name === "type" && material.endsWith("chest") && mirror !== "none") {
            value = value.replace(/left|right/g, s => s === "left" ? "right" : "left");
        }
        if (name === "axis" && rotation % 180 !== 0 && value !== "y") value = value === "x" ? "z" : "x";
        if (name === "rotation") {
            let r = Number(value);
            if (mirror === "x") r = -r;
            if (mirror === "z") r = 8 - r;
            value = String(((r + rotation / 90 * 4) % 16 + 16) % 16);
        }
        if (mirror !== "none" && (name === "hinge" || (name === "shape" && material.endsWith("_stairs")))) {
            value = value.replace(/left|right/g, s => s === "left" ? "right" : "left");
        }
        if (name === "shape" && material.endsWith("rail")) {
            if (value.startsWith("ascending_")) value = "ascending_" + direction(value.slice(10), rotation, mirror);
            else {
                const directions = value.split("_").map(d => direction(d, rotation, mirror));
                value = ["north", "south", "east", "west"].filter(d => directions.includes(d)).join("_");
            }
        }
        return `${name}=${value}`;
    });
    return `${material}[${mapped.sort().join(",")}]`;
}
function fixture() {
    const fills = [{ from: [-10, 0, -10], to: [10, 0, 10], block: "minecraft:stone" }];
    const blocks = [];
    const stairShapes = ["straight", "inner_left", "inner_right", "outer_left", "outer_right"];
    for (const [row, facing] of ["north", "east", "south", "west"].entries())
        for (const [column, shape] of stairShapes.entries()) blocks.push({
            pos: [-8 + column * 3, 1, -8 + row * 2], block: `minecraft:oak_stairs[facing=${facing},shape=${shape},half=${row % 2 ? "top" : "bottom"}]`
        });
    blocks.push(
        { pos: [7, 1, -8], block: "minecraft:oak_stairs" },
        { pos: [7, 1, -6], block: "minecraft:oak_log[axis=x]" },
        { pos: [7, 1, -4], block: "minecraft:oak_log[axis=z]" },
        { pos: [7, 1, -2], block: "minecraft:oak_log" },
        { pos: [3, 1, 2], block: "minecraft:red_bed[facing=south,part=foot]" },
        { pos: [3, 1, 3], block: "minecraft:red_bed[facing=south,part=head]" },
        { pos: [6, 1, 2], block: "minecraft:oak_door[facing=north,half=lower,hinge=left]" },
        { pos: [6, 2, 2], block: "minecraft:oak_door[facing=north,half=upper,hinge=left]" },
        { pos: [-8, 1, 6], block: "minecraft:oak_sign", sign: { front: ["Default sign"], back: ["Back stays"] } },
        { pos: [-6, 1, 3], block: "minecraft:oak_sign[rotation=3]", sign: { front: ["Angled sign"], glowing: true, waxed: true } },
        { pos: [0, 2, 7], block: "minecraft:stone" },
        { pos: [0, 2, 6], block: "minecraft:oak_wall_sign[facing=north]", sign: { front: ["Wall sign"], back: ["Not reversed"] } },
        { pos: [-3, 2, 7], block: "minecraft:stone" },
        { pos: [-3, 2, 6], block: "minecraft:wall_torch[facing=north]" },
        { pos: [-6, 1, 7], block: "minecraft:stone" },
        { pos: [-6, 2, 7], block: "minecraft:stone" },
        { pos: [-6, 1, 6], block: "minecraft:ladder[facing=north]" },
        { pos: [-6, 2, 6], block: "minecraft:ladder[facing=north]" },
        { pos: [-2, 1, 2], block: "minecraft:chest[facing=south]" },
        { pos: [-1, 1, 2], block: "minecraft:chest[facing=south]" },
        { pos: [0, 1, 2], block: "minecraft:hopper[facing=west]" },
        { pos: [-3, 1, 7], block: "minecraft:stone" },
        { pos: [-3, 1, 6], block: "minecraft:lever[face=wall,facing=north]" },
        { pos: [-4, 1, 2], block: "minecraft:oak_trapdoor[facing=east,half=top,open=true]" },
        { pos: [-8, 1, 2], block: "minecraft:jigsaw[orientation=east_up]" },
        { pos: [-8, 1, 4], block: "minecraft:jigsaw[orientation=up_north]" },
        { pos: [0, 1, 4], block: "minecraft:glass_pane[north=true,east=false,south=false,west=true]" },
        { pos: [2, 1, 6], block: "minecraft:oak_fence[north=true,east=false,south=false,west=true]" },
        { pos: [8, 1, 6], block: "minecraft:rail[shape=north_east]" },
        { pos: [8, 1, 8], block: "minecraft:rail[shape=south_west]" },
        { pos: [4, 1, 8], block: "minecraft:rail[shape=ascending_north]" },
        { pos: [4, 1, 7], block: "minecraft:stone" },
        { pos: [0, 1, 8], block: "minecraft:rail[shape=east_west]" }
    );
    return { fills, blocks, text: [
        { text: "R", pos: [0, 5, 0], facing: "up", block: "minecraft:white_concrete", background: "minecraft:black_concrete" },
        { text: "L", pos: [3, 3, 6], facing: "south", block: "minecraft:yellow_concrete", background: "minecraft:blue_concrete" }
    ] };
}
let baseSnapshot, targetSnapshot;
const autoSnapshotIds = new Set();
plugin.start();
try {
    await client.connect(transport);
    const players = await tool("mc_players", {});
    assert.match(content(players), /No players online/, "No players may be connected during disposable acceptance tests");
    const { tools } = await client.listTools();
    assert.ok(tools.find(t => t.name === "mc_build").inputSchema.properties.transform);
    baseSnapshot = await snapshot(baseOrigin);
    targetSnapshot = await snapshot(targetOrigin);
    const pristine = await read(targetOrigin);
    const f = fixture();
    const baselineFixture = structuredClone(f);
    for (const fill of baselineFixture.fills) { fill.from = add(fill.from, baseOrigin); fill.to = add(fill.to, baseOrigin); }
    for (const block of baselineFixture.blocks) block.pos = add(block.pos, baseOrigin);
    for (const text of baselineFixture.text) text.pos = add(text.pos, baseOrigin);
    await tool("mc_build", { ...baselineFixture, connect: false });
    const baseline = await read(baseOrigin);
    for (const rotation of [0, 90, 180, 270]) for (const mirror of ["none", "x", "z"]) {
        const label = `${rotation}/${mirror}`;
        const build = await tool("mc_build", { ...f, transform: { origin: targetOrigin, rotation, mirror }, connect: false, snapshot: true });
        const snapshotMatch = content(build).match(/Snapshot[^\n]*?(snap-[\w-]+)/i);
        assert.ok(snapshotMatch, content(build));
        autoSnapshotIds.add(snapshotMatch[1]);
        const built = await read(targetOrigin);
        for (const [p, raw] of baseline.states) {
            const target = position(p.split(",").map(Number), rotation, mirror);
            check(`${label} ${p} ${raw}`, () => assert.equal(state(built.states.get(key(target))), state(raw, rotation, mirror)));
        }
        for (const sign of baseline.signs) {
            const local = sign.pos.map((n, i) => n - baseOrigin[i]);
            const world = add(position(local, rotation, mirror), targetOrigin);
            check(`${label} sign ${sign.front[0]}`, () => {
                const found = built.signs.find(s => key(s.pos) === key(world));
                assert.ok(found);
                assert.deepEqual(found.front, sign.front);
                assert.deepEqual(found.back, sign.back);
                assert.equal(found.waxed, sign.waxed);
            });
        }
        // The transformed partial filter must keep half/shape as wildcards, but exclude other facings.
        const filteredPos = [-8, 1, -8];
        await tool("mc_build", { transform: { origin: targetOrigin, rotation, mirror }, connect: false,
            fills: [{ from: [-10, 1, -10], to: [10, 1, 10], block: "minecraft:gold_block", filter: "minecraft:oak_stairs[facing=north]" }] });
        const filtered = await read(targetOrigin);
        check(`${label} partial filter`, () => assert.equal(filtered.states.get(key(position(filteredPos, rotation, mirror))), "minecraft:gold_block"));
        check(`${label} filter excludes east`, () => assert.equal(
            state(filtered.states.get(key(position([-8, 1, -6], rotation, mirror)))), state(baseline.states.get("-8,1,-6"), rotation, mirror)));
        // Property names and mirrored corner shape must both be preserved in partial filters.
        await tool("mc_build", { transform: { origin: targetOrigin, rotation, mirror }, connect: false,
            fills: [
                { from: [-10, 1, -10], to: [10, 1, 10], block: "minecraft:diamond_block", filter: "minecraft:oak_stairs[facing=east,shape=inner_left]" },
                { from: [0, 1, 4], to: [0, 1, 4], block: "minecraft:emerald_block", filter: "minecraft:glass_pane[north=true]" }
            ] });
        const shapeFiltered = await read(targetOrigin);
        check(`${label} mirrored shape filter`, () => assert.equal(shapeFiltered.states.get(key(position([-5, 1, -6], rotation, mirror))), "minecraft:diamond_block"));
        check(`${label} cardinal property filter`, () => assert.equal(shapeFiltered.states.get(key(position([0, 1, 4], rotation, mirror))), "minecraft:emerald_block"));
        await restore(snapshotMatch[1]);
        const restored = await read(targetOrigin);
        check(`${label} auto-snapshot restoration`, () => assert.deepEqual(restored.states, pristine.states));
        console.log(`Checked transform ${label}`);
    }
    // Also test the normal default connection pass, not only exact-state connect:false writes.
    // Use the same pristine starting state as each target, including for automatic chest pairing.
    await restore(baseSnapshot);
    await tool("mc_build", baselineFixture);
    const connectedBaseline = await read(baseOrigin);
    for (const rotation of [0, 90, 180, 270]) for (const mirror of ["none", "x", "z"]) {
        await tool("mc_build", { ...f, transform: { origin: targetOrigin, rotation, mirror } });
        const connected = await read(targetOrigin);
        for (const [p, raw] of connectedBaseline.states) {
            const target = position(p.split(",").map(Number), rotation, mirror);
            check(`connected ${rotation}/${mirror} ${p} ${raw}`, () => assert.equal(
                state(connected.states.get(key(target))), state(raw, rotation, mirror)));
        }
        await restore(targetSnapshot);
        console.log(`Checked default connection pass ${rotation}/${mirror}`);
    }
    // Exercise multiple native-transform batches and request-local caching with 160 distinct
    // states, each used twice. All are supported by a floor and fit in the reserved region.
    const manyBlocks = [];
    for (const species of ["oak", "spruce"])
      for (const facing of ["north", "east", "south", "west"])
        for (const half of ["top", "bottom"])
            for (const shape of ["straight", "inner_left", "inner_right", "outer_left", "outer_right"])
                for (const waterlogged of ["true", "false"])
                    for (let repeat = 0; repeat < 2; repeat++) {
                        const i = manyBlocks.length;
                        manyBlocks.push({ pos: [-9 + i % 19, 1, -9 + Math.floor(i / 19)],
                            block: `minecraft:${species}_stairs[facing=${facing},half=${half},shape=${shape},waterlogged=${waterlogged}]` });
                    }
    await tool("mc_build", { transform: { origin: targetOrigin, rotation: 270, mirror: "x" }, connect: false,
        fills: [{ from: [-10, 0, -10], to: [10, 0, 10], block: "minecraft:stone" }], blocks: manyBlocks });
    const batchedRead = await read(targetOrigin);
    for (const b of manyBlocks) check(`batched ${b.block}`, () => assert.equal(
        state(batchedRead.states.get(key(position(b.pos, 270, "x")))), state(b.block, 270, "x")));
    await restore(targetSnapshot);
    // A late invalid state must not allow earlier fills or snapshots to run.
    const beforeInvalid = await read(targetOrigin);
    const beforeSnapshotIds = (await plugin.request("list_snapshots", {})).snapshots.map(s => s.id).sort();
    const invalid = await client.callTool({ name: "mc_build", arguments: {
        transform: { origin: targetOrigin, rotation: 90 }, snapshot: true,
        fills: [{ from: [0, 0, 0], to: [1, 1, 1], block: "minecraft:diamond_block" }],
        blocks: [{ pos: [0, 2, 0], block: "minecraft:not_a_block" }]
    } });
    check("invalid state rejected", () => assert.ok(invalid.isError));
    const afterInvalid = await read(targetOrigin);
    check("invalid state causes no writes", () => assert.deepEqual(afterInvalid.states, beforeInvalid.states));
    const snapshots = await plugin.request("list_snapshots", {});
    check("invalid state causes no snapshot", () => assert.deepEqual(snapshots.snapshots.map(s => s.id).sort(), beforeSnapshotIds));
    const auto = snapshots.snapshots.filter(s => autoSnapshotIds.has(s.id));
    check("all auto-snapshot bounds are transformed world bounds", () => {
        assert.equal(auto.length, 12);
        for (const snap of auto) {
            assert.deepEqual(snap.from, add(targetOrigin, extentMin));
            assert.deepEqual(snap.to, add(targetOrigin, [10, 9, 10]));
        }
    });
} finally {
    console.log(JSON.stringify({ checks, failures, failureExamples }, null, 2));
    try {
        if (targetSnapshot) await restore(targetSnapshot);
    } finally {
        try {
            if (baseSnapshot) await restore(baseSnapshot);
        } finally {
            await client.close();
            plugin.close();
        }
    }
}
assert.equal(failures, 0, "Transform acceptance failures");
