// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Complete sparse exact comparison; output truncation never truncates verification itself. */
public final class VerifyTask extends BuildTask {
    private final World world; private final BuildExpectation expected; private final boolean placement; private final int samples;
    private final List<BuildExpectation.Observed> differences=new ArrayList<>();
    private final JsonArray rows=new JsonArray(); private final java.util.Map<String,Long> kinds=new java.util.TreeMap<>();
    private final Set<String> exclusions=new java.util.TreeSet<>();
    private int index; private long ignored;
    public VerifyTask(World world,BuildExpectation e,boolean placement,int samples) { super(e.bounds());this.world=world;expected=e;this.placement=placement;this.samples=samples; }
    @Override public World world() {return world;}
    @Override public long volume() {return expected.cells().size();}
    @Override public boolean step(long deadline) {
        while(index<expected.cells().size()) {
            var e=expected.cells().get(index++);var p=e.pos();Block b=world.getBlockAt(p.x(),p.y(),p.z());String actual=b.getBlockData().getAsString();
            Set<String> excluded=BuildExpectation.excluded(e.state(),placement,expected.connected());exclusions.addAll(excluded);
            boolean stateMatches=BuildExpectation.stateMatches(e.state(),actual,excluded);
            SignSnapshot sign=e.sign()!=null || BuildExpectation.material(actual).endsWith("_sign") ? SignAccess.read(b) : null;
            if(!stateMatches || (e.sign()!=null && !e.sign().equals(sign))) {
                var o=new BuildExpectation.Observed(e,actual,sign,b.getState() instanceof TileState);differences.add(o);
                String kind=stateMatches ? "sign_metadata" : BuildExpectation.kind(e,actual);kinds.merge(kind,1L,Long::sum);
                if(rows.size()<samples)rows.add(BuildExpectation.difference(o,excluded));
            } else if(!e.state().equals(actual))ignored++;
            advance(1); if(index%64==0 && System.nanoTime()>=deadline)return false;
        }
        return true;
    }
    public List<BuildExpectation.Observed> differences() {return differences;}
    @Override public JsonElement buildResult(long queuedMs,long elapsedMs) {
        JsonObject r=new JsonObject();r.addProperty("world",expected.world());r.addProperty("mode",placement ? "placement" : "exact");
        r.addProperty("checkedCells",expected.cells().size());r.addProperty("mismatchedCells",differences.size());r.addProperty("matched",differences.isEmpty());
        r.add("differences",rows);r.addProperty("differencesTruncated",differences.size()>samples);JsonObject k=new JsonObject();kinds.forEach(k::addProperty);r.add("counts",k);
        JsonArray a=new JsonArray();exclusions.forEach(a::add);r.add("excludedProperties",a);r.addProperty("ignoredConnectionCells",ignored);
        r.addProperty("elapsedMs",elapsedMs);r.addProperty("scope","Frozen eligible final cells only; skipped/untouched cells, arbitrary NBT, rich sign styling, entities and concurrent world locking are outside scope.");return r;
    }
}
