// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.PluginConfig;
import cc.wujm.ashlar.engine.RequestValidator;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code mc_build} with {@code cells} or {@code cellsFile} (Step 16): merges sparse cells into fill
 * boxes ({@link CellMerge}), splits them into parts within the per-operation limits, validates and
 * protection-checks every part before the first write, then builds the parts one after another
 * under one journal entry. A part that fails stops the run and reports a resume token.
 */
final class CellBuild {
    private CellBuild() {}

    static final long MAX_CELLS = 2_000_000;
    static final int MAX_INLINE = CellFiles.MAX_STAGE_CALL;
    static final int MAX_PARTS = 1000;
    static final int PART_CHUNKS = 256;
    static final int LISTED_PARTS = 12;
    private static final Set<String> KEYS = Set.of("cells", "cellsFile", "world", "connect", "liquids", "snapshot", "label", "journal",
            "override", "detailed", "dryRun", "resume");
    private static final Pattern RESUME = Pattern.compile("([0-9a-f]{12}):(\\d+)");
    private static final Pattern CHANGED = Pattern.compile("(?m)^(?:Fills|Blocks|Text): (\\d+)/(\\d+) changed");

    record Plan(CellSet cells, List<CellMerge.Part> parts, List<McBuild.Args> args, String fingerprint, int start) {}

    static boolean applies(JsonObject args) {
        return ArgParse.has(args, "cells") || ArgParse.has(args, "cellsFile");
    }

    /** Reads, merges and splits the cells; no world access. */
    static Plan plan(JsonObject args, PluginConfig cfg, CellFiles files) {
        for (String k : args.keySet()) {
            if (!KEYS.contains(k)) throw new ToolArgError("with cells or cellsFile, mc_build accepts only " + String.join(", ", KEYS.stream().sorted().toList())
                    + "; got " + k);
        }
        if (ArgParse.has(args, "cells") == ArgParse.has(args, "cellsFile")) throw new ToolArgError("give exactly one of cells or cellsFile");
        CellSet.Builder builder;
        if (ArgParse.has(args, "cells")) {
            JsonArray a = ArgParse.requireArray(args, "cells");
            if (a.size() > MAX_INLINE) {
                throw new ToolArgError("cells: at most " + MAX_INLINE + " inline; stage larger sets with mc_blueprint action \"stage\" and build them with cellsFile");
            }
            builder = new CellSet.Builder(MAX_CELLS, "cells");
            int[] xyz = new int[3];
            for (int i = 0; i < a.size(); i++) {
                String s = CellFiles.cell(a.get(i), "cells[" + i + "]", xyz);
                builder.add(xyz[0], xyz[1], xyz[2], s);
            }
        } else {
            if (files == null) throw new ToolArgError("cell files are unavailable");
            String name = CellFiles.name(ArgParse.requireString(args, "cellsFile"), "cellsFile");
            Path p = files.find(name);
            if (p == null) throw new ToolArgError("cellsFile '" + name + "': no finished staged upload or export with that name (finalize staged uploads first)");
            builder = new CellSet.Builder(MAX_CELLS, "cellsFile '" + name + "'");
            CellFiles.read(p, "cellsFile '" + name + "'", builder::add);
        }
        CellSet set = builder.build();

        boolean snapshot = ArgParse.optBoolean(args, "snapshot", false);
        JsonObject options = new JsonObject();
        if (ArgParse.has(args, "liquids")) options.add("liquids", args.get("liquids"));
        boolean flow = new RequestValidator(cfg).resolveLiquidsFlow(options);
        if (snapshot && !cfg.snapshot().enabled()) throw new ToolArgError("snapshot is disabled in config.yml");
        long envelope = snapshot ? cfg.snapshot().maxVolume() : Long.MAX_VALUE;
        var limits = cfg.limits();
        List<CellMerge.Box> boxes = CellMerge.merge(set, Math.min(limits.maxBlocksPerOperation(), envelope));
        List<CellMerge.Part> parts = CellMerge.pack(boxes, set.palette, new CellMerge.Limits(limits.maxBlocksPerOperation(),
                Math.min(limits.maxChunksPerOperation(), PART_CHUNKS), flow ? limits.maxFlowingLiquidsPerOperation() : Long.MAX_VALUE, envelope));
        if (parts.size() > MAX_PARTS) throw new ToolArgError("cells split into " + parts.size() + " parts, more than " + MAX_PARTS + "; build a smaller area per call");

        String world = ArgParse.optString(args, "world");
        Boolean connect = ArgParse.optBooleanNullable(args, "connect");
        String liquids = ArgParse.optEnum(args, "liquids", List.of("static", "flow"), null);
        String fp = fingerprint(set, snapshot, liquids, connect, world);
        int start = 1;
        if (ArgParse.has(args, "resume")) {
            Matcher m = RESUME.matcher(ArgParse.requireString(args, "resume"));
            if (!m.matches()) throw new ToolArgError("resume: must be the token from a stopped cells build, like \"0123456789ab:3\"");
            if (!m.group(1).equals(fp)) throw new ToolArgError("resume: the token belongs to different cells or options; nothing was written");
            start = Integer.parseInt(m.group(2));
            if (start < 1 || start > parts.size()) throw new ToolArgError("resume: part must be 1-" + parts.size());
        }
        List<McBuild.Args> out = new ArrayList<>();
        for (CellMerge.Part p : parts) {
            List<McBuild.FillOpArg> fills = new ArrayList<>(p.boxes().size());
            for (CellMerge.Box b : p.boxes()) {
                fills.add(new McBuild.FillOpArg(new int[]{b.x0(), b.y0(), b.z0()}, new int[]{b.x1(), b.y1(), b.z1()}, set.palette.get(b.id()), null, null));
            }
            out.add(new McBuild.Args(world, fills, List.of(), List.of(), snapshot, connect, liquids));
        }
        return new Plan(set, parts, out, fp, start);
    }

