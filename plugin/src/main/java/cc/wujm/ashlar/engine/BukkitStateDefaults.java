// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * {@link StateCanon.Defaults} backed by {@link Bukkit#createBlockData(String)}, a pure registry lookup
 * that CLAUDE.md allows off the main thread. {@code getAsString()} of a freshly created material lists
 * every property with its default value.
 */
public final class BukkitStateDefaults implements StateCanon.Defaults {

    public static final StateCanon CANON = new StateCanon(new BukkitStateDefaults());
    public static final StateDiagnosis DIAGNOSIS = new StateDiagnosis(new StateDiagnosis.Probe() {
        private volatile List<String> blocks;

        @Override public Map<String, String> defaults(String material) {
            return new BukkitStateDefaults().of(material);
        }

        @Override public boolean valid(String state) {
            try {
                Bukkit.createBlockData(state);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        @Override public Collection<String> blocks() {
            List<String> b = blocks;
            if (b == null) blocks = b = Arrays.stream(Material.values()).filter(m -> m.isBlock() && !m.isLegacy())
                    .map(m -> m.getKey().getKey()).toList();
            return b;
        }
    });

    /** Why {@code state} does not parse, e.g. {@code oak_stairs facing=up is invalid; facing: north|south|west|east}. */
    public static String diagnose(String state) {
        try {
            return DIAGNOSIS.diagnose(state);
        } catch (RuntimeException e) {
            return "not a valid block state";
        }
    }

    @Override
    public Map<String, String> of(String material) {
        try {
            return StateCanon.parse(Bukkit.createBlockData("minecraft:" + material).getAsString()).properties();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
