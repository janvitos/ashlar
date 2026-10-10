// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import java.util.List;
import java.util.Objects;

/**
 * Guarded sparse repairs. No connection pass, neighbor refresh, physics or matching-cell writes.
 * {@code liveEntities} (mc_diff receipts): the guard compares block states only and detects block
 * entities from the live block, because diff receipts carry neither sign values nor entity flags.
 */
public final class RepairTask extends BuildTask {
    private final World world; private final List<BuildExpectation.Observed> observations; private final List<SparseOp> targets;
    private final boolean checkOnly,allowEntities,liveEntities; private final JsonArray issues=new JsonArray();
    private int scan,write; private long stale,protectedCells,written,signWrites;
    public RepairTask(Region bounds,World world,List<BuildExpectation.Observed> observations,List<SparseOp> targets,boolean checkOnly,boolean allowEntities) {
        this(bounds,world,observations,targets,checkOnly,allowEntities,false);
    }
    public RepairTask(Region bounds,World world,List<BuildExpectation.Observed> observations,List<SparseOp> targets,boolean checkOnly,boolean allowEntities,boolean liveEntities) {
        super(bounds);this.world=world;this.observations=observations;this.targets=targets;this.checkOnly=checkOnly;this.allowEntities=allowEntities;this.liveEntities=liveEntities;
    }
    @Override public World world() {return world;}
    @Override public long volume() {return observations.size();}
    private boolean unchanged(Block b,BuildExpectation.Observed o) {
        if(!b.getBlockData().getAsString().equals(o.actual()))return false;
        if(liveEntities)return true;
        SignSnapshot sign=o.sign()!=null || BuildExpectation.material(o.actual()).endsWith("_sign") ? SignAccess.read(b) : null;
        return Objects.equals(sign,o.sign());
    }
    private void issue(BuildExpectation.Observed o,String reason) {
        if(issues.size()<100) {JsonObject r=new JsonObject();r.add("pos",o.expected().pos().json());r.addProperty("reason",reason);issues.add(r);}
    }
    @Override public boolean step(long deadline) {
        // Complete guard pass before this task writes anything, even when the caller skipped snapshots.
        while(scan<observations.size()) {
            var o=observations.get(scan);var p=o.expected().pos();Block b=world.getBlockAt(p.x(),p.y(),p.z());
            if(!unchanged(b,o)){stale++;issue(o,"observed block/sign values changed; compare again");}
            boolean entity=liveEntities ? b.getState(false) instanceof TileState : o.blockEntity();
            if(!allowEntities && entity && !BuildExpectation.material(o.actual()).equals(BuildExpectation.material(o.expected().state()))) {
                protectedCells++;issue(o,"existing block entity replacement/deletion requires allowBlockEntityReplacement:true; snapshots do not back up NBT");
            }
            scan++;if(scan%64==0 && System.nanoTime()>=deadline)return false;
        }
        if(checkOnly || stale>0 || protectedCells>0)return true;
        while(write<observations.size()) {
            var o=observations.get(write);var p=o.expected().pos();Block b=world.getBlockAt(p.x(),p.y(),p.z());BlockData target=targets.get(write).block();
            // An edit after the guard pass/snapshot is never blindly overwritten.
            if(!unchanged(b,o)){stale++;issue(o,"late edit skipped; compare again");}
            else {
                boolean blockChanged=!b.getBlockData().equals(target);
                if(blockChanged)b.setBlockData(target,false);
                boolean signChanged=o.expected().sign()!=null && !o.expected().sign().equals(SignAccess.read(b));
                if(signChanged){SignAccess.apply(b,o.expected().sign());signWrites++;}
                if(blockChanged || signChanged){written++;addChanged(1);}
            }
            write++;advance(1);if(write%64==0 && System.nanoTime()>=deadline)return false;
        }
        return true;
    }
    @Override public JsonElement buildResult(long queuedMs,long elapsedMs) {
        JsonObject r=new JsonObject();r.addProperty("selectedCells",observations.size());r.addProperty("writtenCells",written);r.addProperty("signWrites",signWrites);
        r.addProperty("staleCells",stale);r.addProperty("protectedBlockEntities",protectedCells);r.addProperty("guardPassed",stale==0 && protectedCells==0);
        r.add("issues",issues);r.addProperty("issuesTruncated",stale+protectedCells>100);r.addProperty("elapsedMs",elapsedMs);return r;
    }
}
