// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.protect;

import cc.wujm.ashlar.engine.Region;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One named, persistent box that writing tools refuse to touch ({@link Mode#DENY}) or only warn
 * about ({@link Mode#WARN}) unless the call overrides it by name. {@code owner} is the creator as
 * {@code KIND:id}, the same form the journal and verification stores use.
 */
public record ProtectedRegion(String name, String world, Region box, Mode mode, String note, String owner, Instant createdAt) {

    public enum Mode {
        DENY, WARN;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Mode parse(String raw) {
            return switch (raw) {
                case "deny" -> DENY;
                case "warn" -> WARN;
                default -> throw new IllegalArgumentException("mode must be \"deny\" or \"warn\"");
            };
        }
    }

    /** Lowercase letters, digits, '-' and '_'; starts with a letter or digit; at most 64 characters. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    public static final int MAX_NOTE = 200;

    public ProtectedRegion {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name must be 1-64 lowercase letters, digits, '-' or '_', starting with a letter or digit");
        }
        if (world == null || world.isBlank()) throw new IllegalArgumentException("world must not be blank");
        if (note != null && note.length() > MAX_NOTE) throw new IllegalArgumentException("note must be at most " + MAX_NOTE + " characters");
    }

    public boolean intersects(Region r) {
        return r.minX() <= box.maxX() && r.maxX() >= box.minX()
                && r.minY() <= box.maxY() && r.maxY() >= box.minY()
                && r.minZ() <= box.maxZ() && r.maxZ() >= box.minZ();
    }

    public boolean contains(int x, int y, int z) {
        return x >= box.minX() && x <= box.maxX() && y >= box.minY() && y <= box.maxY() && z >= box.minZ() && z <= box.maxZ();
    }

    /** The overlap of {@code r} with this region, or {@code null} when they do not intersect. */
    public Region intersection(Region r) {
        if (!intersects(r)) return null;
        return new Region(Math.max(r.minX(), box.minX()), Math.max(r.minY(), box.minY()), Math.max(r.minZ(), box.minZ()),
                Math.min(r.maxX(), box.maxX()), Math.min(r.maxY(), box.maxY()), Math.min(r.maxZ(), box.maxZ()));
    }
}