    static CompletableFuture<String> run(McBuild build, InvocationContext ctx, JsonObject args, PluginConfig cfg, CellFiles files) {
        Plan plan = plan(args, cfg, files);
        int total = plan.parts().size();
        List<String> override = ProtectionGuard.parseOverride(args);
        long cells = 0;
        for (int k = plan.start(); k <= total; k++) cells += plan.parts().get(k - 1).volume();
        McBuild.JournalArgs.parse(args).requireFits(cfg.journal(), cells, total > 1 ? "this cells build" : "this build");
        CompletableFuture<Void> checks = CompletableFuture.completedFuture(null);
        for (int k = plan.start(); k <= total; k++) {
            McBuild.Args a = plan.args().get(k - 1);
            checks = checks.thenCompose(v -> build.validatePart(a));
        }
        return checks.thenCompose(v -> {
            Set<String> protection = new LinkedHashSet<>();
            for (int k = plan.start(); k <= total; k++) protection.addAll(build.protect(ctx, plan.args().get(k - 1), override));
            String head = summary(plan);
            if (ArgParse.optBoolean(args, "dryRun", false)) {
                List<String> lines = new ArrayList<>(List.of(head, "Dry run: every part passed validation; nothing was written."));
                lines.addAll(protection);
                return CompletableFuture.completedFuture(String.join("\n", lines));
            }
            return build.journalledCells(ctx, args, jctx -> parts(build, jctx, plan)).thenApply(text -> {
                List<String> lines = new ArrayList<>(List.of(head, text));
                lines.addAll(protection);
                return String.join("\n", lines);
            });
        });
    }

