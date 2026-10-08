// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.render;

import com.google.gson.JsonObject;
import java.util.Set;

/** Pure, bounded camera inputs. Azimuth is compass bearing: north=0, east=90. */
public record RenderCamera(double azimuth,double elevation,double fov) {
    public RenderCamera {
        if(!Double.isFinite(azimuth)||azimuth<0||azimuth>=360)throw new IllegalArgumentException("camera.azimuth must be finite in [0,360)");
        if(!Double.isFinite(elevation)||elevation<5||elevation>85)throw new IllegalArgumentException("camera.elevation must be finite in [5,85]");
        if(!Double.isFinite(fov)||fov<20||fov>90)throw new IllegalArgumentException("camera.fov must be finite in [20,90]");
    }
    public static boolean angled(String view){return view.equals("isometric")||view.equals("perspective");}
    public static RenderCamera defaults(String view){return new RenderCamera(135,view.equals("isometric")?35.26438968:30,50);}
    public static RenderCamera parse(JsonObject args,String view){
        if(!args.has("camera")||args.get("camera").isJsonNull())return angled(view)?defaults(view):null;
        if(!angled(view))throw new IllegalArgumentException("camera is only supported for isometric/perspective views");
        if(!args.get("camera").isJsonObject())throw new IllegalArgumentException("camera must be an object");
        JsonObject c=args.getAsJsonObject("camera");for(String k:c.keySet())if(!Set.of("azimuth","elevation","fov").contains(k))throw new IllegalArgumentException("unknown camera field: "+k);
        if(view.equals("isometric")&&c.has("fov"))throw new IllegalArgumentException("camera.fov applies only to perspective");
        var d=defaults(view);return new RenderCamera(number(c,"azimuth",d.azimuth),number(c,"elevation",d.elevation),number(c,"fov",d.fov));
    }
    /** Raw RPC parity with tool validation for the new volume-priced views. */
    public static void validateBounds(JsonObject args,String view) {
        if (!angled(view)) return;
        for (String field : new String[]{"from","to"}) {
            if (!args.has(field) || !args.get(field).isJsonArray() || args.getAsJsonArray(field).size()!=3)
                throw new IllegalArgumentException("angled views require [x,y,z] bounds");
            for (int i=0;i<3;i++) {
                int n=integer(args.getAsJsonArray(field).get(i),field);
                if (i!=1 && Math.abs((long)n)>=30_000_000) throw new IllegalArgumentException("angled bounds must be inside horizontal +/-30000000");
            }
        }
        if (args.has("scale")) { int n=integer(args.get("scale"),"scale");if(n<0||n>16)throw new IllegalArgumentException("scale must be 0-16"); }
        if (args.has("grid") && integer(args.get("grid"),"grid")!=0) throw new IllegalArgumentException("angled views require grid:0");
    }
    private static int integer(com.google.gson.JsonElement e,String field) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(field+" must contain signed integers");
        try { return e.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException(field+" must contain signed integers"); }
    }
    private static double number(JsonObject c,String key,double fallback){
        if(!c.has(key))return fallback;var v=c.get(key);if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("camera."+key+" must be a number");return v.getAsDouble();
    }
    public JsonObject json(){JsonObject c=new JsonObject();c.addProperty("azimuth",azimuth);c.addProperty("elevation",elevation);c.addProperty("fov",fov);return c;}
}
