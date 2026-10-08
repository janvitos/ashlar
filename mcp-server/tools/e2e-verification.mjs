#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in disposable-server acceptance; restores a pristine test region, never manages server lifecycle.
import assert from "node:assert/strict";
import {randomUUID} from "node:crypto";
import {fileURLToPath} from "node:url";
import {Client} from "@modelcontextprotocol/client";
import {StdioClientTransport,getDefaultEnvironment} from "@modelcontextprotocol/client/stdio";
import {PluginClient} from "../dist/plugin-client.js";
assert.equal(process.env.ASHLAR_DISPOSABLE_TEST,"1");
const url=new URL(process.env.MC_PLUGIN_URL);assert.ok(["127.0.0.1","localhost","[::1]"].includes(url.hostname));assert.ok(process.env.MC_PLUGIN_TOKEN);
const plugin=new PluginClient({url:url.href,token:process.env.MC_PLUGIN_TOKEN,defaultTimeoutMs:120000});
const client=new Client({name:"verification-acceptance",version:"1.0.0"});
const transport=new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL("../dist/cli.js",import.meta.url)),"--stdio"],env:{...getDefaultEnvironment(),MC_PLUGIN_URL:url.href,MC_PLUGIN_TOKEN:process.env.MC_PLUGIN_TOKEN}});
const origin=[576,100,0],from=[536,99,-40],to=[616,120,40],plans=new Set(),blueprint="e2e_verify_"+randomUUID().replaceAll("-","");
let snapshot,saved=false,forceAdded=false,checks=0;
const text=r=>r.content.filter(c=>c.type==="text").map(c=>c.text).join("\n");
function check(fn){checks++;fn();}
async function tool(name,args){const r=await client.callTool({name,arguments:args});assert.ok(!r.isError,text(r));return r;}
async function json(name,args){return JSON.parse(text(await tool(name,args)));}
async function read(){return plugin.request("read_region",{from,to});}
async function fingerprint(){return {region:await read(),snapshots:(await plugin.request("list_snapshots",{})).snapshots.map(s=>s.id).sort()};}
// Async fingerprint check is deliberately outside assertion callbacks.
async function noWrites(fn){const before=await fingerprint();const r=await fn();const after=await fingerprint();check(()=>assert.deepEqual(after,before));return r;}
const placed=b=>({...b,transform:{origin},connect:false});
async function prepare(build){const r=await noWrites(()=>json("mc_verify",{action:"prepare",build}));plans.add(r.planId);check(()=>assert.equal(r.persistent,false));return r.planId;}
async function verify(planId,options={}){return noWrites(()=>json("mc_verify",{action:"check",planId,...options}));}
async function rejected(name,args,pattern){const before=await fingerprint();const r=await client.callTool({name,arguments:args});check(()=>assert.equal(r.isError,true));if(pattern)check(()=>assert.match(text(r),pattern));const after=await fingerprint();check(()=>assert.deepEqual(after,before));}
async function reset(){await tool("mc_restore",{id:snapshot});}
function states(region){const a=[];for(const [idx,n] of region.runs)for(let i=0;i<n;i++)a.push(region.palette[idx]);return a;}
async function repairExact(planId,comparisonId,extra={}){
    const before=states(await read());const r=await json("mc_repair",{planId,comparisonId,...extra});const after=states(await read());
    let changed=0;for(let i=0;i<before.length;i++)if(before[i]!==after[i])changed++;
    check(()=>assert.ok(changed<=r.repair.writtenCells));check(()=>assert.equal(r.repair.guardPassed,true));return r;
}
plugin.start();
try{
    await client.connect(transport);const health=await plugin.request("health",{});assert.equal(health.onlinePlayers,0);assert.equal(health.queuedOperations,0);
    const base=await read();assert.ok(base.palette.every(s=>s==="minecraft:air"),"Region must be pristine air to avoid losing existing NBT during snapshot cleanup");assert.equal(base.signs.length,0);
    const catalog=await client.listTools();check(()=>assert.equal(catalog.tools.length,13));check(()=>assert.ok(catalog.tools.some(t=>t.name==="mc_verify")));check(()=>assert.equal(catalog.tools.find(t=>t.name==="mc_repair").annotations.destructiveHint,true));
    snapshot=(await plugin.request("snapshot",{from,to,label:"verification acceptance pristine region"})).id;
    const build=placed({fills:[{from:[0,0,0],to:[4,3,4],block:"minecraft:stone",mode:"hollow"}],blocks:[
        {pos:[1,1,1],block:"minecraft:oak_stairs[facing=north,half=bottom,shape=straight]"},
        {pos:[2,1,1],block:"minecraft:oak_sign[rotation=3]",sign:{front:["Original"],back:["Back"],color:"blue",glowing:true,waxed:true}},
        {pos:[3,1,1],block:"minecraft:diamond_block"}]});
    const p=await prepare(build);let v=await verify(p);check(()=>assert.ok(v.mismatchedCells>0));await tool("mc_build",build);v=await verify(p);check(()=>assert.equal(v.matched,true));
    await tool("mc_build",placed({blocks:[{pos:[0,0,0],block:"minecraft:air"},{pos:[3,1,1],block:"minecraft:gold_block"},{pos:[1,1,1],block:"minecraft:oak_stairs[facing=south,half=bottom,shape=straight]"},{pos:[1,1,2],block:"minecraft:gold_block"},{pos:[2,1,1],block:"minecraft:oak_sign[rotation=3]",sign:{front:["Wrong"],color:"red",glowing:false,waxed:false}}]}));
    v=await verify(p,{limit:1});check(()=>assert.equal(v.mismatchedCells,5));check(()=>assert.equal(v.differences.length,1));check(()=>assert.equal(v.differencesTruncated,true));
    v=await verify(p);for(const kind of ["missing","wrong_material","wrong_state","unexpected","sign_metadata"])check(()=>assert.equal(v.counts[kind],1));
    const stair=v.differences.find(d=>d.kind==="wrong_state");check(()=>assert.equal(stair.propertyDifferences.facing.actual,"south"));
    await rejected("mc_repair",{planId:p,comparisonId:v.comparisonId,maxChanges:1},/maxChanges/);
    await rejected("mc_repair",{planId:p,comparisonId:v.comparisonId,positions:[[600,100,0]]},/mismatches/);
    const first=await repairExact(p,v.comparisonId,{positions:[[576,100,0]]});check(()=>assert.equal(first.mismatchedCells,4));check(()=>assert.equal(first.repair.writtenCells,1));
    await rejected("mc_repair",{planId:p,comparisonId:v.comparisonId},/stale/);
    let repaired=await repairExact(p,first.comparisonId);check(()=>assert.equal(repaired.matched,true));check(()=>assert.equal(repaired.repair.writtenCells,4));
    const noOp=await noWrites(()=>json("mc_repair",{planId:p,comparisonId:repaired.comparisonId}));check(()=>assert.equal(noOp.repair.writtenCells,0));check(()=>assert.equal(noOp.matched,true));
    // Stale values (not just superseded IDs) reject before snapshots and any write.
    await tool("mc_build",placed({blocks:[{pos:[0,0,0],block:"minecraft:gold_block"}]}));v=await verify(p);
    await tool("mc_build",placed({blocks:[{pos:[0,0,0],block:"minecraft:dirt"}]}));await rejected("mc_repair",{planId:p,comparisonId:v.comparisonId},/guard rejected/);
    v=await verify(p);repaired=await repairExact(p,v.comparisonId,{snapshot:false});check(()=>assert.equal(repaired.matched,true));check(()=>assert.ok(!repaired.snapshot));
    await reset();
    // Freeze keep/filters before construction; checks must NOT reevaluate the predicates.
    await tool("mc_build",placed({blocks:[{pos:[0,0,0],block:"minecraft:gold_block"},{pos:[4,0,0],block:"minecraft:dirt"}]}));
    const conditional=placed({fills:[{from:[0,0,0],to:[2,0,0],block:"minecraft:stone",mode:"keep"},{from:[4,0,0],to:[5,0,0],block:"minecraft:diamond_block",filter:"minecraft:dirt"}]});
    const cp=await prepare(conditional);await tool("mc_build",conditional);v=await verify(cp);check(()=>assert.equal(v.matched,true));
    await tool("mc_build",placed({blocks:[{pos:[1,0,0],block:"minecraft:gold_block"},{pos:[4,0,0],block:"minecraft:air"},{pos:[0,0,0],block:"minecraft:emerald_block"}]}));
    v=await verify(cp);check(()=>assert.equal(v.checkedCells,3));check(()=>assert.equal(v.mismatchedCells,2));repaired=await repairExact(cp,v.comparisonId);check(()=>assert.equal(repaired.matched,true));
    const skipState=await plugin.request("read_region",{from:[576,100,0],to:[576,100,0]});check(()=>assert.equal(skipState.palette[0],"minecraft:emerald_block"));
    await reset();
    // Sequential sign patches preserve the unspecified face, color and glow.
    await tool("mc_build",placed({blocks:[{pos:[0,0,0],block:"minecraft:stone"},{pos:[0,1,0],block:"minecraft:oak_sign",sign:{front:["Before"],back:["Preserve"],color:"blue",glowing:true,waxed:true}}]}));
    const signBuild=placed({blocks:[{pos:[0,1,0],block:"minecraft:oak_sign",sign:{front:["New front"],color:"red"}},{pos:[0,1,0],block:"minecraft:oak_sign",sign:{back:["New back"],glowing:true,waxed:true}}]});
    const sp=await prepare(signBuild);await tool("mc_build",signBuild);v=await verify(sp);check(()=>assert.equal(v.matched,true));
    await tool("mc_build",placed({blocks:[{pos:[0,1,0],block:"minecraft:oak_sign",sign:{front:["Broken"],back:["Broken back"],color:"green",waxed:false}}]}));v=await verify(sp);check(()=>assert.equal(v.counts.sign_metadata,1));
    const signs=await repairExact(sp,v.comparisonId);check(()=>assert.equal(signs.matched,true));check(()=>assert.equal(signs.repair.signWrites,1));
    await reset();
    // Protected block entities: deleting an unexpected sign requires explicit acknowledgement.
    const ep=await prepare(placed({blocks:[{pos:[0,0,0],block:"minecraft:air"}]}));await tool("mc_build",placed({blocks:[{pos:[0,0,0],block:"minecraft:oak_sign",sign:{front:["Do not discard"]}}]}));v=await verify(ep);
    await rejected("mc_repair",{planId:ep,comparisonId:v.comparisonId},/block entity/);repaired=await repairExact(ep,v.comparisonId,{allowBlockEntityReplacement:true});check(()=>assert.equal(repaired.matched,true));
    await reset();
    // Exact paired chests must stay paired; correcting a different cell must not rewrite them.
    const chestBuild=placed({blocks:[{pos:[0,0,0],block:"minecraft:chest[facing=north,type=left]"},{pos:[1,0,0],block:"minecraft:chest[facing=north,type=right]"},{pos:[3,0,0],block:"minecraft:stone"}]});
    const hp=await prepare(chestBuild);await tool("mc_build",chestBuild);v=await verify(hp);check(()=>assert.equal(v.matched,true));
    // Item administration is the command escape hatch; no block placement/terrain inspection commands.
    const force=await tool("mc_command",{command:"forceload add 576 0"});assert.match(text(force),/Marked chunk/i);forceAdded=true;
    const item=await tool("mc_command",{command:"item replace block 576 100 0 container.0 with minecraft:diamond 7"});check(()=>assert.match(text(item),/Replaced/i));
    async function inventoryPresent(){const r=await tool("mc_command",{command:"execute if items block 576 100 0 container.0 minecraft:diamond"});check(()=>assert.match(text(r),/Test passed/i));}
    await inventoryPresent();
    await tool("mc_build",placed({blocks:[{pos:[3,0,0],block:"minecraft:gold_block"}]}));v=await verify(hp);repaired=await repairExact(hp,v.comparisonId);check(()=>assert.equal(repaired.repair.writtenCells,1));check(()=>assert.equal(repaired.matched,true));await inventoryPresent();
    await tool("mc_build",placed({blocks:[{pos:[0,0,0],block:"minecraft:chest[facing=west,type=left]"}]}));v=await verify(hp);check(()=>assert.equal(v.mismatchedCells,1));repaired=await repairExact(hp,v.comparisonId);check(()=>assert.equal(repaired.matched,true));await inventoryPresent();
    await tool("mc_command",{command:"item replace block 576 100 0 container.0 with minecraft:air"});
    await reset();
    // Connected differences are visible in exact mode and explicitly excluded only in placement mode.
    const panes={...placed({blocks:[{pos:[0,0,0],block:"minecraft:glass_pane[north=false,south=false,east=false,west=false]"},{pos:[1,0,0],block:"minecraft:glass_pane"},{pos:[3,0,0],block:"minecraft:oak_stairs[facing=north,shape=straight]"}]}),connect:true};
    const pp=await prepare(panes);await tool("mc_build",panes);v=await verify(pp);check(()=>assert.ok(v.mismatchedCells>0));let pv=await verify(pp,{mode:"placement"});check(()=>assert.equal(pv.matched,true));check(()=>assert.ok(pv.ignoredConnectionCells>0));
    await tool("mc_build",placed({blocks:[{pos:[3,0,0],block:"minecraft:oak_stairs[facing=south,shape=straight]"}]}));pv=await verify(pp,{mode:"placement"});check(()=>assert.equal(pv.mismatchedCells,1));repaired=await repairExact(pp,pv.comparisonId);check(()=>assert.equal(repaired.matched,true));
    await reset();
    // Frozen blueprint receipt remains valid after its source document is deleted.
    await tool("mc_blueprint",{action:"save",id:blueprint,document:{version:1,components:{unit:{palette:{wall:"minecraft:stone"},fills:[{from:[0,0,0],to:[2,0,2],block:"$wall"}],blocks:[{pos:[1,1,1],block:"minecraft:oak_stairs[facing=north]"}]}},instances:[{component:"unit",pos:[0,0,0],repeat:{count:2,step:[4,0,0]}}]}});saved=true;
    for(const rotation of [0,90,180,270])for(const mirror of ["none","x","z"]){
        const b={blueprint:{id:blueprint,palette:{wall:"minecraft:gold_block"}},transform:{origin,rotation,mirror},connect:false};const bp=await prepare(b);await tool("mc_build",b);v=await verify(bp);check(()=>assert.equal(v.matched,true));
        const pos=[576,100,0];await tool("mc_build",{blocks:[{pos,block:"minecraft:air"}],connect:false});v=await verify(bp);check(()=>assert.equal(v.mismatchedCells,1));repaired=await repairExact(bp,v.comparisonId,{snapshot:false});check(()=>assert.equal(repaired.matched,true));await reset();
    }
    const detached=await prepare({blueprint:{id:blueprint},transform:{origin},connect:false});await tool("mc_blueprint",{action:"delete",id:blueprint});saved=false;v=await verify(detached);repaired=await repairExact(detached,v.comparisonId,{snapshot:false});check(()=>assert.equal(repaired.matched,true));await reset();
    const flow=await prepare({...placed({blocks:[{pos:[0,0,0],block:"minecraft:water"}]}),liquids:"flow"});v=await verify(flow);await rejected("mc_repair",{planId:flow,comparisonId:v.comparisonId},/flowing-fluid/);
    await rejected("mc_verify",{action:"prepare",build:placed({fills:[{from:[0,0,0],to:[1,0,0],block:"minecraft:stone"}],blocks:[{pos:[3,0,0],block:"minecraft:no_such_block"}]})},/block/);
    const beforeDelete=await fingerprint();for(const id of plans)await tool("mc_verify",{action:"delete",planId:id});plans.clear();const afterDelete=await fingerprint();check(()=>assert.deepEqual(afterDelete,beforeDelete));
    console.log(JSON.stringify({checks,failures:0}));
}finally{
    if(forceAdded)await tool("mc_command",{command:"item replace block 576 100 0 container.0 with minecraft:air"});
    if(snapshot)await tool("mc_restore",{id:snapshot});if(forceAdded)await tool("mc_command",{command:"forceload remove 576 0"});for(const id of plans)await tool("mc_verify",{action:"delete",planId:id});if(saved)await tool("mc_blueprint",{action:"delete",id:blueprint});await client.close();plugin.close();
}
