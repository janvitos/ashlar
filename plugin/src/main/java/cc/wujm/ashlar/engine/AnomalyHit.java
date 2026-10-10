// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

/** One {@code mc_diff} anomaly sample: kind (floating/stacked/unsupported/enclosedAir), position, live state, reason. */
public record AnomalyHit(String kind, int x, int y, int z, String state, String reason) {
}
