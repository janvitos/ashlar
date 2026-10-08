// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

/** Pure geometry shared by readonly planning and its tests. */
public final class PlanGeometry {
    private PlanGeometry() {}

    /** 0 means untouched, 1 means the supplied target, 2 means hollow-interior air. */
    public static int target(FillMode mode, Region r, int x, int y, int z) {
        boolean shell = x == r.minX() || x == r.maxX() || y == r.minY() || y == r.maxY()
                || z == r.minZ() || z == r.maxZ();
        return switch (mode) {
            case REPLACE, KEEP -> 1;
            case OUTLINE -> shell ? 1 : 0;
            case HOLLOW -> shell ? 1 : 2;
            case WALLS -> WallGeometry.isWallCell(x, z, r) ? 1 : 0;
        };
    }

    public static long checkedVolume(Region r) {
        try {
            return Math.multiplyExact(Math.multiplyExact((long) r.maxX() - r.minX() + 1,
                    (long) r.maxY() - r.minY() + 1), (long) r.maxZ() - r.minZ() + 1);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("build bounds volume overflows a signed 64-bit integer");
        }
    }
}
