#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in disposable-server test. Restores its region and removes its blueprint; no lifecycle management.
import assert from "node:assert/strict";
import fs from "node:fs";
import {randomUUID} from "node:crypto";
import {fileURLToPath} from "node:url";
import {Client} from "@modelcontextprotocol/client";
import {StdioClientTransport,getDefaultEnvironment} from "@modelcontextprotocol/client/stdio";
import {PluginClient} from "../dist/plugin-client.js";
assert.equal(process.env.ASHLAR_DISPOSABLE_TEST,"1");
const url = new URL(process.env.MC_PLUGIN_URL);
assert.ok(["127.0.0.1","localhost","[::1]"].includes(url.hostname));
assert.ok(process.env.MC_PLUGIN_TOKEN);
const plugin = new PluginClient({url:url.href,token:process.env.MC_PLUGIN_TOKEN,defaultTimeoutMs:120000});
const client = new Client({name:"preflight-acceptance",version:"1.0.0"});
const transport = new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL("../dist/cli.js",import.meta.url)),"--stdio"],
    env:{...getDefaultEnvironment(),MC_PLUGIN_URL:url.href,MC_PLUGIN_TOKEN:process.env.MC_PLUGIN_TOKEN}});
const origin=[384,100,0], from=[344,99,-40], to=[424,118,40];
const id="e2e_plan_"+randomUUID().replaceAll("-","");
let saved=false,snapshot,checks=0;
const outDir=process.env.ASHLAR_PREVIEW_OUTPUT;
const text=r=>r.content.filter(c=>c.type==="text").map(c=>c.text).join("\n");
function check(fn){checks++;fn();}
async function tool(name,args){const r=await client.callTool({name,arguments:args});assert.ok(!r.isError,text(r));return r;}
async function read(){return plugin.request("read_region",{from,to});}
async function snapshotIds(){return (await plugin.request("list_snapshots",{})).snapshots.map(s=>s.id).sort();}
async function fingerprint(){return {region:await read(),snapshots:await snapshotIds()};}
async function plan(build,options={image:false}){
    const before=await fingerprint();const result=await tool("mc_plan",{build,...options});const report=JSON.parse(text(result));
    check(()=>assert.equal(report.dryRun,true));
    const after=await fingerprint();check(()=>assert.deepEqual(after,before,"Planning must not change blocks, signs or snapshot IDs"));
    return {result,report};
}
function decode(r){const values=[];for(const [index,count] of r.runs)for(let i=0;i<count;i++)values.push(r.palette[index]);return values;}
async function parity(build,imageParity=false){
    const before=decode(await read());const {report,result}=await plan(build,imageParity?{image:true,preview:{view:"top",scale:8,grid:0}}:{image:false});
    const built=await tool("mc_build",build);assert.ok(!/WARNINGS/i.test(text(built)),text(built));
    const after=decode(await read());let changed=0,existing=0,cleared=0;
    for(let i=0;i<before.length;i++)if(before[i]!==after[i]){changed++;if(!before[i].startsWith("minecraft:air"))existing++;if(after[i]==="minecraft:air"&&before[i]!=="minecraft:air")cleared++;}
    check(()=>assert.equal(report.blockStateChanges,changed));check(()=>assert.equal(report.existingNonAirChanged,existing));check(()=>assert.equal(report.clearedCells,cleared));
    if(imageParity){
        const actual=await tool("mc_render",{from:report.from,to:report.to,view:"top",scale:8,grid:0});
        check(()=>assert.equal(result.content.find(c=>c.type==="image").data,actual.content.find(c=>c.type==="image").data,"Preview must match actual unconnected build rendering"));
    }
    await tool("mc_restore",{id:snapshot});
    return report;
}
const house={fills:[{from:[0,0,0],to:[4,3,4],block:"minecraft:stone",mode:"hollow"},{from:[0,3,0],to:[4,3,4],block:"minecraft:blue_concrete"}],
    blocks:[{pos:[2,1,0],block:"minecraft:oak_door[facing=south,half=lower,hinge=left]"},{pos:[2,2,0],block:"minecraft:oak_door[facing=south,half=upper,hinge=left]"},
        {pos:[0,1,2],block:"minecraft:glass"},{pos:[1,1,1],block:"minecraft:oak_sign[rotation=3]",sign:{front:["New"]}}],
    text:[{text:"A",pos:[7,1,4],block:"minecraft:gold_block",background:"minecraft:blue_concrete"}]};
