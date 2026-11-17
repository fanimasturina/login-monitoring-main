package com.ilm.core.ml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Isolation Forest (Liu, Ting and Zhou, 2008) written in plain Java.
 * Unsupervised: it needs no labelled incidents. Anomalies are isolated by fewer random
 * splits, so a short average path means a high score. Score is in (0,1); about 0.5 is normal.
 */
public final class IsolationForest {
    private static final double EULER = 0.5772156649015329;

    private static final class Tree {
        int[] feature;   // -1 = leaf
        double[] split;
        int[] left;
        int[] right;
        int[] size;      // training rows that reached the node
    }

    private final int nFeatures;
    private final int sampleSize;
    private final Tree[] trees;
    /** Per-feature median of the training data; used to explain a score. */
    private final double[] baseline;
    private final double cNorm;

    private IsolationForest(int nFeatures, int sampleSize, Tree[] trees, double[] baseline) {
        this.nFeatures = nFeatures;
        this.sampleSize = sampleSize;
        this.trees = trees;
        this.baseline = baseline;
        this.cNorm = c(sampleSize);
    }

    public int featureCount() { return nFeatures; }
    public int treeCount() { return trees.length; }
    public int sampleSize() { return sampleSize; }

    public static IsolationForest fit(double[][] data, int nTrees, int sampleSize, long seed) {
        if (data.length < 2) throw new IllegalArgumentException("Need at least 2 rows to train");
        int nf = data[0].length;
        int psi = Math.min(sampleSize, data.length);
        int maxDepth = (int) Math.ceil(Math.log(psi) / Math.log(2));
        Random rnd = new Random(seed);
        Tree[] trees = new Tree[nTrees];
        for (int t = 0; t < nTrees; t++) {
            int[] idx = sample(data.length, psi, rnd);
            Builder b = new Builder(data, rnd, maxDepth);
            b.build(idx, 0);
            trees[t] = b.toTree();
        }
        double[] base = new double[nf];
        double[] colv = new double[data.length];
        for (int f = 0; f < nf; f++) {
            for (int i = 0; i < data.length; i++) colv[i] = data[i][f];
            double[] sorted = colv.clone();
            Arrays.sort(sorted);
            base[f] = sorted[sorted.length / 2];
        }
        return new IsolationForest(nf, psi, trees, base);
    }

    private static int[] sample(int n, int k, Random rnd) {
        int[] all = new int[n];
        for (int i = 0; i < n; i++) all[i] = i;
        for (int i = 0; i < k; i++) {
            int j = i + rnd.nextInt(n - i);
            int tmp = all[i]; all[i] = all[j]; all[j] = tmp;
        }
        return Arrays.copyOf(all, k);
    }

    private static final class Builder {
        final double[][] data;
        final Random rnd;
        final int maxDepth;
        final List<double[]> nodes = new ArrayList<>(); // {feature, split, left, right, size}

        Builder(double[][] data, Random rnd, int maxDepth) {
            this.data = data;
            this.rnd = rnd;
            this.maxDepth = maxDepth;
        }

        int build(int[] idx, int depth) {
            int me = nodes.size();
            double[] node = {-1, 0, -1, -1, idx.length};
            nodes.add(node);
            if (depth >= maxDepth || idx.length <= 1) return me;
            int nf = data[0].length;
            int[] candidates = new int[nf];
            double[] lo = new double[nf], hi = new double[nf];
            int nc = 0;
            for (int f = 0; f < nf; f++) {
                double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
                for (int i : idx) {
                    double v = data[i][f];
                    if (v < mn) mn = v;
                    if (v > mx) mx = v;
                }
                if (mx > mn) { candidates[nc] = f; lo[f] = mn; hi[f] = mx; nc++; }
            }
            if (nc == 0) return me;
            int f = candidates[rnd.nextInt(nc)];
            double split = lo[f] + rnd.nextDouble() * (hi[f] - lo[f]);
            int nl = 0;
            for (int i : idx) if (data[i][f] < split) nl++;
            if (nl == 0 || nl == idx.length) { // split landed on the edge; use the midpoint
                split = (lo[f] + hi[f]) / 2;
                nl = 0;
                for (int i : idx) if (data[i][f] < split) nl++;
                if (nl == 0 || nl == idx.length) return me;
            }
            int[] l = new int[nl], r = new int[idx.length - nl];
            int li = 0, ri = 0;
            for (int i : idx) { if (data[i][f] < split) l[li++] = i; else r[ri++] = i; }
            node[0] = f;
            node[1] = split;
            node[2] = build(l, depth + 1);
            node[3] = build(r, depth + 1);
            return me;
        }

