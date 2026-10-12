// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.render;

import cc.wujm.ashlar.engine.RegionData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure off-main schematic ray renderer. No world reads, textures, physics or verification passes. */
public final class ShapeRenderer {
    private ShapeRenderer() {}
    private static final long MAX_PIXELS=1_000_000,MAX_WORK=64_000_000,BASE_VOLUME=200_000,MAX_CELLS=16_000_000;
    /** Intersection budget: MAX_WORK up to 200,000 cells, then doubled per doubling of volume (scale halves in the same steps). */
    static long workBudget(long volume){long parts=(volume+BASE_VOLUME-1)/BASE_VOLUME;return parts<=1?MAX_WORK:MAX_WORK*Long.highestOneBit(parts-1)*2;}
    private static final int MAX_LAYERS=8,PADDING=8;
    /** Explicit angled scales go up to 32 px per block for close-ups (auto stays at most 16). */
    public static final int MAX_SCALE=32;
    public record Output(ImageRenderer.Output image,JsonObject details) {}
    /** One cell of a decorative entity drawn as a placeholder panel; coordinates are relative to the region. */
    public record Placeholder(int x,int y,int z,String type,String facing) {}
    /**
     * Optional inputs: per-palette x/y/z face colours ({@code null} entries use the palette colour), a per-cell
     * {@link SnapshotDiff} mark array in region order, and entity placeholders. Any field may be null.
     */
    public record Extras(int[][] faceColors,byte[] diff,List<Placeholder> placeholders) {
        public static final Extras NONE=new Extras(null,null,null);
    }
    static final int ADDED_TINT=0xFF2ECC40,CHANGED_TINT=0xFFFF9F1C,REMOVED_COLOR=0xFFE5383B,PAINTING_COLOR=0xFFB07A3C,FRAME_COLOR=0xFF8A5A2E;
    private static final BlockShapes.Shape GHOST=new BlockShapes.Shape(List.of(BlockShapes.CUBE),.35,"modeled");
    public static final class BudgetExceededException extends IllegalArgumentException {
        public BudgetExceededException(String message) { super(message); }
    }
    private record Vec(double x,double y,double z){
        Vec add(Vec v){return new Vec(x+v.x,y+v.y,z+v.z);}Vec mul(double n){return new Vec(x*n,y*n,z*n);}double dot(Vec v){return x*v.x+y*v.y+z*v.z;}
        Vec unit(){return mul(1/Math.sqrt(dot(this)));}JsonArray json(){JsonArray a=new JsonArray();a.add(x);a.add(y);a.add(z);return a;}
    }
    private record Hit(double enter,double exit,int axis,int sign) {}
    private static Hit intersect(Vec o,Vec d,double x0,double y0,double z0,double x1,double y1,double z1){
        double lo=Double.NEGATIVE_INFINITY,hi=Double.POSITIVE_INFINITY;int axis=1,sign=1;
        for(int a=0;a<3;a++){
            double p=a==0?o.x:a==1?o.y:o.z,v=a==0?d.x:a==1?d.y:d.z,min=a==0?x0:a==1?y0:z0,max=a==0?x1:a==1?y1:z1;
            if(Math.abs(v)<1e-12){if(p<min||p>max)return null;continue;}
            double t0=(min-p)/v,t1=(max-p)/v;int s=v>0?-1:1;if(t0>t1){double t=t0;t0=t1;t1=t;}
            if(t0>lo){lo=t0;axis=a;sign=s;}hi=Math.min(hi,t1);if(hi<lo)return null;
        }
        return new Hit(lo,hi,axis,sign);
    }
    public static Output render(RegionData data,int[] colors,String view,RenderCamera camera,int scale){return render(data,colors,view,camera,scale,Extras.NONE);}
    public static Output render(RegionData data,int[] colors,String view,RenderCamera camera,int scale,Extras extras){
        if(!RenderCamera.angled(view))throw new IllegalArgumentException("shape view must be isometric or perspective");
        if(scale<0||scale>MAX_SCALE)throw new IllegalArgumentException("scale must be 0-"+MAX_SCALE);
        var r=data.region();long volume=r.volume();if(volume<1||volume>MAX_CELLS||data.volume()!=volume)throw new IllegalArgumentException("shape region must contain 1-"+MAX_CELLS+" valid cells");
        int dx=r.maxX()-r.minX()+1,dy=r.maxY()-r.minY()+1,dz=r.maxZ()-r.minZ()+1;
        if(colors.length!=data.palette().size())throw new IllegalArgumentException("palette colors do not match");
        int[] flat=new int[(int)volume];int pos=0;int[] ids=data.runIndex(),lengths=data.runLength();
        for(int i=0;i<ids.length;i++){if(ids[i]<0||ids[i]>=colors.length||lengths[i]<=0||lengths[i]>flat.length-pos)throw new IllegalArgumentException("invalid region runs");Arrays.fill(flat,pos,pos+lengths[i],ids[i]);pos+=lengths[i];}
        if(pos!=flat.length)throw new IllegalArgumentException("incomplete region runs");
        BlockShapes.Shape[] shapes=new BlockShapes.Shape[colors.length];int[] color=colors.clone();int maxBoxes=1;JsonArray fallbacks=new JsonArray(),approx=new JsonArray(),colorFallbacks=new JsonArray();int fallbackCount=0,approxCount=0,colorFallbackCount=0;
        for(int i=0;i<shapes.length;i++){
            String state=data.palette().get(i);shapes[i]=BlockShapes.resolve(state);maxBoxes=Math.max(maxBoxes,shapes[i].boxes().size());
            if(shapes[i].fidelity().equals("cube_fallback")){fallbackCount++;if(fallbacks.size()<30)fallbacks.add(state);}
            if(shapes[i].fidelity().equals("approximate")){approxCount++;if(approx.size()<30)approx.add(state);}
            if(color[i]==0&&!shapes[i].boxes().isEmpty()){
                colorFallbackCount++;
                color[i]=BlockShapes.material(state).contains("glass")?0xFFB4DBE8:0xFFA2A2A2;if(colorFallbacks.size()<30)colorFallbacks.add(state);
            }
        }
        int palette=shapes.length,ghostId=palette,paintingId=palette+1,frameId=palette+2;
        int[][] face=new int[palette+3][];
        for(int i=0;i<palette;i++){int[] fc=extras.faceColors()==null?null:extras.faceColors()[i];face[i]=fc==null||color[i]!=colors[i]?new int[]{color[i],color[i],color[i]}:fc.clone();}
        face[ghostId]=new int[]{REMOVED_COLOR,REMOVED_COLOR,REMOVED_COLOR};face[paintingId]=new int[]{PAINTING_COLOR,PAINTING_COLOR,PAINTING_COLOR};face[frameId]=new int[]{FRAME_COLOR,FRAME_COLOR,FRAME_COLOR};
        byte[] diff=extras.diff();if(diff!=null&&diff.length!=flat.length)throw new IllegalArgumentException("diff marks do not match the region");
        java.util.Map<Integer,List<Hung>> hung=new java.util.HashMap<>();
        if(extras.placeholders()!=null)for(var q:extras.placeholders()){
            if(q.x()<0||q.x()>=dx||q.y()<0||q.y()>=dy||q.z()<0||q.z()>=dz)continue;
            boolean painting=q.type().equals("painting");hung.computeIfAbsent((q.y()*dz+q.z())*dx+q.x(),k->new ArrayList<>()).add(new Hung(BlockShapes.entityPanel(q.type(),q.facing()),painting?paintingId:frameId));
        }
        for(var l:hung.values())maxBoxes=Math.max(maxBoxes,shapes.length==0?l.size():l.size()+1);
        long changed=0,added=0,removed=0,outside=0;
        if(diff!=null)for(byte d:diff)switch(d){case SnapshotDiff.CHANGED->changed++;case SnapshotDiff.ADDED->added++;case SnapshotDiff.REMOVED->removed++;case SnapshotDiff.OUTSIDE->outside++;default->{}}
        double yaw=Math.toRadians(camera.azimuth()),pitch=Math.toRadians(camera.elevation());
        Vec eyeDirection=new Vec(Math.sin(yaw)*Math.cos(pitch),Math.sin(pitch),-Math.cos(yaw)*Math.cos(pitch));
        Vec right=new Vec(-Math.cos(yaw),0,-Math.sin(yaw)),up=new Vec(-Math.sin(yaw)*Math.sin(pitch),Math.cos(pitch),Math.cos(yaw)*Math.sin(pitch));
        Vec center=new Vec(dx*.5,dy*.5,dz*.5);double radius=Math.sqrt(dx*(double)dx+dy*(double)dy+dz*(double)dz)*.5;
        boolean perspective=view.equals("perspective");double distance=perspective?radius/Math.sin(Math.toRadians(camera.fov()*.5))*1.2:radius*2+1;
        Vec eye=center.add(eyeDirection.mul(distance));double u0=Double.POSITIVE_INFINITY,u1=-u0,v0=u0,v1=-u0;
        for(int x:new int[]{0,dx})for(int y:new int[]{0,dy})for(int z:new int[]{0,dz}){
            Vec c=new Vec(x-center.x,y-center.y,z-center.z);double q=perspective?distance/(distance-c.dot(eyeDirection)):1;
            double u=c.dot(right)*q,v=c.dot(up)*q;u0=Math.min(u0,u);u1=Math.max(u1,u);v0=Math.min(v0,v);v1=Math.max(v1,v);
        }
        double wide=u1-u0,tall=v1-v0;int requested=scale==0?Math.max(1,Math.min(16,(int)Math.floor(1008/Math.max(wide,tall)))):scale,eff=requested;
        int width,height;long work,budget=workBudget(volume);
        while(true){
            long w=(long)Math.ceil(wide*eff)+2*PADDING,h=(long)Math.ceil(tall*eff)+2*PADDING;
            if(w>Integer.MAX_VALUE||h>Integer.MAX_VALUE)throw new BudgetExceededException("projected image dimensions overflow");
            width=(int)w;height=(int)h;long pixels=w*h;
            work=pixels>MAX_PIXELS ? Long.MAX_VALUE : pixels*((long)dx+dy+dz+3)*maxBoxes;
            if(pixels<=MAX_PIXELS&&work<=budget)break;
            if(eff==1)throw new BudgetExceededException("shape image exceeds pixel/intersection budget at scale=1; use smaller 3D bounds");eff=Math.max(1,eff/2);
        }
        int[] pixels=new int[width*height];long[] visible=new long[palette+3];int capped=0;
        for(int py=0;py<height;py++)for(int px=0;px<width;px++){
            double u=u0+(px-PADDING+.5)/eff,v=v1-(py-PADDING+.5)/eff;
            Vec screen=center.add(right.mul(u)).add(up.mul(v));Vec origin=perspective?eye:screen.add(eyeDirection.mul(distance));Vec direction=perspective?screen.add(eye.mul(-1)).unit():eyeDirection.mul(-1);
            int bg=background(py,height);Hit scene=intersect(origin,direction,0,0,0,dx,dy,dz);int pixel=py*width+px;
            if(scene==null||scene.exit<=0){pixels[pixel]=bg;continue;}
            double t=Math.max(0,scene.enter)+1e-7,end=scene.exit;Vec start=origin.add(direction.mul(t));
            int x=Math.max(0,Math.min(dx-1,(int)Math.floor(start.x))),y=Math.max(0,Math.min(dy-1,(int)Math.floor(start.y))),z=Math.max(0,Math.min(dz-1,(int)Math.floor(start.z)));
            int sx=direction.x>=0?1:-1,sy=direction.y>=0?1:-1,sz=direction.z>=0?1:-1;
            double tx=boundary(origin.x,direction.x,x,sx),ty=boundary(origin.y,direction.y,y,sy),tz=boundary(origin.z,direction.z,z,sz);
            double ix=Math.abs(direction.x)<1e-12?Double.POSITIVE_INFINITY:Math.abs(1/direction.x),iy=Math.abs(direction.y)<1e-12?Double.POSITIVE_INFINITY:Math.abs(1/direction.y),iz=Math.abs(direction.z)<1e-12?Double.POSITIVE_INFINITY:Math.abs(1/direction.z);
            double red=0,green=0,blue=0,remaining=1;int layers=0;boolean first=true;
            while(x>=0&&x<dx&&y>=0&&y<dy&&z>=0&&z<dz&&t<=end+1e-7){
                double next=Math.min(tx,Math.min(ty,tz));int cell=(y*dz+z)*dx+x,id=flat[cell];byte mark=diff==null?SnapshotDiff.SAME:diff[cell];
                var shape=mark==SnapshotDiff.REMOVED&&shapes[id].boxes().isEmpty()?GHOST:shapes[id];if(shape==GHOST)id=ghostId;Hit closest=null;int hitId=id;double opacity=shape.opacity();
                for(var box:shape.boxes()){
                    Hit hit=intersect(origin,direction,x+box.x0(),y+box.y0(),z+box.z0(),x+box.x1(),y+box.y1(),z+box.z1());
                    if(hit!=null&&hit.exit>=t-1e-7&&hit.enter<=next+1e-7&&(closest==null||hit.enter<closest.enter))closest=hit;
                }
                List<Hung> panels=hung.get(cell);
                if(panels!=null)for(var h:panels){
                    var box=h.box();Hit hit=intersect(origin,direction,x+box.x0(),y+box.y0(),z+box.z0(),x+box.x1(),y+box.y1(),z+box.z1());
                    if(hit!=null&&hit.exit>=t-1e-7&&hit.enter<=next+1e-7&&(closest==null||hit.enter<closest.enter)){closest=hit;hitId=h.id();opacity=1;}
                }
                if(closest!=null){
                    if(first){visible[hitId]++;first=false;}double light=.58+.42*Math.max(0,closest.sign*(closest.axis==0?.4:closest.axis==1?.85:-.3));double a=opacity*remaining;
                    int c=face[hitId][closest.axis];if(hitId<palette&&mark==SnapshotDiff.CHANGED)c=mix(c,CHANGED_TINT,.55);else if(hitId<palette&&mark==SnapshotDiff.ADDED)c=mix(c,ADDED_TINT,.55);
                    red+=((c>>16)&255)*light*a;green+=((c>>8)&255)*light*a;blue+=(c&255)*light*a;remaining*=1-opacity;
                    layers++;if(remaining<.02)break;if(layers>=MAX_LAYERS){capped++;break;}
                }
                t=next;
                // Move every tied axis to avoid testing zero-width corner/edge crossings as extra cells.
                if(tx<=next+1e-9){x+=sx;tx+=ix;}if(ty<=next+1e-9){y+=sy;ty+=iy;}if(tz<=next+1e-9){z+=sz;tz+=iz;}
            }
            red+=((bg>>16)&255)*remaining;green+=((bg>>8)&255)*remaining;blue+=(bg&255)*remaining;
            pixels[pixel]=0xFF000000|((int)Math.min(255,Math.round(red))<<16)|((int)Math.min(255,Math.round(green))<<8)|(int)Math.min(255,Math.round(blue));
        }
        List<ImageRenderer.LegendEntry> legend=new ArrayList<>();
        for(int i=0;i<visible.length;i++)if(visible[i]>0)legend.add(new ImageRenderer.LegendEntry(i<palette?data.palette().get(i):i==ghostId?"diff:removed":i==paintingId?"entity:painting":"entity:item_frame",String.format(java.util.Locale.ROOT,"#%06X",(i<palette?color[i]:face[i][0])&0xFFFFFF),visible[i]));
        legend.sort(java.util.Comparator.comparingLong(ImageRenderer.LegendEntry::pixels).reversed());
        JsonObject info=new JsonObject();info.addProperty("projection",perspective?"perspective":"orthographic");JsonObject cam=camera.json();if(!perspective)cam.remove("fov");info.add("camera",cam);
        info.add("screenRight",right.json());info.add("screenUp",up.json());info.addProperty("requestedScale",scale);info.addProperty("resolutionReduced",eff<requested);info.addProperty("estimatedIntersectionWork",work);
        info.addProperty("cubeFallbackStates",fallbackCount);info.add("cubeFallbackSamples",fallbacks);info.addProperty("approximateStates",approxCount);info.add("approximateSamples",approx);info.add("colorFallbackSamples",colorFallbacks);
        info.addProperty("cubeFallbackSamplesTruncated",fallbackCount>30);info.addProperty("approximateSamplesTruncated",approxCount>30);
        info.addProperty("colorFallbackStates",colorFallbackCount);info.addProperty("colorFallbackSamplesTruncated",colorFallbackCount>30);
        if(diff!=null){JsonObject d=new JsonObject();d.addProperty("changed",changed);d.addProperty("added",added);d.addProperty("removed",removed);d.addProperty("outsideSnapshot",outside);
            d.addProperty("tints","changed blocks are tinted orange, added blocks green, removed blocks are drawn as translucent red cubes; cells outside the snapshot are not compared");info.add("diff",d);}
        if(extras.placeholders()!=null)info.addProperty("entityPlaceholderCells",extras.placeholders().size());
        info.addProperty("transparentLayerLimit",MAX_LAYERS);info.addProperty("transparentRaysCapped",capped);
        info.addProperty("assumptions","Schematic state-derived visual cuboids and map colors, not textures/resource-pack models. Approximate/fallback shapes and colors are listed. Stored connections only; no updates. Paintings and item frames are flat placeholder panels; other entities, sign text, lighting engine, shadows, fluid or waterlogging simulation. Legend counts first visible surfaces. Cropped at explicit region bounds.");
        return new Output(new ImageRenderer.Output(width,height,eff,"camera screen-right","camera screen-down",0,0,0,List.copyOf(legend),pixels),info);
    }
    private record Hung(BlockShapes.Box box,int id) {}
    static int mix(int a,int b,double f){int r=(int)Math.round(((a>>16)&255)*(1-f)+((b>>16)&255)*f),g=(int)Math.round(((a>>8)&255)*(1-f)+((b>>8)&255)*f),bl=(int)Math.round((a&255)*(1-f)+(b&255)*f);return 0xFF000000|(r<<16)|(g<<8)|bl;}
    private static double boundary(double origin,double direction,int cell,int step){return Math.abs(direction)<1e-12?Double.POSITIVE_INFINITY:((step>0?cell+1:cell)-origin)/direction;}
    private static int background(int y,int height){double t=y/(double)Math.max(1,height-1);int r=(int)(222-32*t),g=(int)(234-29*t),b=(int)(243-21*t);return 0xFF000000|(r<<16)|(g<<8)|b;}
}
