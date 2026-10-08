// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** World-independent frozen eligible cells, not a reevaluation of keep/filter predicates. */
public record BuildExpectation(String world, Region bounds, List<Cell> cells, boolean connected, boolean flowing) {
    public BuildExpectation { cells=List.copyOf(cells); }
    public record Pos(int x,int y,int z) {
        public JsonArray json() { JsonArray a=new JsonArray(); a.add(x);a.add(y);a.add(z);return a; }
    }
    public record Cell(Pos pos,String state,SignSnapshot sign) {}
    public record Observed(Cell expected,String actual,SignSnapshot sign,boolean blockEntity) {}
    public static String material(String state) { int i=state.indexOf('['); return i<0 ? state : state.substring(0,i); }
    public static Map<String,String> properties(String state) {
        Map<String,String> p=new TreeMap<>(); int i=state.indexOf('[');
        if (i>=0) for (String kv:state.substring(i+1,state.length()-1).split(",")) { String[] s=kv.split("=",2); p.put(s[0],s[1]); }
        return p;
    }
    public static Set<String> excluded(String state,boolean placement,boolean connected) {
        if (!placement || !connected) return Set.of(); String m=material(state);
        if (m.endsWith("_stairs")) return Set.of("shape");
        if (m.endsWith("_fence") || m.endsWith("_pane") || m.equals("minecraft:iron_bars") || m.endsWith("_wall")
                || m.equals("minecraft:redstone_wire") || m.equals("minecraft:tripwire")) return Set.of("north","south","east","west","up");
        // Chest type is intentionally NOT excluded: explicitly paired chests must be verified exactly.
        return Set.of();
    }
    public static boolean stateMatches(String expected,String actual,Set<String> excluded) {
        if (!material(expected).equals(material(actual))) return false;
        if (excluded.isEmpty()) return expected.equals(actual);
        Map<String,String> a=properties(expected), b=properties(actual); excluded.forEach(k -> {a.remove(k);b.remove(k);});return a.equals(b);
    }
    public static String kind(Cell e,String actual) {
        if (material(actual).equals("minecraft:air") && !material(e.state()).equals("minecraft:air")) return "missing";
        if (material(e.state()).equals("minecraft:air") && !material(actual).equals("minecraft:air")) return "unexpected";
        return material(e.state()).equals(material(actual)) ? "wrong_state" : "wrong_material";
    }
    public static JsonObject difference(Observed o,Set<String> excluded) {
        Cell e=o.expected(); JsonObject d=new JsonObject(); d.add("pos",e.pos().json());d.addProperty("expected",e.state());d.addProperty("actual",o.actual());
        d.addProperty("kind",stateMatches(e.state(),o.actual(),excluded) ? "sign_metadata" : kind(e,o.actual()));
        JsonObject props=new JsonObject(); Map<String,String> a=properties(e.state()), b=properties(o.actual());
        Set<String> keys=new java.util.TreeSet<>(a.keySet());keys.addAll(b.keySet());
        for (String k:keys) if (!excluded.contains(k) && !java.util.Objects.equals(a.get(k),b.get(k))) { JsonObject v=new JsonObject();v.addProperty("expected",a.get(k));v.addProperty("actual",b.get(k));props.add(k,v); }
        d.add("propertyDifferences",props);
        if (e.sign()!=null && !e.sign().equals(o.sign())) {d.add("expectedSign",e.sign().json());d.add("actualSign",o.sign()==null ? com.google.gson.JsonNull.INSTANCE : o.sign().json());}
        return d;
    }
}
