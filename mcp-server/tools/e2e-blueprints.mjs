#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in destructive test for a disposable loopback server with no players.
// Does not start, stop or reload the server. Snapshots/restores both reserved regions.
// ASHLAR_TEST_KEEP_BLUEPRINT=1 retains the test document for a separate reload-persistence check.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/client";
import { StdioClientTransport, getDefaultEnvironment } from "@modelcontextprotocol/client/stdio";
import { PluginClient } from "../dist/plugin-client.js";

assert.equal(process.env.ASHLAR_DISPOSABLE_TEST, "1");
assert.ok(process.env.MC_PLUGIN_TOKEN);
const url = new URL(process.env.MC_PLUGIN_URL);
assert.ok(["127.0.0.1", "localhost", "[::1]"].includes(url.hostname));
const plugin = new PluginClient({ url: url.href, token: process.env.MC_PLUGIN_TOKEN, defaultTimeoutMs: 60000 });
const client = new Client({ name: "blueprint-acceptance", version: "1.0.0" });
const transport = new StdioClientTransport({ command: process.execPath,
    args: [fileURLToPath(new URL("../dist/cli.js", import.meta.url)), "--stdio"],
    env: { ...getDefaultEnvironment(), MC_PLUGIN_URL: url.href, MC_PLUGIN_TOKEN: process.env.MC_PLUGIN_TOKEN } });
