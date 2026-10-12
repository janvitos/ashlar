#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Find large, untouched areas of one biome by reading a world's region files offline.

Never touches the running server, so it is safe where /locate is not (mc_command refuses
/locate: it searches on the main thread and can crash the server). Reads .mca files
read-only at low priority. Samples one chunk every 64 blocks (8 x 8 per region), counts
the vertical sections whose biome palette contains the biome, and reads InhabitedTime
(ticks players have spent nearby). A sample counts when at least --min-sections sections
are the biome and InhabitedTime is under --max-inhabited minutes. Prints the largest
squares made only of counting samples, at least --spacing blocks apart. Confirm a
candidate with mc_survey before building: region files lag the live world until autosave.

Usage: python3 -I findsite.py <world>/region --biome minecraft:desert [--near X Z --max-dist N]
"""
import argparse
import os
import struct
import sys
import zlib
from multiprocessing import Pool

STEP = 64  # blocks between samples: chunks 2, 6, ..., 30 of each region


def scan(job):
    path, rx, rz, needle, inhabited = job
    out, skipped = [], 0
    with open(path, 'rb') as f:
        hdr = f.read(4096)
        for lz in range(2, 32, 4):
            for lx in range(2, 32, 4):
                off = struct.unpack_from('>I', hdr, 4 * (lx + lz * 32))[0]
                if not off:
                    continue
                try:
                    f.seek((off >> 8) * 4096)
                    n, comp = struct.unpack('>IB', f.read(5))
                    if comp != 2:  # 2 = zlib, the default; others (LZ4, external) are counted as skipped
                        skipped += 1
                        continue
                    raw = zlib.decompress(f.read(n - 1))
                except (OSError, struct.error, zlib.error):
                    skipped += 1
                    continue
                m = raw.find(inhabited)
                it = struct.unpack_from('>q', raw, m + len(inhabited))[0] if m >= 0 else -1
                out.append((rx * 32 + lx, rz * 32 + lz, raw.count(needle), it))
    return out, skipped


def main():
    p = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    p.add_argument('region_dir')
    p.add_argument('--biome', required=True, help='e.g. minecraft:desert')
    p.add_argument('--min-sections', type=int, default=6, help='sections of the 24 that must be the biome (default 6)')
    p.add_argument('--max-inhabited', type=float, default=5, help='minutes of player time allowed per chunk (default 5)')
    p.add_argument('--near', type=int, nargs=2, metavar=('X', 'Z'), default=(0, 0))
    p.add_argument('--max-dist', type=int, default=0, help='only candidates within this many blocks of --near (0: any)')
    p.add_argument('--spacing', type=int, default=1500, help='blocks between listed candidates (default 1500)')
    p.add_argument('--top', type=int, default=20)
    p.add_argument('--workers', type=int, default=4)
    a = p.parse_args()

    biome = a.biome if ':' in a.biome else 'minecraft:' + a.biome
    # NBT strings are length-prefixed, so minecraft:desert does not match minecraft:desert_pyramid.
    needle = struct.pack('>H', len(biome)) + biome.encode()
    inhabited = b'\x04' + struct.pack('>H', 13) + b'InhabitedTime'
    jobs = []
    for fn in os.listdir(a.region_dir):
        parts = fn.split('.')
        if len(parts) == 4 and parts[0] == 'r' and parts[3] == 'mca':
            jobs.append((os.path.join(a.region_dir, fn), int(parts[1]), int(parts[2]), needle, inhabited))
    if not jobs:
        sys.exit('no r.X.Z.mca files in ' + a.region_dir)
    os.nice(10)
    with Pool(a.workers) as pool:
        res = pool.map(scan, jobs, chunksize=16)
    samples = [s for r, _ in res for s in r]
    skipped = sum(k for _, k in res)

    limit = a.max_inhabited * 60 * 20
    ok = {(cx // 4, cz // 4) for cx, cz, n, it in samples if n >= a.min_sections and 0 <= it < limit}
    # Largest all-ok square ending at each sample (grid of 64-block cells).
    size = {}
    for gx, gz in sorted(ok, key=lambda k: (k[1], k[0])):
        size[(gx, gz)] = 1 + min(size.get((gx, gz - 1), 0), size.get((gx - 1, gz), 0), size.get((gx - 1, gz - 1), 0))
    picked = []
    for (gx, gz), s in sorted(size.items(), key=lambda kv: -kv[1]):
        if s < 2 or len(picked) >= a.top:
            break
        x = round((gx - s / 2 + 0.5) * STEP + 40)  # sample chunk centre: chunk 2 of 4 -> +40
        z = round((gz - s / 2 + 0.5) * STEP + 40)
        dist = round(((x - a.near[0]) ** 2 + (z - a.near[1]) ** 2) ** 0.5)
        if a.max_dist and dist > a.max_dist:
            continue
        if any(abs(x - px) < a.spacing and abs(z - pz) < a.spacing for px, pz, _, _ in picked):
            continue
        picked.append((x, z, s * STEP, dist))

    print(f'{len(jobs)} regions, {len(samples)} chunks sampled, {skipped} skipped, {len(ok)} matching {biome}')
    if not picked:
        print('no candidate square of at least 2 samples (128 blocks)')
    for x, z, side, dist in picked:
        print(f'centre {x},{z}  square ~{side} blocks  {dist} blocks from {a.near[0]},{a.near[1]}')


if __name__ == '__main__':
    main()
