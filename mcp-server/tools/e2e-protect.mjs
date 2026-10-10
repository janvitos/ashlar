#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in disposable-server acceptance for protected regions; restores a pristine test region, never manages server lifecycle.
import assert from "node:assert/strict";
import {fileURLToPath} from "node:url";
import {Client} from "@modelcontextprotocol/client";
import {StdioClientTransport,getDefaultEnvironment} from "@modelcontextprotocol/client/stdio";
import {PluginClient} from "../dist/plugin-client.js";
assert.equal(process.env.ASHLAR_DISPOSABLE_TEST,"1");
const url=new URL(process.env.MC_PLUGIN_URL);assert.ok(["127.0.0.1","localhost","[::1]"].includes(url.hostname));assert.ok(process.env.MC_PLUGIN_TOKEN);
const plugin=new PluginClient({url:url.href,token:process.env.MC_PLUGIN_TOKEN,defaultTimeoutMs:120000});
const client=new Client({name:"protect-acceptance",version:"1.0.0"});
const transport=new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL("../dist/cli.js",import.meta.url)),"--stdio"],env:{...getDefaultEnvironment(),MC_PLUGIN_URL:url.href,MC_PLUGIN_TOKEN:process.env.MC_PLUGIN_TOKEN}});
const origin=[576,100,0],from=[536,99,-40],to=[616,120,40];
let snapshot,restored=false,checks=0;const created=new Set(),regions=new Set();
const text=r=>r.content.filter(c=>c.type==="text").map(c=>c.text).join("\n");
function check(fn){checks++;fn();}
async function checkA(fn){checks++;await fn();}
async function tool(name,args){const r=await client.callTool({name,arguments:args});assert.ok(!r.isError,text(r));return text(r);}
async function rejected(name,args){const r=await client.callTool({name,arguments:args});assert.equal(r.isError,true,text(r));return text(r);}
const at=(x,y,z)=>[origin[0]+x,origin[1]+y,origin[2]+z];
function journalId(t){const m=t.match(/Journal: (jrn-\d{8}-\d{6}-[0-9a-f]{4})/);assert.ok(m,t);created.add(m[1]);return m[1];}
async function state(x,y,z){const p=at(x,y,z);const r=await plugin.request("read_region",{from:p,to:p});return r.palette[0];}
const snapshots=async()=>(await plugin.request("list_snapshots",{})).snapshots.length;
plugin.start();
try{
    await client.connect(transport);const health=await plugin.request("health",{});assert.equal(health.onlinePlayers,0);assert.equal(health.queuedOperations,0);
    const base=await plugin.request("read_region",{from,to});assert.ok(base.palette.every(s=>s==="minecraft:air"),"Region must be pristine air");
    snapshot=(await plugin.request("snapshot",{from,to,label:"protect acceptance pristine region"})).id;
    const catalog=await client.listTools();check(()=>assert.equal(catalog.tools.length,16));
    check(()=>assert.equal(catalog.tools.find(t=>t.name==="mc_protect").annotations.destructiveHint,false));
    // A deny region (3x3x3 core) and a warn region.
    let t=await tool("mc_protect",{action:"add",name:"e2e-core",from:at(2,0,2),to:at(4,2,4),note:"e2e approved module"});regions.add("e2e-core");
    check(()=>assert.match(t,/'e2e-core' \(deny\) added/));
    t=await tool("mc_protect",{action:"add",name:"e2e-warn",from:at(10,0,0),to:at(12,2,2),mode:"warn"});regions.add("e2e-warn");
    t=await tool("mc_protect",{action:"list",touches:{from:at(3,1,3),to:at(3,1,3)}});
    check(()=>assert.match(t,/1 protected region:\ne2e-core  deny/));
    await checkA(async()=>assert.match(await rejected("mc_protect",{action:"add",name:"e2e-core",from:at(0,0,0),to:at(1,1,1)}),/already exists/));
    // A fill crossing the core is rejected before the snapshot and before any write.
    const snapsBefore=await snapshots();
    t=await rejected("mc_build",{fills:[{from:at(0,0,0),to:at(6,0,6),block:"minecraft:stone"}],snapshot:true});
    check(()=>assert.match(t,/'e2e-core' \(deny\) covers 9 target cells of this call in \[578,100,2\]\.\.\[580,100,4\]/));
    check(()=>assert.match(t,/Note: e2e approved module/));check(()=>assert.match(t,/override:\["e2e-core"\]/));
    await checkA(async()=>assert.equal(snapsBefore,await snapshots()));
    const untouched=await plugin.request("read_region",{from,to});check(()=>assert.ok(untouched.palette.every(s=>s==="minecraft:air")));
    // dryRun and mc_plan report the overlap without rejecting.
    t=await tool("mc_build",{fills:[{from:at(0,0,0),to:at(6,0,6),block:"minecraft:stone"}],dryRun:true});
    let report=JSON.parse(t);check(()=>assert.equal(report.protectedPass,false));check(()=>assert.equal(report.protected[0].name,"e2e-core"));check(()=>assert.equal(report.protected[0].cells,9));
    t=await tool("mc_plan",{build:{fills:[{from:at(0,0,0),to:at(6,0,6),block:"minecraft:stone"}],override:["e2e-core"]},image:false});
    report=JSON.parse(t);check(()=>assert.equal(report.protectedPass,true));check(()=>assert.equal(report.protected[0].status,"overridden"));
    // Walls around the core do not touch it; outline/walls only target their shell.
    t=await tool("mc_build",{fills:[{from:at(1,1,1),to:at(5,1,5),block:"minecraft:oak_planks",mode:"walls"}],label:"protect e2e walls"});journalId(t);
    check(()=>assert.doesNotMatch(t,/Protection/));
    // Unknown override names and wildcards are rejected.
    await checkA(async()=>assert.match(await rejected("mc_build",{blocks:[{pos:at(3,0,3),block:"minecraft:stone"}],override:["e2e-nope"]}),/no protected region named 'e2e-nope'/));
    await rejected("mc_build",{blocks:[{pos:at(3,0,3),block:"minecraft:stone"}],override:["*"]});
    // Override writes and says so; a warn region writes with a warning.
    t=await tool("mc_build",{fills:[{from:at(0,0,0),to:at(6,0,6),block:"minecraft:stone"}],override:["e2e-core"],label:"protect e2e override"});
    const over=journalId(t);check(()=>assert.match(t,/Protection override: region 'e2e-core' \(deny\) - 9 target cells/));
    await checkA(async()=>assert.equal(await state(3,0,3),"minecraft:stone"));
    t=await tool("mc_build",{blocks:[{pos:at(11,0,1),block:"minecraft:stone"}],label:"protect e2e warn"});journalId(t);
    check(()=>assert.match(t,/Protection warning: region 'e2e-warn' \(warn\) covers 1 target cells/));await checkA(async()=>assert.equal(await state(11,0,1),"minecraft:stone"));
    // Journal undo of cells inside the core: dry run reports, a real undo needs the override.
    t=await tool("mc_restore",{journal:over,dryRun:true});check(()=>assert.match(t,/the real call would be rejected without override:\["e2e-core"\]/));
    t=await rejected("mc_restore",{journal:over});check(()=>assert.match(t,/'e2e-core' \(deny\) covers 9 target cells/));
    await checkA(async()=>assert.equal(await state(3,0,3),"minecraft:stone"));
    t=await tool("mc_restore",{journal:over,override:["e2e-core"]});journalId(t);check(()=>assert.match(t,/49\/49 cells restored/));
    await checkA(async()=>assert.equal(await state(3,0,3),"minecraft:air"));
    // A snapshot restore covers the whole snapshot box, core included.
    t=await rejected("mc_restore",{id:snapshot});check(()=>assert.match(t,/'e2e-core' \(deny\) covers 27 target cells/));
    t=await tool("mc_restore",{id:snapshot,override:["e2e-core"]});restored=true;
    const m=t.match(/Journal: (jrn-[0-9a-f-]+)/);if(m)created.add(m[1]);
    check(()=>assert.match(t,/Protection warning: region 'e2e-warn'/));check(()=>assert.match(t,/Protection override: region 'e2e-core'/));
    // Removal, then nothing is protected.
    for(const name of [...regions]){await tool("mc_protect",{action:"remove",name});regions.delete(name);}
    await checkA(async()=>assert.match(await tool("mc_protect",{action:"list"}),/No protected regions/));
    console.log(`protect acceptance passed: ${checks} checks`);
}finally{
    for(const name of regions)await client.callTool({name:"mc_protect",arguments:{action:"remove",name}});
    if(snapshot&&!restored){const t=await tool("mc_restore",{id:snapshot});const m=t.match(/Journal: (jrn-[0-9a-f-]+)/);if(m)created.add(m[1]);}
    for(const id of created)await client.callTool({name:"mc_snapshot",arguments:{action:"journal-delete",id}});
    await client.close();plugin.close();
}
