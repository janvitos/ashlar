#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in disposable-server acceptance for mc_walk; restores a pristine test region, never manages server lifecycle.
import assert from "node:assert/strict";
import {fileURLToPath} from "node:url";
import {Client} from "@modelcontextprotocol/client";
import {StdioClientTransport,getDefaultEnvironment} from "@modelcontextprotocol/client/stdio";
import {PluginClient} from "../dist/plugin-client.js";
assert.equal(process.env.ASHLAR_DISPOSABLE_TEST,"1");
const url=new URL(process.env.MC_PLUGIN_URL);assert.ok(["127.0.0.1","localhost","[::1]"].includes(url.hostname));assert.ok(process.env.MC_PLUGIN_TOKEN);
const plugin=new PluginClient({url:url.href,token:process.env.MC_PLUGIN_TOKEN,defaultTimeoutMs:120000});
const client=new Client({name:"walk-acceptance",version:"1.0.0"});
const transport=new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL("../dist/cli.js",import.meta.url)),"--stdio"],env:{...getDefaultEnvironment(),MC_PLUGIN_URL:url.href,MC_PLUGIN_TOKEN:process.env.MC_PLUGIN_TOKEN}});
const origin=[576,100,0],from=[536,99,-40],to=[616,120,40];
let snapshot,restored=false,forced=false,probes=false,checks=0;const created=new Set();
const text=r=>r.content.filter(c=>c.type==="text").map(c=>c.text).join("\n");
function check(fn){checks++;fn();}
async function checkA(fn){checks++;await fn();}
async function tool(name,args){const r=await client.callTool({name,arguments:args});assert.ok(!r.isError,text(r));return r;}
async function rejected(name,args){const r=await client.callTool({name,arguments:args});assert.equal(r.isError,true,text(r));return text(r);}
async function build(args){const r=await tool("mc_build",args);const m=text(r).match(/Journal: (jrn-[0-9a-f-]+)/);if(m)created.add(m[1]);return r;}
const at=(x,y,z)=>[origin[0]+x,origin[1]+y,origin[2]+z];
const door=(open,half)=>`minecraft:oak_door[facing=east,half=${half},hinge=left,open=${open},powered=false]`;
const box={from:at(0,-1,-8),to:at(16,3,8)};
plugin.start();
try{
    await client.connect(transport);const health=await plugin.request("health",{});assert.equal(health.onlinePlayers,0);assert.equal(health.queuedOperations,0);
    const base=await plugin.request("read_region",{from,to});assert.ok(base.palette.every(s=>s==="minecraft:air"),"Region must be pristine air");
    snapshot=(await plugin.request("snapshot",{from,to,label:"walk acceptance pristine region"})).id;
    const catalog=await client.listTools();check(()=>assert.equal(catalog.tools.length,18));
    check(()=>assert.equal(catalog.tools.find(t=>t.name==="mc_walk").annotations.readOnlyHint,true));
    await tool("mc_command",{command:`forceload add ${from[0]} ${from[2]} ${to[0]} ${to[2]}`});forced=true;
    // Floor; a wall at x=6 with a closed door at z=0; a one-high ledge at x>=10 reached only by a stair facing east at (9,0,3).
    await build({fills:[{from:at(0,-1,-8),to:at(16,-1,8),block:"minecraft:stone"},{from:at(6,0,-8),to:at(6,2,8),block:"minecraft:stone_bricks"},
        {from:at(10,0,-8),to:at(16,0,8),block:"minecraft:stone"}],
        blocks:[{pos:at(6,0,0),block:door(false,"lower")},{pos:at(6,1,0),block:door(false,"upper")},
            {pos:at(9,0,3),block:"minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"}],label:"walk e2e room"});
    const start=at(2,0,0),onLedge=at(12,1,0);
    let t=text(await tool("mc_walk",{from:start,to:onLedge,box}));
    check(()=>assert.match(t,/NOT reachable/));check(()=>assert.match(t,new RegExp(`closed door at ${at(6,0,0).join(",")}`)));
    t=text(await tool("mc_walk",{from:start,to:onLedge,box,rules:{doors:"any"}}));
    check(()=>assert.match(t,/reachable in \d+ moves/));check(()=>assert.match(t,new RegExp(`${origin[0]+9},${origin[1]+1},${origin[2]+3}`)),"path climbs the stair");
    t=text(await tool("mc_walk",{from:start,box,targets:"doors"}));
    check(()=>assert.match(t,/Doors: 1 found, 0 reachable, 1 not:/));check(()=>assert.match(t,/east side unreachable/));
    t=text(await tool("mc_walk",{from:start,box,targets:"stairs",rules:{doors:"any"}}));check(()=>assert.match(t,/Stairs: 1 found, 1 reachable, 0 not/));
    // Without the stair the ledge is a 1.0 rise: too high unless jumping.
    t=text(await tool("mc_walk",{from:at(8,0,0),to:onLedge,box}));check(()=>assert.match(t,/reachable in/),"stair route");
    t=text(await tool("mc_walk",{from:at(8,0,-6),to:at(12,1,-6),box:{from:at(7,-1,-8),to:at(16,3,-4)}}));
    check(()=>assert.match(t,/step too high \(rise 1\.00\)/));
    t=text(await tool("mc_walk",{from:at(8,0,-6),to:at(12,1,-6),box:{from:at(7,-1,-8),to:at(16,3,-4)},rules:{jump:true}}));check(()=>assert.match(t,/reachable in/));
    // Opening the door in the world opens the route under the default rules.
    await build({blocks:[{pos:at(6,0,0),block:door(true,"lower")},{pos:at(6,1,0),block:door(true,"upper")}],label:"walk e2e open door"});
    t=text(await tool("mc_walk",{from:start,to:onLedge,box}));check(()=>assert.match(t,/reachable in \d+ moves/));
    await checkA(async()=>assert.match(await rejected("mc_walk",{from:start,box:{from:[450,-64,-100],to:[700,300,100]}}),/walk limit/));
    await checkA(async()=>assert.match(await rejected("mc_walk",{from:at(2,2,0),box}),/not a standing place/));
    // Engine collision heights: armor stands dropped onto each block settle on its collision top.
    const probe=[["snow",at(0,0,7),"minecraft:snow[layers=3]",.25,.5],["carpet",at(2,0,7),"minecraft:red_carpet",1/16,.5],
        ["slab",at(4,0,7),"minecraft:oak_slab[type=bottom,waterlogged=false]",.5,.5],
        ["stairlow",at(0,0,5),"minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",.5,.2],
        ["door",at(2,0,5),door(true,"lower"),0,.5]];
    await build({blocks:[...probe.map(([,p,b])=>({pos:p,block:b})),{pos:[origin[0]+2,origin[1]+1,origin[2]+5],block:door(true,"upper")}],label:"walk e2e probes"});
    probes=true;
    for(const [name,p,,,dx] of probe)await tool("mc_command",{command:`summon minecraft:armor_stand ${p[0]+dx} ${p[1]+2.5} ${p[2]+.5} {Tags:["ashlarwalk","ashlarwalk_${name}"],Invisible:1b}`});
    await new Promise(r=>setTimeout(r,4000));
    const heights={};
    for(const [name,p,,expect] of probe){
        const out=text(await tool("mc_command",{command:`data get entity @e[tag=ashlarwalk_${name},limit=1] Pos[1]`}));
        const y=Number(out.match(/(-?\d+(?:\.\d+)?)d/)[1]);heights[name]=+(y-p[1]).toFixed(4);
        check(()=>assert.ok(Math.abs(y-(p[1]+expect))<1e-3,`${name}: settled at ${y}, expected ${p[1]+expect}`));
    }
    console.log(`walk acceptance passed: ${checks} checks; collision tops ${JSON.stringify(heights)}`);
}finally{
    if(probes)await client.callTool({name:"mc_command",arguments:{command:"kill @e[tag=ashlarwalk]"}});
    if(snapshot&&!restored){const r=await client.callTool({name:"mc_restore",arguments:{id:snapshot}});const m=text(r).match(/Journal: (jrn-[0-9a-f-]+)/);if(m)created.add(m[1]);restored=!r.isError;}
    if(forced)await client.callTool({name:"mc_command",arguments:{command:`forceload remove ${from[0]} ${from[2]} ${to[0]} ${to[2]}`}});
    for(const id of created)await client.callTool({name:"mc_snapshot",arguments:{action:"journal-delete",id}});
    await client.close();plugin.close();
}
