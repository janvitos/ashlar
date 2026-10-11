// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Art;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Rotation;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.EulerAngle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bukkit adapter for decorative entities (Step 17): reading, spawning and updating paintings, item
 * frames and armor stands as {@link EntitySpec}s. Main thread only, except {@link #check}, which
 * only does registry lookups. Marker armor stands (holograms of other plugins) are never touched.
 */
public final class EntityAccess {
    private EntityAccess() {}

    /** Tag on every entity Ashlar spawns; value is the journal id or "1". */
    public static final NamespacedKey PLACED = new NamespacedKey("ashlar", "placed");

    /** Thrown when one entity cannot be placed; the message names the reason. */
    public static final class Failure extends RuntimeException {
        public Failure(String message) {
            super(message, null, false, false);
        }
    }

    // ------------------------------------------------------------------ registry checks (any thread)

    public static Art art(String variant) {
        NamespacedKey key = NamespacedKey.fromString(variant);
        Art art = key == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.PAINTING_VARIANT).get(key);
        if (art == null) throw new Failure("unknown painting variant \"" + variant + "\"");
        return art;
    }

    public static Material item(String id) {
        Material m = Material.matchMaterial(id);
        if (m == null || !m.isItem() || m.isAir()) throw new Failure("unknown item \"" + id + "\"");
        return m;
    }

    /** Registry checks for every spec before anything is written; returns the first problem or null. */
    public static String check(List<EntitySpec> specs) {
        for (int i = 0; i < specs.size(); i++) {
            EntitySpec s = specs.get(i);
            try {
                if (s.variant() != null) art(s.variant());
                if (s.item() != null) item(s.item());
                for (String v : s.equipment().values()) item(v);
            } catch (Failure f) {
                return "entities[" + i + "]: " + f.getMessage();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ reading

    public static boolean decorative(Entity e) {
        return e instanceof Painting || e instanceof ItemFrame || (e instanceof ArmorStand a && !a.isMarker());
    }

    /** Entities load separately from chunks; tasks wait (a few ticks at most) until they are in. */
    public static boolean entitiesLoaded(World world, Region r) {
        for (int cx = r.minX() >> 4; cx <= r.maxX() >> 4; cx++) {
            for (int cz = r.minZ() >> 4; cz <= r.maxZ() >> 4; cz++) {
                if (!world.isChunkLoaded(cx, cz) || !world.getChunkAt(cx, cz).isEntitiesLoaded()) return false;
            }
        }
        return true;
    }

    /** Ticks a task waits for entities to load before it goes ahead anyway. */
    public static final int ENTITY_WAIT_TICKS = 100;

    public static boolean placed(Entity e) {
        return e.getPersistentDataContainer().has(PLACED, PersistentDataType.STRING);
    }

    /** Decorative entities whose cell is inside {@code box}. */
    public static List<Entity> in(World world, Region box) {
        BoundingBox bb = new BoundingBox(box.minX() - 1, box.minY() - 1, box.minZ() - 1, box.maxX() + 2, box.maxY() + 2, box.maxZ() + 2);
        List<Entity> out = new ArrayList<>();
        for (Entity e : world.getNearbyEntities(bb, EntityAccess::decorative)) {
            int[] c = read(e).cell();
            if (c[0] >= box.minX() && c[0] <= box.maxX() && c[1] >= box.minY() && c[1] <= box.maxY() && c[2] >= box.minZ() && c[2] <= box.maxZ()) {
                out.add(e);
            }
        }
        out.sort(java.util.Comparator.comparing(e -> read(e).key()));
        return out;
    }

    public static EntitySpec read(Entity e) {
        boolean placed = placed(e);
        if (e instanceof Painting p) {
            Art art = p.getArt();
            String facing = face(p.getFacing());
            BoundingBox bb = p.getBoundingBox();
            int[] min = {floor(bb.getMinX() + 1e-3), floor(bb.getMinY() + 1e-3), floor(bb.getMinZ() + 1e-3)};
            int[] max = {floor(bb.getMaxX() - 1e-3), floor(bb.getMaxY() - 1e-3), floor(bb.getMaxZ() - 1e-3)};
            return new EntitySpec(EntitySpec.PAINTING, EntityGeometry.lowerLeftFromRange(min, max, facing), null, facing, false,
                    RegistryAccess.registryAccess().getRegistry(RegistryKey.PAINTING_VARIANT).getKeyOrThrow(art).toString(), null, 0, false, false, 0f, null, null, false, false, placed);
        }
        if (e instanceof ItemFrame f) {
            ItemStack it = f.getItem();
            String item = it == null || it.getType().isAir() ? null : it.getType().getKey().toString();
            Location l = f.getLocation();
            return new EntitySpec(f instanceof GlowItemFrame ? EntitySpec.GLOW_ITEM_FRAME : EntitySpec.ITEM_FRAME,
                    new int[]{l.getBlockX(), l.getBlockY(), l.getBlockZ()}, null, face(f.getFacing()), false, null, item,
                    f.getRotation().ordinal(), f.isFixed(), !f.isVisible(), 0f, null, null, false, false, placed);
        }
        ArmorStand a = (ArmorStand) e;
        Location l = a.getLocation();
        Map<String, String> eq = new LinkedHashMap<>();
        for (String slot : EntitySpec.SLOTS) {
            ItemStack it = a.getItem(slot(slot));
            if (it != null && !it.getType().isAir()) eq.put(slot, it.getType().getKey().toString());
        }
        Map<String, double[]> pose = new LinkedHashMap<>();
        for (String part : EntitySpec.PARTS) {
            EulerAngle ang = pose(a, part);
            if (!defaultPose(part, ang)) {
                pose.put(part, new double[]{round(Math.toDegrees(ang.getX())), round(Math.toDegrees(ang.getY())), round(Math.toDegrees(ang.getZ()))});
            }
        }
        return new EntitySpec(EntitySpec.ARMOR_STAND, new int[]{l.getBlockX(), l.getBlockY(), l.getBlockZ()},
                new double[]{l.getX(), l.getY(), l.getZ()}, null, false, null, null, 0, false, !a.isVisible(),
                (float) EntityGeometry.wrapYaw(l.getYaw()), eq, pose, a.isSmall(), !a.hasGravity(), placed);
    }

    private static boolean defaultPose(String part, EulerAngle a) {
        // Armor stands start with a few non-zero limbs; report them only when changed.
        EulerAngle d = defaultAngle(part);
        return Math.abs(d.getX() - a.getX()) < 1e-4 && Math.abs(d.getY() - a.getY()) < 1e-4 && Math.abs(d.getZ() - a.getZ()) < 1e-4;
    }

    // ------------------------------------------------------------------ placing

    /** Why {@code s} cannot be placed in {@code world} now, or null. Does not look at other entities. */
    public static String blocked(World world, EntitySpec s) {
        if (s.type().equals(EntitySpec.PAINTING)) {
            Art art = art(s.variant());
            int w = art.getBlockWidth(), h = art.getBlockHeight();
            int[] ll = EntityGeometry.lowerLeft(s.cell(), s.fromRight(), w, s.facing());
            for (int[] c : EntityGeometry.cells(ll, w, h, s.facing())) {
                if (world.getBlockAt(c[0], c[1], c[2]).getType().isSolid()) return "cell " + str(c) + " of the " + w + "x" + h + " painting is not clear";
                int[] b = EntityGeometry.support(c, s.facing());
                if (!world.getBlockAt(b[0], b[1], b[2]).getType().isSolid()) return "no solid wall behind " + str(c) + " (at " + str(b) + ")";
            }
            return null;
        }
        if (s.frame()) {
            int[] c = s.cell();
            if (world.getBlockAt(c[0], c[1], c[2]).getType().isSolid()) return "cell " + str(c) + " is not clear";
            int[] b = EntityGeometry.support(c, s.facing());
            Material m = world.getBlockAt(b[0], b[1], b[2]).getType();
            if (!m.isSolid() && !Tag.ALL_SIGNS.isTagged(m)) return "no solid block to hang on at " + str(b);
            return null;
        }
        double[] at = s.exact();
        if (world.getBlockAt(floor(at[0]), floor(at[1]), floor(at[2])).getType().isSolid()) return "position " + str(s.cell()) + " is inside a solid block";
        return null;
    }

    /** Spawns {@code s}; the result is checked against the request, so a misplaced entity is removed again. */
    public static Entity spawn(World world, EntitySpec s, String tag) {
        String why = blocked(world, s);
        if (why != null) throw new Failure(why);
        Entity e;
        switch (s.type()) {
            case EntitySpec.PAINTING -> {
                Art art = art(s.variant());
                int[] ll = EntityGeometry.lowerLeft(s.cell(), s.fromRight(), art.getBlockWidth(), s.facing());
                int[] a = EntityGeometry.anchor(ll, art.getBlockWidth(), art.getBlockHeight(), s.facing());
                BlockFace face = BlockFace.valueOf(s.facing().toUpperCase(Locale.ROOT));
                e = world.spawn(new Location(world, a[0] + 0.5, a[1] + 0.5, a[2] + 0.5), Painting.class, p -> {
                    p.setFacingDirection(face, true);
                    p.setArt(art, true);
                    tag(p, tag);
                });
            }
            case EntitySpec.ITEM_FRAME, EntitySpec.GLOW_ITEM_FRAME -> {
                Class<? extends ItemFrame> cls = s.type().equals(EntitySpec.GLOW_ITEM_FRAME) ? GlowItemFrame.class : ItemFrame.class;
                int[] c = s.cell();
                e = world.spawn(new Location(world, c[0] + 0.5, c[1] + 0.5, c[2] + 0.5), cls, f -> {
                    f.setFacingDirection(BlockFace.valueOf(s.facing().toUpperCase(Locale.ROOT)), true);
                    frameValues(f, s);
                    tag(f, tag);
                });
            }
            default -> {
                double[] at = s.exact();
                e = world.spawn(new Location(world, at[0], at[1], at[2], s.yaw(), 0f), ArmorStand.class, a -> {
                    standValues(a, s);
                    tag(a, tag);
                });
            }
        }
        EntitySpec got = read(e);
        String want = s.type().equals(EntitySpec.PAINTING) ? lowerLeftKey(s) : s.key();
        boolean wrongArt = s.variant() != null && !s.variant().equals(got.variant());
        if (!s.type().equals(EntitySpec.ARMOR_STAND) && (!got.key().equals(want) || wrongArt)) {
            e.remove();
            throw new Failure("the server placed it as " + got.describe() + " instead; nothing kept");
        }
        return e;
    }

    /** A painting request with its lower-left cell resolved; other specs unchanged. */
    public static EntitySpec resolve(EntitySpec s) {
        if (!s.type().equals(EntitySpec.PAINTING) || !s.fromRight()) return s;
        int[] ll = EntityGeometry.lowerLeft(s.cell(), true, art(s.variant()).getBlockWidth(), s.facing());
        return new EntitySpec(s.type(), ll, null, s.facing(), false, s.variant(), null, 0, false, false, 0f, null, null, false, false, s.placed());
    }

    /** Key of a painting request with its lower-left cell resolved (requests may name the lower-right cell). */
    private static String lowerLeftKey(EntitySpec s) {
        Art art = art(s.variant());
        int[] ll = EntityGeometry.lowerLeft(s.cell(), s.fromRight(), art.getBlockWidth(), s.facing());
        return new EntitySpec(s.type(), ll, null, s.facing(), false, s.variant(), null, 0, false, false, 0f, null, null, false, false, false).key();
    }

    /** Sets the decorative values of an existing entity (same type, cell and facing) to {@code s}. */
    public static void update(Entity e, EntitySpec s) {
        if (e instanceof Painting p) {
            p.setArt(art(s.variant()), true);
        } else if (e instanceof ItemFrame f) {
            frameValues(f, s);
        } else if (e instanceof ArmorStand a) {
            double[] at = s.exact();
            a.teleport(new Location(e.getWorld(), at[0], at[1], at[2], s.yaw(), 0f));
            standValues(a, s);
        }
    }

    private static void frameValues(ItemFrame f, EntitySpec s) {
        f.setItem(s.item() == null ? null : new ItemStack(item(s.item())), false);
        f.setRotation(Rotation.values()[s.rotation()]);
        f.setFixed(s.fixed());
        f.setVisible(!s.invisible());
    }

    private static void standValues(ArmorStand a, EntitySpec s) {
        a.setSmall(s.small());
        a.setVisible(!s.invisible());
        a.setGravity(!s.noGravity());
        for (String slot : EntitySpec.SLOTS) {
            String id = s.equipment().get(slot);
            a.setItem(slot(slot), id == null ? null : new ItemStack(item(id)));
        }
        for (String part : EntitySpec.PARTS) {
            double[] d = s.pose().get(part);
            EulerAngle ang = d == null ? defaultAngle(part) : new EulerAngle(Math.toRadians(d[0]), Math.toRadians(d[1]), Math.toRadians(d[2]));
            switch (part) {
                case "head" -> a.setHeadPose(ang);
                case "body" -> a.setBodyPose(ang);
                case "leftArm" -> a.setLeftArmPose(ang);
                case "rightArm" -> a.setRightArmPose(ang);
                case "leftLeg" -> a.setLeftLegPose(ang);
                default -> a.setRightLegPose(ang);
            }
        }
    }

    private static EulerAngle defaultAngle(String part) {
        return switch (part) {
            case "leftArm" -> new EulerAngle(Math.toRadians(-10), 0, Math.toRadians(-10));
            case "rightArm" -> new EulerAngle(Math.toRadians(-15), 0, Math.toRadians(10));
            case "leftLeg" -> new EulerAngle(Math.toRadians(-1), 0, Math.toRadians(-1));
            case "rightLeg" -> new EulerAngle(Math.toRadians(1), 0, Math.toRadians(1));
            default -> EulerAngle.ZERO;
        };
    }

    private static EulerAngle pose(ArmorStand a, String part) {
        return switch (part) {
            case "head" -> a.getHeadPose();
            case "body" -> a.getBodyPose();
            case "leftArm" -> a.getLeftArmPose();
            case "rightArm" -> a.getRightArmPose();
            case "leftLeg" -> a.getLeftLegPose();
            default -> a.getRightLegPose();
        };
    }

    private static EquipmentSlot slot(String s) {
        return switch (s) {
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            case "mainHand" -> EquipmentSlot.HAND;
            default -> EquipmentSlot.OFF_HAND;
        };
    }

    private static void tag(Entity e, String tag) {
        if (tag != null) e.getPersistentDataContainer().set(PLACED, PersistentDataType.STRING, tag);
    }

    private static String face(BlockFace f) {
        return f.name().toLowerCase(Locale.ROOT);
    }

    private static int floor(double d) {
        return (int) Math.floor(d);
    }

    private static double round(double d) {
        return Math.round(d * 100) / 100.0;
    }

    private static String str(int[] c) {
        return c[0] + "," + c[1] + "," + c[2];
    }
}
