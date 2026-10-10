#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in disposable-server acceptance for mc_diff; restores a pristine test region, never manages server lifecycle.
import assert from "node:assert/strict";
import {randomUUID} from "node:crypto";
import {fileURLToPath} from "node:url";
import {Client} from "@modelcontextprotocol/client";
import {StdioClientTransport,getDefaultEnvironment} from "@modelcontextprotocol/client/stdio";
import {PluginClient} from "../dist/plugin-client.js";
assert.equal(process.env.ASHLAR_DISPOSABLE_TEST,"1");
const url=new URL(process.env.MC_PLUGIN_URL);assert.ok(["127.0.0.1","localhost","[::1]"].includes(url.hostname));assert.ok(process.env.MC_PLUGIN_TOKEN);
const plugin=new PluginClient({url:url.href,token:process.env.MC_PLUGIN_TOKEN,defaultTimeoutMs:120000});
const client=new Client({name:"diff-acceptance",version:"1.0.0"});
const transport=new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL("../dist/cli.js",import.meta.url)),"--stdio"],env:{...getDefaultEnvironment(),MC_PLUGIN_URL:url.href,MC_PLUGIN_TOKEN:process.env.MC_PLUGIN_TOKEN}});
const origin=[576,100,0],from=[536,99,-40],to=[616,120,40],blueprint="e2e_diff_"+randomUUID().replaceAll("-","");
const sceneFrom=[576,100,0],sceneTo=[584,103,8];
let snapshot,saved=false,checks=0;
const text=r=>r.content.filter(c=>c.type==="text").map(c=>c.text).join("\n");
function check(fn){checks++;fn();}
async function tool(name,args){const r=await client.callTool({name,arguments:args});assert.ok(!r.isError,text(r));return r;}
async function diff(args){return text(await tool("mc_diff",args));}
const at=(x,y,z)=>[origin[0]+x,origin[1]+y,origin[2]+z];
const count=(t,k)=>Number((t.match(new RegExp("\\b"+k+" (\\d+)"))||[])[1]);
plugin.start();
try{
    await client.connect(transport);const health=await plugin.request("health",{});assert.equal(health.onlinePlayers,0);assert.equal(health.queuedOperations,0);
    const base=await plugin.request("read_region",{from,to});assert.ok(base.palette.every(s=>s==="minecraft:air"),"Region must be pristine air");
    const catalog=await client.listTools();check(()=>assert.equal(catalog.tools.length,16));check(()=>assert.equal(catalog.tools.find(t=>t.name==="mc_diff").annotations.readOnlyHint,true));
    snapshot=(await plugin.request("snapshot",{from,to,label:"diff acceptance pristine region"})).id;
    await tool("mc_build",{fills:[{from:at(0,0,0),to:at(8,0,8),block:"minecraft:stone"}],blocks:[
        {pos:at(1,1,1),block:"minecraft:light[level=11]"},{pos:at(2,1,2),block:"minecraft:oak_stairs[facing=north]"},
        {pos:at(3,1,3),block:"minecraft:diamond_block"},{pos:at(5,1,5),block:"minecraft:stone"}],connect:false});
    const scene=(await plugin.request("snapshot",{from:sceneFrom,to:sceneTo,label:"diff acceptance scene"})).id;
    // Canonical equivalence: explicit default property values compare equal.
    let t=await diff({from:at(1,1,1),to:at(1,1,1),against:{expected:[[...at(1,1,1),"light[level=11,waterlogged=false]"]]}});
    check(()=>assert.match(t,/No differences/));
    const inspect=text(await tool("mc_inspect",{from:at(1,1,1),to:at(1,1,1)}));check(()=>assert.match(inspect,/light\[level=11\]/));check(()=>assert.doesNotMatch(inspect,/waterlogged/));
    // Five changed cells plus one floating and one stacked snow layer (and its supported base).
    await tool("mc_build",{blocks:[{pos:at(3,1,3),block:"minecraft:gold_block"},{pos:at(2,1,2),block:"minecraft:oak_stairs[facing=south]"},
        {pos:at(5,1,5),block:"minecraft:air"},{pos:at(7,1,7),block:"minecraft:cobblestone"},{pos:at(0,0,0),block:"minecraft:air"},
        {pos:at(4,3,4),block:"minecraft:snow[layers=1]"},{pos:at(7,1,1),block:"minecraft:snow[layers=2]"},{pos:at(7,2,1),block:"minecraft:snow[layers=1]"}],connect:false});
    t=await diff({from:sceneFrom,to:sceneTo,against:{snapshot:scene},anomalies:["floating","stacked"],format:"cells"});
    check(()=>assert.match(t,/ 8 differ/));check(()=>assert.equal(count(t,"missing"),2));check(()=>assert.equal(count(t,"unexpected"),4));
    check(()=>assert.equal(count(t,"floating"),1));check(()=>assert.equal(count(t,"stacked"),1));
    check(()=>assert.match(t,new RegExp("floating \\["+at(4,3,4).join(",")+"\\]")));check(()=>assert.match(t,new RegExp("stacked \\["+at(7,2,1).join(",")+"\\]")));
    const ignored=await diff({from:sceneFrom,to:sceneTo,against:{snapshot:scene},ignore:["snow"],compare:"material"});check(()=>assert.match(ignored,/ 4 differ/));
    const diffId=t.match(/diffId: (diff-[0-9a-f-]+)/)[1];
    const r=JSON.parse(text(await tool("mc_repair",{diffId,snapshot:false})));check(()=>assert.equal(r.repair.writtenCells,8));
    t=await diff({from:sceneFrom,to:sceneTo,against:{snapshot:scene}});check(()=>assert.match(t,/No differences/));
    // Blueprint reference after a clean build reports zero differences.
    await tool("mc_blueprint",{action:"save",id:blueprint,document:{version:1,components:{unit:{fills:[{from:[0,0,0],to:[2,0,2],block:"minecraft:stone"}],blocks:[{pos:[1,1,1],block:"minecraft:oak_stairs[facing=north]"}]}},instances:[{component:"unit",pos:[0,0,0],repeat:{count:2,step:[4,0,0]}}]}});saved=true;
    const bo=[576,110,0];await tool("mc_build",{blueprint:{id:blueprint},transform:{origin:bo},connect:false});
    t=await diff({from:bo,to:[bo[0]+6,bo[1]+1,bo[2]+2],against:{blueprint:{id:blueprint},transform:{origin:bo}},scope:"declared"});check(()=>assert.match(t,/No differences/));
    // Chunked live reads over a ~620k-cell box (read-only).
    t=await diff({from:[536,-64,-40],to:[616,30,40],against:{expected:[[576,100,0,"stone"]]}});
    const reads=Number(t.match(/(\d+) live read/)[1]);check(()=>assert.ok(reads>=4,t.split("\n")[0]));check(()=>assert.match(t,/Compared 0 cells/));
    console.log(`mc_diff acceptance passed: ${checks} checks; chunked read used ${reads} parts`);
}finally{
    if(snapshot)await tool("mc_restore",{id:snapshot});if(saved)await tool("mc_blueprint",{action:"delete",id:blueprint});await client.close();plugin.close();
}
