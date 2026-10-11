#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in disposable-server acceptance for bulk cells, staging and mc_export; restores a pristine test region, never manages server lifecycle.
import assert from "node:assert/strict";
import {fileURLToPath} from "node:url";
import {Client} from "@modelcontextprotocol/client";
import {StdioClientTransport,getDefaultEnvironment} from "@modelcontextprotocol/client/stdio";
import {PluginClient} from "../dist/plugin-client.js";
assert.equal(process.env.ASHLAR_DISPOSABLE_TEST,"1");
const url=new URL(process.env.MC_PLUGIN_URL);assert.ok(["127.0.0.1","localhost","[::1]"].includes(url.hostname));assert.ok(process.env.MC_PLUGIN_TOKEN);
const plugin=new PluginClient({url:url.href,token:process.env.MC_PLUGIN_TOKEN,defaultTimeoutMs:300000});
const client=new Client({name:"bulk-acceptance",version:"1.0.0"});
const transport=new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL("../dist/cli.js",import.meta.url)),"--stdio"],env:{...getDefaultEnvironment(),MC_PLUGIN_URL:url.href,MC_PLUGIN_TOKEN:process.env.MC_PLUGIN_TOKEN}});
const from=[536,99,-40],to=[616,120,40];
let snapshot,restored=false,forced=false,checks=0;const created=new Set();
const text=r=>r.content.filter(c=>c.type==="text").map(c=>c.text).join("\n");
function check(fn){checks++;fn();}
async function checkA(fn){checks++;await fn();}
async function tool(name,args){const r=await client.callTool({name,arguments:args},undefined,{timeout:300000});assert.ok(!r.isError,text(r));return r;}
async function rejected(name,args){const r=await client.callTool({name,arguments:args});assert.equal(r.isError,true,text(r));return text(r);}
function journal(t){const m=t.match(/Journal: (jrn-[0-9a-f-]+)/);if(m)created.add(m[1]);return t;}
async function build(args){return journal(text(await tool("mc_build",args)));}
// Rolling terrain over the whole region: stone core, coarse dirt (grass never spreads onto it), grass, snow layers on the high ground, air pockets above the low ground.
const cells=[];
for(let dx=0;dx<=80;dx++)for(let dz=0;dz<=80;dz++){
    const x=from[0]+dx,z=from[2]+dz,h=100+Math.round(5+3*Math.sin(dx/9)+3*Math.cos(dz/11));
    for(let y=from[1];y<=h;y++)cells.push([x,y,z,y===h?"minecraft:grass_block[snowy=false]":y>h-3?"minecraft:coarse_dirt":"minecraft:stone"]);
    if(h>=109)cells.push([x,h+1,z,"minecraft:snow[layers=1]"]);else if(dx%10===0)cells.push([x,h+1,z,"minecraft:air"]);
}
// A fresh name per run: the tools have no delete action, so the operator removes plugins/Ashlar/{staging,exports}/e2ebulk* afterwards.
const run=`e2ebulk-${Date.now().toString(36)}`,rle=`${run}-rle`,sparse=`${run}-cells`;
const top=Math.max(...cells.map(c=>c[1])),terrain={from,to:[to[0],top,to[2]]};
plugin.start();
try{
    await client.connect(transport);const health=await plugin.request("health",{});assert.equal(health.onlinePlayers,0);assert.equal(health.queuedOperations,0);
    const base=await plugin.request("read_region",{from,to});assert.ok(base.palette.every(s=>s==="minecraft:air"),"Region must be pristine air");
    snapshot=(await plugin.request("snapshot",{from,to,label:"bulk acceptance pristine region"})).id;
    const catalog=await client.listTools();check(()=>assert.equal(catalog.tools.length,19));
    check(()=>assert.equal(catalog.tools.find(t=>t.name==="mc_export").annotations.readOnlyHint,true));
    check(()=>assert.ok(catalog.tools.find(t=>t.name==="mc_build").inputSchema.properties.cellsFile));
    await tool("mc_command",{command:`forceload add ${from[0]} ${from[2]} ${to[0]} ${to[2]}`});forced=true;
    // Staged upload in two calls, then finalize.
    const half=Math.ceil(cells.length/2);
    let t=text(await tool("mc_blueprint",{action:"stage",name:run,cells:cells.slice(0,half),reset:true}));check(()=>assert.match(t,new RegExp(`${half} cells`)));
    t=text(await tool("mc_blueprint",{action:"stage",name:run,cells:cells.slice(half)}));check(()=>assert.match(t,new RegExp(`${cells.length} cells`)));
    await checkA(async()=>assert.match(await rejected("mc_build",{cellsFile:run}),/no finished staged upload/));
    t=text(await tool("mc_blueprint",{action:"finalize",name:run}));check(()=>assert.match(t,new RegExp(`${cells.length} cells`)));
    // Dry run, then the real build.
    t=text(await tool("mc_build",{cellsFile:run,dryRun:true}));
    check(()=>assert.match(t,new RegExp(`^Cells: ${cells.length} \\(solid \\d+, air \\d+, late \\d+\\) merged into \\d+ boxes`)));check(()=>assert.match(t,/Dry run: every part passed validation/));
    const parts=Number(t.match(/in (\d+) part\(s\)/)[1]);
    let t0=Date.now();t=await build({cellsFile:run,label:"bulk e2e terrain"});const buildMs=Date.now()-t0;
    check(()=>assert.match(t,new RegExp(`Built ${parts} of ${parts} part\\(s\\)`)));check(()=>assert.match(t,/Journal: jrn-/));
    const boxes=Number(t.match(/merged into (\d+) boxes/)[1]);
    t=text(await tool("mc_diff",{...terrain,against:{expectedFile:run},scope:"declared"}));check(()=>assert.match(t,/No differences/));
    // Export both formats, then compare the world against them.
    t0=Date.now();t=text(await tool("mc_export",{...terrain,name:rle,format:"palette-rle"}));const exportMs=Date.now()-t0;
    const volume=(to[0]-from[0]+1)*(top-from[1]+1)*(to[2]-from[2]+1);
    check(()=>assert.match(t,new RegExp(`${volume} cells read, ${volume} written`)));
    t=text(await tool("mc_export",{...terrain,name:sparse,skipAir:true}));check(()=>assert.match(t,/\(air skipped\)/));
    const solid=Number(t.match(/, (\d+) written/)[1]);check(()=>assert.equal(solid,cells.filter(c=>c[3]!=="minecraft:air").length));
    await checkA(async()=>assert.match(await rejected("mc_export",{...terrain,name:rle}),/already exists/));
    await checkA(async()=>assert.match(await rejected("mc_export",{from:[0,-64,0],to:[400,300,400],name:`${run}-big`}),/max-export-volume/));
    t=text(await tool("mc_diff",{...terrain,against:{expectedFile:rle}}));check(()=>assert.match(t,/No differences/));
    // Round trip: back to air, rebuild from the export, compare again.
    t=text(await tool("mc_restore",{id:snapshot}));journal(t);
    t=text(await tool("mc_diff",{...terrain,against:{expectedFile:sparse},scope:"declared"}));check(()=>assert.doesNotMatch(t,/No differences/));
    t=await build({cellsFile:rle,label:"bulk e2e rebuild"});check(()=>assert.match(t,/Built \d+ of \d+ part\(s\)/));
    t=text(await tool("mc_diff",{...terrain,against:{expectedFile:sparse},scope:"declared"}));check(()=>assert.match(t,/No differences/));
    // Read-only round trip of a 515,200-cell box around the region (export, then diff against it).
    const wide={from:[from[0]-40,from[1],from[2]-40],to:[to[0]+40,from[1]+19,to[2]+39]};
    t0=Date.now();t=text(await tool("mc_export",{...wide,name:`${run}-wide`,format:"palette-rle"}));const wideMs=Date.now()-t0;
    check(()=>assert.match(t,/515200 cells read, 515200 written/));
    t=text(await tool("mc_diff",{...wide,against:{expectedFile:`${run}-wide`}}));check(()=>assert.match(t,/No differences/));
    // Inline cells, resume tokens and argument errors.
    const pad=[from[0]+2,top+3,from[2]+2];
    t=await build({cells:[[pad[0],pad[1],pad[2],"stone"],[pad[0]+1,pad[1],pad[2],"stone"],[pad[0],pad[1]+1,pad[2],"torch"]],label:"bulk e2e inline"});
    check(()=>assert.match(t,/^Cells: 3 \(solid 2, air 0, late 1\) merged into 2 boxes \(1, 0, 1\) in 2 part\(s\)\./));
    await checkA(async()=>assert.match(await rejected("mc_build",{cells:[[pad[0],pad[1],pad[2],"stone"]],resume:"000000000000:1"}),/different cells or options/));
    await checkA(async()=>assert.match(await rejected("mc_build",{cells:[[0,64,0,"stone"],[0,64,0,"dirt"]]}),/given twice with different states/));
    await checkA(async()=>assert.match(await rejected("mc_build",{cells:[[0,64,0,"stone"]],fills:[]}),/accepts only/));
    await checkA(async()=>assert.match(await rejected("mc_build",{cellsFile:"../etc"}),/plain name|must match pattern/));
    console.log(`bulk acceptance passed: ${checks} checks; ${cells.length} cells -> ${boxes} boxes in ${parts} part(s), build ${buildMs} ms, export ${volume} cells ${exportMs} ms, 515200 cells ${wideMs} ms`);
}finally{
    if(snapshot&&!restored){const r=await client.callTool({name:"mc_restore",arguments:{id:snapshot}},undefined,{timeout:300000});journal(text(r));restored=!r.isError;}
    if(forced)await client.callTool({name:"mc_command",arguments:{command:`forceload remove ${from[0]} ${from[2]} ${to[0]} ${to[2]}`}});
    for(const id of created)await client.callTool({name:"mc_snapshot",arguments:{action:"journal-delete",id}});
    await client.close();plugin.close();
}
