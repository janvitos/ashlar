// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SignSnapshotTest {
    @Test void patchesOnlySpecifiedFaceAndPreservesOtherValues(){var before=SignSnapshot.empty().patch(new SignData(null,List.of("Back","","",""),"blue",true,true));var after=before.patch(new SignData(List.of("Front","","",""),null,"red",false,false));assertEquals(before.back(),after.back());assertEquals("Front",after.front().lines().getFirst());assertEquals("red",after.front().color());assertFalse(after.waxed());}
    @Test void unspecifiedColorIsPreserved(){var s=SignSnapshot.empty().patch(new SignData(List.of("A","","",""),null,"blue",true,false)).patch(new SignData(List.of("B","","",""),null,null,false,true));assertEquals("blue",s.front().color());assertFalse(s.front().glowing());assertTrue(s.waxed());}
    @Test void faceDefensivelyCopies(){var lines=new ArrayList<>(List.of("A","","",""));var face=new SignSnapshot.Face(lines,"black",false);lines.set(0,"B");assertEquals("A",face.lines().getFirst());assertThrows(UnsupportedOperationException.class,() -> face.lines().clear());}
    @Test void requiresFourLines(){assertThrows(IllegalArgumentException.class,() -> new SignSnapshot.Face(List.of("A"),"black",false));}
}