const id = "e2e_windows_" + randomUUID().replaceAll("-", "");
const base = [128, 100, 0], target = [256, 100, 0];
const min = [-30, 0, -30], max = [30, 14, 30];
const add = (a, b) => a.map((v, i) => v + b[i]);
const key = p => p.join(",");
let checks = 0;
function check(fn) { checks++; fn(); }
const textOf = result => result.content.filter(c => c.type === "text").map(c => c.text).join("\n");
async function tool(name, args) {
    const r = await client.callTool({ name, arguments: args });
    assert.ok(!r.isError, textOf(r));
    if (name === "mc_build") assert.ok(!/WARNINGS/i.test(textOf(r)), textOf(r));
    return r;
}
async function blueprint(action, args = {}) { return tool("mc_blueprint", { action, ...args }); }
async function read(origin) {
    const r = await plugin.request("read_region", { from: add(origin, min), to: add(origin, max) });
    const states = new Map(); let run = 0, remaining = r.runs[0][1];
    for (let y = min[1]; y <= max[1]; y++) for (let z = min[2]; z <= max[2]; z++) for (let x = min[0]; x <= max[0]; x++) {
        states.set(key([x,y,z]), r.palette[r.runs[run][0]]);
        if (--remaining === 0 && run + 1 < r.runs.length) remaining = r.runs[++run][1];
    }
    return { states, signs: r.signs ?? [] };
}
async function snapshot(origin) { return (await plugin.request("snapshot", { from: add(origin,min), to: add(origin,max), label: "blueprint acceptance" })).id; }
async function restore(id) { await tool("mc_restore", { id }); }
function position(p, rotation, mirror) {
    let [x,y,z] = p; if (mirror === "x") x = -x; if (mirror === "z") z = -z;
    for (let r = 0; r < rotation; r += 90) [x,z] = [-z,x];
    return [x,y,z];
}
function direction(d, rotation, mirror) {
    const vectors = { north:[0,0,-1], east:[1,0,0], south:[0,0,1], west:[-1,0,0] };
    if (!vectors[d]) return d;
    const p = position(vectors[d],rotation,mirror);
    return Object.keys(vectors).find(k => key(vectors[k]) === key(p));
}
function state(raw, rotation = 0, mirror = "none") {
    const bracket = raw.indexOf("["); if (bracket < 0) return raw;
    const material = raw.slice(0,bracket);
    const props = raw.slice(bracket+1,-1).split(",").map(s => {
        let [name,value] = s.split("="); name = direction(name,rotation,mirror);
        if (name === "facing") value = direction(value,rotation,mirror);
        if (name === "axis" && rotation % 180 && value !== "y") value = value === "x" ? "z" : "x";
        if (name === "rotation") {
            let r = Number(value); if (mirror === "x") r = -r; if (mirror === "z") r = 8-r;
            value = String(((r+rotation/90*4)%16+16)%16);
        }
        if (mirror !== "none" && (name === "hinge" || (name === "shape" && material.endsWith("_stairs"))))
            value = value.replace(/left|right/g, s => s === "left" ? "right" : "left");
        return `${name}=${value}`;
    });
    return `${material}[${props.sort().join(",")}]`;
}
const document = {
    version:1, description:"Reusable window rows and a badge", dimensions:[40,12,40], constraints:{ style:"gothic", symmetry:true },
    palette:{ wall:"minecraft:stone_bricks", trim:"minecraft:oak_stairs[half=top]", pane:"minecraft:glass", paint:"minecraft:yellow_concrete", backing:"minecraft:blue_concrete", empty:"minecraft:air" },
    components:{
        window:{ palette:{wall:"minecraft:dirt"}, fills:[
            {from:[0,0,0],to:[5,0,3],block:"$wall",filter:"$empty"},
            {from:[0,1,0],to:[0,3,0],block:"$wall"}, {from:[4,1,0],to:[4,3,0],block:"$wall"},
            {from:[1,1,0],to:[3,3,0],block:"$pane"}, {from:[0,4,0],to:[4,4,0],block:"$wall"}
        ], blocks:[
            {pos:[0,4,0],block:"$trim[facing=north,shape=outer_left,half=bottom]"},
            {pos:[4,4,0],block:"$trim[facing=north,shape=outer_right]"},
            {pos:[5,1,0],block:"minecraft:oak_sign[rotation=3]",sign:{front:["$wall"],back:["Not reversed"],waxed:true}},
            {pos:[2,1,2],block:"minecraft:red_bed[facing=south,part=foot]"}, {pos:[2,1,3],block:"minecraft:red_bed[facing=south,part=head]"},
            {pos:[0,1,3],block:"minecraft:oak_door[facing=north,half=lower,hinge=left]"}, {pos:[0,2,3],block:"minecraft:oak_door[facing=north,half=upper,hinge=left]"}
        ], text:[{text:"R",pos:[0,8,0],facing:"up",block:"$paint",background:"$backing"}]},
        badge:{text:[{text:"L",pos:[0,1,0],block:"$paint",background:"$backing"}]}
    },
    instances:[
        {component:"window",pos:[-14,0,-12],repeat:{count:3,step:[12,0,0]}},
        {component:"window",pos:[-14,0,12],rotation:90,mirror:"z",palette:{paint:"minecraft:lime_concrete"},repeat:{count:2,step:[12,0,0]}},
        {component:"badge",pos:[8,1,16],rotation:270,mirror:"x"}
    ]
};
const overrides = { paint:"minecraft:gold_block" };
function bind(raw, palette) {
    if (!raw.startsWith("$")) return raw;
    const [,role,suffix] = raw.match(/^\$([a-z_]+)(\[.*\])?$/);
    const bound = palette[role]; if (!suffix) return bound;
    const [material,body] = bound.split("[");
    const props = Object.fromEntries((body ? body.slice(0,-1).split(",") : []).map(s => s.split("=")));
    for (const s of suffix.slice(1,-1).split(",")) { const [k,v] = s.split("="); props[k] = v; }
    return material + "[" + Object.entries(props).map(([k,v]) => `${k}=${v}`).join(",") + "]";
}
let baseSnapshot, targetSnapshot, saved = false;
plugin.start();
try {
    await client.connect(transport);
    assert.match(textOf(await tool("mc_players",{})), /No players online/);
    const catalog = await client.listTools(); check(() => assert.ok(catalog.tools.some(t => t.name === "mc_blueprint")));
    baseSnapshot = await snapshot(base); targetSnapshot = await snapshot(target);
    const pristine = await read(target);
    await blueprint("save",{id,document}); saved = true;
    const got = JSON.parse(textOf(await blueprint("get",{id})));
    check(() => assert.deepEqual(got,document));
    const list = JSON.parse(textOf(await blueprint("list")));
    check(() => assert.ok(list.blueprints.some(b => b.id === id && b.components === 2)));
    const accidental = await client.callTool({name:"mc_blueprint",arguments:{action:"save",id,document}});
    check(() => assert.ok(accidental.isError));
    // Independent reference: manually repeat Step 1 direct builds, not the blueprint compiler.
    for (const instance of document.instances) {
        const component = document.components[instance.component];
        const palette = {...component.palette,...document.palette,...overrides,...instance.palette};
        for (let i = 0; i < (instance.repeat?.count ?? 1); i++) {
            const local = add(instance.pos, (instance.repeat?.step ?? [0,0,0]).map(v => v*i));
            const args = {transform:{origin:add(base,local),rotation:instance.rotation ?? 0,mirror:instance.mirror ?? "none"},connect:false};
            for (const kind of ["fills","blocks","text"]) if (component[kind]) {
                args[kind] = structuredClone(component[kind]);
                for (const op of args[kind]) for (const field of ["block","filter","background"]) if (op[field]) op[field] = bind(op[field],palette);
            }
            await tool("mc_build",args);
        }
    }
    const reference = await read(base);
    const nonAir = [...reference.states].filter(([,s]) => s !== "minecraft:air");
    for (const rotation of [0,90,180,270]) for (const mirror of ["none","x","z"]) {
        const result = await tool("mc_build",{blueprint:{id,palette:overrides},transform:{origin:target,rotation,mirror},connect:false,snapshot:true});
        const autoId = textOf(result).match(/Snapshot[^\n]*?(snap-[\w-]+)/i)[1];
        const built = await read(target);
        check(() => assert.equal([...built.states.values()].filter(s => s !== "minecraft:air").length,nonAir.length));
        for (const [p,raw] of nonAir) check(() => assert.equal(state(built.states.get(key(position(p.split(",").map(Number),rotation,mirror)))),state(raw,rotation,mirror)));
        for (const sign of reference.signs) check(() => {
            const local = sign.pos.map((v,i) => v-base[i]); const world = add(position(local,rotation,mirror),target);
            const found = built.signs.find(s => key(s.pos) === key(world)); assert.ok(found);
            assert.deepEqual(found.front,sign.front); assert.deepEqual(found.back,sign.back); assert.equal(found.waxed,sign.waxed);
        });
        await restore(autoId);
        const restored = await read(target); check(() => assert.deepEqual(restored.states,pristine.states));
        console.log(`Checked blueprint transform ${rotation}/${mirror}`);
    }
    const snapshotsBefore = (await plugin.request("list_snapshots",{})).snapshots.map(s => s.id).sort();
    const invalidDoc = {version:1,components:{good:{blocks:[{pos:[0,0,0],block:"minecraft:diamond_block"}]},bad:{blocks:[{pos:[0,0,0],block:"minecraft:not_a_block"}]}},
        instances:[{component:"good",pos:[0,0,0]},{component:"bad",pos:[1,0,0]}]};
    await blueprint("save",{id,document:invalidDoc,overwrite:true});
    const invalid = await client.callTool({name:"mc_build",arguments:{blueprint:{id},transform:{origin:target},snapshot:true}});
    check(() => assert.ok(invalid.isError));
    const afterInvalid = await read(target); check(() => assert.deepEqual(afterInvalid.states,pristine.states));
    const afterSnapshotIds = (await plugin.request("list_snapshots",{})).snapshots.map(s => s.id).sort();
    check(() => assert.deepEqual(afterSnapshotIds,snapshotsBefore));
    await blueprint("save",{id,document,overwrite:true});
    const unresolved = structuredClone(document); delete unresolved.palette.trim;
    await blueprint("save",{id,document:unresolved,overwrite:true});
    const unbound = await client.callTool({name:"mc_build",arguments:{blueprint:{id},transform:{origin:target}}});
    check(() => assert.ok(unbound.isError));
    await blueprint("save",{id,document,overwrite:true});
    const pathAttempt = await client.callTool({name:"mc_blueprint",arguments:{action:"get",id:"../escape"}});
    check(() => assert.ok(pathAttempt.isError));
    console.log(JSON.stringify({checks,failures:0,blueprintId:id,kept:process.env.ASHLAR_TEST_KEEP_BLUEPRINT === "1"}));
} finally {
    try { if (targetSnapshot) await restore(targetSnapshot); }
    finally {
        try { if (baseSnapshot) await restore(baseSnapshot); }
        finally {
            try { if (saved && process.env.ASHLAR_TEST_KEEP_BLUEPRINT !== "1") await blueprint("delete",{id}); }
            finally { await client.close(); plugin.close(); }
        }
    }
}
