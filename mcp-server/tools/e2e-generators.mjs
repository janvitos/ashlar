#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in disposable architectural acceptance. No lifecycle management; cleans documents/world by default.
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
const client=new Client({name:'generator-acceptance',version:'1.0.0'});
const transport=new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL('../dist/cli.js',import.meta.url)),'--stdio'],env:{...getDefaultEnvironment(),MC_PLUGIN_URL:url.href,MC_PLUGIN_TOKEN:process.env.MC_PLUGIN_TOKEN}});
const from=[896,99,-40],to=[976,125,40],prefix='e2e_gen_'+randomUUID().replaceAll('-','').slice(0,12),out=process.env.ASHLAR_GENERATOR_OUTPUT||'/tmp/ashlar-step6-previews';
const ids=[],docs=[];let snapshot,checks=0;const keep=process.env.ASHLAR_TEST_KEEP_BLUEPRINT==='1',keepScene=process.env.ASHLAR_TEST_KEEP_SCENE==='1';
const text=r=>r.content.filter(c=>c.type==='text').map(c=>c.text).join('\n');
function check(fn){checks++;fn();}
async function tool(name,args){const r=await client.callTool({name,arguments:args});assert.ok(!r.isError,text(r));if(name==='mc_build')assert.ok(!/WARNINGS/.test(text(r)),text(r));return r;}
async function read(a=from,b=to){return plugin.request('read_region',{from:a,to:b});}
function decode(r,a,b){const m=new Map();let run=0,left=r.runs[0][1];for(let y=a[1];y<=b[1];y++)for(let z=a[2];z<=b[2];z++)for(let x=a[0];x<=b[0];x++){const state=r.palette[r.runs[run][0]];if(state!=='minecraft:air')m.set([x,y,z].join(','),state);if(--left===0&&run+1<r.runs.length)left=r.runs[++run][1];}return m;}
function expand(doc){const m=new Map();for(const c of Object.values(doc.components))for(const f of c.fills||[]){const match=f.block.match(/^\$(\w+)(\[.*\])?$/);let raw=doc.palette[match[1]],props={};const split=s=>{const i=s.indexOf('[');if(i>=0)for(const pair of s.slice(i+1,-1).split(',')){const[k,v]=pair.split('=');props[k]=v;}return i<0?s:s.slice(0,i);};const material=split(raw);if(match[2])split('x'+match[2]);for(let x=f.from[0];x<=f.to[0];x++)for(let y=f.from[1];y<=f.to[1];y++)for(let z=f.from[2];z<=f.to[2];z++)m.set([x,y,z].join(','),{material,props});}return m;}
function compare(expected,actual,origin){check(()=>assert.equal(actual.size,expected.size));for(const[k,e]of expected){const p=k.split(',').map((n,i)=>Number(n)+origin[i]);const state=actual.get(p.join(','));check(()=>assert.ok(state));check(()=>assert.equal(state.split('[')[0],e.material));for(const[prop,value]of Object.entries(e.props))check(()=>assert.ok(state.includes(`${prop}=${value}`),`${p}: ${state} expected ${prop}=${value}`));}}
const fixtures=[
 [{kind:'roof',style:'gable',width:9,depth:11,gableInfill:true},[902,100,-30]],
 [{kind:'roof',style:'hip',width:9,depth:11,materials:{full:'minecraft:stone_bricks',stairs:'minecraft:stone_brick_stairs[half=top,shape=inner_left]',slab:'minecraft:stone_brick_slab[type=top]'}},[922,100,-30]],
 [{kind:'roof',style:'shed',width:5,depth:7},[942,100,-30]],
 [{kind:'arch',style:'round',width:9,depth:2},[902,100,-8]],
 [{kind:'arch',style:'pointed',width:11,depth:2},[922,100,-8]],
 [{kind:'tower',diameter:13,height:12},[946,100,-8]],
 [{kind:'stairs',steps:7,width:3},[902,100,12]],
 [{kind:'stairs',style:'switchback',steps:6,width:3},[922,100,12]]
];
plugin.start();
try{
 await client.connect(transport);const health=await plugin.request('health',{});assert.equal(health.onlinePlayers,0);assert.equal(health.queuedOperations,0);
 const pristine=await read();assert.ok(pristine.palette.every(s=>s==='minecraft:air'));assert.equal(pristine.signs.length,0);
 snapshot=(await plugin.request('snapshot',{from,to,label:'architectural generator acceptance'})).id;
 for(let i=0;i<fixtures.length;i++){
  const[generator,origin]=fixtures[i],id=prefix+'_'+i;ids.push(id);
  const result=JSON.parse(text(await tool('mc_blueprint',{action:'generate',id,generator})));
  check(()=>assert.ok(result.generator.operations<=10000));check(()=>assert.ok(result.generator.requestedCells<=200000));
  const doc=JSON.parse(text(await tool('mc_blueprint',{action:'get',id})));check(()=>assert.equal(doc.version,1));
  docs.push(doc);const unchanged=await read();check(()=>assert.deepEqual(unchanged,pristine));
 }
 // Reject before replacing a valid existing document or touching the world.
 const existing=docs[0];
 for(const bad of [{kind:'roof',width:2},{kind:'arch',width:8},{kind:'roof',materials:{stairs:'minecraft:stone'}},{kind:'roof',materials:{full:'minecraft:air'}},{kind:'roof',materials:{slab:'minecraft:oak_slab[bad=value]'}},{kind:'tower',height:2.5},{kind:'roof',unknown:1},{kind:'stairs',style:'switchback',width:16,steps:64,gap:8,landing:16}]){
  const r=await client.callTool({name:'mc_blueprint',arguments:{action:'generate',id:ids[0],generator:bad,overwrite:true}});check(()=>assert.equal(r.isError,true));
  const after=JSON.parse(text(await tool('mc_blueprint',{action:'get',id:ids[0]})));check(()=>assert.deepEqual(after,existing));
 }
 const duplicate=await client.callTool({name:'mc_blueprint',arguments:{action:'generate',id:ids[0],generator:{kind:'roof'}}});check(()=>assert.equal(duplicate.isError,true));
 for(let i=0;i<fixtures.length;i++){
  const origin=fixtures[i][1];await tool('mc_build',{blueprint:{id:ids[i]},transform:{origin},connect:false});
  compare(expand(docs[i]),decode(await read(),from,to),origin);await tool('mc_restore',{id:snapshot});
 }
 // All project rotations/mirrors preserve explicit hip-corner handedness.
 for(const rotation of [0,90,180,270])for(const mirror of ['none','x','z']){
  const origin=[936,100,0];await tool('mc_build',{blueprint:{id:ids[1]},transform:{origin,rotation,mirror},connect:false});
  const expected=new Map();const rotate=(p)=>{let[x,y,z]=p;if(mirror==='x')x=-x;if(mirror==='z')z=-z;for(let n=0;n<rotation;n+=90)[x,z]=[-z,x];return[x,y,z];};
  for(const[k,e]of expand(docs[1])){const props={...e.props};if(props.facing){const v={north:[0,0,-1],east:[1,0,0],south:[0,0,1],west:[-1,0,0]};const target=rotate(v[props.facing]).join(',');props.facing=Object.keys(v).find(d=>v[d].join(',')===target);}if(mirror!=='none'&&props.shape)props.shape=props.shape.replace(/left|right/g,s=>s==='left'?'right':'left');expected.set(rotate(k.split(',').map(Number)).join(','),{material:e.material,props});}
  compare(expected,decode(await read(),from,to),origin);await tool('mc_restore',{id:snapshot});
 }
 const gallery={version:1,components:{floor:{fills:[{from:[0,-1,0],to:[64,-1,61],block:'minecraft:white_concrete'}]}},instances:[{component:'floor',pos:[0,0,0]}]};
 for(let i=0;i<docs.length;i++){const c=Object.values(docs[i].components)[0];gallery.components['part_'+i]={...c,palette:docs[i].palette};gallery.instances.push({component:'part_'+i,pos:[fixtures[i][1][0]-896,0,fixtures[i][1][2]+40]});}
 const galleryId=prefix+'_gallery';ids.push(galleryId);await tool('mc_blueprint',{action:'save',id:galleryId,document:gallery});
 const build={blueprint:{id:galleryId},transform:{origin:[896,100,-40]},connect:false};
 const before=await read();const plan=await tool('mc_plan',{build,preview:{view:'isometric',camera:{azimuth:135,elevation:35},scale:16,grid:0}});const after=await read();check(()=>assert.deepEqual(after,before));
 await tool('mc_build',build);const report=JSON.parse(text(plan));const r=await tool('mc_render',{from:report.from,to:report.to,view:'isometric',camera:{azimuth:135,elevation:35},scale:16,grid:0});check(()=>assert.equal(plan.content.find(c=>c.type==='image').data,r.content.find(c=>c.type==='image').data));
 fs.mkdirSync(out,{recursive:true});fs.writeFileSync(`${out}/architectural-gallery.png`,Buffer.from(r.content.find(c=>c.type==='image').data,'base64'));
 // Targeted close-up: the broad gallery foreshortens roof slopes and corner detail.
 const close=await tool('mc_render',{from:[922,100,-30],to:[930,104,-20],view:'isometric',camera:{azimuth:135,elevation:45},scale:16,grid:0});fs.writeFileSync(`${out}/hip-closeup.png`,Buffer.from(close.content.find(c=>c.type==='image').data,'base64'));
 console.log(JSON.stringify({checks,failures:0,images:out,kept:keep,keptScene:keepScene,snapshot,ids}));
}finally{if(snapshot&&!keepScene)await tool('mc_restore',{id:snapshot});if(!keep)for(const id of ids){const listed=JSON.parse(text(await tool('mc_blueprint',{action:'list'})));if(JSON.stringify(listed).includes(id))await tool('mc_blueprint',{action:'delete',id});}await client.close();plugin.close();}
