// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.bukkit.Bukkit;

import java.util.Map;

/**
 * {@link StateCanon.Defaults} backed by {@link Bukkit#createBlockData(String)}, a pure registry lookup
 * that CLAUDE.md allows off the main thread. {@code getAsString()} of a freshly created material lists
 * every property with its default value.
 */
public final class BukkitStateDefaults implements StateCanon.Defaults {

    public static final StateCanon CANON = new StateCanon(new BukkitStateDefaults());

    @Override
    public Map<String, String> of(String material) {
        try {
            return StateCanon.parse(Bukkit.createBlockData("minecraft:" + material).getAsString()).properties();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
