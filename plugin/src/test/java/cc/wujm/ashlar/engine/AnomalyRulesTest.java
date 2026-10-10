// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import cc.wujm.ashlar.engine.AnomalyRules.Below;
import cc.wujm.ashlar.engine.AnomalyRules.Kind;
import cc.wujm.ashlar.engine.AnomalyRules.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnomalyRulesTest {
    private static final Below STONE = new Below(false, true, true, true, true, 0, false, false);
    private static final Below FENCE = new Below(false, true, false, false, true, 0, false, false);
    private static final Below SLAB = new Below(false, true, false, true, true, 0, false, false);
    private static Below snow(int layers) { return new Below(false, true, layers == 8, layers == 8, layers == 8, layers, false, false); }

    @Test void snowLayers() {
        assertEquals(Result.OK, AnomalyRules.evaluate(Kind.SNOW, STONE));
        assertEquals(Result.FLOATING, AnomalyRules.evaluate(Kind.SNOW, Below.AIR));
        assertEquals(Result.FLOATING, AnomalyRules.evaluate(Kind.SNOW, FENCE));
        assertEquals(Result.STACKED, AnomalyRules.evaluate(Kind.SNOW, snow(3)));
        assertEquals(Result.OK, AnomalyRules.evaluate(Kind.SNOW, snow(8)));
        assertEquals(Result.FLOATING, AnomalyRules.evaluate(Kind.SNOW, new Below(false, true, true, true, true, 0, false, true)));
        assertEquals(Result.OK, AnomalyRules.evaluate(Kind.SNOW, new Below(false, true, false, false, false, 0, true, false)));
        assertTrue(AnomalyRules.reason(Kind.SNOW, Result.STACKED, snow(3)).contains("layers=3"));
    }

    @Test void otherKinds() {
        assertEquals(Result.OK, AnomalyRules.evaluate(Kind.CARPET, FENCE));
        assertEquals(Result.FLOATING, AnomalyRules.evaluate(Kind.CARPET, Below.AIR));
        assertEquals(Result.OK, AnomalyRules.evaluate(Kind.PLATE, FENCE));
        assertEquals(Result.FLOATING, AnomalyRules.evaluate(Kind.RAIL, FENCE));
        assertEquals(Result.OK, AnomalyRules.evaluate(Kind.RAIL, SLAB));
        assertEquals(Result.OK, AnomalyRules.evaluate(Kind.TORCH, FENCE));
        assertEquals(Result.FLOATING, AnomalyRules.evaluate(Kind.LANTERN, Below.AIR));
        assertEquals(Result.FLOATING, AnomalyRules.evaluate(Kind.PLANT, Below.AIR));
        assertEquals(Result.OK, AnomalyRules.evaluate(Kind.NONE, Below.AIR));
        assertEquals("nothing below", AnomalyRules.reason(Kind.PLANT, Result.FLOATING, Below.AIR));
    }
}
