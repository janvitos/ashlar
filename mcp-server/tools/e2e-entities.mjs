#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in disposable-server acceptance for mc_build entities, entity/sign snapshots and journal undo; restores a pristine test region, never manages server lifecycle.
import assert from "node:assert/strict";
import {fileURLToPath} from "node:url";
import {Client} from "@modelcontextprotocol/client";
import {StdioClientTransport,getDefaultEnvironment} from "@modelcontextprotocol/client/stdio";
import {PluginClient} from "../dist/plugin-client.js";
assert.equal(process.env.ASHLAR_DISPOSABLE_TEST,"1");
const url=new URL(process.env.MC_PLUGIN_URL);assert.ok(["127.0.0.1","localhost","[::1]"].includes(url.hostname));assert.ok(process.env.MC_PLUGIN_TOKEN);
const plugin=new PluginClient({url:url.href,token:process.env.MC_PLUGIN_TOKEN,defaultTimeoutMs:300000});
const client=new Client({name:"entities-acceptance",version:"1.0.0"});
const transport=new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL("../dist/cli.js",import.meta.url)),"--stdio"],env:{...getDefaultEnvironment(),MC_PLUGIN_URL:url.href,MC_PLUGIN_TOKEN:process.env.MC_PLUGIN_TOKEN}});
const from=[536,99,-40],to=[616,120,40];
const area=`x=${from[0]},y=${from[1]},z=${from[2]},dx=${to[0]-from[0]},dy=${to[1]-from[1]},dz=${to[2]-from[2]}`;
let snapshot,restored=false,forced=false,checks=0;const created=new Set();
const text=r=>r.content.filter(c=>c.type==="text").map(c=>c.text).join("\n");
function check(fn){checks++;fn();}
async function checkA(fn){checks++;await fn();}
async function tool(name,args){const r=await client.callTool({name,arguments:args},undefined,{timeout:300000});assert.ok(!r.isError,text(r));return r;}
async function rejected(name,args){const r=await client.callTool({name,arguments:args});assert.equal(r.isError,true,text(r));journal(text(r));return text(r);}
function journal(t){for(const m of t.matchAll(/(jrn-[0-9]{8}-[0-9]{6}-[0-9a-f]{4})/g))created.add(m[1]);return t;}
async function build(args){return journal(text(await tool("mc_build",args)));}
async function command(c){return text(await tool("mc_command",{command:c}));}
// The wall the pictures hang on (z=0, facing south into z>=1) and a floor (y=99).
const look={from:[558,99,-1],to:[592,108,8]};
async function inspect(){return text(await tool("mc_inspect",{...look,entities:true}));}
function entityLines(t){const i=t.indexOf("Entities (");return i<0?[]:t.slice(i).split("\n").slice(1).map(s=>s.trim()).filter(Boolean);}
const decor=[
    {type:"painting",pos:[561,101,1],facing:"south",variant:"kebab"},
    {type:"painting",pos:[563,101,1],facing:"south",variant:"minecraft:pool"},
    {type:"painting",pos:[566,101,1],facing:"south",variant:"skeleton"},
    {type:"item_frame",pos:[571,101,1],facing:"south",item:"clock",rotation:2},
    {type:"glow_item_frame",pos:[561,100,4],facing:"up",item:"minecraft:diamond"},
    {type:"armor_stand",pos:[573,100,4],yaw:45,equipment:{head:"iron_helmet",mainHand:"torch"},pose:{rightArm:[-90,0,0]}},
];
plugin.start();
try{
    await client.connect(transport);const health=await plugin.request("health",{});assert.equal(health.onlinePlayers,0);assert.equal(health.queuedOperations,0);
    const base=await plugin.request("read_region",{from,to});assert.ok(base.palette.every(s=>s==="minecraft:air"),"Region must be pristine air");
    snapshot=(await plugin.request("snapshot",{from,to,label:"entities acceptance pristine region"})).id;
    const catalog=await client.listTools();
    check(()=>assert.ok(catalog.tools.find(t=>t.name==="mc_build").inputSchema.properties.entities));
    check(()=>assert.ok(catalog.tools.find(t=>t.name==="mc_inspect").inputSchema.properties.entities));
    await command(`forceload add ${from[0]} ${from[2]} ${to[0]} ${to[2]}`);forced=true;
    let t=await build({fills:[{from:[558,100,0],to:[592,107,0],block:"stone"},{from:[558,99,1],to:[592,99,8],block:"stone"}],label:"entities e2e wall"});
    check(()=>assert.match(t,/Journal: jrn-/));
    // Errors before anything is written.
    await checkA(async()=>assert.match(await rejected("mc_build",{entities:[{type:"painting",pos:[561,101,1],facing:"south",variant:"no_such_art"}]}),/unknown painting variant/));
    await checkA(async()=>assert.match(await rejected("mc_build",{entities:[{type:"item_frame",pos:[561,101,1],facing:"south",item:"no_such_item"}]}),/unknown item/));
    await checkA(async()=>assert.match(await rejected("mc_build",{cells:[[561,110,1,"stone"]],entities:decor}),/cannot be combined with cells/));
    t=text(await tool("mc_build",{entities:decor,dryRun:true}));
    check(()=>assert.equal(JSON.parse(t).entities.count,6));
    // Place one of each kind; entities run after the call's blocks.
    t=await build({entities:decor,label:"entities e2e decor"});
    check(()=>assert.match(t,/Entities: placed 6\/6\./));
    const decorJournal=t.match(/Journal: (jrn-[0-9a-f-]+)/)[1];
    check(()=>assert.match(t,/6 sign\/entity records/));
    t=await inspect();const placed=entityLines(t);
    check(()=>assert.equal(placed.length,6,t));
    check(()=>assert.ok(placed.includes("painting at 566,101,1 facing south minecraft:skeleton [Ashlar]"),t));
    check(()=>assert.ok(placed.includes("painting at 563,101,1 facing south minecraft:pool [Ashlar]"),t));
    check(()=>assert.ok(placed.includes("item_frame at 571,101,1 facing south holding minecraft:clock rotation 2 fixed [Ashlar]"),t));
    check(()=>assert.ok(placed.includes("glow_item_frame at 561,100,4 facing up holding minecraft:diamond fixed [Ashlar]"),t));
    check(()=>assert.ok(placed.some(l=>/^armor_stand at 573,100,4 yaw 45 equipment .*iron_helmet.* posed rightArm \[Ashlar\]$/.test(l)),t));
    // Vanilla anchors: a 4x3 picture hangs on its second column, middle row; a 2x1 on its left cell.
    t=await command("data get entity @e[type=minecraft:painting,limit=1,sort=nearest,x=567.5,y=102.5,z=1.0] block_pos");
    check(()=>assert.match(t,/567, 102, 1/,t));
    t=await command("data get entity @e[type=minecraft:painting,limit=1,sort=nearest,x=564.0,y=101.5,z=1.0] block_pos");
    check(()=>assert.match(t,/563, 101, 1/,t));
    // The same call again changes nothing.
    t=await build({entities:decor,label:"entities e2e again"});
    check(()=>assert.match(t,/Entities: placed 0\/6, 6 already there unchanged\./));
    // A painting without a wall is reported; the rest of the call still runs.
    t=await build({entities:[{type:"painting",pos:[600,110,0],facing:"south",variant:"kebab"},{type:"armor_stand",pos:[590,100,7]}],label:"entities e2e partial"});
    check(()=>assert.match(t,/Entities: placed 1\/2, 1 not placed\./));
    const partialJournal=t.match(/Journal: (jrn-[0-9a-f-]+)/)[1];
    t=journal(text(await tool("mc_restore",{journal:partialJournal})));
    check(()=>assert.match(t,/1 placed entities removed/));
    // Journal undo removes what the call placed; undoing the undo brings it back.
    t=journal(text(await tool("mc_restore",{journal:decorJournal,dryRun:true})));
    check(()=>assert.match(t,/6 placed entities would be removed/));
    t=journal(text(await tool("mc_restore",{journal:decorJournal})));
    check(()=>assert.match(t,/6 placed entities removed/));
    const undoJournal=t.match(/Journal: (jrn-[0-9a-f-]+)/)[1];
    t=await inspect();check(()=>assert.equal(entityLines(t).length,0,t));
    t=journal(text(await tool("mc_restore",{journal:undoJournal})));
    check(()=>assert.match(t,/6 removed entities respawned/));
    t=await inspect();check(()=>assert.deepEqual(entityLines(t),placed));
    // Sign text: a text-only rewrite is journalled and undone.
    t=await build({blocks:[{pos:[590,103,1],block:"oak_wall_sign[facing=south]",sign:{front:["hello"]}}],label:"entities e2e sign"});
    t=await build({blocks:[{pos:[590,103,1],block:"oak_wall_sign[facing=south]",sign:{front:["world"]}}],label:"entities e2e sign text"});
    check(()=>assert.match(t,/1 sign\/entity records/));
    const signJournal=t.match(/Journal: (jrn-[0-9a-f-]+)/)[1];
    t=journal(text(await tool("mc_restore",{journal:signJournal})));
    check(()=>assert.match(t,/1 sign texts restored/));
    t=await inspect();check(()=>assert.match(t,/hello/));check(()=>assert.doesNotMatch(t,/world/));
    // Snapshots keep sign text and entities.
    t=text(await tool("mc_snapshot",{from:look.from,to:look.to,label:"entities e2e decor"}));
    const decorSnap=t.match(/(snap-[0-9a-z-]+|[0-9]{8}-[0-9]{6}-[0-9a-f]+)/)[1];
    await command(`kill @e[type=minecraft:painting,${area}]`);
    await command(`kill @e[type=minecraft:item_frame,${area}]`);
    await command(`kill @e[type=minecraft:glow_item_frame,${area}]`);
    await command(`kill @e[type=minecraft:armor_stand,${area}]`);
    await command(`kill @e[type=minecraft:item,${area}]`);
    await build({blocks:[{pos:[590,103,1],block:"oak_wall_sign[facing=south]",sign:{front:["changed"]}}],label:"entities e2e sign again"});
    t=await inspect();check(()=>assert.equal(entityLines(t).length,0,t));
    t=journal(text(await tool("mc_restore",{id:decorSnap})));
    check(()=>assert.match(t,/Signs and entities: 1 sign texts restored, 6 entities respawned/));
    t=await inspect();check(()=>assert.deepEqual(entityLines(t),placed));check(()=>assert.match(t,/hello/));
    // replace-in-box removes Ashlar-placed stands only; a summoned one is kept.
    await command("summon minecraft:armor_stand 575.5 100 6.5");
    t=await build({entities:[{type:"armor_stand",pos:[570,100,6],yaw:180}],entityMode:"replace-in-box",entityBox:{from:[558,100,1],to:[592,100,8]},label:"entities e2e replace"});
    check(()=>assert.match(t,/Entities: placed 1\/1, 1 Ashlar-placed removed from entityBox\./));
    t=await inspect();const stands=entityLines(t).filter(l=>l.startsWith("armor_stand"));
    check(()=>assert.deepEqual(stands,["armor_stand at 570,100,6 yaw -180 [Ashlar]","armor_stand at 575,100,6 yaw 0"],t));
    // A mirrored transform keeps a 2x1 picture on the same wall cells: pos names its lower-right cell.
    t=await build({transform:{origin:[580,101,1],mirror:"x"},entities:[{type:"painting",pos:[3,0,0],facing:"south",variant:"sea"}],label:"entities e2e mirror"});
    check(()=>assert.match(t,/Entities: placed 1\/1\./));
    t=await inspect();check(()=>assert.ok(entityLines(t).includes("painting at 576,101,1 facing south minecraft:sea [Ashlar]"),t));
    // Entities survive a chunk unload: drop the force-load, wait for the chunks to unload, then read them again.
    const before=entityLines(await inspect());
    await command(`forceload remove ${from[0]} ${from[2]} ${to[0]} ${to[2]}`);forced=false;
    let unloaded=false;
    for(let i=0;i<30&&!unloaded;i++){await new Promise(r=>setTimeout(r,1000));unloaded=/Test failed/.test(await command("execute if loaded 566 101 1"));}
    check(()=>assert.ok(unloaded,"chunk did not unload"));
    await command(`forceload add ${from[0]} ${from[2]} ${to[0]} ${to[2]}`);forced=true;
    t=await inspect();check(()=>assert.deepEqual(entityLines(t),before,t));check(()=>assert.equal(before.length,8));
    console.log(`entities acceptance passed: ${checks} checks`);
}finally{
    for(const type of ["painting","item_frame","glow_item_frame","armor_stand","item"])await client.callTool({name:"mc_command",arguments:{command:`kill @e[type=minecraft:${type},${area}]`}});
    if(snapshot&&!restored){const r=await client.callTool({name:"mc_restore",arguments:{id:snapshot}},undefined,{timeout:300000});journal(text(r));restored=!r.isError;}
    if(forced)await client.callTool({name:"mc_command",arguments:{command:`forceload remove ${from[0]} ${from[2]} ${to[0]} ${to[2]}`}});
    for(const id of created)await client.callTool({name:"mc_snapshot",arguments:{action:"journal-delete",id}});
    await client.close();plugin.close();
}
