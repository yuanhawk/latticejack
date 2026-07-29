package com.latticejack.pqc;

import java.util.Arrays;

/** Minimal, dependency-free percentile/mean helpers for the benchmark harness. */
final class Stats {
    private Stats() {}

    static long[] sorted(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy;
    }

    /** Nearest-rank percentile over an already-sorted array. */
    static double percentile(long[] sortedValues, double p) {
        if (sortedValues.length == 0) {
            return Double.NaN;
        }
        int idx = (int) Math.ceil(p / 100.0 * sortedValues.length) - 1;
        idx = Math.max(0, Math.min(sortedValues.length - 1, idx));
        return sortedValues[idx];
    }

    static double mean(long[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return (double) sum / values.length;
    }
}
