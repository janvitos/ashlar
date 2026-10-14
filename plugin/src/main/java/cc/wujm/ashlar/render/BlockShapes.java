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
        if(m.endsWith("_pane") || m.endsWith("_bars"))return pane(p,m.endsWith("_bars")?1:.35);
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
        if(m.endsWith("lantern") && !m.equals("sea_lantern") && !m.equals("jack_o_lantern"))return lantern(p);
        if(m.endsWith("chain") && p.containsKey("axis"))return shape(List.of(along(p.get("axis"),px(6.5,0,6.5,9.5,16,9.5))),1,"approximate");
        if(m.equals("end_rod"))return shape(List.of(toward(facing(p,"up"),px(6,0,6,10,1,10)),toward(facing(p,"up"),px(7,1,7,9,16,9))),1,"approximate");
        if(m.endsWith("lightning_rod"))return shape(List.of(toward(facing(p,"up"),px(7,0,7,9,12,9)),toward(facing(p,"up"),px(6,12,6,10,16,10))),1,"approximate");
        if(m.equals("candle") || m.endsWith("_candle"))return candles(Integer.parseInt(p.getOrDefault("candles","1")));
        if(m.equals("bell"))return bell(p);
        if(m.endsWith("campfire"))return shape(rotate(facing,List.of(px(1,0,0,5,4,16),px(11,0,0,15,4,16),px(0,3,1,16,7,5),px(0,3,11,16,7,15),px(5,0,5,11,1,11))),1,"approximate");
        if(m.equals("pointed_dripstone"))return dripstone(p);
        if(m.endsWith("_button"))return button(p,facing);
        if(m.equals("lever"))return lever(p,facing);
        if(m.equals("flower_pot") || m.startsWith("potted_"))return shape(m.startsWith("potted_")?List.of(px(5,0,5,11,6,11),px(6,6,6,10,14,10)):List.of(px(5,0,5,11,6,11)),1,"approximate");
        if((m.endsWith("_head") || m.endsWith("_skull")) && !m.equals("piston_head"))return shape(List.of(m.contains("_wall_")?rot(facing,px(4,4,8,12,12,16)):px(4,0,4,12,8,12)),1,"approximate");
        if(axis(state)>=0)return box(CUBE);
        if(m.endsWith("torch"))return shape(List.of(b(7.0/16,0,7.0/16,9.0/16,10.0/16,9.0/16)),1,"approximate");
        if(FULL.contains(m) || List.of("_planks","_log","_wood","_stem","_hyphae","_concrete","_terracotta","_wool","_ore","_block","_bricks","_tiles").stream().anyMatch(m::endsWith))return box(CUBE);
        return shape(List.of(CUBE),1,"cube_fallback");
    }
    /** Pillar-like blocks whose end faces differ from their sides: the {@code axis} index (0=x, 1=y, 2=z), or -1. */
    public static int axis(String state){
        String m=material(state);String a=properties(state).get("axis");
        if(a==null || !(PILLARS.contains(m) || m.endsWith("_log") || m.endsWith("_stem") || m.endsWith("froglight")))return -1;
        return switch(a){case "x"->0;case "z"->2;default->1;};
    }
    private static final Set<String> PILLARS=Set.of("basalt","polished_basalt","quartz_pillar","purpur_pillar","hay_block","bone_block","deepslate","bamboo_block","stripped_bamboo_block","muddy_mangrove_roots");
    /** Blocks whose map colour is the top only; their sides read as this other block. */
    private static final Map<String,String> SIDES=Map.of("grass_block","minecraft:dirt","podzol","minecraft:dirt","mycelium","minecraft:dirt","dirt_path","minecraft:dirt","crimson_nylium","minecraft:netherrack","warped_nylium","minecraft:netherrack");
    /**
     * Colours of the x-, y- and z-facing faces for one palette state, or {@code null} when every face uses
     * {@code base}. {@code mapColor} resolves a state to its map colour (0 when it has none). Pillars take
     * their end colour from the {@code axis=y} state and their side colour from {@code axis=x} (logs colour
     * by axis in vanilla); when both match, the ends are lightened so a sideways pillar still reads.
     */
    public static int[] faceColors(String state,int base,java.util.function.ToIntFunction<String> mapColor){
        String m=material(state);int a=axis(state);
        if(a>=0){
            String id="minecraft:"+m;int end=mapColor.applyAsInt(id+"[axis=y]"),side=mapColor.applyAsInt(id+"[axis=x]");
            if(end==0)end=base;if(side==0)side=base;if(end==side)end=lighten(side,.22);
            int[] c={side,side,side};c[a]=end;return c;
        }
        String other=SIDES.get(m);if(other==null)return null;
        int side=mapColor.applyAsInt(other);return side==0?null:new int[]{side,base,side};
    }
    static int lighten(int argb,double f){int r=(argb>>16)&255,g=(argb>>8)&255,b=argb&255;r+=(int)Math.round((255-r)*f);g+=(int)Math.round((255-g)*f);b+=(int)Math.round((255-b)*f);return (argb&0xFF000000)|(r<<16)|(g<<8)|b;}
    /** Schematic placeholder panels for one cell of a decorative entity hung on (or lying against) a block face. */
    public static Box entityPanel(String type,String facing){
        boolean frame=!type.equals("painting");double lo=frame?2:0,hi=frame?14:16;
        return switch(facing){case "up"->px(lo,0,lo,hi,1,hi);case "down"->px(lo,15,lo,hi,16,hi);default->rot(facing,px(lo,lo,15,hi,hi,16));};
    }
    private static Box px(double x0,double y0,double z0,double x1,double y1,double z1){return b(x0/16,y0/16,z0/16,x1/16,y1/16,z1/16);}
    private static String facing(Map<String,String> p,String def){return p.getOrDefault("facing",def);}
    /** Turns a box drawn for {@code facing=north} (clockwise from above). */
    static Box rot(String facing,Box q){return switch(facing){
        case "east"->b(1-q.z1(),q.y0(),q.x0(),1-q.z0(),q.y1(),q.x1());
        case "south"->b(1-q.x1(),q.y0(),1-q.z1(),1-q.x0(),q.y1(),1-q.z0());
        case "west"->b(q.z0(),q.y0(),1-q.x1(),q.z1(),q.y1(),1-q.x0());
        default->q;};}
    private static List<Box> rotate(String facing,List<Box> boxes){return boxes.stream().map(q->rot(facing,q)).toList();}
    /** Points a box drawn for {@code facing=up} (base on the floor) toward {@code facing}. */
    static Box toward(String facing,Box q){return switch(facing){
        case "up"->q;
        case "down"->b(q.x0(),1-q.y1(),q.z0(),q.x1(),1-q.y0(),q.z1());
        default->rot(facing,b(q.x0(),q.z0(),1-q.y1(),q.x1(),q.z1(),1-q.y0()));};}
    private static Box along(String axis,Box q){return switch(axis){case "x"->toward("east",q);case "z"->toward("south",q);default->q;};}
    private static Shape lantern(Map<String,String> p){
        return shape("true".equals(p.get("hanging"))?List.of(px(5,1,5,11,8,11),px(6,8,6,10,10,10),px(7,10,7,9,16,9)):List.of(px(5,0,5,11,7,11),px(6,7,6,10,9,10)),1,"approximate");
    }
    private static Shape candles(int n){
        List<Box> all=List.of(px(7,0,7,9,6,9),px(9,0,6,11,5,8),px(6,0,9,8,4,11),px(5,0,5,7,5,7));
        List<Box> two=List.of(px(5,0,7,7,6,9),px(9,0,6,11,5,8));
        return shape(n<=1?all.subList(0,1):n==2?two:all.subList(0,Math.min(4,n)),1,"approximate");
    }
    private static Shape bell(Map<String,String> p){
        String f=facing(p,"north");List<Box> a=new ArrayList<>(List.of(px(4,4,4,12,6,12),px(5,6,5,11,13,11)));
        switch(p.getOrDefault("attachment","floor")){
            case "ceiling"->a.add(px(7,13,7,9,16,9));
            case "single_wall"->a.add(rot(f,px(7,13,7,9,15,16)));
            case "double_wall"->a.add(rot(f,px(7,13,0,9,15,16)));
            default->a.addAll(rotate(f,List.of(px(0,0,6,2,16,10),px(14,0,6,16,16,10),px(2,13,7,14,15,9))));
        }
        return shape(a,1,"approximate");
    }
    private static Shape dripstone(Map<String,String> p){
        String t=p.getOrDefault("thickness","tip");double w=switch(t){case "base"->2;case "middle"->3;case "frustum"->4;default->5;};
        Box q=px(w,0,w,16-w,t.equals("tip")?11:16,16-w);
        return shape(List.of("down".equals(p.get("vertical_direction"))?toward("down",q):q),1,"approximate");
    }
    /** Rods and chains that renders widen so they stay visible; walking, sightlines and light keep the true shape. */
    public static boolean thin(String state){String m=material(state);return m.equals("end_rod")||m.endsWith("chain")||m.endsWith("lightning_rod");}
    /** Render-only: each long box of a {@link #thin} block grows to at least {@code min} across, about its centre. */
    public static Shape widenThin(Shape s,String state,double min){
        if(!thin(state))return s;
        List<Box> out=new ArrayList<>();
        for(Box q:s.boxes()){
            if(Math.max(q.x1()-q.x0(),Math.max(q.y1()-q.y0(),q.z1()-q.z0()))<.5){out.add(q);continue;}
            double[] x=grow(q.x0(),q.x1(),min),y=grow(q.y0(),q.y1(),min),z=grow(q.z0(),q.z1(),min);
            out.add(b(x[0],y[0],z[0],x[1],y[1],z[1]));
        }
        return shape(out,s.opacity(),s.fidelity());
    }
    private static double[] grow(double lo,double hi,double min){
        if(hi-lo>=min)return new double[]{lo,hi};
        double c=Math.max(min/2,Math.min(1-min/2,(lo+hi)/2));return new double[]{c-min/2,c+min/2};
    }
    private static Shape button(Map<String,String> p,String facing){
        double d="true".equals(p.get("powered"))?1:2;
        Box q=switch(p.getOrDefault("face","wall")){case "floor"->rot(facing,px(5,0,6,11,d,10));case "ceiling"->rot(facing,px(5,16-d,6,11,16,10));default->rot(facing,px(5,6,16-d,11,10,16));};
        return shape(List.of(q),1,"approximate");
    }
    private static Shape lever(Map<String,String> p,String facing){
        List<Box> a=switch(p.getOrDefault("face","wall")){
            case "floor"->List.of(px(5,0,4,11,3,12),px(7,3,7,9,11,9));
            case "ceiling"->List.of(px(5,13,4,11,16,12),px(7,5,7,9,13,9));
            default->List.of(px(5,4,13,11,12,16),px(7,7,6,9,9,13));
        };
        return shape(rotate(facing,a),1,"approximate");
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
