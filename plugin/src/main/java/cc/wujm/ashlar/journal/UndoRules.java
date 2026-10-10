// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.journal;

/** Per-cell decision for a journal undo. Pure; the restore task supplies the live comparisons. */
public final class UndoRules {

    private UndoRules() {
    }

    public enum Mode { SAFE, FORCE }

    public enum Decision {
        /** Live state already equals the original: nothing to write. */
        ALREADY_ORIGINAL,
        /** Live state is still what the journalled call wrote: restore it. */
        RESTORE,
        /** Force mode: live state changed since, overwrite it anyway. */
        RESTORE_OVERWRITE,
        /** Safe mode: the cell changed since the journalled call; leave it. */
        CONFLICT_CHANGED,
        /** The live cell holds a block entity (sign text, inventory) and replacement was not allowed. */
        CONFLICT_BLOCK_ENTITY
    }

    public static Decision decide(boolean liveIsOld, boolean liveIsNew, boolean liveHasBlockEntity, Mode mode,
            boolean allowBlockEntityReplacement) {
        if (liveIsOld) return Decision.ALREADY_ORIGINAL;
        if (!liveIsNew && mode == Mode.SAFE) return Decision.CONFLICT_CHANGED;
        if (liveHasBlockEntity && !allowBlockEntityReplacement) return Decision.CONFLICT_BLOCK_ENTITY;
        return liveIsNew ? Decision.RESTORE : Decision.RESTORE_OVERWRITE;
    }
}
