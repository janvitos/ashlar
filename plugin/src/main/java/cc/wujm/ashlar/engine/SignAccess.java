// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.DyeColor;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import java.util.Locale;

/** Main-thread-only adapter for supported sign values. Never used by pure manifest tests. */
public final class SignAccess {
    private SignAccess() {}
    public static SignSnapshot read(Block b) {
        if (!(b.getState() instanceof Sign s)) return null;
        return new SignSnapshot(read(s.getSide(Side.FRONT)),read(s.getSide(Side.BACK)),s.isWaxed());
    }
    private static SignSnapshot.Face read(SignSide s) {
        var plain=PlainTextComponentSerializer.plainText();
        return new SignSnapshot.Face(java.util.stream.IntStream.range(0,4).mapToObj(i -> plain.serialize(s.line(i))).toList(),s.getColor().name().toLowerCase(Locale.ROOT),s.isGlowingText());
    }
    /** Only different managed fields change, preserving unchanged rich-text lines. */
    public static void apply(Block b,SignSnapshot expected) {
        if (!(b.getState() instanceof Sign s)) throw new IllegalStateException("repair target has no sign entity");
        apply(s.getSide(Side.FRONT),expected.front());apply(s.getSide(Side.BACK),expected.back());
        if(s.isWaxed()!=expected.waxed())s.setWaxed(expected.waxed());s.update(true,false);
    }
    private static void apply(SignSide s,SignSnapshot.Face f) {
        var plain=PlainTextComponentSerializer.plainText();
        for(int i=0;i<4;i++)if(!plain.serialize(s.line(i)).equals(f.lines().get(i)))s.line(i,Component.text(f.lines().get(i)));
        DyeColor c=DyeColor.valueOf(f.color().toUpperCase(Locale.ROOT));
        if(s.getColor()!=c)s.setColor(c);if(s.isGlowingText()!=f.glowing())s.setGlowingText(f.glowing());
    }
}
