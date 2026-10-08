#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Disposable opt-in terrain acceptance, no lifecycle management. Restores world/deletes documents.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {Client} from '@modelcontextprotocol/client';
import {StdioClientTransport,getDefaultEnvironment} from '@modelcontextprotocol/client/stdio';
import {PluginClient} from '../dist/plugin-client.js';
assert.equal(process.env.ASHLAR_DISPOSABLE_TEST,'1');assert.ok(process.env.MC_PLUGIN_TOKEN);
const url=new URL(process.env.MC_PLUGIN_URL);assert.ok(['127.0.0.1','localhost','[::1]'].includes(url.hostname));
const plugin=new PluginClient({url:url.href,token:process.env.MC_PLUGIN_TOKEN,defaultTimeoutMs:120000});
const client=new Client({name:'terrain-fit-acceptance',version:'1.0.0'});
const transport=new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL('../dist/cli.js',import.meta.url)),'--stdio'],env:{...getDefaultEnvironment(),MC_PLUGIN_URL:url.href,MC_PLUGIN_TOKEN:process.env.MC_PLUGIN_TOKEN}});
const from=[1024,99,-40],to=[1104,125,40],prefix='e2e_fit_'+randomUUID().replaceAll('-','').slice(0,12),ids=[],out=process.env.ASHLAR_TERRAIN_OUTPUT||'/tmp/ashlar-step7-previews';
let snapshot,checks=0;const text=r=>r.content.filter(c=>c.type==='text').map(c=>c.text).join('\n');function check(fn){checks++;fn();}
async function tool(name,args){const r=await client.callTool({name,arguments:args});assert.ok(!r.isError,text(r));if(name==='mc_build')assert.ok(!/WARNINGS/.test(text(r)),text(r));return r;}
async function read(){return plugin.request('read_region',{from,to});}
function decode(r){const states=new Map();let run=0,left=r.runs[0][1];for(let y=from[1];y<=to[1];y++)for(let z=from[2];z<=to[2];z++)for(let x=from[0];x<=to[0];x++){states.set([x,y,z].join(','),r.palette[r.runs[run][0]]);if(--left===0&&run+1<r.runs.length)left=r.runs[++run][1];}return states;}
async function fit(id,site){const before=await read(),snaps=await plugin.request('list_snapshots',{});const r=JSON.parse(text(await tool('mc_blueprint',{action:'fit',id,site})));const after=await read(),later=await plugin.request('list_snapshots',{});check(()=>assert.deepEqual(after,before));check(()=>assert.deepEqual(later,snaps));return r;}
async function rejected(args){const before=await read(),listed=await plugin.request('tool_call',{name:'mc_blueprint',args:{action:'list'}});const r=await client.callTool({name:'mc_blueprint',arguments:{action:'fit',...args}});check(()=>assert.equal(r.isError,true));const after=await read(),listAfter=await plugin.request('tool_call',{name:'mc_blueprint',args:{action:'list'}});check(()=>assert.deepEqual(after,before));check(()=>assert.deepEqual(listAfter,listed));}
async function comparePlacement(id,report){const before=decode(await read());const doc=JSON.parse(text(await tool('mc_blueprint',{action:'get',id})));const build={...report.site.build,snapshot:false};
 await tool('mc_build',build);const after=decode(await read());const origin=report.site.origin;const expected=new Map();
 for(const f of doc.components.foundation.fills){let raw=doc.palette[f.block.match(/^\$(\w+)/)[1]];let props={};const split=s=>{const i=s.indexOf('[');if(i>=0)for(const pair of s.slice(i+1,-1).split(',')){const[k,v]=pair.split('=');props[k]=v;}return i<0?s:s.slice(0,i);};const material=split(raw),suffix=f.block.indexOf('[');if(suffix>=0)split('x'+f.block.slice(suffix));
  for(let x=f.from[0];x<=f.to[0];x++)for(let y=f.from[1];y<=f.to[1];y++)for(let z=f.from[2];z<=f.to[2];z++){const key=[x+origin[0],y+origin[1],z+origin[2]].join(',');if(before.get(key)===f.filter)expected.set(key,{material,props});}
 }
 for(const[k,state]of before){if(!expected.has(k)){check(()=>assert.equal(after.get(k),state));continue;}const e=expected.get(k),actual=after.get(k);check(()=>assert.equal(actual.split('[')[0],e.material));for(const[prop,value]of Object.entries(e.props))check(()=>assert.ok(actual.includes(`${prop}=${value}`)));}
 return expected;
}
const terrain={fills:[{from:[1026,100,-36],to:[1065,100,20],block:'minecraft:stone'},{from:[1068,100,-36],to:[1098,100,20],block:'minecraft:stone'}]};
for(let x=1026;x<=1065;x++)terrain.fills.push({from:[x,101,-36],to:[x,101+(x-1026)%4,-1],block:'minecraft:dirt'});
plugin.start();
try{
 await client.connect(transport);const health=await plugin.request('health',{});assert.equal(health.onlinePlayers,0);assert.equal(health.queuedOperations,0);const pristine=await read();assert.ok(pristine.palette.every(s=>s==='minecraft:air'));snapshot=(await plugin.request('snapshot',{from,to,label:'terrain fit acceptance pristine'})).id;
 await tool('mc_build',terrain);
 const solidId=prefix+'_solid',pierId=prefix+'_piers';ids.push(solidId,pierId);
 const solidSite={from:[1036,-20],to:[1044,-12],floorY:108,maxDepth:8,entrance:{facing:'south',width:3,maxRun:8}};
 const solid=await fit(solidId,solidSite);check(()=>assert.equal(solid.site.supportColumns,81));check(()=>assert.ok(solid.site.entranceRows>1));
 const piers=await fit(pierId,{from:[1074,-20],to:[1082,-12],floorY:108,maxDepth:8,mode:'piers',spacing:4,entrance:{facing:'south',maxRun:8}});check(()=>assert.equal(piers.site.supportColumns,9));
 // Generation does not replace an existing document, even for invalid native states.
 for(const bad of [{...solidSite,floorY:101},{...solidSite,maxDepth:1,entrance:{maxRun:1}},{...solidSite,materials:{full:'minecraft:sand'}},{...solidSite,materials:{stairs:'minecraft:stone'}},{...solidSite,from:[1036.5,-20]},{...solidSite,unknown:true},{...solidSite,world:'missing_world'}])await rejected({id:solidId,site:bad,overwrite:true});
 const preserved=JSON.parse(text(await tool('mc_blueprint',{action:'get',id:solidId})));check(()=>assert.ok(preserved.constraints.fittedSite));
 // Air-only plans preserve a later protected edit instead of overwriting it.
 await tool('mc_build',{blocks:[{pos:[1036,107,-20],block:'minecraft:chest[facing=south]'}],connect:false});
 await comparePlacement(solidId,solid);const states=decode(await read());check(()=>assert.ok(states.get('1036,107,-20').startsWith('minecraft:chest')));
 await tool('mc_restore',{id:snapshot});await tool('mc_build',terrain);
 await comparePlacement(pierId,piers);
 // Hazardous anchors/occupied headroom reject with no file or world mutation.
 for(const state of ['minecraft:water','minecraft:lava','minecraft:short_grass','minecraft:chest','minecraft:oak_log','minecraft:sand']){
  await tool('mc_build',{blocks:[{pos:[1036,104,-20],block:state}],connect:false});await rejected({id:solidId,site:solidSite,overwrite:true});await tool('mc_build',{blocks:[{pos:[1036,104,-20],block:'minecraft:air'}],connect:false});
 }
 await tool('mc_build',{blocks:[{pos:[1036,109,-20],block:'minecraft:stone'}],connect:false});await rejected({id:solidId,site:solidSite,overwrite:true});await tool('mc_build',{blocks:[{pos:[1036,109,-20],block:'minecraft:air'}],connect:false});
 // Four bounded entry directions on a deliberately flat test patch reach equal landings.
 await tool('mc_build',{fills:[{from:[1048,101,-8],to:[1064,110,14],block:'minecraft:air'}]});
 for(const facing of ['north','south','east','west']){const id=prefix+'_'+facing;ids.push(id);const r=await fit(id,{from:[1051,1],to:[1055,5],floorY:108,maxDepth:8,entrance:{facing,width:1,maxRun:8}});check(()=>assert.equal(r.site.entranceRows,8));}
 await tool('mc_restore',{id:snapshot});await tool('mc_build',terrain);
 // One appearance image of two fitted decks and their entrances; static plan parity is opt-in testing only.
 const build={...solid.site.build,snapshot:false};const before=await read();const plan=await tool('mc_plan',{build,preview:{view:'isometric',camera:{azimuth:135,elevation:35},scale:16,grid:0}});const after=await read();check(()=>assert.deepEqual(after,before));await tool('mc_build',build);const p=JSON.parse(text(plan));const live=await tool('mc_render',{from:p.from,to:p.to,view:'isometric',camera:{azimuth:135,elevation:35},scale:16,grid:0});check(()=>assert.equal(plan.content.find(c=>c.type==='image').data,live.content.find(c=>c.type==='image').data));
 await tool('mc_build',{...piers.site.build,snapshot:false});fs.mkdirSync(out,{recursive:true});
 const gallery=await tool('mc_render',{from:[1034,99,-22],to:[1084,110,0],view:'isometric',camera:{azimuth:135,elevation:35},scale:16,grid:0});fs.writeFileSync(`${out}/fitted-foundations.png`,Buffer.from(gallery.content.find(c=>c.type==='image').data,'base64'));
 console.log(JSON.stringify({checks,failures:0,images:out}));
}finally{if(snapshot)await tool('mc_restore',{id:snapshot});for(const id of ids){const listed=JSON.parse(text(await tool('mc_blueprint',{action:'list'})));if(JSON.stringify(listed).includes(id))await tool('mc_blueprint',{action:'delete',id});}await client.close();plugin.close();}
