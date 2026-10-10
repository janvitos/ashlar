// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.text.TextExpand;
import cc.wujm.ashlar.log.OperationLog;
import cc.wujm.ashlar.protect.ProtectedRegion;
import cc.wujm.ashlar.protect.ProtectedRegions;
import cc.wujm.ashlar.protect.ProtectionCheck;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Applies protected regions (Step 13) to the write targets of one tool call, before any snapshot or
 * write. A {@code deny} region rejects the call, a {@code warn} region adds a warning line, and a
 * region named in the call's {@code override} list is written as requested and logged. Pure apart
 * from logging; safe on any thread.
 */
public final class ProtectionGuard {

    static final int MAX_OVERRIDE = 32;

    private final ProtectedRegions regions;
    private final ConfigHolder config;
    private final OperationLog operations;
    private final Logger logger;

    public ProtectionGuard(ProtectedRegions regions, ConfigHolder config, OperationLog operations, Logger logger) {
        this.regions = regions;
        this.config = config;
        this.operations = operations;
        this.logger = logger;
    }

    /** {@code override}: an optional list of unique region names; no wildcard. */
    static List<String> parseOverride(JsonObject args) {
        if (!ArgParse.has(args, "override")) return List.of();
        JsonArray arr = ArgParse.requireArray(args, "override");
        if (arr.size() > MAX_OVERRIDE) throw new ToolArgError("override: at most " + MAX_OVERRIDE + " region names");
        Set<String> names = new LinkedHashSet<>();
        for (JsonElement el : arr) {
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) throw new ToolArgError("override: must contain only region names");
            String name = el.getAsString();
            if (!ProtectedRegion.NAME.matcher(name).matches()) {
                throw new ToolArgError("override: '" + name + "' is not a region name (no wildcards; name each region explicitly)");
            }
            if (!names.add(name)) throw new ToolArgError("override: '" + name + "' is listed twice");
        }
        return List.copyOf(names);
    }

    /** The overlaps of one call, split by what happens to them. */
    record Verdict(List<ProtectionCheck.Overlap> denied, List<ProtectionCheck.Overlap> warned, List<ProtectionCheck.Overlap> overridden) {
        boolean blocked() {
            return !denied.isEmpty();
        }

        boolean any() {
            return blocked() || !warned.isEmpty() || !overridden.isEmpty();
        }

        /** {@code [{name, mode, cells, from, to, status}]} for planning reports. */
        JsonArray toJson() {
            JsonArray out = new JsonArray();
            for (var o : denied) out.add(row(o, "denied"));
            for (var o : warned) out.add(row(o, "warn"));
            for (var o : overridden) out.add(row(o, "overridden"));
            return out;
        }

        /** Adds {@code protected} and {@code protectedPass} to a planning report when anything overlaps. */
        void addTo(JsonObject report) {
            if (!any()) return;
            report.add("protected", toJson());
            report.addProperty("protectedPass", !blocked());
        }

        List<String> lines() {
            List<String> out = new ArrayList<>();
            for (var o : warned) {
                out.add("Protection warning: region '" + o.region().name() + "' (warn) covers " + o.cells() + " target cells of this call in "
                        + span(o.bounds()) + "; written anyway." + note(o.region()));
            }
            for (var o : overridden) {
                out.add("Protection override: region '" + o.region().name() + "' (" + o.region().mode().id() + ") - " + o.cells()
                        + " target cells in " + span(o.bounds()) + " written as requested; the override is logged.");
            }
            return out;
        }

        /** {@link #lines()} plus what a real call would reject, for dry runs. */
        List<String> previewLines() {
            List<String> out = new ArrayList<>();
            for (var o : denied) {
                out.add("Protection: region '" + o.region().name() + "' (deny) covers " + o.cells() + " target cells in " + span(o.bounds())
                        + "; the real call would be rejected without override:[\"" + o.region().name() + "\"].");
            }
            out.addAll(lines());
            return out;
        }

        String rejection() {
            List<String> out = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (var o : denied) {
                names.add("\"" + o.region().name() + "\"");
                out.add("Protected region '" + o.region().name() + "' (deny) covers " + o.cells() + " target cells of this call in "
                        + span(o.bounds()) + "." + note(o.region()));
            }
            out.add("Nothing was written and no snapshot was taken. Only if the user explicitly wants to change that area, repeat the call with"
                    + " override:[" + String.join(",", names) + "].");
            return String.join("\n", out);
        }

        private static JsonObject row(ProtectionCheck.Overlap o, String status) {
            JsonObject r = new JsonObject();
            r.addProperty("name", o.region().name());
            r.addProperty("mode", o.region().mode().id());
            r.addProperty("cells", o.cells());
            r.add("from", ProtectedRegions.point(o.bounds().minX(), o.bounds().minY(), o.bounds().minZ()));
            r.add("to", ProtectedRegions.point(o.bounds().maxX(), o.bounds().maxY(), o.bounds().maxZ()));
            r.addProperty("status", status);
            return r;
        }

        private static String note(ProtectedRegion r) {
            return r.note() == null ? "" : " Note: " + r.note();
        }
    }

    static String span(Region b) {
        return "[" + b.minX() + "," + b.minY() + "," + b.minZ() + "]..[" + b.maxX() + "," + b.maxY() + "," + b.maxZ() + "]";
    }

    /** Collects targets in {@code world} ({@code null} = the default world) and splits the overlaps by {@code override}. */
    Verdict evaluate(String world, List<String> override, Consumer<ProtectionCheck> targets) {
        for (String name : override) {
            if (regions.get(name) == null) throw new ToolArgError("override: no protected region named '" + name + "'");
        }
        String name = world != null ? world : config.get().world().defaultWorld();
        ProtectionCheck check = new ProtectionCheck(regions.inWorld(name));
        if (!check.empty()) targets.accept(check);
        List<ProtectionCheck.Overlap> denied = new ArrayList<>(), warned = new ArrayList<>(), overridden = new ArrayList<>();
        for (var o : check.overlaps()) {
            if (override.contains(o.region().name())) overridden.add(o);
            else if (o.region().mode() == ProtectedRegion.Mode.DENY) denied.add(o);
            else warned.add(o);
        }
        return new Verdict(denied, warned, overridden);
    }

    /**
     * Rejects the call when a deny region is hit without an override, otherwise logs every override
     * use and returns the warning/override lines for the result.
     */
    List<String> enforce(InvocationContext ctx, String tool, String world, List<String> override, Consumer<ProtectionCheck> targets) {
        Verdict v = evaluate(world, override, targets);
        if (v.blocked()) throw new ToolArgError(v.rejection());
        for (var o : v.overridden()) {
            String who = VerificationService.owner(ctx);
            logger.info("Protected region '" + o.region().name() + "' overridden by " + who + " (" + ctx.principal().display() + ") via "
                    + tool + ": " + o.cells() + " target cells in " + span(o.bounds()));
            if (operations != null) operations.append(who, tool + " protect-override " + o.region().name(), "ok", o.cells());
        }
        return v.lines();
    }

    /** A guard bound to one call, for services that find their write targets late. */
    record Gate(ProtectionGuard guard, InvocationContext ctx, String tool, List<String> override) {
        List<String> enforce(String world, Consumer<ProtectionCheck> targets) {
            return guard.enforce(ctx, tool, world, override, targets);
        }

        /** Reports without rejecting or logging, for dry runs. */
        List<String> preview(String world, Consumer<ProtectionCheck> targets) {
            return guard.evaluate(world, override, targets).previewLines();
        }
    }

    Gate gate(InvocationContext ctx, String tool, List<String> override) {
        return new Gate(this, ctx, tool, override);
    }

    /** Every write target of a direct or compiled {@code mc_build} request (world coordinates). */
    static Consumer<ProtectionCheck> targets(McBuild.Args a) {
        return check -> {
            for (McBuild.FillOpArg f : a.fills()) check.box(Region.of(f.from(), f.to()), shape(f.mode()));
            for (McBuild.TextArg t : a.text()) {
                if (t.background() != null) check.box(Region.of(t.expanded().bboxMin(), t.expanded().bboxMax()), ProtectionCheck.Shape.SOLID);
                for (TextExpand.Run run : t.expanded().inkRuns()) check.box(Region.of(run.from(), run.to()), ProtectionCheck.Shape.SOLID);
            }
            for (McBuild.SparseOpArg b : a.blocks()) check.cell(b.pos()[0], b.pos()[1], b.pos()[2]);
        };
    }

    /** Every cell of a journal entry (an undo may write any of them). */
    static Consumer<ProtectionCheck> cells(cc.wujm.ashlar.journal.JournalCells cells) {
        return check -> {
            long[] pos = cells.pos();
            for (long p : pos) check.cell(cc.wujm.ashlar.journal.JournalRecorder.x(p), cc.wujm.ashlar.journal.JournalRecorder.y(p),
                    cc.wujm.ashlar.journal.JournalRecorder.z(p));
        };
    }

    /** The expected positions of selected repair cells. */
    static Consumer<ProtectionCheck> cells(List<cc.wujm.ashlar.engine.BuildExpectation.Observed> selected) {
        return check -> {
            for (var o : selected) {
                var p = o.expected().pos();
                check.cell(p.x(), p.y(), p.z());
            }
        };
    }

    /** {@code outline} writes only the shell and {@code walls} only the sides; keep and filters count as the whole box. */
    static ProtectionCheck.Shape shape(String mode) {
        if (mode == null) return ProtectionCheck.Shape.SOLID;
        return switch (mode) {
            case "outline" -> ProtectionCheck.Shape.SHELL;
            case "walls" -> ProtectionCheck.Shape.SIDES;
            default -> ProtectionCheck.Shape.SOLID;
        };
    }
}
