// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.AnomalyHit;
import cc.wujm.ashlar.engine.BuildExpectation;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.StateCanon;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Pure comparison of live sub-boxes against a {@link DiffReference}. Parts must be fed in y,z,x order
 * ({@link DiffPlan} guarantees it), so the first {@code samples} differences are already sorted.
 * States compare by canonical form ({@link StateCanon}); the three air variants compare equal.
 */
final class DiffAccumulator {

    static final int MAX_TRANSITIONS = 10_000;
    static final int MAX_COLUMNS = 100_000;
    static final List<String> ANOMALY_ORDER = List.of("floating", "stacked", "unsupported", "enclosedAir");

    record Settings(boolean declaredOnly, boolean materialOnly, int samples, int receiptCap, boolean enclosedAir) {
    }

    record Sample(int x, int y, int z, String kind, String expected, String found) {
    }

    /** Per-palette view of one state source (reference, baseline or a live part). */
    private final class Source {
        final int[] key;
        final boolean[] air, ignored;
        final String[] shown;

        Source(List<String> palette) {
            int n = palette.size();
            key = new int[n];
            air = new boolean[n];
            ignored = new boolean[n];
            shown = new String[n];
            for (int i = 0; i < n; i++) {
                String raw = palette.get(i);
                String c = safeCanon(raw);
                String material = StateCanon.material(c);
                air[i] = StateCanon.isAir(material);
                shown[i] = air[i] ? "air" : settings.materialOnly() ? material : c;
                key[i] = keys.computeIfAbsent(shown[i], k -> keys.size());
                ignored[i] = ignore.test(raw);
            }
        }
    }

    private final Region box;
    private final int dx, dy, dz;
    private final DiffReference ref, baseline;
    private final Settings settings;
    private final Function<String, String> canon;
    private final Predicate<String> ignore;
    private final Map<String, Integer> keys = new HashMap<>();
    private final Source refSource, baseSource;

    long compared, ignoredCells, undeclared, missing, unexpected, wrongMaterial, wrongProperties;
    private final Map<String, Long> transitions = new HashMap<>();
    private long otherTransitions;
    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
    private final Map<Long, int[]> columns = new HashMap<>();
    private boolean columnsTruncated;
    final List<Sample> samples = new ArrayList<>();
    final List<BuildExpectation.Observed> receipt = new ArrayList<>();
    private final BitSet airBits;
    final Map<String, Long> anomalyCounts = new LinkedHashMap<>();
    final Map<String, List<AnomalyHit>> anomalyHits = new LinkedHashMap<>();

    DiffAccumulator(DiffReference ref, DiffReference baseline, Settings settings, Function<String, String> canon, Predicate<String> ignore) {
        this.box = ref.box;
        this.dx = box.maxX() - box.minX() + 1;
        this.dy = box.maxY() - box.minY() + 1;
        this.dz = box.maxZ() - box.minZ() + 1;
        this.ref = ref;
        this.baseline = baseline;
        this.settings = settings;
        this.canon = canon;
        this.ignore = ignore;
        this.refSource = new Source(ref.palette);
        this.baseSource = baseline == null ? null : new Source(baseline.palette);
        this.airBits = settings.enclosedAir() ? new BitSet(Math.toIntExact(box.volume())) : null;
    }

