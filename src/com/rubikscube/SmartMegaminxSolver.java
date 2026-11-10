package com.rubikscube;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * State-based Smart Solve for the standard 12-face Megaminx (1 row, 132 stickers).
 *
 * It reads the CURRENT sticker colours (no move history), works out which of the 30 edge and
 * 20 corner pieces sits where and how it is twisted, and then solves the pieces in layer order
 * (the face around face F1 first, then the rings below it) with fixed "commutator" sequences:
 * a corner 3-cycle (8 turns) and an edge 3-cycle (12 turns), plus variants that also twist
 * or flip pieces. Each sequence is conjugated by a short setup so that it touches only the
 * three pieces we want; the setups come from a Dijkstra search over the (slot triple, twist
 * pattern) space, built in memory on first use. Pieces that are already solved are never
 * disturbed. Remaining twisted corners and flipped edges are fixed in place at the end.
 *
 * The result is verified on a copy of the sticker colours; a wrong answer is never returned.
 * Solutions are valid but not the shortest. Contains no Android APIs.
 */
public final class SmartMegaminxSolver {
    private SmartMegaminxSolver() { }

    private static final int STICKERS = 132;

    // Base macros as commutators [A, B] = A B A' B' (move numbers 1..12, negative = prime).
    private static final int[][][] BASE_CORNER = {
        {{1, -11, -1}, {9}},     // pure corner 3-cycle
        {{1, 3, -1}, {5}}        // corner 3-cycle with twists
    };
    private static final int[][][] BASE_EDGE = {
        {{1, 3, -1}, {8, 11, -8}},   // pure edge 3-cycle
        {{1, -3, -1}, {8, 11, -8}}   // edge 3-cycle with flips
    };

    // ------------------------------------------------------------------ shared static data
    private static boolean ready;
    private static ShapePuzzleModel geometry;
    private static int[][] genPerm;            // [24][132] sticker destination
    private static int[][] slotStickers;       // global slot -> stickers
    private static int[] slotOfSticker;
    private static final HashMap<Integer, Integer> MASK_TO_SLOT = new HashMap<Integer, Integer>();
    private static int[] slotHomeFace;         // face colour of sticker index 0 at home
    private static double[] slotHeight;        // for layer ordering
    private static int[][] SYMMETRY;         // 60 face permutations (rotations of the dodecahedron)
    private static Type edges;
    private static Type corners;

    /** One kind of piece (edges or corners) with its generator ops and search table. */
    private static final class Type {
        final int k;          // orientations (2 edges, 3 corners)
        final int size;       // number of slots
        final int kPat;       // k^3 patterns
        int[] slots;          // type index -> global slot
        int[] typeIndex;      // global slot -> type index (or -1)
        int[][] sig;          // [24][size]
        int[][] del;          // [24][size]
        int[] dist;           // state -> cost
        int[] prevState;      // state -> predecessor or -1 (source)
        byte[] prevGen;
        int[] sourceWord;     // for source states: id into words
        List<int[]> words = new ArrayList<int[]>();
        Type(int k, int size) {
            this.k = k;
            this.size = size;
            this.kPat = k * k * k;
        }
        int state(int a, int b, int c, int pat) {
            return ((a * size + b) * size + c) * kPat + pat;
        }
    }

    // ------------------------------------------------------------------ public entry

