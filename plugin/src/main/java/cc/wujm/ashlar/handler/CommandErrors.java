// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.handler;

/** Recognises command failures that are the caller's mistake rather than a server fault. Pure Java. */
final class CommandErrors {
    private CommandErrors() {}

    /** The message of a brigadier {@code CommandSyntaxException} in {@code e}'s cause chain, or null. */
    static String syntaxError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getClass().getSimpleName().equals("CommandSyntaxException")) {
                return t.getMessage() == null ? "invalid command syntax" : t.getMessage();
            }
        }
        return null;
    }
}
