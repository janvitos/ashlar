#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in disposable-server acceptance for mc_build `shapes` (cylinders, domes, cones...): placement, transforms, plans, blueprints and undo; restores a pristine test region, never manages server lifecycle.
import assert from "node:assert/strict";
import {writeFileSync} from "node:fs";
import {fileURLToPath} from "node:url";
import {Client} from "@modelcontextprotocol/client";
import {StdioClientTransport,getDefaultEnvironment} from "@modelcontextprotocol/client/stdio";
import {PluginClient} from "../dist/plugin-client.js";
assert.equal(process.env.ASHLAR_DISPOSABLE_TEST,"1");
const url=new URL(process.env.MC_PLUGIN_URL);assert.ok(["127.0.0.1","localhost","[::1]"].includes(url.hostname));assert.ok(process.env.MC_PLUGIN_TOKEN);
const out=process.env.ASHLAR_E2E_OUT;
const plugin=new PluginClient({url:url.href,token:process.env.MC_PLUGIN_TOKEN,defaultTimeoutMs:300000});
const client=new Client({name:"curves-acceptance",version:"1.0.0"});
const transport=new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL("../dist/cli.js",import.meta.url)),"--stdio"],env:{...getDefaultEnvironment(),MC_PLUGIN_URL:url.href,MC_PLUGIN_TOKEN:process.env.MC_PLUGIN_TOKEN}});
const from=[536,99,-40],to=[616,120,40];
let snapshot,restored=false,forced=false,checks=0;const created=new Set(),ids=[];
const text=r=>r.content.filter(c=>c.type==="text").map(c=>c.text).join("\n");
function check(fn){checks++;fn();}
async function tool(name,args){const r=await client.callTool({name,arguments:args},undefined,{timeout:300000});assert.ok(!r.isError,text(r));return r;}
async function rejected(name,args){const r=await client.callTool({name,arguments:args});assert.equal(r.isError,true,text(r));return text(r);}
function journal(t){for(const m of t.matchAll(/(jrn-[0-9]{8}-[0-9]{6}-[0-9a-f]{4})/g))created.add(m[1]);return t;}
async function build(args){return journal(text(await tool("mc_build",args)));}
async function states(a,b){
    const r=await plugin.request("read_region",{from:a,to:b});const m=new Map();let run=0,left=r.runs[0][1];
    for(let y=a[1];y<=b[1];y++)for(let z=a[2];z<=b[2];z++)for(let x=a[0];x<=b[0];x++){m.set(`${x},${y},${z}`,r.palette[r.runs[run][0]]);if(--left===0&&run+1<r.runs.length)left=r.runs[++run][1];}
    return m;
}
const count=(m,block)=>[...m.values()].filter(s=>s===block).length;

// A round tower: hollow cylinder, copper dome shell, solid cone spire.
const tower={label:"curves e2e tower",shapes:[
    {shape:"cylinder",from:[540,100,-30],to:[548,109,-22],block:"minecraft:stone_bricks",thickness:1},
    {shape:"dome",from:[540,110,-30],to:[548,113,-22],block:"minecraft:copper_block",thickness:1},
    {shape:"cone",from:[543,114,-27],to:[545,118,-25],block:"minecraft:dark_oak_planks"}]};
