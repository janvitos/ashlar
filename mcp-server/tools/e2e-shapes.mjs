#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in disposable visual acceptance. No server lifecycle management. Restores a pristine region by default.
import assert from "node:assert/strict";
import fs from "node:fs";
import {fileURLToPath} from "node:url";
import {Client} from "@modelcontextprotocol/client";
import {StdioClientTransport,getDefaultEnvironment} from "@modelcontextprotocol/client/stdio";
import {PluginClient} from "../dist/plugin-client.js";
assert.equal(process.env.ASHLAR_DISPOSABLE_TEST,"1");const url=new URL(process.env.MC_PLUGIN_URL);assert.ok(["127.0.0.1","localhost","[::1]"].includes(url.hostname));assert.ok(process.env.MC_PLUGIN_TOKEN);
const plugin=new PluginClient({url:url.href,token:process.env.MC_PLUGIN_TOKEN,defaultTimeoutMs:120000});
const client=new Client({name:"shape-acceptance",version:"1.0.0"});
const transport=new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL("../dist/cli.js",import.meta.url)),"--stdio"],env:{...getDefaultEnvironment(),MC_PLUGIN_URL:url.href,MC_PLUGIN_TOKEN:process.env.MC_PLUGIN_TOKEN}});
const from=[720,99,-40],to=[816,119,40],out=process.env.ASHLAR_SHAPE_OUTPUT||"/tmp/ashlar-step5-previews",keep=process.env.ASHLAR_TEST_KEEP_SCENE==="1";
let snapshot,checks=0;const text=r=>r.content.filter(c=>c.type==="text").map(c=>c.text).join("\n");function check(fn){checks++;fn();}
async function tool(name,args){const r=await client.callTool({name,arguments:args});assert.ok(!r.isError,text(r));return r;}
async function read(){return plugin.request("read_region",{from,to});}
async function fingerprint(){return {region:await read(),snapshots:(await plugin.request("list_snapshots",{})).snapshots.map(s=>s.id).sort()};}
async function readonly(fn){const b=await fingerprint();const r=await fn();const a=await fingerprint();check(()=>assert.deepEqual(a,b));return r;}
async function image(name,args){const r=await readonly(()=>tool("mc_render",args));fs.mkdirSync(out,{recursive:true});fs.writeFileSync(`${out}/${name}.png`,Buffer.from(r.content.find(c=>c.type==="image").data,"base64"));const m=JSON.parse(text(r));check(()=>assert.ok(m.width*m.height<=1000000));check(()=>assert.ok(m.geometry.estimatedIntersectionWork<=64000000));return r;}
async function rejected(args){const b=await fingerprint();const r=await client.callTool({name:"mc_render",arguments:args});check(()=>assert.equal(r.isError,true));const a=await fingerprint();check(()=>assert.deepEqual(a,b));}
const cases=[];
for(const type of ["bottom","top","double"])cases.push(`minecraft:stone_slab[type=${type}]`);
for(const facing of ["north","east","south","west"])for(const half of ["bottom","top"])for(const shape of ["straight","inner_left","inner_right","outer_left","outer_right"])cases.push(`minecraft:oak_stairs[facing=${facing},half=${half},shape=${shape}]`);
for(const facing of ["north","east","south","west"])for(const hinge of ["left","right"])for(const open of [false,true])cases.push(`minecraft:oak_door[facing=${facing},hinge=${hinge},open=${open},half=lower]`);
for(const facing of ["north","east","south","west"])for(const half of ["bottom","top"])for(const open of [false,true])cases.push(`minecraft:oak_trapdoor[facing=${facing},half=${half},open=${open}]`);
cases.push("minecraft:oak_fence[north=true,east=true,south=false,west=false]","minecraft:cobblestone_wall[up=true,north=low,south=tall,east=none,west=none]","minecraft:glass_pane[north=true,south=true,east=true,west=true]","minecraft:glass","minecraft:iron_bars[north=true,east=true]","minecraft:oak_fence_gate[facing=north,open=true]","minecraft:oak_fence_gate[facing=north,open=false]","minecraft:anvil","minecraft:water","minecraft:red_bed[facing=north,part=foot]","minecraft:chest[facing=north,type=single]","minecraft:oak_sign[rotation=3]");
const galleryBlocks=[],nativeCases=[];for(let i=0;i<cases.length;i++){const pos=[744+(i%8)*6,101,-34+Math.floor(i/8)*6];galleryBlocks.push({pos,block:cases[i]});nativeCases.push({pos,state:cases[i]});if(cases[i].includes("oak_door["))galleryBlocks.push({pos:[pos[0],102,pos[2]],block:cases[i].replace("half=lower","half=upper")});if(cases[i].includes("red_bed"))galleryBlocks.push({pos:[pos[0],101,pos[2]-1],block:cases[i].replace("part=foot","part=head")});}
for(const b of galleryBlocks)if(b.block.startsWith("minecraft:oak_sign["))b.sign={front:["Read-only render","Keep this text","blue and glowing","waxed fixture"],back:["Still unchanged"],color:"blue",glowing:true,waxed:true};
const gallery={fills:[{from:[742,100,-36],to:[789,100,34],block:"minecraft:white_concrete"}],blocks:galleryBlocks,connect:false};
const house={transform:{origin:[768,100,0]},connect:false,fills:[{from:[-1,0,-1],to:[14,0,12],block:"minecraft:stone_bricks"},{from:[0,1,0],to:[13,5,11],block:"minecraft:oak_planks",mode:"walls"},{from:[1,1,1],to:[12,1,10],block:"minecraft:oak_planks"},{from:[3,2,11],to:[5,3,11],block:"minecraft:glass_pane[east=true,west=true]"},{from:[9,2,11],to:[11,3,11],block:"minecraft:glass_pane[east=true,west=true]"},{from:[13,2,3],to:[13,3,8],block:"minecraft:glass"}],blocks:[{pos:[7,1,11],block:"minecraft:oak_door[facing=north,half=lower,hinge=left,open=true]"},{pos:[7,2,11],block:"minecraft:oak_door[facing=north,half=upper,hinge=left,open=true]"},{pos:[7,0,13],block:"minecraft:stone_brick_stairs[facing=north]"},{pos:[7,0,14],block:"minecraft:stone_brick_slab"},{pos:[13,4,7],block:"minecraft:oak_trapdoor[facing=west,open=true]"}]};
for(let k=0;k<6;k++){house.fills.push({from:[0,6+k,k],to:[0,6+k,11-k],block:"minecraft:oak_planks"},{from:[13,6+k,k],to:[13,6+k,11-k],block:"minecraft:oak_planks"},{from:[-1,6+k,k-1],to:[14,6+k,k-1],block:"minecraft:dark_oak_stairs[facing=south]"},{from:[-1,6+k,12-k],to:[14,6+k,12-k],block:"minecraft:dark_oak_stairs[facing=north]"});}
house.fills.push({from:[-1,12,5],to:[14,12,6],block:"minecraft:dark_oak_slab[type=bottom]"});
plugin.start();
try{
 await client.connect(transport);const h=await plugin.request("health",{});assert.equal(h.onlinePlayers,0);assert.equal(h.queuedOperations,0);const base=await read();assert.ok(base.palette.every(s=>s==="minecraft:air"));assert.equal(base.signs.length,0);
 snapshot=(await plugin.request("snapshot",{from,to,label:"shape acceptance pristine scene"})).id;
 await tool("mc_build",gallery);fs.mkdirSync(out,{recursive:true});fs.writeFileSync(`${out}/native-gallery-cases.json`,JSON.stringify(nativeCases));
 const bounds={from:[742,100,-36],to:[789,103,34]};
 for(const azimuth of [45,135,225,315])await image(`gallery-iso-${azimuth}`,{...bounds,view:"isometric",camera:{azimuth},scale:16});
 await image("gallery-closeup",{from:[742,100,-36],to:[766,103,-27],view:"isometric",camera:{azimuth:135,elevation:40},scale:16});
 await image("gallery-doors",{from:[742,100,-6],to:[789,103,8],view:"isometric",camera:{azimuth:225,elevation:25},scale:16});
 await image("gallery-perspective",{...bounds,view:"perspective",camera:{azimuth:135,elevation:50,fov:65},scale:16});
 if(!keep){
  await tool("mc_restore",{id:snapshot});
  // Optional planning path uses exactly the same renderer as actual static states.
  for(const view of ["isometric","perspective"]){
   const preview=await readonly(()=>tool("mc_plan",{build:house,preview:{view,camera:{azimuth:135,elevation:30},scale:16,grid:0}}));const report=JSON.parse(text(preview));
   await tool("mc_build",house);const live=await image(`house-${view}`,{from:report.from,to:report.to,view,camera:{azimuth:135,elevation:30},scale:16,grid:0});
   check(()=>assert.equal(preview.content.find(c=>c.type==="image").data,live.content.find(c=>c.type==="image").data,"static preview/live image parity"));
   await image(`house-${view}-rear`,{from:report.from,to:report.to,view,camera:{azimuth:315,elevation:35},scale:16,grid:0});
   await tool("mc_restore",{id:snapshot});
  }
  for(const bad of [{from:[768,0],to:[778,10],view:"isometric"},{...bounds,view:"isometric",camera:{fov:50}},{...bounds,view:"perspective",camera:{azimuth:360}},{...bounds,view:"perspective",camera:{elevation:0}},{...bounds,view:"isometric",grid:10},{...bounds,view:"isometric",scale:1.5},{...bounds,view:"top",camera:{}},{from:[768.5,100,0],to:[770,103,2],view:"isometric"},{from:[768,100,0],to:[868,150,100],view:"isometric"}])await rejected(bad);
  for(const bad of [{from:[768,0],to:[778,10],view:"isometric"},{...bounds,view:"isometric",grid:10},{...bounds,view:"isometric",scale:1.5},{...bounds,view:"top",camera:{}},{...bounds,view:"isometric",camera:{fov:50}},{from:[768.5,100,0],to:[770,103,2],view:"isometric"},{from:[2147483648,100,0],to:[770,103,2],view:"isometric"}]){
   await readonly(async()=>{await assert.rejects(plugin.request("render",bad),e=>e.code==="BAD_REQUEST");checks++;});
  }
  const raw=await readonly(()=>plugin.request("render",{...bounds,view:"isometric",scale:8}));check(()=>assert.equal(raw.geometry.projection,"orthographic"));
 }
 console.log(JSON.stringify({checks,failures:0,images:out,kept:keep,snapshot}));
}finally{if(snapshot&&!keep)await tool("mc_restore",{id:snapshot});await client.close();plugin.close();}