const placed=b=>({...b,transform:{origin},connect:false});
plugin.start();
try{
    await client.connect(transport);assert.match(text(await tool("mc_players",{})),/No players online/);
    const catalog=await client.listTools();check(()=>assert.ok(catalog.tools.find(t=>t.name==="mc_plan").annotations.readOnlyHint));
    snapshot=(await plugin.request("snapshot",{from,to,label:"preflight acceptance"})).id;
    // Existing gold inside the planned hollow room, plus an existing sign outside it.
    await tool("mc_build",placed({blocks:[{pos:[2,1,2],block:"minecraft:gold_block"},{pos:[6,0,0],block:"minecraft:stone"},
        {pos:[6,1,0],block:"minecraft:oak_sign",sign:{front:["Existing"],waxed:true}}]}));
    for(const view of ["top","north","west","slice"]){
        const options={image:true,preview:{view,scale:16,grid:0,...(view==="slice"?{slice:{axis:"y",at:101}}:{})}};
        const {result,report}=await plan(placed(house),options);
        check(()=>assert.equal(report.valid,true));check(()=>assert.equal(report.strictSitePass,true));
        check(()=>assert.equal(report.existingNonAirChanged,1));check(()=>assert.equal(report.clearedCells,1));
        check(()=>assert.deepEqual(report.collisionSamples[0],{pos:[386,101,2],existing:"minecraft:gold_block",target:"minecraft:air",kind:"clear"}));
        check(()=>assert.ok(report.overlappingCells>=29));
        const image=result.content.find(c=>c.type==="image");check(()=>assert.ok(image));
        const png=Buffer.from(image.data,"base64");check(()=>assert.equal(png.subarray(1,4).toString(),"PNG"));
        if(outDir){fs.mkdirSync(outDir,{recursive:true});fs.writeFileSync(`${outDir}/preview-${view}.png`,png);}
    }
    // Bad data in a later phase must never allow earlier fills or snapshots to run.
    const failures=[
        {...house,blocks:[{pos:[0,1,0],block:"minecraft:not_a_block"}]},
        {...house,blocks:[{pos:[0,1,0],block:"minecraft:stone",sign:{front:["Invalid sign target"]}}]},
        {...house,blocks:[{pos:[0,1,0],block:"minecraft:oak_sign",sign:{front:["Bad color"],color:"not_a_color"}}]},
        {...house,blocks:[{pos:[0,1000,0],block:"minecraft:stone"}]},
        {fills:[{from:[0,0,0],to:[99,49,99],block:"minecraft:stone"}],blocks:[{pos:[0,1,0],block:"minecraft:stone"}]},
        {blocks:[{pos:[0,0,0],block:"minecraft:stone"},{pos:[1000000,0,1000000],block:"minecraft:stone"}]}
    ];
    for(const bad of failures){
        const before=await fingerprint();const r=await client.callTool({name:"mc_build",arguments:{...placed(bad),snapshot:true}});
        check(()=>assert.ok(r.isError,text(r)));const after=await fingerprint();check(()=>assert.deepEqual(after,before));
    }
    const missing=placed({fills:[{from:[0,0,0],to:[0,0,0],block:"minecraft:stone"}],blocks:[{pos:[0,1,0],block:"minecraft:oak_door[half=lower]"}]});
    const missingPlan=await plan(missing);check(()=>assert.equal(missingPlan.report.valid,false));check(()=>assert.equal(missingPlan.report.pairErrors,1));
    const beforeStrict=await fingerprint();const strict=await client.callTool({name:"mc_build",arguments:{...missing,preflight:true,snapshot:true}});
    check(()=>assert.ok(strict.isError));
    const afterStrict=await fingerprint();check(()=>assert.deepEqual(afterStrict,beforeStrict));
    const bed=await plan(placed({blocks:[{pos:[0,2,0],block:"minecraft:red_bed[facing=east,part=foot]"}]}));
    check(()=>assert.equal(bed.report.pairErrors,1));
    const unsupported=await plan(placed({blocks:[{pos:[10,5,10],block:"minecraft:oak_wall_sign[facing=east]",sign:{front:["Floating"]}}]}));
    check(()=>assert.equal(unsupported.report.supportWarnings,1));
    const supported=await plan(placed({blocks:[{pos:[9,5,10],block:"minecraft:stone"},{pos:[10,5,10],block:"minecraft:oak_wall_sign[facing=east]",sign:{front:["Supported"]}}]}));
    check(()=>assert.equal(supported.report.supportWarnings,0));
    const dryBefore=await fingerprint();const dry=await tool("mc_build",{...missing,dryRun:true,snapshot:true});
    check(()=>assert.equal(JSON.parse(text(dry)).valid,false));const dryAfter=await fingerprint();check(()=>assert.deepEqual(dryAfter,dryBefore));
    // Support removal must also flag an existing attached block outside the explicit plan bounds.
    await tool("mc_build",placed({blocks:[{pos:[9,5,10],block:"minecraft:stone"},{pos:[10,5,10],block:"minecraft:oak_wall_sign[facing=east]",sign:{front:["Existing"]}}]}));
    const removed=await plan(placed({blocks:[{pos:[9,5,10],block:"minecraft:air"}]}));
    check(()=>assert.ok(removed.report.issues.some(i=>i.kind==="support"&&i.pos.join(",")==="394,105,10")));
    await tool("mc_restore",{id:snapshot});
    // Compare all fill modes and sequential filters with actual unconnected execution.
    for(const mode of ["replace","keep","outline","hollow","walls"]){
        await tool("mc_build",placed({blocks:[{pos:[1,1,1],block:"minecraft:gold_block"}]}));
        await parity(placed({fills:[{from:[0,0,0],to:[2,2,2],block:"minecraft:stone",mode}]}));
    }
    await parity(placed({fills:[{from:[0,0,0],to:[2,2,2],block:"minecraft:stone"},
        {from:[0,0,0],to:[2,2,2],block:"minecraft:gold_block",filter:"minecraft:stone"},
        {from:[0,0,0],to:[2,2,2],block:"minecraft:dirt",mode:"keep"},
        {from:[0,0,0],to:[2,2,2],block:"minecraft:glass",filter:"minecraft:dirt"}]}));
    const document={version:1,components:{house},instances:[{component:"house",pos:[0,0,0],repeat:{count:2,step:[16,0,0]}}]};
    await tool("mc_blueprint",{action:"save",id,document});saved=true;
    for(const rotation of [0,90,180,270])for(const mirror of ["none","x","z"]){
        const report=await parity({blueprint:{id},transform:{origin,rotation,mirror},connect:false},true);
        check(()=>assert.equal(report.valid,true));check(()=>assert.equal(report.supportWarnings,0));
        console.log(`Checked planned blueprint ${rotation}/${mirror}`);
    }
    // A strict success executes only after a clean final-scene check.
    await tool("mc_build",{...placed(house),preflight:true});
    await tool("mc_restore",{id:snapshot});
    // Slice previews fit even when the full virtual envelope exceeds the image-read cap.
    // The larger area is read only and separately fingerprinted in two legal read-volume tiles.
    const large=placed({fills:[{from:[0,0,0],to:[79,39,79],block:"minecraft:stone",mode:"walls"}]});
    const readLarge=async()=>[await plugin.request("read_region",{from:origin,to:[463,119,79]}),await plugin.request("read_region",{from:[384,120,0],to:[463,139,79]})];
    const largeBefore=await readLarge();
    const full=await client.callTool({name:"mc_plan",arguments:{build:large}});check(()=>assert.ok(full.isError));
    const slice=await tool("mc_plan",{build:large,preview:{view:"slice",slice:{axis:"y",at:101},grid:0}});
    check(()=>assert.ok(slice.content.some(c=>c.type==="image")));
    const largeAfter=await readLarge();check(()=>assert.deepEqual(largeAfter,largeBefore));
    console.log(JSON.stringify({checks,failures:0,previews:outDir??null}));
}finally{
    try{if(snapshot)await tool("mc_restore",{id:snapshot});}
    finally{try{if(saved)await tool("mc_blueprint",{action:"delete",id});}finally{await client.close();plugin.close();}}
}
