// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RegionData;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;

/** Pure conservative site fitting; never clears or replaces observed terrain. */
public final class TerrainFitter {
    private TerrainFitter() {}
    private static final Set<String> ANCHORS = Set.of("stone", "granite", "diorite", "andesite", "deepslate", "tuff", "calcite", "dirt", "grass_block", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "clay", "packed_mud", "sandstone", "red_sandstone", "moss_block", "snow_block", "ice", "packed_ice", "blue_ice", "end_stone", "netherrack", "basalt", "smooth_basalt", "blackstone", "obsidian", "crying_obsidian");
    public record Entrance(String facing, int width, int offset, int maxRun) {}
    public record Request(int x0, int z0, int x1, int z1, int floorY, int maxDepth, String mode,
            int spacing, Entrance entrance, String full, String stairs) {
        public int deckY() { return floorY - 1; }
        public Region readBounds() {
            int a=x0,b=z0,c=x1,d=z1;
            if(entrance!=null) switch(entrance.facing) {
                case "north" -> b-=entrance.maxRun;
                case "south" -> d+=entrance.maxRun;
                case "west" -> a-=entrance.maxRun;
                case "east" -> c+=entrance.maxRun;
                default -> throw new IllegalStateException();
            }
            return new Region(a,deckY()-maxDepth,b,c,deckY()+2,d);
        }
    }
    public record Output(JsonObject document, JsonObject summary) {}

    public static Request parse(JsonObject s) {
        BlueprintCompiler.fields(s,Set.of("world","from","to","floorY","maxDepth","mode","spacing","entrance","materials"),"site");
        int[] a=point2(s,"from"),b=point2(s,"to");
        int x0=Math.min(a[0],b[0]),z0=Math.min(a[1],b[1]),x1=Math.max(a[0],b[0]),z1=Math.max(a[1],b[1]);
        int w=x1-x0+1,d=z1-z0+1;
        if(w>64||d>64)throw new ToolArgError("site footprint dimensions must be 1-64");
        if(!s.has("floorY"))throw new ToolArgError("site.floorY is required: walking level selected from a recent survey/design");
        int y=BuildTransform.strictInt(s.get("floorY"),"site.floorY");
        int depth=integer(s,"maxDepth",32,1,64),spacing=integer(s,"spacing",4,1,16);
        String mode=ArgParse.has(s,"mode")?ArgParse.requireEnum(s,"mode",List.of("solid","piers")):"solid";
        if(s.has("spacing")&&!mode.equals("piers"))throw new ToolArgError("spacing applies only to piers");
        if((long)y-depth-1<Integer.MIN_VALUE||(long)y+1>Integer.MAX_VALUE)throw new ToolArgError("site Y range overflows");
        Entrance e=null;
        if(s.has("entrance")) {
            JsonObject j=ArgParse.requireObject(s.get("entrance"),"entrance");
            BlueprintCompiler.fields(j,Set.of("facing","width","offset","maxRun"),"entrance");
            String facing=ArgParse.has(j,"facing")?ArgParse.requireEnum(j,"facing",List.of("north","south","east","west")):"south";
            int span=facing.equals("north")||facing.equals("south")?w:d;
            int ew=integer(j,"width",Math.min(3,span),1,Math.min(16,span));
            int offset=integer(j,"offset",(span-ew)/2,0,span-ew);
            int run=integer(j,"maxRun",Math.min(16,depth),1,Math.min(32,depth));
            e=new Entrance(facing,ew,offset,run);
        }
        String full="minecraft:stone_bricks",stairs="minecraft:stone_brick_stairs";
        if(s.has("materials")) {
            JsonObject m=ArgParse.requireObject(s.get("materials"),"site.materials");
            BlueprintCompiler.fields(m,Set.of("full","stairs"),"site.materials");
            if(m.has("full"))full=ArgParse.requireString(m,"full");
            if(m.has("stairs"))stairs=ArgParse.requireString(m,"stairs");
        }
        Request r=new Request(x0,z0,x1,z1,y,depth,mode,spacing,e,full,stairs);
        Region read=r.readBounds();
        if(Math.abs((long)read.minX())>=30_000_000||Math.abs((long)read.maxX())>=30_000_000||Math.abs((long)read.minZ())>=30_000_000||Math.abs((long)read.maxZ())>=30_000_000)throw new ToolArgError("site bounds must stay inside horizontal +/-30000000");
        if(read.volume()>200_000)throw new ToolArgError("site capture exceeds 200000 cells; reduce footprint/depth/run");
        return r;
    }
    private static int[] point2(JsonObject s,String field) {
        JsonArray a=ArgParse.requireArray(s,field);
        if(a.size()!=2)throw new ToolArgError("site."+field+" must be [x,z]");
        int[] p={BuildTransform.strictInt(a.get(0),field),BuildTransform.strictInt(a.get(1),field)};
        for(int n:p)if(Math.abs((long)n)>=30_000_000)throw new ToolArgError("site coordinates must be inside horizontal +/-30000000");
        return p;
    }
    private static int integer(JsonObject s,String key,int fallback,int min,int max) {
        int n=s.has(key)?BuildTransform.strictInt(s.get(key),key):fallback;
        if(n<min||n>max)throw new ToolArgError("site."+key+" must be "+min+"-"+max);
        return n;
    }

