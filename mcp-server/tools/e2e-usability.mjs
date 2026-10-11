#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in disposable-server acceptance for mc_command vanilla routing, blueprint tags/touches and the mc_build blueprint trace line; restores a pristine test region, never manages server lifecycle.
import assert from "node:assert/strict";
import {fileURLToPath} from "node:url";
import {Client} from "@modelcontextprotocol/client";
import {StdioClientTransport,getDefaultEnvironment} from "@modelcontextprotocol/client/stdio";
import {PluginClient} from "../dist/plugin-client.js";
assert.equal(process.env.ASHLAR_DISPOSABLE_TEST,"1");
const url=new URL(process.env.MC_PLUGIN_URL);assert.ok(["127.0.0.1","localhost","[::1]"].includes(url.hostname));assert.ok(process.env.MC_PLUGIN_TOKEN);
const plugin=new PluginClient({url:url.href,token:process.env.MC_PLUGIN_TOKEN,defaultTimeoutMs:300000});
const client=new Client({name:"usability-acceptance",version:"1.0.0"});
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

const ids=[];
plugin.start();
try{
    await client.connect(transport);const health=await plugin.request("health",{});assert.equal(health.onlinePlayers,0);assert.equal(health.queuedOperations,0);
    const base=await plugin.request("read_region",{from,to});assert.ok(base.palette.every(s=>s==="minecraft:air"),"Region must be pristine air");
    snapshot=(await plugin.request("snapshot",{from,to,label:"usability acceptance pristine region"})).id;
    const catalog=await client.listTools();
    check(()=>assert.ok(catalog.tools.find(t=>t.name==="mc_command").inputSchema.properties.vanilla));
    check(()=>assert.ok(catalog.tools.find(t=>t.name==="mc_blueprint").inputSchema.properties.action.enum.includes("touches")));
    // mc_command: no plugin here takes over a vanilla name, so there is no note; vanilla:true sends minecraft:<name>.
    let t=await command("time query gametime");
    check(()=>assert.match(t,/The game time is \d+/));check(()=>assert.doesNotMatch(t,/vanilla:true/));
    t=text(await tool("mc_command",{command:"/time query gametime",vanilla:true}));
    check(()=>assert.match(t,/The game time is \d+/));check(()=>assert.doesNotMatch(t,/Ran as/));
    await checkA(async()=>assert.match(await rejected("mc_command",{command:"plugins",vanilla:true}),/no vanilla command "plugins"; nothing was run/));
    await checkA(async()=>assert.match(await rejected("mc_command",{command:"minecraft:time query gametime",vanilla:true}),/already names a namespace/));
    t=await command("plugins");check(()=>assert.doesNotMatch(t,/vanilla:true/));
    // Blueprints with tags, in absolute coordinates inside the test region.
    const stamp=Date.now().toString(36);const mod=`e2e-${stamp}`;
    const tower={version:1,description:"usability e2e tower",tags:{module:mod,status:"draft",note:"e2e"},components:{t:{fills:[{from:[600,100,10],to:[602,104,12],block:"$wall",mode:"hollow"}]}},
        instances:[{component:"t",pos:[0,0,0]}]};
    const wall={version:1,tags:{module:mod,status:"approved"},components:{w:{fills:[{from:[540,100,-30],to:[549,101,-30],block:"stone_bricks"}]}},instances:[{component:"w",pos:[0,0,0]}]};
    const towerId=`e2etower${stamp}`,wallId=`e2ewall${stamp}`;
    for(const [id,doc] of [[towerId,tower],[wallId,wall]]){await tool("mc_blueprint",{action:"save",id,document:doc});ids.push(id);}
    let j=JSON.parse(text(await tool("mc_blueprint",{action:"list",tags:{module:mod}})));
    check(()=>assert.deepEqual(j.blueprints.map(b=>b.id).sort(),[towerId,wallId].sort()));
    j=JSON.parse(text(await tool("mc_blueprint",{action:"list",tags:{module:mod,status:"draft"}})));
    check(()=>assert.deepEqual(j.blueprints.map(b=>b.id),[towerId]));
    check(()=>assert.deepEqual(j.blueprints[0].tags,tower.tags));
    j=JSON.parse(text(await tool("mc_blueprint",{action:"touches",from:[595,95,5],to:[600,100,10]})));
    check(()=>assert.ok(j.blueprints.some(b=>b.id===towerId&&JSON.stringify(b.bounds)==='{"from":[600,100,10],"to":[602,104,12]}'),JSON.stringify(j)));
    check(()=>assert.ok(!j.blueprints.some(b=>b.id===wallId)));
    j=JSON.parse(text(await tool("mc_blueprint",{action:"touches",from:[603,100,10],to:[610,110,20]})));
    check(()=>assert.ok(!j.blueprints.some(b=>b.id===towerId)));
    await checkA(async()=>assert.match(await rejected("mc_blueprint",{action:"tag",id:towerId,tags:{status:"finished"}}),/status/));
    await checkA(async()=>assert.match(await rejected("mc_blueprint",{action:"save",id:`e2ebad${stamp}`,document:{...tower,tags:{owner:"x"}}}),/additional properties|unknown field 'owner'/));
    // A blueprint build ends with the trace line; then mark it built.
    await command(`forceload add ${from[0]} ${from[2]} ${to[0]} ${to[2]}`);forced=true;
    t=await build({blueprint:{id:towerId,palette:{wall:"stone"}},label:"usability e2e tower"});
    check(()=>assert.match(t,new RegExp(`\\nBlueprint: ${towerId} \\(module=${mod}, status=draft\\); journal (jrn-[0-9a-f-]+)\\.$`),t));
    const jid=t.match(/Journal: (jrn-[0-9a-f-]+)/)[1];check(()=>assert.ok(t.endsWith(`journal ${jid}.`)));
    t=text(await tool("mc_build",{blueprint:{id:towerId,palette:{wall:"stone"}},dryRun:true}));
    check(()=>assert.doesNotMatch(t,/Blueprint: /));
    t=text(await tool("mc_blueprint",{action:"tag",id:towerId,tags:{status:"built",note:null,undo:wallId}}));
    check(()=>assert.deepEqual(JSON.parse(t).tags,{module:mod,status:"built",undo:wallId}));
    t=await build({blueprint:{id:towerId,palette:{wall:"stone"}},journal:false});
    check(()=>assert.match(t,new RegExp(`Blueprint: ${towerId} \\(module=${mod}, status=built, undo=${wallId}\\); no journal\\.$`),t));
    const pal=(await plugin.request("read_region",{from:[600,100,10],to:[602,104,12]})).palette;
    check(()=>assert.ok(pal.includes("minecraft:stone"),JSON.stringify(pal)));
    console.log(`usability acceptance passed: ${checks} checks`);
}finally{
    if(snapshot&&!restored){const r=await client.callTool({name:"mc_restore",arguments:{id:snapshot}},undefined,{timeout:300000});journal(text(r));restored=!r.isError;}
    if(forced)await client.callTool({name:"mc_command",arguments:{command:`forceload remove ${from[0]} ${from[2]} ${to[0]} ${to[2]}`}});
    for(const id of created)await client.callTool({name:"mc_snapshot",arguments:{action:"journal-delete",id}});
    for(const id of ids)await client.callTool({name:"mc_blueprint",arguments:{action:"delete",id}});
    await client.close();plugin.close();
}