        Tree toTree() {
            Tree t = new Tree();
            int n = nodes.size();
            t.feature = new int[n]; t.split = new double[n]; t.left = new int[n]; t.right = new int[n]; t.size = new int[n];
            for (int i = 0; i < n; i++) {
                double[] d = nodes.get(i);
                t.feature[i] = (int) d[0]; t.split[i] = d[1]; t.left[i] = (int) d[2]; t.right[i] = (int) d[3]; t.size[i] = (int) d[4];
            }
            return t;
        }
    }

    /** Average path length of an unsuccessful BST search with n nodes. */
    private static double c(int n) {
        if (n <= 1) return 0;
        if (n == 2) return 1;
        return 2 * (Math.log(n - 1) + EULER) - 2.0 * (n - 1) / n;
    }

    public double score(double[] x) {
        double sum = 0;
        for (Tree t : trees) {
            int node = 0, depth = 0;
            while (t.feature[node] >= 0) {
                node = x[t.feature[node]] < t.split[node] ? t.left[node] : t.right[node];
                depth++;
            }
            sum += depth + c(t.size[node]);
        }
        double avg = sum / trees.length;
        return cNorm <= 0 ? 0.5 : Math.pow(2, -avg / cNorm);
    }

    /**
     * How much each feature pushes the score up: the score drop when that one feature is
     * replaced by its normal (median) value. Simple, model-specific alternative to SHAP.
     */
    public double[] contributions(double[] x) {
        double full = score(x);
        double[] out = new double[nFeatures];
        double[] copy = x.clone();
        for (int f = 0; f < nFeatures; f++) {
            if (x[f] == baseline[f]) continue;
            copy[f] = baseline[f];
            out[f] = full - score(copy);
            copy[f] = x[f];
        }
        return out;
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder(trees.length * 8000);
        sb.append("IF1;").append(nFeatures).append(';').append(sampleSize).append(';').append(trees.length).append('\n');
        for (int i = 0; i < baseline.length; i++) { if (i > 0) sb.append(','); sb.append(baseline[i]); }
        sb.append('\n');
        for (Tree t : trees) {
            for (int i = 0; i < t.feature.length; i++) {
                if (i > 0) sb.append('|');
                sb.append(t.feature[i]).append(',').append(t.split[i]).append(',').append(t.left[i]).append(',')
                        .append(t.right[i]).append(',').append(t.size[i]);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static IsolationForest deserialize(String text) {
        String[] lines = text.split("\n");
        String[] head = lines[0].trim().split(";");
        if (!"IF1".equals(head[0])) throw new IllegalArgumentException("Unknown model format");
        int nf = Integer.parseInt(head[1]), psi = Integer.parseInt(head[2]), nt = Integer.parseInt(head[3]);
        double[] base = Arrays.stream(lines[1].trim().split(",")).mapToDouble(Double::parseDouble).toArray();
        if (base.length != nf || lines.length < nt + 2) throw new IllegalArgumentException("Model data is incomplete");
        Tree[] trees = new Tree[nt];
        for (int t = 0; t < nt; t++) {
            String[] nodes = lines[t + 2].trim().split("\\|");
            Tree tr = new Tree();
            int n = nodes.length;
            tr.feature = new int[n]; tr.split = new double[n]; tr.left = new int[n]; tr.right = new int[n]; tr.size = new int[n];
            for (int i = 0; i < n; i++) {
                String[] p = nodes[i].split(",");
                tr.feature[i] = Integer.parseInt(p[0]);
                tr.split[i] = Double.parseDouble(p[1]);
                tr.left[i] = Integer.parseInt(p[2]);
                tr.right[i] = Integer.parseInt(p[3]);
                tr.size[i] = Integer.parseInt(p[4]);
                if (tr.feature[i] >= nf || tr.left[i] >= n || tr.right[i] >= n) throw new IllegalArgumentException("Model data is corrupt");
            }
            trees[t] = tr;
        }
        return new IsolationForest(nf, psi, trees, base);
    }
}