    private static CompletableFuture<String> parts(McBuild build, InvocationContext ctx, Plan plan) {
        int total = plan.parts().size();
        List<String> lines = new ArrayList<>();
        long[] sums = {0, 0};
        int[] failedAt = {0};
        String[] error = {null};
        McBuild.DeferredWarnings deferred = new McBuild.DeferredWarnings();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int k = plan.start(); k <= total; k++) {
            int part = k;
            CellMerge.Part p = plan.parts().get(k - 1);
            McBuild.Args a = plan.args().get(k - 1);
            chain = chain.thenCompose(v -> {
                if (failedAt[0] != 0) return CompletableFuture.completedFuture(null);
                return build.runPart(ctx, a, deferred).handle((text, t) -> {
                    if (t != null) {
                        failedAt[0] = part;
                        error[0] = message(t);
                        return null;
                    }
                    Matcher m = CHANGED.matcher(text);
                    long changed = 0, requested = 0;
                    while (m.find()) {
                        changed += Long.parseLong(m.group(1));
                        requested += Long.parseLong(m.group(2));
                    }
                    sums[0] += changed;
                    sums[1] += requested;
                    if (lines.size() < LISTED_PARTS) {
                        lines.add(String.format(Locale.ROOT, "Part %d/%d (%s, %d boxes): %d/%d changed", part, total,
                                p.phase().name().toLowerCase(Locale.ROOT), p.boxes().size(), changed, requested) + extra(text));
                    }
                    return null;
                });
            });
        }
        // Parts check support as they go; a later part may hold up a block an earlier one flagged.
        CompletableFuture<List<String>> warnings = chain.thenComposeAsync(v -> build.recheckedWarningLines(ctx, plan.args().getFirst().world(), deferred));
        return warnings.thenApply(warningLines -> {
            int done = (failedAt[0] == 0 ? total : failedAt[0] - 1) - plan.start() + 1;
            List<String> out = new ArrayList<>();
            out.add((failedAt[0] == 0 ? "Built " : "PARTIAL: built ") + done + " of " + (total - plan.start() + 1) + " part(s): "
                    + sums[0] + "/" + sums[1] + " cells changed.");
            out.addAll(lines);
            if (done > lines.size()) out.add("... and " + (done - lines.size()) + " more part(s).");
            if (failedAt[0] != 0) {
                out.add("Stopped at part " + failedAt[0] + "/" + total + ": " + error[0]);
                out.add("Parts before it stay built. Fix the cause, then repeat this call with resume:\"" + plan.fingerprint() + ":" + failedAt[0] + "\".");
            }
            out.addAll(warningLines);
            return String.join("\n", out);
        });
    }

    /** Warning and chest lines of one part's result, kept on the same line. */
    private static String extra(String text) {
        List<String> rest = new ArrayList<>();
        for (String l : text.split("\n")) if (!CHANGED.matcher(l).find() && !l.isBlank()) rest.add(l.trim());
        return rest.isEmpty() ? "" : "; " + String.join("; ", rest);
    }

    static String summary(Plan plan) {
        long[] perPhase = new long[3];
        long[] boxes = new long[3];
        for (CellMerge.Part p : plan.parts()) {
            boxes[p.phase().ordinal()] += p.boxes().size();
            for (CellMerge.Box b : p.boxes()) perPhase[p.phase().ordinal()] += b.volume();
        }
        String s = String.format(Locale.ROOT, "Cells: %d (solid %d, air %d, late %d) merged into %d boxes (%d, %d, %d) in %d part(s)",
                plan.cells().size(), perPhase[0], perPhase[1], perPhase[2], boxes[0] + boxes[1] + boxes[2], boxes[0], boxes[1], boxes[2],
                plan.parts().size());
        return s + (plan.start() > 1 ? "; resuming at part " + plan.start() + "." : ".");
    }

    static String fingerprint(CellSet set, boolean snapshot, String liquids, Boolean connect, String world) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update((snapshot + "|" + liquids + "|" + connect + "|" + world + "|").getBytes(StandardCharsets.UTF_8));
            byte[] buf = new byte[12];
            for (int i = 0; i < set.size(); i++) {
                long k = set.keys[i];
                for (int b = 0; b < 8; b++) buf[b] = (byte) (k >>> (56 - 8 * b));
                int h = set.state(i).hashCode();
                for (int b = 0; b < 4; b++) buf[8 + b] = (byte) (h >>> (24 - 8 * b));
                d.update(buf);
            }
            return HexFormat.of().formatHex(d.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String message(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
