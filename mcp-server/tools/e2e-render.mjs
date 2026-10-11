#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in disposable-server acceptance for mc_render shape families, per-face colours, entity placeholders, compareTo and zoom; restores a pristine test region, never manages server lifecycle.
import assert from "node:assert/strict";
import {fileURLToPath} from "node:url";
import {Client} from "@modelcontextprotocol/client";
import {StdioClientTransport,getDefaultEnvironment} from "@modelcontextprotocol/client/stdio";
import {PluginClient} from "../dist/plugin-client.js";
assert.equal(process.env.ASHLAR_DISPOSABLE_TEST,"1");
const url=new URL(process.env.MC_PLUGIN_URL);assert.ok(["127.0.0.1","localhost","[::1]"].includes(url.hostname));assert.ok(process.env.MC_PLUGIN_TOKEN);
const plugin=new PluginClient({url:url.href,token:process.env.MC_PLUGIN_TOKEN,defaultTimeoutMs:300000});
const client=new Client({name:"render-acceptance",version:"1.0.0"});
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
import {writeFileSync} from "node:fs";
const out=process.env.RENDER_OUT;
const geometry=r=>JSON.parse(text(r)).geometry;
const save=(r,name)=>{if(out)writeFileSync(`${out}/${name}.png`,Buffer.from(r.content.find(c=>c.type==="image").data,"base64"));};
plugin.start();
try{
    await client.connect(transport);const health=await plugin.request("health",{});assert.equal(health.onlinePlayers,0);assert.equal(health.queuedOperations,0);
    const base=await plugin.request("read_region",{from,to});assert.ok(base.palette.every(s=>s==="minecraft:air"),"Region must be pristine air");
    snapshot=(await plugin.request("snapshot",{from,to,label:"render acceptance pristine region"})).id;
    const spec=(await client.listTools()).tools.find(t=>t.name==="mc_render");
    check(()=>assert.ok(spec.inputSchema.properties.compareTo&&spec.inputSchema.properties.zoom));
    await command(`forceload add ${from[0]} ${from[2]} ${to[0]} ${to[2]}`);forced=true;
    // Gallery: stone floor 560..590 x 99 x 1..8 with a grass strip at z=0 (nothing on it, so it cannot decay), a stone back wall at z=6 (y 100..103) and a stone beam at y=103 over z=2.
    const g=[];let x=560;const at=(b,dy=0,z=2)=>g.push({pos:[x,100+dy,z],block:b});
    const next=()=>{x+=2;};
    at("lantern[hanging=false]");next();at("soul_lantern[hanging=true]",2);next();at("iron_chain[axis=y]");at("iron_chain[axis=y]",1);next();
    at("iron_chain[axis=x]");next();at("glass_pane[east=true,west=true]");next();at("iron_bars[north=true,south=true]");next();
    at("end_rod[facing=up]");next();at("lightning_rod[facing=east]");next();at("white_candle[candles=4]");next();
    at("bell[attachment=floor,facing=north]");next();at("campfire[facing=north,lit=false]");next();
    at("pointed_dripstone[thickness=tip,vertical_direction=up]");at("pointed_dripstone[thickness=tip,vertical_direction=down]",2);next();
    at("flower_pot");next();at("potted_poppy");next();at("skeleton_skull[rotation=0]");
    x=560;const row2=()=>{x+=2;};
    at("oak_log[axis=x]",0,4);row2();at("basalt[axis=x]",0,4);row2();at("basalt[axis=y]",0,4);row2();at("quartz_pillar[axis=z]",0,4);row2();
    at("cobblestone_wall[up=true,east=low,west=none,north=none,south=none]",0,4);x++;at("cobblestone_wall[up=false,east=low,west=low,north=none,south=none]",0,4);x++;
    at("cobblestone_wall[up=true,west=low,east=none,north=none,south=none]",0,4);row2();
    // Wall-mounted pieces against the back wall (z=6) face north from z=5.
    at("stone_button[face=wall,facing=north,powered=false]",1,5);row2();at("lever[face=wall,facing=north,powered=false]",1,5);row2();
    at("zombie_wall_head[facing=north]",1,5);
    const fills=[{from:[560,99,1],to:[590,99,8],block:"stone"},{from:[560,99,0],to:[590,99,0],block:"grass_block"},{from:[560,100,6],to:[590,103,6],block:"stone"},{from:[562,103,1],to:[562,103,3],block:"stone"},{from:[582,103,1],to:[582,103,3],block:"stone"}];
    const entities=[{type:"painting",pos:[578,101,5],facing:"north",variant:"minecraft:pool"},{type:"item_frame",pos:[581,101,5],facing:"north",item:"minecraft:clock"}];
    let t=await build({fills,blocks:g,entities,label:"render e2e gallery"});
    check(()=>assert.match(t,/Journal: jrn-/,t));
    const box={from:[559,99,-1],to:[591,104,8]};
    let r=await tool("mc_render",{...box,view:"isometric",camera:{azimuth:20,elevation:35}});save(r,"gallery");let geo=geometry(r);
    check(()=>assert.equal(geo.cubeFallbackStates,0,JSON.stringify(geo.cubeFallbackSamples)));
    check(()=>assert.equal(geo.entityPlaceholderCells,3,JSON.stringify(geo)));
    check(()=>assert.ok(JSON.parse(text(r)).geometry&&!("diff" in geo)));
    r=await tool("mc_render",{...box,view:"perspective"});save(r,"gallery-perspective");
    check(()=>assert.equal(geometry(r).cubeFallbackStates,0));
    // Zoom: a tight close-up of the bell.
    r=await tool("mc_render",{zoom:{pos:[578,100,2],radius:2}});save(r,"zoom-bell");
    const zs=JSON.parse(text(r));
    check(()=>assert.equal(zs.view,"isometric"));
    check(()=>assert.deepEqual([zs.bounds.from,zs.bounds.to],[[576,98,0],[580,102,4]]));
    check(()=>assert.equal(zs.scale,32));
    await checkA(async()=>assert.match(await rejected("mc_render",{zoom:{pos:[578,100,2]},...box}),/either zoom or from\/to/));
    // compareTo: snapshot the gallery, change one block, remove one and add one, then diff.
    const gsnap=(await plugin.request("snapshot",{...box,label:"render e2e gallery"})).id;
    t=await build({blocks:[{pos:[562,100,4],block:"stone"},{pos:[560,100,2],block:"air"},{pos:[590,100,8],block:"gold_block"}],label:"render e2e changes"});
    r=await tool("mc_render",{...box,view:"isometric",camera:{azimuth:20,elevation:35},compareTo:gsnap});save(r,"compare");geo=geometry(r);
    check(()=>assert.deepEqual([geo.diff.changed,geo.diff.added,geo.diff.removed,geo.diff.outsideSnapshot],[1,1,1,0],JSON.stringify(geo.diff)));
    check(()=>assert.equal(geo.diff.snapshot,gsnap));
    // compareTo against the pristine snapshot marks every gallery block as added.
    r=await tool("mc_render",{...box,view:"isometric",compareTo:snapshot});geo=geometry(r);
    check(()=>assert.ok(geo.diff.added>200&&geo.diff.changed===0&&geo.diff.removed===0,JSON.stringify(geo.diff)));
    await checkA(async()=>assert.match(await rejected("mc_render",{...box,view:"north",compareTo:gsnap}),/only with view/));
    await checkA(async()=>assert.match(await rejected("mc_render",{...box,view:"isometric",compareTo:"no-such-snapshot"}),/no snapshot/));
    await checkA(async()=>assert.match(await rejected("mc_render",{from:[536,99,30],to:[540,104,40],view:"isometric",compareTo:gsnap}),/does not overlap/));
    console.log(`render acceptance passed: ${checks} checks`);
}finally{
    if(snapshot&&!restored){const r=await client.callTool({name:"mc_restore",arguments:{id:snapshot}},undefined,{timeout:300000});journal(text(r));restored=!r.isError;}
    if(forced)await client.callTool({name:"mc_command",arguments:{command:`forceload remove ${from[0]} ${from[2]} ${to[0]} ${to[2]}`}});
    for(const id of created)await client.callTool({name:"mc_snapshot",arguments:{action:"journal-delete",id}});
    await client.close();plugin.close();
}
