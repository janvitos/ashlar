// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A vanilla syntax error is reported as text instead of failing the call. */
class CommandErrorsTest {

    /** Stands in for brigadier's exception, matched by simple name. */
    static final class CommandSyntaxException extends Exception {
        CommandSyntaxException(String message) {
            super(message);
        }
    }

    @Test
    void findsTheSyntaxErrorInTheCauseChain() {
        var e = new RuntimeException("Unhandled exception executing 'time query daytime'",
                new CommandSyntaxException("Can't find element 'minecraft:daytime' at position 18"));
        assertEquals("Can't find element 'minecraft:daytime' at position 18", CommandErrors.syntaxError(e));
    }

    @Test
    void otherExceptionsAreNotSyntaxErrors() {
        assertNull(CommandErrors.syntaxError(new RuntimeException("boom", new IllegalStateException("plugin bug"))));
        assertNull(CommandErrors.syntaxError(new RuntimeException("no cause")));
    }
}
