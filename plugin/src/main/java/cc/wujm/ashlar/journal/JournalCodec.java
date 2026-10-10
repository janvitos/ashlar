// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.journal;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Gzip binary form of {@link JournalCells}: magic {@code AJRN}, version, palette (modified UTF-8
 * strings), cell count, then per cell the packed position and two palette indices.
 */
public final class JournalCodec {

    static final int MAGIC = 0x414A524E;
    static final int VERSION = 1;
    private static final int MAX_PALETTE = 1 << 20;
    private static final int MAX_CELLS = 50_000_000;

    private JournalCodec() {
    }

    public static void write(JournalCells cells, OutputStream raw) throws IOException {
        GZIPOutputStream gz = new GZIPOutputStream(raw, 1 << 16);
        DataOutputStream out = new DataOutputStream(gz);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(cells.palette().size());
        for (String s : cells.palette()) out.writeUTF(s);
        out.writeInt(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            out.writeLong(cells.pos()[i]);
            out.writeInt(cells.oldState()[i]);
            out.writeInt(cells.newState()[i]);
        }
        out.flush();
        gz.finish();
    }

    public static JournalCells read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new GZIPInputStream(raw, 1 << 16));
        if (in.readInt() != MAGIC) throw new IOException("not a journal file");
        int version = in.readInt();
        if (version != VERSION) throw new IOException("unsupported journal version " + version);
        int paletteSize = in.readInt();
        if (paletteSize < 0 || paletteSize > MAX_PALETTE) throw new IOException("bad palette size " + paletteSize);
        List<String> palette = new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) palette.add(in.readUTF());
        int n = in.readInt();
        if (n < 0 || n > MAX_CELLS) throw new IOException("bad cell count " + n);
        long[] pos = new long[n];
        int[] o = new int[n];
        int[] w = new int[n];
        for (int i = 0; i < n; i++) {
            pos[i] = in.readLong();
            o[i] = in.readInt();
            w[i] = in.readInt();
            if (o[i] < 0 || o[i] >= paletteSize || w[i] < 0 || w[i] >= paletteSize) throw new IOException("bad palette index");
        }
        return new JournalCells(palette, pos, o, w);
    }
}