    public static Output fit(Request r,RegionData data) {
        if(!data.region().equals(r.readBounds())||data.volume()!=data.region().volume())throw new ToolArgError("terrain capture does not match requested bounds");
        Grid grid=new Grid(data);Builder b=new Builder(r,grid);
        int supports=0,minGround=Integer.MAX_VALUE,maxGround=Integer.MIN_VALUE;
        for(int z=r.z0;z<=r.z1;z++)for(int x=r.x0;x<=r.x1;x++) {
            requireHeadroom(grid,x,r.deckY(),z);
            boolean pier=r.mode.equals("solid")||((x-r.x0)%r.spacing==0||x==r.x1)&&((z-r.z0)%r.spacing==0||z==r.z1);
            if(pier) {
                int ground=anchor(grid,x,z,r.deckY(),r);
                b.column(x,z,ground+1,r.deckY(),"$full");supports++;
                minGround=Math.min(minGround,ground);maxGround=Math.max(maxGround,ground);
            } else if(!air(grid.at(x,r.deckY(),z))) {
                if(!natural(grid.at(x,r.deckY(),z)))reject("occupied deck",x,r.deckY(),z,grid.at(x,r.deckY(),z));
            } else b.column(x,z,r.deckY(),r.deckY(),"$full");
        }
        int rows=0;
        if(r.entrance!=null) {
            Entrance e=r.entrance;boolean landed=false;
            for(int row=0;row<e.maxRun;row++) {
                int base=r.deckY()-row,max=Integer.MIN_VALUE;int[] ground=new int[e.width];
                for(int lane=0;lane<e.width;lane++) {
                    int[] p=entryPoint(r,row,lane);ground[lane]=anchor(grid,p[0],p[1],r.deckY(),r);max=Math.max(max,ground[lane]);
                }
                if(max>base)throw new ToolArgError("entrance meets terrain above its descending plane; choose another facing/floorY or explicitly handle excavation");
                for(int lane=0;lane<e.width;lane++) {
                    int[] p=entryPoint(r,row,lane);requireHeadroom(grid,p[0],base,p[1]);
                    if(max==base)b.column(p[0],p[1],ground[lane]+1,base,"$full");
                    else {
                        b.column(p[0],p[1],ground[lane]+1,base-1,"$full");
                        b.column(p[0],p[1],base,base,"$stairs[facing="+opposite(e.facing)+",half=bottom,shape=straight]");
                    }
                }
                rows=row+1;if(max==base){landed=true;break;}
            }
            if(!landed)throw new ToolArgError("entrance cannot reach a landing within maxRun; choose a longer bounded run or a lower floorY");
        }
        Output result=b.finish();JsonObject report=result.summary;
        report.addProperty("floorY",r.floorY);report.addProperty("maxDepth",r.maxDepth);
        report.addProperty("maximumSupportHeight",r.deckY()-minGround);
        report.addProperty("supportColumns",supports);report.addProperty("minimumAnchorY",minGround);report.addProperty("maximumAnchorY",maxGround);report.addProperty("entranceRows",rows);
        report.addProperty("siteSpecific",true);report.addProperty("assumptions","Observed bounded natural full-block anchors only. Air-filtered additive deck/supports; no vegetation, liquids, excavation or structure/NBT replacement. Footprint deck and entry headroom checked at capture time. Piers are schematic support spacing, not structural engineering. No world lock: later edits can make supports skip or headroom unsafe; inspect concrete changes, do not repeat full scans by default. Use original world/origin; relocating a fitted document is not terrain-aware.");
        return result;
    }
    private static void requireHeadroom(Grid g,int x,int base,int z) {
        for(int y=base+1;y<=base+2;y++)if(!air(g.at(x,y,z)))reject("occupied headroom",x,y,z,g.at(x,y,z));
    }
    private static int anchor(Grid g,int x,int z,int top,Request r) {
        for(int y=top;y>=r.deckY()-r.maxDepth;y--) {
            String state=g.at(x,y,z);if(air(state))continue;
            if(!natural(state))reject("unsupported/liquid/vegetation/protected anchor",x,y,z,state);
            return y;
        }
        throw new ToolArgError("no natural anchor within maxDepth at ["+x+","+z+"]");
    }
    private static void reject(String reason,int x,int y,int z,String state) {throw new ToolArgError("terrain fit rejected: "+reason+" at ["+x+","+y+","+z+"]: "+state);}
    private static boolean air(String s) {return s.equals("minecraft:air")||s.equals("minecraft:cave_air")||s.equals("minecraft:void_air");}
    private static boolean natural(String s) {int i=s.indexOf('[');String id=i<0?s:s.substring(0,i);return id.startsWith("minecraft:")&&ANCHORS.contains(id.substring(10));}
    private static String opposite(String f) {return switch(f){case "north"->"south";case "south"->"north";case "east"->"west";case "west"->"east";default->throw new IllegalStateException();};}
    private static int[] entryPoint(Request r,int row,int lane) {
        Entrance e=r.entrance;return switch(e.facing) {
            case "north" ->new int[]{r.x0+e.offset+lane,r.z0-row-1};
            case "south" ->new int[]{r.x0+e.offset+lane,r.z1+row+1};
            case "west" ->new int[]{r.x0-row-1,r.z0+e.offset+lane};
            case "east" ->new int[]{r.x1+row+1,r.z0+e.offset+lane};
            default ->throw new IllegalStateException();
        };
    }
    private static final class Grid {
        final Region bounds;final int dx,dz;final String[] states;
        Grid(RegionData data) {bounds=data.region();dx=bounds.maxX()-bounds.minX()+1;dz=bounds.maxZ()-bounds.minZ()+1;states=new String[(int)data.volume()];int i=0;for(var it=data.iterator();it.hasNext();)states[i++]=it.next();}
        String at(int x,int y,int z) {if(x<bounds.minX()||x>bounds.maxX()||y<bounds.minY()||y>bounds.maxY()||z<bounds.minZ()||z>bounds.maxZ())throw new ToolArgError("terrain query outside capture");return states[((y-bounds.minY())*dz+z-bounds.minZ())*dx+x-bounds.minX()];}
    }
    private static final class Builder {
        final Request r;final Grid grid;final JsonArray fills=new JsonArray();long visits;int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        Builder(Request r,Grid grid){this.r=r;this.grid=grid;}
        void column(int x,int z,int lo,int hi,String block) {
            if(lo>hi)return;
            int start=lo;String filter=grid.at(x,lo,z);
            for(int y=lo;y<=hi;y++) {
                String current=grid.at(x,y,z);
                if(!air(current))reject("occupied proposed support/deck",x,y,z,current);
                if(!current.equals(filter)){emit(x,z,start,y-1,block,filter);start=y;filter=current;}
            }
            emit(x,z,start,hi,block,filter);
        }
        void emit(int x,int z,int lo,int hi,String block,String filter) {
            visits+=(long)hi-lo+1;
            if(visits>200_000||fills.size()>=10000)throw new ToolArgError("fitted geometry exceeds operation/requested-cell limits");
            minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,lo);maxY=Math.max(maxY,hi);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);
            if(!fills.isEmpty()) {
                JsonObject last=fills.get(fills.size()-1).getAsJsonObject();JsonArray a=last.getAsJsonArray("from"),b=last.getAsJsonArray("to");
                if(last.get("block").getAsString().equals(block)&&last.get("filter").getAsString().equals(filter)
                        &&b.get(0).getAsInt()+1==x-r.x0&&a.get(1).getAsInt()==lo-r.floorY&&b.get(1).getAsInt()==hi-r.floorY
                        &&a.get(2).getAsInt()==z-r.z0&&b.get(2).getAsInt()==z-r.z0) {b.set(0,new com.google.gson.JsonPrimitive(x-r.x0));return;}
            }
            JsonObject f=new JsonObject();f.add("from",point(x-r.x0,lo-r.floorY,z-r.z0));f.add("to",point(x-r.x0,hi-r.floorY,z-r.z0));f.addProperty("block",block);f.addProperty("filter",filter);fills.add(f);
        }
        Output finish() {
            if(fills.isEmpty())throw new ToolArgError("site already meets the chosen plane; no fitted blocks to save");
            JsonObject doc=new JsonObject();doc.addProperty("version",1);doc.addProperty("description","Site-specific additive foundation/entrance; use reported original origin/world. No clearing or later-world reservation.");doc.add("dimensions",point(maxX-minX+1,maxY-minY+1,maxZ-minZ+1));
            JsonObject palette=new JsonObject();palette.addProperty("full",r.full);palette.addProperty("stairs",r.stairs);doc.add("palette",palette);
            JsonObject c=new JsonObject();c.add("fills",fills);JsonObject components=new JsonObject();components.add("foundation",c);doc.add("components",components);JsonObject instance=new JsonObject();instance.addProperty("component","foundation");instance.add("pos",point(0,0,0));JsonArray instances=new JsonArray();instances.add(instance);doc.add("instances",instances);BlueprintCompiler.validate(doc);
            JsonObject report=new JsonObject();report.add("from",point(minX,minY,minZ));report.add("to",point(maxX,maxY,maxZ));report.add("origin",point(r.x0,r.floorY,r.z0));report.addProperty("requestedCells",visits);report.addProperty("operations",fills.size());report.addProperty("mode",r.mode);return new Output(doc,report);
        }
    }
    static JsonArray point(int x,int y,int z){JsonArray a=new JsonArray();a.add(x);a.add(y);a.add(z);return a;}
}
