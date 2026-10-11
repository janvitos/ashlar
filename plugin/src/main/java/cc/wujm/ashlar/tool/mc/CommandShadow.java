// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import java.util.Locale;

/**
 * Step 19 {@code mc_command}: finds a plugin command that takes over a vanilla one (EssentialsX
 * {@code tp}, {@code kill}, {@code give}...) so the reply can say so, and rewrites the first token to
 * {@code minecraft:<label>} for {@code vanilla:true}. Pure Java over a {@link Lookup}.
 */
final class CommandShadow {
    private CommandShadow() {}

    /** The command map as seen by the console. */
    interface Lookup {
        /** Owning plugin of the command registered under {@code label}, {@code ""} for a server/vanilla command, or null when none. */
        String owner(String label);

        /** Whether a command is registered under {@code label} (used with {@code minecraft:<label>}). */
        boolean exists(String label);
    }

    /** {@code plugin} owns {@code label}; {@code vanilla} tells whether {@code minecraft:<label>} exists. */
    record Result(String label, String plugin, boolean vanilla) {
        boolean shadows() {
            return plugin != null && vanilla;
        }
    }

    /** The first token in lower case, or null for an empty or namespaced ({@code ns:cmd}) label. */
    static String label(String command) {
        String first = command.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return first.isEmpty() || first.contains(":") ? null : first;
    }

    /** Null when the label is namespaced or unknown. */
    static Result check(String command, Lookup lookup) {
        String label = label(command);
        if (label == null) return null;
        String owner = lookup.owner(label);
        boolean vanilla = lookup.exists("minecraft:" + label);
        if (owner == null && !vanilla) return null;
        return new Result(label, owner == null || owner.isEmpty() ? null : owner, vanilla);
    }

    /** {@code command} with its first token replaced by {@code minecraft:<label>}. */
    static String vanilla(String command, String label) {
        String s = command.strip();
        int space = s.indexOf(' ');
        return "minecraft:" + label + (space < 0 ? "" : s.substring(space));
    }
}
