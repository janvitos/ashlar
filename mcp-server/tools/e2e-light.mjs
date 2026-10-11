#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in disposable-server acceptance for mc_light (dark spawn spaces, suggested lights) and mc_survey decor/walk heights; restores a pristine test region, never manages server lifecycle.
import assert from "node:assert/strict";
import {fileURLToPath} from "node:url";
import {Client} from "@modelcontextprotocol/client";
import {StdioClientTransport,getDefaultEnvironment} from "@modelcontextprotocol/client/stdio";
import {PluginClient} from "../dist/plugin-client.js";
assert.equal(process.env.ASHLAR_DISPOSABLE_TEST,"1");
const url=new URL(process.env.MC_PLUGIN_URL);assert.ok(["127.0.0.1","localhost","[::1]"].includes(url.hostname));assert.ok(process.env.MC_PLUGIN_TOKEN);
const plugin=new PluginClient({url:url.href,token:process.env.MC_PLUGIN_TOKEN,defaultTimeoutMs:300000});
const client=new Client({name:"light-acceptance",version:"1.0.0"});
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

const lit=a=>text(a).match(/: (\d+) dark of (\d+) (spawn spaces|open cells)/);
const row=(t,label,z)=>{const s=t.slice(t.indexOf(label));const m=s.match(new RegExp(`\\nz=${z}: ([^\\n]+)`));return m[1].split(" ");};
plugin.start();
try{
    await client.connect(transport);const health=await plugin.request("health",{});assert.equal(health.onlinePlayers,0);assert.equal(health.queuedOperations,0);
    const base=await plugin.request("read_region",{from,to});assert.ok(base.palette.every(s=>s==="minecraft:air"),"Region must be pristine air");
    snapshot=(await plugin.request("snapshot",{from,to,label:"light acceptance pristine region"})).id;
    const catalog=await client.listTools();
    check(()=>assert.equal(catalog.tools.length,19));
    const spec=catalog.tools.find(t=>t.name==="mc_light");
    check(()=>assert.ok(spec&&spec.annotations.readOnlyHint));
    check(()=>assert.ok(catalog.tools.find(t=>t.name==="mc_survey").inputSchema.properties.walkHeight));
    await command(`forceload add ${from[0]} ${from[2]} ${to[0]} ${to[2]}`);forced=true;
    // A closed stone room: shell 600..610 x 100..105 x 0..10, inside 9x4x9; 81 floor cells are spawn spaces.
    const room={from:[600,100,0],to:[610,105,10]};
    let t=await build({fills:[{...room,block:"stone",mode:"hollow"}],label:"light e2e room"});
    check(()=>assert.match(t,/Journal: jrn-/));
    await checkA(async()=>assert.match(await rejected("mc_light",{...room,suggest:{block:"torch",placement:"hidden"}}),/needs block "light"/));
    await checkA(async()=>assert.match(await rejected("mc_light",{...room,threshold:7,suggest:{level:7}}),/above threshold/));
    await checkA(async()=>assert.match(await rejected("mc_light",{...room,suggest:{block:"no_such_block"}}),/not a block state/));
    let r=await tool("mc_light",{...room,suggest:{}});t=text(r);
    check(()=>assert.deepEqual(lit(r).slice(1,4),["81","81","spawn spaces"],t));
    check(()=>assert.match(t,/Dark bounds: \[601,101,1\]\.\.\[609,101,9\]; by feet y: 101=81/,t));
    check(()=>assert.match(t,/sky light ignored/));
    const cells=JSON.parse(t.match(/mc_build cells: (\[.*\])$/m)[1]);
    check(()=>assert.ok(cells.length>=1&&cells.length<=2,t));
    check(()=>assert.ok(cells.every(c=>c[1]===101&&c[3]==="minecraft:light[level=11]"),t));
    check(()=>assert.match(t,/light 81 of 81 dark cells\./));
    // Open cells (spawnable:false) count every air cell in the box: 9*4*9.
    r=await tool("mc_light",{...room,spawnable:false});check(()=>assert.deepEqual(lit(r).slice(1,4),["324","324","open cells"],text(r)));
    // Sky light: the inside is closed, so includeSky changes nothing.
    r=await tool("mc_light",{...room,includeSky:true});check(()=>assert.equal(lit(r)[1],"81",text(r)));
    // Ceiling placement with a hanging lantern lands just under the roof.
    r=await tool("mc_light",{...room,suggest:{block:"lantern[hanging=true]",level:15,placement:"ceiling"}});t=text(r);
    check(()=>assert.ok(JSON.parse(t.match(/mc_build cells: (\[.*\])$/m)[1]).every(c=>c[1]===104&&c[3]==="minecraft:lantern[hanging=true]"),t));
    // Build the suggested hidden lights, let light update, and the room reads 0 dark.
    t=await build({cells,label:"light e2e lights"});
    check(()=>assert.match(t,/Journal: jrn-/));
    let dark="81";
    for(let i=0;i<20&&dark!=="0";i++){await new Promise(res=>setTimeout(res,500));dark=lit(await tool("mc_light",{...room,suggest:{}}))[1];}
    check(()=>assert.equal(dark,"0"));
    t=text(await tool("mc_light",{...room,suggest:{}}));check(()=>assert.match(t,/Suggestions: none needed\./,t));
    // Threshold above the light reaching the corners (11 - 8 = 3) finds them dark again.
    r=await tool("mc_light",{...room,threshold:3});check(()=>assert.ok(Number(lit(r)[1])>0,text(r)));
    // Survey fixtures on a stone row y=100 at z=-30, x=540..546: carpet, 2-layer snow, lantern, bottom slab, fence, 1-layer snow, nothing.
    await build({fills:[{from:[540,100,-30],to:[546,100,-30],block:"stone"}],blocks:[
        [540,101,-30,"white_carpet"],[541,101,-30,"snow[layers=2]"],[542,101,-30,"lantern"],[543,101,-30,"oak_slab[type=bottom]"],
        [544,101,-30,"oak_fence"],[545,101,-30,"snow[layers=1]"]].map(([x,y,z,b])=>({pos:[x,y,z],block:b})),label:"light e2e survey row"});
    const sv={from:[540,-30],to:[546,-30],format:"text"};
    t=text(await tool("mc_survey",{...sv,type:"TOP_SOLID_IGNORE_DECOR",matrix:true,walkHeight:true}));
    const ground=row(t,"Height matrix",-30),walk=row(t,"Walk heights",-30);
    check(()=>assert.deepEqual([ground[0],ground[1],ground[2],ground[5]],Array(4).fill(ground[6]),t));
    check(()=>assert.deepEqual([ground[3],ground[4]],Array(2).fill(String(Number(ground[6])+1)),t));
    check(()=>assert.deepEqual(walk,["101.063","101.125","101.563","101.5","102.5","101","101"],t));
    t=text(await tool("mc_survey",{...sv,type:"TOP_SOLID_IGNORE_DECOR",snowLayers:0,matrix:true}));
    check(()=>assert.equal(row(t,"Height matrix",-30)[1],String(Number(ground[6])+1),t));
    t=text(await tool("mc_survey",{...sv,type:"ANY",matrix:true}));
    check(()=>assert.ok(row(t,"Height matrix",-30).slice(0,6).every(h=>h===String(Number(ground[6])+1)),t));
    await checkA(async()=>assert.match(await rejected("mc_survey",{...sv,snowLayers:2}),/only with type/));
    await checkA(async()=>assert.match(await rejected("mc_survey",{from:[536,-40],to:[616,40],walkHeight:true}),/4096|4,096/));
    console.log(`light acceptance passed: ${checks} checks`);
}finally{
    if(snapshot&&!restored){const r=await client.callTool({name:"mc_restore",arguments:{id:snapshot}},undefined,{timeout:300000});journal(text(r));restored=!r.isError;}
    if(forced)await client.callTool({name:"mc_command",arguments:{command:`forceload remove ${from[0]} ${from[2]} ${to[0]} ${to[2]}`}});
    for(const id of created)await client.callTool({name:"mc_snapshot",arguments:{action:"journal-delete",id}});
    await client.close();plugin.close();
}
