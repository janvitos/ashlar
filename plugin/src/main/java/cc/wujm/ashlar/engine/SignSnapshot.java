// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;

/** Immutable supported sign values. Rich styling and arbitrary NBT are deliberately outside scope. */
public record SignSnapshot(Face front, Face back, boolean waxed) {
    public record Face(List<String> lines, String color, boolean glowing) {
        public Face { lines = List.copyOf(lines); if (lines.size() != 4) throw new IllegalArgumentException("sign face requires four lines"); }
        Face patch(List<String> text, SignData p) { return text == null ? this : new Face(text,p.color() == null ? color : p.color().trim().toLowerCase(Locale.ROOT),p.glowing()); }
        JsonObject json() { JsonObject o=new JsonObject(); JsonArray a=new JsonArray(); lines.forEach(a::add); o.add("lines",a); o.addProperty("color",color); o.addProperty("glowing",glowing); return o; }
    }
    public static SignSnapshot empty() { Face f=new Face(List.of("","","",""),"black",false); return new SignSnapshot(f,f,false); }
    public SignSnapshot patch(SignData p) { return new SignSnapshot(front.patch(p.front(),p),back.patch(p.back(),p),p.waxed()); }
    public JsonObject json() { JsonObject o=new JsonObject(); o.add("front",front.json()); o.add("back",back.json()); o.addProperty("waxed",waxed); return o; }
}
