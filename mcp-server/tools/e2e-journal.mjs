#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in disposable-server acceptance for the build journal; restores a pristine test region, never manages server lifecycle.
import assert from "node:assert/strict";
import {fileURLToPath} from "node:url";
import {Client} from "@modelcontextprotocol/client";
import {StdioClientTransport,getDefaultEnvironment} from "@modelcontextprotocol/client/stdio";
import {PluginClient} from "../dist/plugin-client.js";
assert.equal(process.env.ASHLAR_DISPOSABLE_TEST,"1");
const url=new URL(process.env.MC_PLUGIN_URL);assert.ok(["127.0.0.1","localhost","[::1]"].includes(url.hostname));assert.ok(process.env.MC_PLUGIN_TOKEN);
const plugin=new PluginClient({url:url.href,token:process.env.MC_PLUGIN_TOKEN,defaultTimeoutMs:120000});
const client=new Client({name:"journal-acceptance",version:"1.0.0"});
const transport=new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL("../dist/cli.js",import.meta.url)),"--stdio"],env:{...getDefaultEnvironment(),MC_PLUGIN_URL:url.href,MC_PLUGIN_TOKEN:process.env.MC_PLUGIN_TOKEN}});
const origin=[576,100,0],from=[536,99,-40],to=[616,120,40];
let snapshot,checks=0;const created=new Set();
const text=r=>r.content.filter(c=>c.type==="text").map(c=>c.text).join("\n");
function check(fn){checks++;fn();}
async function tool(name,args){const r=await client.callTool({name,arguments:args});assert.ok(!r.isError,text(r));return text(r);}
const at=(x,y,z)=>[origin[0]+x,origin[1]+y,origin[2]+z];
function journalId(t){const m=t.match(/Journal: (jrn-\d{8}-\d{6}-[0-9a-f]{4})/);assert.ok(m,t);created.add(m[1]);return m[1];}
async function state(x,y,z){const p=at(x,y,z);const r=await plugin.request("read_region",{from:p,to:p});return r.palette[0];}
// Untracked "hand" edits: journal:false writes through the executor, which keeps the chunks loaded (a console setblock can hit an unloaded chunk).
const cmd=(x,y,z,b)=>tool("mc_build",{blocks:[{pos:at(x,y,z),block:b}],journal:false,connect:false});
plugin.start();
try{
    await client.connect(transport);const health=await plugin.request("health",{});assert.equal(health.onlinePlayers,0);assert.equal(health.queuedOperations,0);
    const base=await plugin.request("read_region",{from,to});assert.ok(base.palette.every(s=>s==="minecraft:air"),"Region must be pristine air");
    snapshot=(await plugin.request("snapshot",{from,to,label:"journal acceptance pristine region"})).id;
    // Build A, overlapping build B, then three untracked hand edits inside A only.
    const a=journalId(await tool("mc_build",{fills:[{from:at(0,0,0),to:at(4,2,4),block:"minecraft:stone"}],label:"journal e2e A"}));
    const b=journalId(await tool("mc_build",{fills:[{from:at(3,0,0),to:at(6,2,4),block:"minecraft:gold_block"}],label:"journal e2e B"}));
    const untracked=await tool("mc_build",{blocks:[{pos:at(20,0,0),block:"minecraft:stone"}],journal:false});check(()=>assert.doesNotMatch(untracked,/Journal:/));
    for(const [x,y,z] of [[0,0,0],[1,1,1],[2,2,2]])await cmd(x,y,z,"minecraft:dirt");
    // Dry run first: same counts, no writes.
    const beforeDry=await plugin.request("read_region",{from,to});
    let t=await tool("mc_restore",{journal:a,dryRun:true});check(()=>assert.match(t,/42\/75 cells would be restored/));
    const afterDry=await plugin.request("read_region",{from,to});check(()=>assert.deepEqual(afterDry,beforeDry));    check(()=>assert.doesNotMatch(t,/Journal:/));
    t=await tool("mc_restore",{journal:a});
    check(()=>assert.match(t,/42\/75 cells restored/));check(()=>assert.match(t,/Kept 33 cells: 33 changed since/));
    const undoA=journalId(t);
    const after=[await state(4,1,1),await state(3,0,0),await state(1,1,1),await state(0,1,0)];
    check(()=>assert.deepEqual(after,["minecraft:gold_block","minecraft:gold_block","minecraft:dirt","minecraft:air"]));
    // Undo the undo: A's 42 restored cells come back.
    t=await tool("mc_restore",{journal:undoA});check(()=>assert.match(t,/42\/42 cells restored/));journalId(t);
    const redo=[await state(0,1,0),await state(1,1,1),await state(4,1,1)];
    check(()=>assert.deepEqual(redo,["minecraft:stone","minecraft:dirt","minecraft:gold_block"]));
    // Dry-run undo of B: all 60 of its cells are still as B wrote them.
    t=await tool("mc_restore",{journal:b,dryRun:true});check(()=>assert.match(t,/60\/60 cells would be restored/));
    // Neighbour shape changes from the connection pass are journalled and undone.
    await cmd(10,0,0,"minecraft:oak_fence");
    t=await tool("mc_build",{blocks:[{pos:at(11,0,0),block:"minecraft:oak_fence"}],label:"journal e2e fence"});const fence=journalId(t);
    check(()=>assert.match(t,/\(2 cells\)/));
    const connected=await state(10,0,0);check(()=>assert.match(connected,/east=true/));
    t=await tool("mc_restore",{journal:fence});check(()=>assert.match(t,/2\/2 cells restored/));journalId(t);
    const fenceBack=[await state(10,0,0),await state(11,0,0)];
    check(()=>assert.match(fenceBack[0],/east=false/));check(()=>assert.equal(fenceBack[1],"minecraft:air"));
    // journal-list: exact touches filter, label filter, delete.
    const list=await tool("mc_snapshot",{action:"journal-list",touches:{from:at(11,0,0),to:at(11,0,0)}});
    check(()=>assert.match(list,new RegExp(fence)));check(()=>assert.doesNotMatch(list,new RegExp(a)));
    const byLabel=await tool("mc_snapshot",{action:"journal-list",label:"journal e2e"});check(()=>assert.match(byLabel,new RegExp(a)));check(()=>assert.match(byLabel,/undone by/));
    await tool("mc_snapshot",{action:"journal-delete",id:fence});created.delete(fence);
    const gone=await tool("mc_snapshot",{action:"journal-list",label:"journal e2e fence"});check(()=>assert.match(gone,/No journal entries match/));
    const rejected=await client.callTool({name:"mc_restore",arguments:{journal:fence}});check(()=>assert.equal(rejected.isError,true));
    console.log(`journal acceptance passed: ${checks} checks`);
}finally{
    if(snapshot){const t=await tool("mc_restore",{id:snapshot});const m=t.match(/Journal: (jrn-[0-9a-f-]+)/);if(m)created.add(m[1]);}
    for(const id of created)await client.callTool({name:"mc_snapshot",arguments:{action:"journal-delete",id}});
    await client.close();plugin.close();
}
