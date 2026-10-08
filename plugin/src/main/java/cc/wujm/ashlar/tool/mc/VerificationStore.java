// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.BuildExpectation;
import cc.wujm.ashlar.tool.ToolArgError;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Bounded, owner-scoped ephemeral expectations. Reload/restart intentionally invalidates IDs. */
public final class VerificationStore {
    public record Comparison(String id,boolean placement,List<BuildExpectation.Observed> differences) {
        public Comparison { differences=List.copyOf(differences); }
    }
    public static final class Plan {
        public final String id,owner; public final Instant created,expires; public final BuildExpectation expectation;
        private Comparison comparison; private boolean busy;
        Plan(String id,String owner,Instant now,Duration ttl,BuildExpectation e) { this.id=id;this.owner=owner;created=now;expires=now.plus(ttl);expectation=e; }
    }
    private final Map<String,Plan> plans=new LinkedHashMap<>();
    private final Clock clock; private final int maxPlans; private final long maxCells; private final Duration ttl;
    public VerificationStore() { this(Clock.systemUTC(),32,1_000_000,Duration.ofHours(2)); }
    VerificationStore(Clock clock,int maxPlans,long maxCells,Duration ttl) { this.clock=clock;this.maxPlans=maxPlans;this.maxCells=maxCells;this.ttl=ttl; }
    private void purge() { Instant now=clock.instant();plans.values().removeIf(p -> !p.busy && !now.isBefore(p.expires)); }
    public synchronized Plan put(String owner,BuildExpectation e) {
        purge(); long cells=plans.values().stream().mapToLong(p -> p.expectation.cells().size()).sum();
        if (plans.size()>=maxPlans || e.cells().size()>maxCells-cells) throw new ToolArgError("verification storage quota exceeded; delete old plans");
        Plan p=new Plan("plan-"+UUID.randomUUID(),owner,clock.instant(),ttl,e);plans.put(p.id,p);return p;
    }
    public synchronized Plan get(String id,String owner) {
        purge();Plan p=plans.get(id);if(p==null || !p.owner.equals(owner))throw new ToolArgError("verification plan not found or expired");return p;
    }
    public synchronized List<Plan> list(String owner) { purge();return plans.values().stream().filter(p -> p.owner.equals(owner)).toList(); }
    public synchronized void delete(String id,String owner) { Plan p=get(id,owner);if(p.busy)throw new ToolArgError("verification plan is busy");plans.remove(id); }
    public synchronized void acquire(Plan p) { if(plans.get(p.id)!=p)throw new ToolArgError("verification plan expired");if(p.busy)throw new ToolArgError("verification plan is busy");p.busy=true; }
    public synchronized void release(Plan p) { p.busy=false; }
    public synchronized Comparison comparison(Plan p,String id) {
        if(p.comparison==null || !p.comparison.id().equals(id))throw new ToolArgError("comparison is stale or missing; run mc_verify check again");return p.comparison;
    }
    public synchronized Comparison record(Plan p,boolean placement,List<BuildExpectation.Observed> differences) {
        Comparison c=new Comparison("comparison-"+UUID.randomUUID(),placement,differences);p.comparison=c;return c;
    }
}
