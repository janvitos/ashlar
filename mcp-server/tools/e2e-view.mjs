#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in disposable-server acceptance for first-person views and sightlines; restores a pristine test region, never manages server lifecycle.
import assert from "node:assert/strict";
import {writeFileSync} from "node:fs";
import {fileURLToPath} from "node:url";
import {Client} from "@modelcontextprotocol/client";
import {StdioClientTransport,getDefaultEnvironment} from "@modelcontextprotocol/client/stdio";
import {PluginClient} from "../dist/plugin-client.js";
assert.equal(process.env.ASHLAR_DISPOSABLE_TEST,"1");
const url=new URL(process.env.MC_PLUGIN_URL);assert.ok(["127.0.0.1","localhost","[::1]"].includes(url.hostname));assert.ok(process.env.MC_PLUGIN_TOKEN);
const plugin=new PluginClient({url:url.href,token:process.env.MC_PLUGIN_TOKEN,defaultTimeoutMs:120000});
const client=new Client({name:"view-acceptance",version:"1.0.0"});
const transport=new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL("../dist/cli.js",import.meta.url)),"--stdio"],env:{...getDefaultEnvironment(),MC_PLUGIN_URL:url.href,MC_PLUGIN_TOKEN:process.env.MC_PLUGIN_TOKEN}});
const origin=[576,100,0],from=[536,99,-40],to=[616,120,40];
let snapshot,restored=false,forced=false,checks=0;const created=new Set();
const text=r=>r.content.filter(c=>c.type==="text").map(c=>c.text).join("\n");
function check(fn){checks++;fn();}
async function tool(name,args){const r=await client.callTool({name,arguments:args});assert.ok(!r.isError,text(r));return r;}
async function rejected(name,args){const r=await client.callTool({name,arguments:args});assert.equal(r.isError,true,text(r));return text(r);}
const at=(x,y,z)=>[origin[0]+x,origin[1]+y,origin[2]+z];
plugin.start();
try{
    await client.connect(transport);const health=await plugin.request("health",{});assert.equal(health.onlinePlayers,0);assert.equal(health.queuedOperations,0);
    const base=await plugin.request("read_region",{from,to});assert.ok(base.palette.every(s=>s==="minecraft:air"),"Region must be pristine air");
    snapshot=(await plugin.request("snapshot",{from,to,label:"view acceptance pristine region"})).id;
    const catalog=await client.listTools();check(()=>assert.equal(catalog.tools.length,16));
    check(()=>assert.equal(catalog.tools.find(t=>t.name==="mc_sightline").annotations.readOnlyHint,true));
    // No players online, so keep the test area loaded explicitly (removed in finally).
    await tool("mc_command",{command:`forceload add ${from[0]} ${from[2]} ${to[0]} ${to[2]}`});forced=true;
    // A wall 8 blocks east of the eye: x=+8, z=-4..4, y=0..4, with a one-block gap at z=0, y=1; a floor under it all; a bottom slab row in front.
    let r=await tool("mc_build",{fills:[{from:at(0,-1,-6),to:at(20,-1,6),block:"minecraft:stone"},{from:at(8,0,-4),to:at(8,4,4),block:"minecraft:stone_bricks"},{from:at(8,1,0),to:at(8,1,0),block:"minecraft:air"},{from:at(5,0,-4),to:at(5,0,4),block:"minecraft:oak_slab[type=bottom]"}],label:"view e2e wall"});
    const m=text(r).match(/Journal: (jrn-[0-9a-f-]+)/);if(m)created.add(m[1]);
    const eye=[origin[0]+.5,origin[1]+1.62,origin[2]+.5];
    // Through the gap is visible, beside it is blocked by the wall, over the slab is visible.
    let t=text(await tool("mc_sightline",{eye,targets:[at(14,1,0),at(14,1,2),at(5,1,0),at(3,-1,0)]}));
    check(()=>assert.match(t,new RegExp(`${at(14,1,0).join(",")}  visible`)));
    check(()=>assert.match(t,new RegExp(`${at(14,1,2).join(",")}  blocked by minecraft:stone_bricks at ${at(8,1,1).join(",")}`)));
    check(()=>assert.match(t,new RegExp(`${at(5,1,0).join(",")}  visible`)));
    check(()=>assert.match(t,new RegExp(`${at(3,-1,0).join(",")}  visible`)));
    check(()=>assert.match(t,/3 visible, 1 blocked of 4/));
    // An ignore pattern lets the line through the wall.
    t=text(await tool("mc_sightline",{eye,targets:[at(14,1,2)],ignore:["stone_bricks"]}));check(()=>assert.match(t,/1 visible, 0 blocked of 1/));
    // A target in a chunk that is not loaded answers unknown, never visible.
    t=text(await tool("mc_sightline",{eye,targets:[[origin[0]+240,origin[1],origin[2]]]}));check(()=>assert.match(t,/unknown: chunk not loaded/));
    // Cone: the center ray passes through the gap, the rest hits the wall.
    t=text(await tool("mc_sightline",{eye,cone:{yaw:-90,pitch:0,fov:20,rays:5,distance:12}}));
    check(()=>assert.match(t,/5x5 rays/));check(()=>assert.match(t,/stone_bricks/));
    const rows=t.split("\n").slice(2,7);check(()=>assert.equal(rows[2][2],"."));check(()=>assert.equal(rows[2][0],"6"));
    // Argument errors.
    await checkA(async()=>assert.match(await rejected("mc_sightline",{eye,targets:[[origin[0]+400,100,0]]}),/limit is 256/));
    await checkA(async()=>assert.match(await rejected("mc_sightline",{player:"Nobody",targets:[at(1,1,1)]}),/not online/));
    await checkA(async()=>assert.match(await rejected("mc_render",{view:"first-person",eye}),/yaw and pitch are required/));
    // One first-person render looking east at the wall.
    r=await tool("mc_render",{view:"first-person",eye,yaw:-90,pitch:5,distance:64,width:640,height:360});
    const img=r.content.find(c=>c.type==="image");check(()=>assert.ok(img&&img.data.length>1000));
    const info=JSON.parse(text(r));check(()=>assert.equal(info.view,"first-person"));check(()=>assert.ok(info.chunks.loaded>0));
    check(()=>assert.ok(info.legend.some(l=>l.block.startsWith("minecraft:stone_bricks"))));
    if(process.env.ASHLAR_VIEW_PNG)writeFileSync(process.env.ASHLAR_VIEW_PNG,Buffer.from(img.data,"base64"));
    console.log(`view acceptance passed: ${checks} checks; render ${info.width}x${info.height}, chunks ${info.chunks.loaded}/${info.chunks.inView} loaded, unknownPixels ${info.geometry.unknownPixels}`);
}finally{
    if(snapshot&&!restored){const r=await client.callTool({name:"mc_restore",arguments:{id:snapshot}});const m=text(r).match(/Journal: (jrn-[0-9a-f-]+)/);if(m)created.add(m[1]);restored=!r.isError;}
    if(forced)await client.callTool({name:"mc_command",arguments:{command:`forceload remove ${from[0]} ${from[2]} ${to[0]} ${to[2]}`}});
    for(const id of created)await client.callTool({name:"mc_snapshot",arguments:{action:"journal-delete",id}});
    await client.close();plugin.close();
}
async function checkA(fn){checks++;await fn();}