    private String safeCanon(String raw) {
        try {
            return canon.apply(raw);
        } catch (IllegalArgumentException e) {
            // A state the running server no longer knows (old snapshot): compare it verbatim.
            String m = StateCanon.material(raw);
            int open = raw.indexOf('[');
            return open < 0 ? m : m + raw.substring(open).toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Compares the {@code inner} cells of one live part read over {@code outer}. */
    void feed(Region inner, Region outer, List<String> livePalette, int[] grid) {
        Source live = new Source(livePalette);
        int odx = outer.maxX() - outer.minX() + 1, odz = outer.maxZ() - outer.minZ() + 1;
        for (int y = inner.minY(); y <= inner.maxY(); y++) {
            for (int z = inner.minZ(); z <= inner.maxZ(); z++) {
                int o = ((y - outer.minY()) * odz + (z - outer.minZ())) * odx + (inner.minX() - outer.minX());
                int b = ref.boxIndex(inner.minX(), y, z);
                for (int x = inner.minX(); x <= inner.maxX(); x++, o++, b++) {
                    int l = grid[o];
                    if (airBits != null && live.air[l]) airBits.set(b);
                    Source src = refSource;
                    int r = ref.cells[b];
                    if (r == 0) {
                        if (settings.declaredOnly() || baseline == null || baseline.cells[b] == 0) { undeclared++; continue; }
                        src = baseSource;
                        r = baseline.cells[b];
                    }
                    r--;
                    if (src.ignored[r] || live.ignored[l]) { ignoredCells++; continue; }
                    compared++;
                    if (src.key[r] == live.key[l]) continue;
                    record(x, y, z, src == refSource ? ref.palette.get(r) : baseline.palette.get(r), src.shown[r], src.air[r],
                            livePalette.get(l), live.shown[l], live.air[l]);
                }
            }
        }
    }

    private void record(int x, int y, int z, String refRaw, String refShown, boolean refAir, String liveRaw, String liveShown, boolean liveAir) {
        String kind;
        if (refAir && !liveAir) { kind = "unexpected"; unexpected++; }
        else if (!refAir && liveAir) { kind = "missing"; missing++; }
        else if (!StateCanon.material(refShown).equals(StateCanon.material(liveShown))) { kind = "wrong-material"; wrongMaterial++; }
        else { kind = "wrong-properties"; wrongProperties++; }
        String t = refShown + " -> " + liveShown;
        Long n = transitions.get(t);
        if (n != null) transitions.put(t, n + 1);
        else if (transitions.size() < MAX_TRANSITIONS) transitions.put(t, 1L);
        else otherTransitions++;
        minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        long col = ((long) x << 32) | (z & 0xFFFFFFFFL);
        int[] c = columns.get(col);
        if (c != null) { c[0]++; c[2] = y; }
        else if (columns.size() < MAX_COLUMNS) columns.put(col, new int[]{1, y, y});
        else columnsTruncated = true;
        if (samples.size() < settings.samples()) samples.add(new Sample(x, y, z, kind, refShown, liveShown));
        if (receipt.size() < settings.receiptCap()) {
            var pos = new BuildExpectation.Pos(x, y, z);
            receipt.add(new BuildExpectation.Observed(new BuildExpectation.Cell(pos, refRaw, null), liveRaw, null, false));
        }
    }

    /** Merges one part's anomaly counts and samples (parts arrive in y,z,x order). */
    void addAnomalies(Map<String, Long> counts, List<AnomalyHit> hits) {
        counts.forEach((k, v) -> anomalyCounts.merge(k, v, Long::sum));
        for (AnomalyHit h : hits) {
            List<AnomalyHit> list = anomalyHits.computeIfAbsent(h.kind(), k -> new ArrayList<>());
            if (list.size() < settings.samples()) list.add(h);
        }
    }

    /** Air cells in the box that are not 6-connected to an air cell on the box boundary. */
    void finishEnclosedAir() {
        if (airBits == null) return;
        int volume = Math.toIntExact(box.volume());
        BitSet open = new BitSet(volume);
        int[] queue = new int[Math.max(1, airBits.cardinality())];
        int head = 0, tail = 0;
        for (int i = airBits.nextSetBit(0); i >= 0; i = airBits.nextSetBit(i + 1)) {
            int x = i % dx, z = (i / dx) % dz, y = i / (dx * dz);
            if (x == 0 || x == dx - 1 || z == 0 || z == dz - 1 || y == 0 || y == dy - 1) { open.set(i); queue[tail++] = i; }
        }
        while (head < tail) {
            int i = queue[head++];
            int x = i % dx, z = (i / dx) % dz, y = i / (dx * dz);
            if (x > 0) tail = visit(i - 1, open, queue, tail);
            if (x < dx - 1) tail = visit(i + 1, open, queue, tail);
            if (z > 0) tail = visit(i - dx, open, queue, tail);
            if (z < dz - 1) tail = visit(i + dx, open, queue, tail);
            if (y > 0) tail = visit(i - dx * dz, open, queue, tail);
            if (y < dy - 1) tail = visit(i + dx * dz, open, queue, tail);
        }
        BitSet enclosed = (BitSet) airBits.clone();
        enclosed.andNot(open);
        long count = enclosed.cardinality();
        anomalyCounts.merge("enclosedAir", count, Long::sum);
        List<AnomalyHit> list = anomalyHits.computeIfAbsent("enclosedAir", k -> new ArrayList<>());
        for (int i = enclosed.nextSetBit(0); i >= 0 && list.size() < settings.samples(); i = enclosed.nextSetBit(i + 1)) {
            list.add(new AnomalyHit("enclosedAir", box.minX() + i % dx, box.minY() + i / (dx * dz), box.minZ() + (i / dx) % dz,
                    "air", "air pocket not connected to the box boundary"));
        }
    }

    private int visit(int j, BitSet open, int[] queue, int tail) {
        if (airBits.get(j) && !open.get(j)) { open.set(j); queue[tail++] = j; }
        return tail;
    }

    long differing() {
        return missing + unexpected + wrongMaterial + wrongProperties;
    }

    // ------------------------------------------------------------------ text

    private static String pos(int x, int y, int z) {
        return "[" + x + "," + y + "," + z + "]";
    }

    String summaryLine() {
        return "Compared " + compared + " cells; " + differing() + " differ: missing " + missing + ", unexpected " + unexpected
                + ", wrong-material " + wrongMaterial + ", wrong-properties " + wrongProperties + ".";
    }

    String detailLine() {
        StringBuilder s = new StringBuilder("Skipped: " + undeclared + " undeclared, " + ignoredCells + " ignored");
        if (ref.outside > 0) s.append(", ").append(ref.outside).append(" reference cells outside the box");
        return s.append('.').toString();
    }

    List<String> bodyLines(String format) {
        List<String> out = new ArrayList<>();
        if (differing() > 0) out.add("Differences bbox: " + pos(minX, minY, minZ) + ".." + pos(maxX, maxY, maxZ));
        if (format.equals("columns")) {
            out.addAll(columnLines());
        } else if (!transitions.isEmpty()) {
            out.add("Transitions (expected -> found):");
            transitions.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(20).forEach(e -> out.add("  " + e.getKey() + ": " + e.getValue()));
            if (transitions.size() > 20 || otherTransitions > 0) {
                long shown = transitions.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(20).mapToLong(Map.Entry::getValue).sum();
                out.add("  (" + (differing() - shown) + " more cells in other transitions)");
            }
        }
        out.addAll(anomalyLines(format.equals("cells") ? settings.samples() : 5));
        if (!format.equals("columns") && !samples.isEmpty()) {
            int limit = format.equals("cells") ? samples.size() : Math.min(5, samples.size());
            out.add("Cells (first " + limit + " of " + differing() + ", y,z,x order):");
            for (Sample s : samples.subList(0, limit)) {
                out.add("  " + pos(s.x(), s.y(), s.z()) + " " + s.kind() + ": expected " + s.expected() + ", found " + s.found());
            }
        }
        return out;
    }

    private List<String> columnLines() {
        List<String> out = new ArrayList<>();
        if (columns.isEmpty()) return out;
        TreeMap<Long, int[]> sorted = new TreeMap<>(Comparator.comparingInt((Long k) -> (int) (k >> 32)).thenComparingInt(k -> (int) (long) k));
        sorted.putAll(columns);
        out.add("Columns with differences (" + columns.size() + (columnsTruncated ? "+" : "") + "; x,z: cells, y range):");
        int shown = 0;
        for (var e : sorted.entrySet()) {
            if (shown++ >= 1024) { out.add("  ... " + (columns.size() - 1024) + " more columns"); break; }
            int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
            int[] c = e.getValue();
            out.add("  " + x + "," + z + ": " + c[0] + " (y " + c[1] + ".." + c[2] + ")");
        }
        return out;
    }

    private List<String> anomalyLines(int perKind) {
        List<String> out = new ArrayList<>();
        if (anomalyCounts.isEmpty()) return out;
        List<String> parts = new ArrayList<>();
        for (String k : ANOMALY_ORDER) if (anomalyCounts.containsKey(k)) parts.add(k + " " + anomalyCounts.get(k));
        out.add("Anomalies: " + String.join(", ", parts));
        for (String k : ANOMALY_ORDER) {
            List<AnomalyHit> hits = anomalyHits.getOrDefault(k, List.of());
            for (AnomalyHit h : hits.subList(0, Math.min(perKind, hits.size()))) {
                out.add("  " + k + " " + pos(h.x(), h.y(), h.z()) + " " + shortState(h.state()) + " (" + h.reason() + ")");
            }
        }
        return out;
    }

    private String shortState(String raw) {
        return safeCanon(raw);
    }
}