    public static SmartCubeSolver.Result solve(int[] stickerColors,
                                               SmartCubeSolver.Cancellation cancel) {
        if (stickerColors == null || stickerColors.length != STICKERS) {
            throw new IllegalArgumentException("Smart Solve needs the standard 12-face Megaminx");
        }
        synchronized (SmartMegaminxSolver.class) {
            if (!ready) build(cancel);
        }
        check(cancel);
        int[] colors = stickerColors.clone();
        for (int c : colors) {
            if (c < 0 || c > 11) throw new IllegalArgumentException("Invalid sticker colour");
        }
        for (int i = 0; i < STICKERS; i++) {
            if (slotOfSticker[i] < 0 && colors[i] != geometry.stickers[i].face) {
                throw new IllegalArgumentException("Centre stickers are not in their home places");
            }
        }
        if (isSolved(colors)) throw new IllegalArgumentException("Already solved");

        PieceState es = readState(edges, colors);
        PieceState cs = readState(corners, colors);
        ArrayList<Integer> out = new ArrayList<Integer>();

        // A face turn disturbs at most 10 pieces, so a handful of turns can be found directly.
        int disturbed = 0;
        for (int i = 0; i < edges.size; i++) if (es.occ[i] != i || es.ori[i] != 0) disturbed++;
        for (int i = 0; i < corners.size; i++) if (cs.occ[i] != i || cs.ori[i] != 0) disturbed++;
        if (disturbed <= 10 * QUICK_DEPTH) {
            ArrayList<Integer> quick = quickSearch(colors, cancel);
            if (quick != null) return finish(colors, quick);
        }

        // 1. place pieces in layer order
        ArrayList<int[]> order = new ArrayList<int[]>(); // {type 0/1, typeIndex}
        for (int i = 0; i < edges.size; i++) order.add(new int[]{0, i});
        for (int i = 0; i < corners.size; i++) order.add(new int[]{1, i});
        final double[] edgeH = new double[edges.size];
        final double[] cornerH = new double[corners.size];
        for (int i = 0; i < edges.size; i++) edgeH[i] = slotHeight[edges.slots[i]];
        for (int i = 0; i < corners.size; i++) cornerH[i] = slotHeight[corners.slots[i]];
        java.util.Collections.sort(order, new java.util.Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) {
                double ha = a[0] == 0 ? edgeH[a[1]] : cornerH[a[1]];
                double hb = b[0] == 0 ? edgeH[b[1]] : cornerH[b[1]];
                if (Math.abs(ha - hb) > 1e-6) return ha > hb ? -1 : 1;
                if (a[0] != b[0]) return a[0] - b[0];
                return a[1] - b[1];
            }
        });
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int[] t : order) {
                PieceState st = t[0] == 0 ? es : cs;
                Type ty = t[0] == 0 ? edges : corners;
                if (st.occ[t[1]] == t[1]) continue;
                check(cancel);
                placeInto(ty, st, t[1], out);
                progress = true;
                break;
            }
        }
        // 2. fix twisted corners / flipped edges in place
        fixOrientation(edges, es, out, cancel);
        fixOrientation(corners, cs, out, cancel);

        return finish(colors, out);
    }

    private static final int QUICK_DEPTH = 4;

    /** Verifies a move list on a copy of the stickers and wraps it as a Result. */
    private static SmartCubeSolver.Result finish(int[] colors, ArrayList<Integer> out) {
        int[] test = colors.clone();
        for (int g : out) test = applyGen(test, g);
        if (!isSolved(test)) throw new IllegalStateException("Megaminx solver verification failed");
        List<int[]> moves = new ArrayList<int[]>();
        for (int g : out) moves.add(new int[]{g / 2, (g % 2 == 0) ? 1 : -1});
        return new SmartCubeSolver.Result(moves, "Megaminx solve, " + moves.size() + " turns");
    }

    private static ArrayList<Integer> quickSearch(int[] colors, SmartCubeSolver.Cancellation cancel) {
        for (int depth = 1; depth <= QUICK_DEPTH; depth++) {
            int[] path = new int[depth];
            int[][] buf = new int[depth + 1][];
            buf[0] = colors;
            for (int i = 1; i <= depth; i++) buf[i] = new int[STICKERS];
            if (quickDfs(buf, depth, 0, path, cancel)) {
                ArrayList<Integer> r = new ArrayList<Integer>();
                for (int g : path) r.add(g);
                return r;
            }
        }
        return null;
    }

    private static boolean quickDfs(int[][] buf, int depth, int level, int[] path,
                                    SmartCubeSolver.Cancellation cancel) {
        if (level == depth) return isSolved(buf[level]);
        if (level == 1) check(cancel);
        for (int g = 0; g < 24; g++) {
            if (level > 0 && path[level - 1] / 2 == g / 2) continue;
            int[] p = genPerm[g];
            int[] from = buf[level];
            int[] to = buf[level + 1];
            for (int i = 0; i < STICKERS; i++) to[p[i]] = from[i];
            path[level] = g;
            if (quickDfs(buf, depth, level + 1, path, cancel)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ piece state

    private static final class PieceState {
        final int[] occ;  // slot -> home slot of the piece currently there
        final int[] ori;  // slot -> twist of that piece
        PieceState(int n) {
            occ = new int[n];
            ori = new int[n];
        }
    }

    private static PieceState readState(Type ty, int[] colors) {
        PieceState ps = new PieceState(ty.size);
        boolean[] seen = new boolean[ty.size];
        for (int i = 0; i < ty.size; i++) {
            int g = ty.slots[i];
            int[] st = slotStickers[g];
            int mask = 0;
            for (int s : st) mask |= 1 << colors[s];
            Integer home = MASK_TO_SLOT.get(mask);
            if (home == null || ty.typeIndex[home] < 0 || slotStickers[home].length != st.length) {
                throw new IllegalArgumentException("Invalid piece colours");
            }
            int h = ty.typeIndex[home];
            if (seen[h]) throw new IllegalArgumentException("Duplicate piece");
            seen[h] = true;
            ps.occ[i] = h;
            int rc = slotHomeFace[home];
            int o = -1;
            for (int k = 0; k < st.length; k++) if (colors[st[k]] == rc) o = k;
            if (o < 0) throw new IllegalArgumentException("Invalid piece orientation");
            ps.ori[i] = o;
        }
        return ps;
    }

    // ------------------------------------------------------------------ placing pieces

    private static final int W_HOME = 36;
    private static final int W_ORI = 22;

    /** Brings the piece that belongs in home slot y into y, without disturbing homed pieces. */
    private static void placeInto(Type ty, PieceState ps, int y, ArrayList<Integer> out) {
        int x = -1;
        for (int i = 0; i < ty.size; i++) if (ps.occ[i] == y) x = i;
        int bestScore = Integer.MIN_VALUE;
        int bestState = -1;
        int bestZ = -1;
        int[] bestDelta = null;
        for (int z = 0; z < ty.size; z++) {
            if (z == x || z == y || ps.occ[z] == z) continue;
            for (int rot = 0; rot < 3; rot++) {
                // entry cycles a->b->c; rotate so that the cycle is x->y->z
                int a, b, c;
                if (rot == 0) { a = x; b = y; c = z; }
                else if (rot == 1) { a = y; b = z; c = x; }
                else { a = z; b = x; c = y; }
                int base = ty.state(a, b, c, 0);
                for (int pat = 0; pat < ty.kPat; pat++) {
                    int st = base + pat;
                    if (ty.dist[st] >= Integer.MAX_VALUE / 2) continue;
                    int pa = (pat / (ty.k * ty.k)) % ty.k;
                    int pb = (pat / ty.k) % ty.k;
                    int pc = pat % ty.k;
                    int dx, dy, dz;
                    if (rot == 0) { dx = pa; dy = pb; dz = pc; }
                    else if (rot == 1) { dy = pa; dz = pb; dx = pc; }
                    else { dz = pa; dx = pb; dy = pc; }
                    // piece from x -> y, from y -> z, from z -> x
                    int homes = 1;
                    int oris = ((ps.ori[x] + dx) % ty.k == 0) ? 1 : 0;
                    if (ps.occ[y] == z) {
                        homes++;
                        if ((ps.ori[y] + dy) % ty.k == 0) oris++;
                    }
                    if (ps.occ[z] == x) {
                        homes++;
                        if ((ps.ori[z] + dz) % ty.k == 0) oris++;
                    }
                    int score = W_HOME * homes + W_ORI * oris - ty.dist[st];
                    if (score > bestScore) {
                        bestScore = score;
                        bestState = st;
                        bestZ = z;
                        bestDelta = new int[]{x, y, z, dx, dy, dz};
                    }
                }
            }
        }
        if (bestState < 0) throw new IllegalStateException("No cycle available");
        appendWord(ty, bestState, out);
        applyCycle(ty, ps, bestDelta[0], bestDelta[1], bestDelta[2],
                bestDelta[3], bestDelta[4], bestDelta[5]);
    }

    /** x->y->z->x with orientation increments dx,dy,dz applied to the piece leaving each slot. */
    private static void applyCycle(Type ty, PieceState ps, int x, int y, int z,
                                   int dx, int dy, int dz) {
        int ox = ps.occ[x], oy = ps.occ[y], oz = ps.occ[z];
        int rx = ps.ori[x], ry = ps.ori[y], rz = ps.ori[z];
        ps.occ[y] = ox; ps.ori[y] = (rx + dx) % ty.k;
        ps.occ[z] = oy; ps.ori[z] = (ry + dy) % ty.k;
        ps.occ[x] = oz; ps.ori[x] = (rz + dz) % ty.k;
    }

    // ------------------------------------------------------------------ in-place orientation

    private static void fixOrientation(Type ty, PieceState ps, ArrayList<Integer> out,
                                       SmartCubeSolver.Cancellation cancel) {
        int guard = 0;
        while (true) {
            int u = -1;
            int bad = 0;
            for (int i = 0; i < ty.size; i++) {
                if (ps.occ[i] != i) throw new IllegalStateException("Pieces not placed");
                if (ps.ori[i] != 0) {
                    bad++;
                    if (u < 0) u = i;
                }
            }
            if (u < 0) return;
            if (++guard > 40) throw new IllegalStateException("Orientation fix did not finish");
            check(cancel);
            int bestScore = Integer.MAX_VALUE;
            int[] bestInfo = null;
            for (int v = 0; v < ty.size; v++) {
                if (v == u) continue;
                for (int w = 0; w < ty.size; w++) {
                    if (w == u || w == v) continue;
                    int[] listA = candidates(ty, u, v, w);
                    int[] listB = candidates(ty, u, w, v);
                    if (listA.length == 0 || listB.length == 0) continue;
                    for (int ia = 0; ia < listA.length; ia += 4) {
                        for (int ib = 0; ib < listB.length; ib += 4) {
                            // A: u->v->w deltas (au,av,aw); B: u->w->v deltas (bu,bw,bv)
                            int au = listA[ia + 1], av = listA[ia + 2], aw = listA[ia + 3];
                            int bu = listB[ib + 1], bw = listB[ib + 2], bv = listB[ib + 3];
                            // piece originally at u: A leaves u (au), then B leaves v (bv)
                            int du = (au + bv) % ty.k;
                            int dv = (av + bw) % ty.k;
                            int dw = (aw + bu) % ty.k;
                            int nu = (ps.ori[u] + du) % ty.k;
                            int nv = (ps.ori[v] + dv) % ty.k;
                            int nw = (ps.ori[w] + dw) % ty.k;
                            if (nu != 0) continue;
                            int before = 1 + (ps.ori[v] != 0 ? 1 : 0) + (ps.ori[w] != 0 ? 1 : 0);
                            int after = (nv != 0 ? 1 : 0) + (nw != 0 ? 1 : 0);
                            if (after >= before) continue;
                            int score = after * 40 + listA[ia] + listB[ib];
                            if (score < bestScore) {
                                bestScore = score;
                                bestInfo = new int[]{u, v, w, du, dv, dw, ia, ib};
                            }
                        }
                    }
                }
            }
            if (bestInfo == null) throw new IllegalStateException("No orientation fix found");
            int u0 = bestInfo[0], v0 = bestInfo[1], w0 = bestInfo[2];
            appendWord(ty, stateOfCandidate(ty, u0, v0, w0, bestInfo[6]), out);
            appendWord(ty, stateOfCandidate(ty, u0, w0, v0, bestInfo[7]), out);
            ps.ori[u0] = (ps.ori[u0] + bestInfo[3]) % ty.k;
            ps.ori[v0] = (ps.ori[v0] + bestInfo[4]) % ty.k;
            ps.ori[w0] = (ps.ori[w0] + bestInfo[5]) % ty.k;
        }
    }

    // candidates(ty, a, b, c): flat list of {cost, deltaAt a, deltaAt b, deltaAt c} for the cycle
    // a->b->c over all 3 rotations and all patterns (4 ints per candidate).

    private static int[] candidates(Type ty, int a, int b, int c) {
        ArrayList<Integer> res = new ArrayList<Integer>();
        for (int rot = 0; rot < 3; rot++) {
            int p, q, r;
            if (rot == 0) { p = a; q = b; r = c; }
            else if (rot == 1) { p = b; q = c; r = a; }
            else { p = c; q = a; r = b; }
            int base = ty.state(p, q, r, 0);
            for (int pat = 0; pat < ty.kPat; pat++) {
                int st = base + pat;
                if (ty.dist[st] >= Integer.MAX_VALUE / 2) continue;
                int pa = (pat / (ty.k * ty.k)) % ty.k;
                int pb = (pat / ty.k) % ty.k;
                int pc = pat % ty.k;
                int da, db, dc;
                if (rot == 0) { da = pa; db = pb; dc = pc; }
                else if (rot == 1) { db = pa; dc = pb; da = pc; }
                else { dc = pa; da = pb; db = pc; }
                res.add(ty.dist[st]);
                res.add(da);
                res.add(db);
                res.add(dc);
            }
        }
        int[] arr = new int[res.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = res.get(i);
        return arr;
    }

    /** State id of the idx-th candidate (index into the flat list, multiples of 4). */
    private static int stateOfCandidate(Type ty, int a, int b, int c, int flatIndex) {
        int n = flatIndex / 4;
        int seen = 0;
        for (int rot = 0; rot < 3; rot++) {
            int p, q, r;
            if (rot == 0) { p = a; q = b; r = c; }
            else if (rot == 1) { p = b; q = c; r = a; }
            else { p = c; q = a; r = b; }
            int base = ty.state(p, q, r, 0);
            for (int pat = 0; pat < ty.kPat; pat++) {
                int st = base + pat;
                if (ty.dist[st] >= Integer.MAX_VALUE / 2) continue;
                if (seen == n) return st;
                seen++;
            }
        }
        throw new IllegalStateException("Candidate lookup failed");
    }

    // ------------------------------------------------------------------ words

    /** Appends the conjugated macro word for a table state, cancelling adjacent inverse turns. */
    private static void appendWord(Type ty, int state, ArrayList<Integer> out) {
        ArrayList<Integer> prefix = new ArrayList<Integer>();
        int s = state;
        while (ty.prevState[s] >= 0) {
            prefix.add((int) ty.prevGen[s]);
            s = ty.prevState[s];
        }
        int[] base = ty.words.get(ty.sourceWord[s]);
        ArrayList<Integer> fixed = new ArrayList<Integer>(prefix);
        for (int g : base) fixed.add(g);
        for (int i = 0; i < prefix.size(); i++) fixed.add(prefix.get(prefix.size() - 1 - i) ^ 1);
        for (int g : fixed) {
            if (!out.isEmpty() && (out.get(out.size() - 1) ^ 1) == g) out.remove(out.size() - 1);
            else out.add(g);
        }
    }

    private static int[] applyGen(int[] colors, int g) {
        int[] next = new int[STICKERS];
        int[] p = genPerm[g];
        for (int i = 0; i < STICKERS; i++) next[p[i]] = colors[i];
        return next;
    }

    private static boolean isSolved(int[] colors) {
        for (int i = 0; i < STICKERS; i++) {
            if (colors[i] != geometry.stickers[i].face) return false;
        }
        return true;
    }

    private static void check(SmartCubeSolver.Cancellation cancel) {
        if (cancel != null && cancel.cancelled()) throw new RuntimeException("Cancelled");
    }

    // ------------------------------------------------------------------ one-time tables

    private static void build(SmartCubeSolver.Cancellation cancel) {
        geometry = new ShapePuzzleModel(ShapePuzzleModel.PENTAGON, 1);
        genPerm = new int[24][STICKERS];
        for (int g = 0; g < 24; g++) {
            for (int i = 0; i < STICKERS; i++) geometry.colors[i] = i;
            geometry.turn(g / 2, (g % 2 == 0) ? 1 : -1);
            for (int j = 0; j < STICKERS; j++) genPerm[g][geometry.colors[j]] = j;
        }
        geometry.reset();

        // slots: stickers grouped by incident-face mask (edges 2, corners 3)
        TreeMap<Integer, ArrayList<Integer>> byMask = new TreeMap<Integer, ArrayList<Integer>>();
        for (int i = 0; i < STICKERS; i++) {
            int bits = Integer.bitCount(geometry.stickers[i].group);
            if (bits < 2) continue;
            ArrayList<Integer> l = byMask.get(geometry.stickers[i].group);
            if (l == null) {
                l = new ArrayList<Integer>();
                byMask.put(geometry.stickers[i].group, l);
            }
            l.add(i);
        }
        int slotCount = byMask.size();
        slotStickers = new int[slotCount][];
        slotOfSticker = new int[STICKERS];
        Arrays.fill(slotOfSticker, -1);
        slotHomeFace = new int[slotCount];
        slotHeight = new double[slotCount];
        double[] up = geometry.faces[0].normal;
        int slot = 0;
        for (java.util.Map.Entry<Integer, ArrayList<Integer>> e : byMask.entrySet()) {
            ArrayList<Integer> l = e.getValue();
            int[] st = orderStickers(l);
            slotStickers[slot] = st;
            int mask = 0;
            double h = 0;
            for (int s : st) {
                slotOfSticker[s] = slot;
                mask |= 1 << geometry.stickers[s].face;
                double[] c = geometry.stickers[s].centre;
                h += c[0] * up[0] + c[1] * up[1] + c[2] * up[2];
            }
            MASK_TO_SLOT.put(mask, slot);
            slotHomeFace[slot] = geometry.stickers[st[0]].face;
            slotHeight[slot] = h / st.length;
            slot++;
        }

        SYMMETRY = buildSymmetry();

        // type tables
        edges = new Type(2, 30);
        corners = new Type(3, 20);
        initType(edges, 2, cancel);
        initType(corners, 3, cancel);
        for (int[][] m : BASE_EDGE) edges.words.add(commutator(m));
        for (int[][] m : BASE_CORNER) corners.words.add(commutator(m));
        dijkstra(edges, cancel);
        dijkstra(corners, cancel);
        ready = true;
    }


    /** The 60 rotations of the dodecahedron, as permutations of the 12 faces. */
    private static int[][] buildSymmetry() {
        double[][] n = new double[12][];
        for (int f = 0; f < 12; f++) n[f] = geometry.faces[f].normal;
        int nb = -1;
        for (int f = 1; f < 12; f++) if (dot(n[0], n[f]) > 0.4) { nb = f; break; }
        double[][] fa = frame(n[0], n[nb]);
        ArrayList<int[]> maps = new ArrayList<int[]>();
        for (int i = 0; i < 12; i++) {
            for (int j = 0; j < 12; j++) {
                if (i == j || dot(n[i], n[j]) < 0.4) continue;
                double[][] fb = frame(n[i], n[j]);
                // Q = B * A^T applied to each face normal
                int[] map = new int[12];
                boolean ok = true;
                for (int f = 0; f < 12 && ok; f++) {
                    double[] c = {dot(fa[0], n[f]), dot(fa[1], n[f]), dot(fa[2], n[f])};
                    double[] q = new double[3];
                    for (int k = 0; k < 3; k++) q[k] = c[0] * fb[0][k] + c[1] * fb[1][k] + c[2] * fb[2][k];
                    int best = -1;
                    double bd = -2;
                    for (int g = 0; g < 12; g++) {
                        double d = dot(q, n[g]);
                        if (d > bd) { bd = d; best = g; }
                    }
                    if (bd < 0.999999) ok = false;
                    map[f] = best;
                }
                if (ok) maps.add(map);
            }
        }
        if (maps.size() != 60) throw new IllegalStateException("Symmetry group has " + maps.size());
        return maps.toArray(new int[maps.size()][]);
    }

    private static double[][] frame(double[] a, double[] b) {
        double d = dot(a, b);
        double[] e2 = {b[0] - d * a[0], b[1] - d * a[1], b[2] - d * a[2]};
        double l = Math.sqrt(dot(e2, e2));
        for (int k = 0; k < 3; k++) e2[k] /= l;
        double[] e3 = {a[1] * e2[2] - a[2] * e2[1], a[2] * e2[0] - a[0] * e2[2], a[0] * e2[1] - a[1] * e2[0]};
        return new double[][]{a, e2, e3};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static int[] commutator(int[][] m) {
        ArrayList<Integer> w = new ArrayList<Integer>();
        for (int x : m[0]) w.add(gen(x));
        for (int x : m[1]) w.add(gen(x));
        for (int i = m[0].length - 1; i >= 0; i--) w.add(gen(m[0][i]) ^ 1);
        for (int i = m[1].length - 1; i >= 0; i--) w.add(gen(m[1][i]) ^ 1);
        int[] r = new int[w.size()];
        for (int i = 0; i < r.length; i++) r[i] = w.get(i);
        return r;
    }

    private static int gen(int moveNumber) {
        int f = Math.abs(moveNumber) - 1;
        return 2 * f + (moveNumber < 0 ? 1 : 0);
    }

    /** Stickers of one slot: edges by face index; corners counter-clockwise from the lowest face. */
    private static int[] orderStickers(ArrayList<Integer> l) {
        if (l.size() == 2) {
            int a = l.get(0), b = l.get(1);
            if (geometry.stickers[a].face > geometry.stickers[b].face) { int t = a; a = b; b = t; }
            return new int[]{a, b};
        }
        double[] v = new double[3];
        for (int i : l) for (int k = 0; k < 3; k++) v[k] += geometry.stickers[i].normal[k];
        double vl = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        for (int k = 0; k < 3; k++) v[k] /= vl;
        int lo = l.get(0);
        for (int i : l) if (geometry.stickers[i].face < geometry.stickers[lo].face) lo = i;
        double[] n0 = geometry.stickers[lo].normal;
        double d0 = n0[0] * v[0] + n0[1] * v[1] + n0[2] * v[2];
        double[] u = {n0[0] - d0 * v[0], n0[1] - d0 * v[1], n0[2] - d0 * v[2]};
        double ul = Math.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
        for (int k = 0; k < 3; k++) u[k] /= ul;
        double[] w = {v[1] * u[2] - v[2] * u[1], v[2] * u[0] - v[0] * u[2], v[0] * u[1] - v[1] * u[0]};
        final double[] ang = new double[3];
        int[] ids = new int[3];
        for (int q = 0; q < 3; q++) {
            ids[q] = l.get(q);
            double[] nn = geometry.stickers[ids[q]].normal;
            double an = Math.atan2(nn[0] * w[0] + nn[1] * w[1] + nn[2] * w[2],
                    nn[0] * u[0] + nn[1] * u[1] + nn[2] * u[2]);
            if (an < -1e-9) an += 2 * Math.PI;
            if (ids[q] == lo) an = 0;
            ang[q] = an;
        }
        // sort three entries by angle
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                if (ang[j] < ang[i]) {
                    double ta = ang[i]; ang[i] = ang[j]; ang[j] = ta;
                    int ti = ids[i]; ids[i] = ids[j]; ids[j] = ti;
                }
            }
        }
        return ids;
    }

    /** Piece-level effect of every generator: slot permutation and orientation increments. */
    private static void initType(Type ty, int len, SmartCubeSolver.Cancellation cancel) {
        ArrayList<Integer> list = new ArrayList<Integer>();
        for (int s = 0; s < slotStickers.length; s++) if (slotStickers[s].length == len) list.add(s);
        if (list.size() != ty.size) throw new IllegalStateException("Piece count mismatch");
        ty.slots = new int[ty.size];
        ty.typeIndex = new int[slotStickers.length];
        Arrays.fill(ty.typeIndex, -1);
        for (int i = 0; i < ty.size; i++) {
            ty.slots[i] = list.get(i);
            ty.typeIndex[ty.slots[i]] = i;
        }
        ty.sig = new int[24][ty.size];
        ty.del = new int[24][ty.size];
        for (int g = 0; g < 24; g++) {
            int[] p = genPerm[g];
            for (int i = 0; i < ty.size; i++) {
                int[] st = slotStickers[ty.slots[i]];
                int dest = slotOfSticker[p[st[0]]];
                int d = indexInSlot(dest, p[st[0]]);
                for (int k = 1; k < len; k++) {
                    int pk = p[st[k]];
                    if (slotOfSticker[pk] != dest || indexInSlot(dest, pk) != (d + k) % len) {
                        throw new IllegalStateException("Piece structure inconsistent");
                    }
                }
                ty.sig[g][i] = ty.typeIndex[dest];
                ty.del[g][i] = d;
            }
        }
    }

    private static int indexInSlot(int slot, int sticker) {
        int[] st = slotStickers[slot];
        for (int k = 0; k < st.length; k++) if (st[k] == sticker) return k;
        throw new IllegalStateException("Sticker not in slot");
    }

    /** Piece-level op of a word, returned as {sig[], del[]}. */
    private static int[][] opOf(Type ty, int[] word) {
        int[] sig = new int[ty.size];
        int[] del = new int[ty.size];
        for (int i = 0; i < ty.size; i++) sig[i] = i;
        for (int g : word) {
            for (int i = 0; i < ty.size; i++) {
                int cur = sig[i];
                del[i] = (del[i] + ty.del[g][cur]) % ty.k;
                sig[i] = ty.sig[g][cur];
            }
        }
        return new int[][]{sig, del};
    }

    private static void dijkstra(Type ty, SmartCubeSolver.Cancellation cancel) {
        int total = ty.size * ty.size * ty.size * ty.kPat;
        ty.dist = new int[total];
        ty.prevState = new int[total];
        ty.prevGen = new byte[total];
        ty.sourceWord = new int[total];
        Arrays.fill(ty.dist, Integer.MAX_VALUE / 2 + 1);
        Arrays.fill(ty.prevState, -1);
        PriorityQueue<Long> queue = new PriorityQueue<Long>();
        // seed with every base macro and its inverse, in all three rotations
        int baseCount = ty.words.size();
        for (int wi = 0; wi < baseCount; wi++) {
            int[] w = ty.words.get(wi);
            int[] inv = new int[w.length];
            for (int i = 0; i < w.length; i++) inv[i] = w[w.length - 1 - i] ^ 1;
            ty.words.add(inv);
        }
        // every rotation of the whole puzzle maps a macro to another macro of equal length
        int plain = ty.words.size();
        for (int wi = 0; wi < plain; wi++) {
            int[] w = ty.words.get(wi);
            for (int[] map : SYMMETRY) {
                int[] m = new int[w.length];
                for (int i = 0; i < w.length; i++) m[i] = 2 * map[w[i] / 2] + (w[i] & 1);
                ty.words.add(m);
            }
        }
        for (int wi = 0; wi < ty.words.size(); wi++) {
            int[] w = ty.words.get(wi);
            int[][] op = opOf(ty, w);
            int a = -1;
            int moved = 0;
            for (int i = 0; i < ty.size; i++) {
                if (op[0][i] != i || op[1][i] != 0) {
                    moved++;
                    if (a < 0) a = i;
                }
            }
            if (moved != 3) throw new IllegalStateException("Base macro is not a 3-cycle");
            int b = op[0][a];
            int c = op[0][b];
            if (op[0][c] != a) throw new IllegalStateException("Base macro is not a 3-cycle");
            int[] ss = {a, b, c};
            int[] dd = {op[1][a], op[1][b], op[1][c]};
            for (int rot = 0; rot < 3; rot++) {
                int p0 = ss[rot], p1 = ss[(rot + 1) % 3], p2 = ss[(rot + 2) % 3];
                int d0 = dd[rot], d1 = dd[(rot + 1) % 3], d2 = dd[(rot + 2) % 3];
                int st = ty.state(p0, p1, p2, d0 * ty.k * ty.k + d1 * ty.k + d2);
                if (w.length < ty.dist[st]) {
                    ty.dist[st] = w.length;
                    ty.prevState[st] = -1;
                    ty.sourceWord[st] = wi;
                    queue.add(((long) w.length << 32) | st);
                }
            }
        }
        int steps = 0;
        while (!queue.isEmpty()) {
            long top = queue.poll();
            int cost = (int) (top >>> 32);
            int st = (int) (top & 0xFFFFFFFFL);
            if (cost > ty.dist[st]) continue;
            if ((++steps & 0xFFFF) == 0) check(cancel);
            int pat = st % ty.kPat;
            int rest = st / ty.kPat;
            int c = rest % ty.size;
            int b = (rest / ty.size) % ty.size;
            int a = rest / (ty.size * ty.size);
            int pa = (pat / (ty.k * ty.k)) % ty.k;
            int pb = (pat / ty.k) % ty.k;
            int pc = pat % ty.k;
            for (int g = 0; g < 24; g++) {
                int gi = g ^ 1;
                // conjugation  G X G^-1 : cycle slots move by sigma of G^-1
                int na = ty.sig[gi][a], nb = ty.sig[gi][b], nc = ty.sig[gi][c];
                int qa = (ty.del[g][na] + pa + ty.del[gi][b]) % ty.k;
                int qb = (ty.del[g][nb] + pb + ty.del[gi][c]) % ty.k;
                int qc = (ty.del[g][nc] + pc + ty.del[gi][a]) % ty.k;
                int ns = ty.state(na, nb, nc, qa * ty.k * ty.k + qb * ty.k + qc);
                int nd = cost + 2;
                if (nd < ty.dist[ns]) {
                    ty.dist[ns] = nd;
                    ty.prevState[ns] = st;
                    ty.prevGen[ns] = (byte) g;
                    ty.sourceWord[ns] = ty.sourceWord[st];
                    queue.add(((long) nd << 32) | ns);
                }
            }
        }
    }
}
