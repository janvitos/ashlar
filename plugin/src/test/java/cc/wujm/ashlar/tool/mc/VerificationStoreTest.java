// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.BuildExpectation;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VerificationStoreTest {
    private static BuildExpectation expectation(int n) {return new BuildExpectation("world",Region.of(new int[]{0,100,0},new int[]{n,100,0}),java.util.stream.IntStream.range(0,n).mapToObj(i -> new BuildExpectation.Cell(new BuildExpectation.Pos(i,100,0),"minecraft:stone",null)).toList(),false,false);}
    private static final class MutableClock extends Clock {Instant now=Instant.parse("2026-01-01T00:00:00Z");@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return now;}}
    @Test void ownerScopedGetListDelete(){var s=new VerificationStore();var p=s.put("one",expectation(1));assertSame(p,s.get(p.id,"one"));assertTrue(s.list("two").isEmpty());assertThrows(ToolArgError.class,() -> s.get(p.id,"two"));assertThrows(ToolArgError.class,() -> s.delete(p.id,"two"));s.delete(p.id,"one");assertThrows(ToolArgError.class,() -> s.get(p.id,"one"));}
    @Test void planQuotaRejectsWithoutEviction(){var s=new VerificationStore(Clock.systemUTC(),1,20,Duration.ofHours(1));var p=s.put("owner",expectation(1));assertThrows(ToolArgError.class,() -> s.put("owner",expectation(1)));assertSame(p,s.get(p.id,"owner"));}
    @Test void aggregateCellQuotaAndDeletion(){var s=new VerificationStore(Clock.systemUTC(),5,3,Duration.ofHours(1));var p=s.put("owner",expectation(2));assertThrows(ToolArgError.class,() -> s.put("owner",expectation(2)));s.put("owner",expectation(1));s.delete(p.id,"owner");s.put("owner",expectation(2));}
    @Test void expiryRejectsAndReleasesCapacity(){var clock=new MutableClock();var s=new VerificationStore(clock,1,1,Duration.ofHours(1));var p=s.put("owner",expectation(1));clock.now=clock.now.plusSeconds(3600);assertThrows(ToolArgError.class,() -> s.get(p.id,"owner"));assertNotNull(s.put("owner",expectation(1)));}
    @Test void busyPlanCannotBeDeletedOrAcquiredAgain(){var s=new VerificationStore();var p=s.put("owner",expectation(1));s.acquire(p);assertThrows(ToolArgError.class,() -> s.acquire(p));assertThrows(ToolArgError.class,() -> s.delete(p.id,"owner"));s.release(p);s.acquire(p);s.release(p);s.delete(p.id,"owner");assertThrows(ToolArgError.class,() -> s.acquire(p));}
    @Test void operationCanFinishAcrossExpiry(){var clock=new MutableClock();var s=new VerificationStore(clock,1,1,Duration.ofHours(1));var p=s.put("owner",expectation(1));s.acquire(p);clock.now=clock.now.plusSeconds(3601);assertSame(p,s.get(p.id,"owner"));s.release(p);assertThrows(ToolArgError.class,() -> s.get(p.id,"owner"));}
    @Test void newerComparisonInvalidatesOldId(){var s=new VerificationStore();var p=s.put("owner",expectation(1));var first=s.record(p,false,List.of());var second=s.record(p,true,List.of());assertThrows(ToolArgError.class,() -> s.comparison(p,first.id()));assertSame(second,s.comparison(p,second.id()));}
    @Test void comparisonDefensivelyCopies(){var s=new VerificationStore();var p=s.put("owner",expectation(1));var d=new ArrayList<BuildExpectation.Observed>();d.add(new BuildExpectation.Observed(p.expectation.cells().getFirst(),"minecraft:air",null,false));var c=s.record(p,false,d);d.clear();assertEquals(1,c.differences().size());assertThrows(UnsupportedOperationException.class,() -> c.differences().clear());}
}
