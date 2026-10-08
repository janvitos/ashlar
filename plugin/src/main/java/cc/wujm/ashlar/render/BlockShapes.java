// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Schematic VISUAL cuboids, not collision boxes or resource-pack models. No Bukkit access. */
public final class BlockShapes {
    private BlockShapes() {}
    public record Box(double x0,double y0,double z0,double x1,double y1,double z1) {
        public Box { if(x0<0 || y0<0 || z0<0 || x1>1 || y1>1 || z1>1 || x0>=x1 || y0>=y1 || z0>=z1)throw new IllegalArgumentException("invalid visual box"); }
        public double volume(){return (x1-x0)*(y1-y0)*(z1-z0);}
    }
    public record Shape(List<Box> boxes,double opacity,String fidelity) {public Shape{boxes=List.copyOf(boxes);}}
    public static final Box CUBE=new Box(0,0,0,1,1,1);
    private static final Set<String> FULL=Set.of("stone","dirt","coarse_dirt","rooted_dirt","cobblestone","mossy_cobblestone","granite","diorite","andesite","deepslate","cobbled_deepslate","tuff","calcite","bedrock","sand","red_sand","gravel","clay","obsidian","crying_obsidian","netherrack","end_stone","bricks","mud","packed_mud","soul_sand","soul_soil","snow_block","ice","packed_ice","blue_ice","crafting_table","furnace","blast_furnace","smoker","bookshelf","chiseled_bookshelf","barrel","spawner","sponge","wet_sponge","terracotta");
    public static Map<String,String> properties(String state){Map<String,String> p=new TreeMap<>();int i=state.indexOf('[');if(i>=0)for(String kv:state.substring(i+1,state.length()-1).split(",")){String[] a=kv.split("=",2);if(a.length!=2)throw new IllegalArgumentException("invalid state properties");p.put(a[0],a[1]);}return p;}
    public static String material(String state){int i=state.indexOf('[');return (i<0?state:state.substring(0,i)).replace("minecraft:","");}
    private static Shape shape(List<Box> b,double opacity,String fidelity){return new Shape(b,opacity,fidelity);}
    private static Shape box(Box b){return shape(List.of(b),1,"modeled");}
    private static Box b(double x0,double y0,double z0,double x1,double y1,double z1){return new Box(x0,y0,z0,x1,y1,z1);}
    public static Shape resolve(String state){
        String m=material(state);var p=properties(state);String facing=p.getOrDefault("facing","north");
        if(Set.of("air","cave_air","void_air","structure_void","light").contains(m))return shape(List.of(),0,"modeled");
        if(m.endsWith("_slab"))return box(switch(p.getOrDefault("type","bottom")){case "top"->b(0,.5,0,1,1,1);case "double"->CUBE;default->b(0,0,0,1,.5,1);});
        if(m.endsWith("_stairs"))return stairs(p);
        if(m.endsWith("_trapdoor")){
            if(Boolean.parseBoolean(p.getOrDefault("open","false")))return box(panel(facing,3.0/16));
            return box("top".equals(p.get("half"))?b(0,13.0/16,0,1,1,1):b(0,0,0,1,3.0/16,1));
        }
        if(m.endsWith("_door")){
            if(Boolean.parseBoolean(p.getOrDefault("open","false")))facing=turn(facing,"right".equals(p.get("hinge"))?-1:1);
            return box(panel(facing,3.0/16));
        }
        if(m.endsWith("_fence_gate"))return gate(p);
        if(m.endsWith("_fence"))return fence(p);
        if(m.endsWith("_wall"))return wall(p);
        if(m.endsWith("_pane") || m.equals("iron_bars"))return pane(p,m.equals("iron_bars")?1:.35);
        if(m.equals("glass") || m.endsWith("_stained_glass"))return shape(List.of(CUBE),.3,"modeled");
        if(m.endsWith("_leaves"))return shape(List.of(CUBE),.7,"approximate");
        if(m.endsWith("_bed"))return shape(List.of(b(0,3.0/16,0,1,9.0/16,1),b(0,0,0,3.0/16,3.0/16,3.0/16),b(13.0/16,0,0,1,3.0/16,3.0/16),b(0,0,13.0/16,3.0/16,3.0/16,1),b(13.0/16,0,13.0/16,1,3.0/16,1)),1,"approximate");
        if(m.equals("chest") || m.equals("trapped_chest") || m.equals("ender_chest")){
            double x0=1.0/16,z0=1.0/16,x1=15.0/16,z1=15.0/16;
            String type=p.getOrDefault("type","single");if(!type.equals("single")){String side=turn(facing,type.equals("left")?1:-1);switch(side){case "east"->x1=1;case "west"->x0=0;case "north"->z0=0;case "south"->z1=1;default->{}}}
            return shape(List.of(b(x0,0,z0,x1,14.0/16,z1)),1,"approximate");
        }
        if(m.endsWith("_sign")){
            Box board=m.contains("wall_")?wallSign(facing):b(0,.5,7.0/16,1,1,9.0/16);
            if(!m.contains("wall_") && Integer.parseInt(p.getOrDefault("rotation","0"))%8>=4)board=b(7.0/16,.5,0,9.0/16,1,1);
            return shape(m.contains("wall_")||m.contains("hanging")?List.of(board):List.of(board,b(7.0/16,0,7.0/16,9.0/16,.5,9.0/16)),1,"approximate");
        }
        if(m.equals("water") || m.equals("lava")){int level=Integer.parseInt(p.getOrDefault("level","0"));double h=level>=8?1:Math.max(1.0/9,(8-level)/9.0);return shape(List.of(b(0,0,0,1,h,1)),m.equals("water")?.55:1,"approximate");}
        if(m.equals("snow"))return box(b(0,0,0,1,Integer.parseInt(p.getOrDefault("layers","1"))/8.0,1));
        if(m.endsWith("_carpet") || m.equals("redstone_wire"))return shape(List.of(b(0,0,0,1,1.0/16,1)),1,m.equals("redstone_wire")?"approximate":"modeled");
        if(m.endsWith("torch"))return shape(List.of(b(7.0/16,0,7.0/16,9.0/16,10.0/16,9.0/16)),1,"approximate");
        if(FULL.contains(m) || List.of("_planks","_log","_wood","_stem","_hyphae","_concrete","_terracotta","_wool","_ore","_block","_bricks","_tiles").stream().anyMatch(m::endsWith))return box(CUBE);
        return shape(List.of(CUBE),1,"cube_fallback");
    }
    /** A north-facing closed panel sits on the SOUTH edge, opposite its facing. */
    public static Box panel(String facing,double t){return switch(facing){case "south"->b(0,0,0,1,1,t);case "east"->b(0,0,0,t,1,1);case "west"->b(1-t,0,0,1,1,1);default->b(0,0,1-t,1,1,1);};}
    private static Box wallSign(String f){return switch(f){case "north"->b(0,.25,15.0/16,1,.75,1);case "south"->b(0,.25,0,1,.75,1.0/16);case "east"->b(0,.25,0,1.0/16,.75,1);default->b(15.0/16,.25,0,1,.75,1);};}
    public static String turn(String f,int quarter){List<String> faces=List.of("north","east","south","west");int i=faces.indexOf(f);return faces.get(Math.floorMod(i+quarter,4));}
    private static boolean toward(String f,int x,int z){return switch(f){case "north"->z==0;case "south"->z==1;case "east"->x==1;default->x==0;};}
    private static Shape stairs(Map<String,String> p){
        boolean top="top".equals(p.get("half"));String facing=p.getOrDefault("facing","north"),s=p.getOrDefault("shape","straight"),side=turn(facing,s.endsWith("left")?-1:1);
        List<Box> boxes=new ArrayList<>();boxes.add(b(0,top?.5:0,0,1,top?1:.5,1));
        for(int x=0;x<2;x++)for(int z=0;z<2;z++){
            boolean f=toward(facing,x,z),l=toward(side,x,z);boolean high=s.startsWith("inner")?f||l:s.startsWith("outer")?f&&l:f;
            if(high)boxes.add(b(x*.5,top?0:.5,z*.5,(x+1)*.5,top?.5:1,(z+1)*.5));
        }
        return shape(boxes,1,"modeled");
    }
    private static boolean linked(Map<String,String> p,String f){return "true".equals(p.get(f));}
    private static Box arm(String f,double low,double high,double half){return switch(f){case "north"->b(.5-half,low,0,.5+half,high,.5);case "south"->b(.5-half,low,.5,.5+half,high,1);case "east"->b(.5,low,.5-half,1,high,.5+half);default->b(0,low,.5-half,.5,high,.5+half);};}
    private static Shape fence(Map<String,String> p){List<Box> a=new ArrayList<>();a.add(b(.375,0,.375,.625,1,.625));for(String f:List.of("north","south","east","west"))if(linked(p,f)){a.add(arm(f,.375,.5625,.0625));a.add(arm(f,.75,.9375,.0625));}return shape(a,1,"modeled");}
    private static Shape wall(Map<String,String> p){List<Box>a=new ArrayList<>();if(!"false".equals(p.get("up")))a.add(b(.25,0,.25,.75,1,.75));for(String f:List.of("north","south","east","west"))if(!"none".equals(p.getOrDefault(f,"none")))a.add(arm(f,0,"tall".equals(p.get(f))?1:.875,.1875));return shape(a,1,"modeled");}
    private static Shape pane(Map<String,String> p,double opacity){List<Box>a=new ArrayList<>();a.add(b(7.0/16,0,7.0/16,9.0/16,1,9.0/16));for(String f:List.of("north","south","east","west"))if(linked(p,f))a.add(arm(f,0,1,1.0/16));return shape(a,opacity,"modeled");}
    private static Shape gate(Map<String,String> p){boolean ns=Set.of("north","south").contains(p.getOrDefault("facing","north"));List<Box>a=new ArrayList<>();if(ns){a.add(b(0,.3125,.4375,.125,1,.5625));a.add(b(.875,.3125,.4375,1,1,.5625));if(!"true".equals(p.get("open")))a.add(b(.125,.375,.4375,.875,.9375,.5625));}else{a.add(b(.4375,.3125,0,.5625,1,.125));a.add(b(.4375,.3125,.875,.5625,1,1));if(!"true".equals(p.get("open")))a.add(b(.4375,.375,.125,.5625,.9375,.875));}return shape(a,1,"approximate");}
}