plugin.start();
try{
    await client.connect(transport);const health=await plugin.request("health",{});assert.equal(health.onlinePlayers,0);assert.equal(health.queuedOperations,0);
    const base=await plugin.request("read_region",{from,to});assert.ok(base.palette.every(s=>s==="minecraft:air"),"Region must be pristine air");
    snapshot=(await plugin.request("snapshot",{from,to,label:"curves acceptance pristine region"})).id;
    await tool("mc_command",{command:`forceload add ${from[0]} ${from[2]} ${to[0]} ${to[2]}`});forced=true;
    const catalog=await client.listTools();
    check(()=>assert.deepEqual(catalog.tools.find(t=>t.name==="mc_build").inputSchema.properties.shapes.items.properties.shape.enum,["cylinder","sphere","dome","cone","pyramid"]));
    // The dry run counts every planned cell; the real build places exactly those.
    const plan=JSON.parse(text(await tool("mc_build",{...tower,dryRun:true})));
    check(()=>assert.equal(plan.valid,true));check(()=>assert.deepEqual([plan.from,plan.to],[[540,100,-30],[548,118,-22]]));
    const planned=plan.uniquePlannedCells;check(()=>assert.ok(planned>200&&planned<600,String(planned)));
    let t=await build(tower);
    check(()=>assert.match(t,/^Shapes: (\d+)\/\1 changed/m,t));
    const jid=t.match(/Journal: (jrn-[0-9a-f-]+)/)[1];
    let m=await states([540,100,-30],[548,118,-22]);
    check(()=>assert.equal(count(m,"minecraft:stone_bricks")+count(m,"minecraft:copper_block")+count(m,"minecraft:dark_oak_planks"),planned));
    check(()=>assert.equal(m.get("544,100,-26"),"minecraft:air"));          // open, hollow tower
    check(()=>assert.equal(m.get("540,105,-26"),"minecraft:stone_bricks")); // wall at the box edge
    check(()=>assert.equal(m.get("544,113,-26"),"minecraft:copper_block"));  // dome crown
    check(()=>assert.equal(m.get("544,110,-26"),"minecraft:air"));          // dome has no floor
    check(()=>assert.equal(m.get("544,118,-26"),"minecraft:dark_oak_planks")); // spire tip
    check(()=>assert.equal(m.get("540,100,-30"),"minecraft:air"));          // box corner stays outside the circle
    if(out){const r=await tool("mc_render",{from:[538,99,-32],to:[550,119,-20],view:"isometric"});writeFileSync(`${out}/curves-tower.png`,Buffer.from(r.content.find(c=>c.type==="image").data,"base64"));}
    // A transformed sideways cone: local +X becomes world +Z.
    const side=JSON.parse(text(await tool("mc_build",{shapes:[{shape:"cone",from:[0,0,0],to:[8,4,4],axis:"x",block:"minecraft:stone"}],transform:{origin:[580,100,0],rotation:90},dryRun:true})));
    check(()=>assert.deepEqual([side.from,side.to],[[576,100,0],[580,104,8]]));
    // mc_plan takes shapes through its nested build.
    const p=JSON.parse(text(await tool("mc_plan",{build:{shapes:[{shape:"sphere",from:[590,100,10],to:[596,106,16],block:"minecraft:glass",thickness:1}]},image:false})));
    check(()=>assert.equal(p.valid,true));check(()=>assert.ok(p.uniquePlannedCells>50,String(p.uniquePlannedCells)));
    // Errors name the entry and nothing is written.
    t=await rejected("mc_build",{shapes:[{shape:"sphere",from:[590,100,10],to:[596,106,16],block:"minecraft:glass",thickness:20}]});
    check(()=>assert.match(t,/shapes\[0\]\.thickness: must be between 0 and 16|shapes\/0\/thickness must be <= 16/));
    t=await rejected("mc_build",{shapes:[{shape:"dome",from:[590,100,10],to:[596,106,16],block:"minecraft:copper"}]});
    check(()=>assert.match(t,/unknown block 'copper'/));
    // Blueprint components take shapes with roles.
    const id=`e2ecurve${Date.now().toString(36)}`;
    await tool("mc_blueprint",{action:"save",id,document:{version:1,palette:{roof:"minecraft:red_concrete"},
        components:{hut:{shapes:[{shape:"pyramid",from:[0,0,0],to:[4,2,4],block:"$roof",thickness:1}]}},instances:[{component:"hut",pos:[0,0,0]}]}});ids.push(id);
    t=await build({blueprint:{id},transform:{origin:[600,100,20]}});
    check(()=>assert.match(t,/^Shapes: /m));
    m=await states([600,100,20],[604,102,24]);
    check(()=>assert.equal(m.get("602,102,22"),"minecraft:red_concrete"));check(()=>assert.equal(m.get("602,100,22"),"minecraft:air"));
    // Undo removes exactly the tower.
    t=text(await tool("mc_restore",{journal:jid}));journal(t);
    m=await states([540,100,-30],[548,118,-22]);
    check(()=>assert.equal(count(m,"minecraft:air"),m.size));
    console.log(`curves acceptance passed: ${checks} checks`);
}finally{
    if(snapshot&&!restored){const r=await client.callTool({name:"mc_restore",arguments:{id:snapshot}},undefined,{timeout:300000});journal(text(r));restored=!r.isError;}
    if(forced)await client.callTool({name:"mc_command",arguments:{command:`forceload remove ${from[0]} ${from[2]} ${to[0]} ${to[2]}`}});
    for(const id of created)await client.callTool({name:"mc_snapshot",arguments:{action:"journal-delete",id}});
    for(const id of ids)await client.callTool({name:"mc_blueprint",arguments:{action:"delete",id}});
    await client.close();plugin.close();
}
