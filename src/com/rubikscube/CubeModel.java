package com.rubikscube;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java NxN cube state (no Android dependencies, so it is unit-testable).
 *
 * Every sticker stores the centre of its cubie (pos) and outward normal (nrm) in doubled
 * integer coordinates (range -(n-1) .. (n-1), step 2) plus a colour index.
 * A move is an int[]{axis, layer, dir}: axis 0=X 1=Y 2=Z, layer = doubled coordinate of the
 * turned slice, dir = +1 / -1 (right-hand rule about +axis).
 *
 * Face index = axis * 2 + (positive ? 0 : 1):  0=+X(R) 1=-X(L) 2=+Y(U) 3=-Y(D) 4=+Z(F) 5=-Z(B)
 */
public class CubeModel {

    public static final String FACE_LETTERS = "RLUDFB";

    public int n;
    public int count;
    public int[] pos;
    public int[] nrm;
    public int[] col;
    public int[] nAxis; // cached normal axis per sticker

    private final int[] firstColor = new int[6];

    public CubeModel(int size) {
        reset(size);
    }

    public void reset(int size) {
        n = size;
        count = 6 * n * n;
        pos = new int[count * 3];
        nrm = new int[count * 3];
        col = new int[count];
        nAxis = new int[count];
        int i = 0;
        for (int face = 0; face < 6; face++) {
            int axis = face / 2;
            int sign = (face % 2 == 0) ? 1 : -1;
            int j = (axis + 1) % 3;
            int k = (axis + 2) % 3;
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < n; b++) {
                    int o = i * 3;
                    pos[o + axis] = sign * (n - 1);
                    pos[o + j] = 2 * a - (n - 1);
                    pos[o + k] = 2 * b - (n - 1);
                    nrm[o + axis] = sign;
                    col[i] = face;
                    nAxis[i] = axis;
                    i++;
                }
            }
        }
    }

    public int faceOf(int i) {
        int a = nAxis[i];
        return a * 2 + (nrm[i * 3 + a] > 0 ? 0 : 1);
    }

    private static void rot(int[] v, int o, int j, int k, int dir) {
        int a = v[o + j];
        int b = v[o + k];
        if (dir > 0) {
            v[o + j] = -b;
            v[o + k] = a;
        } else {
            v[o + j] = b;
            v[o + k] = -a;
        }
    }

    public void rotate(int axis, int layer, int dir) {
        int j = (axis + 1) % 3;
        int k = (axis + 2) % 3;
        for (int i = 0; i < count; i++) {
            int o = i * 3;
            if (pos[o + axis] == layer) {
                rot(pos, o, j, k, dir);
                rot(nrm, o, j, k, dir);
                nAxis[i] = nrm[o] != 0 ? 0 : (nrm[o + 1] != 0 ? 1 : 2);
            }
        }
    }

    public void apply(int[] m) {
        rotate(m[0], m[1], m[2]);
    }

    public boolean isSolved() {
        for (int f = 0; f < 6; f++) firstColor[f] = -1;
        for (int i = 0; i < count; i++) {
            int f = faceOf(i);
            if (firstColor[f] < 0) firstColor[f] = col[i];
            else if (firstColor[f] != col[i]) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ move helpers

    public static int[] invert(int[] m) {
        return new int[]{m[0], m[1], -m[2]};
    }

    /** Turn the slice that is the k-th (1-based) layer counted from the given face (0..5). */
    public int[] faceMove(int face, int k, boolean clockwise) {
        int axis = face / 2;
        boolean positive = face % 2 == 0;
        int layer = positive ? (n - 1) - 2 * (k - 1) : -(n - 1) + 2 * (k - 1);
        // clockwise seen from a positive face is -1 (right-hand rule), from a negative face +1
        int dir = (clockwise == positive) ? -1 : 1;
        return new int[]{axis, layer, dir};
    }

    /**
     * Parses notation such as "R U R' U' F2 2R x y'" into moves.
     * Supported: faces U D L R F B, optional leading layer number (2R = second layer from R),
     * suffix ' or 2, and whole-cube rotations x y z.
     */
    public List<int[]> parse(String text) {
        List<int[]> out = new ArrayList<int[]>();
        if (text == null) return out;
        String[] tokens = text.trim().split("[\\s,]+");
        for (String t : tokens) {
            if (t.length() == 0) continue;
            int p = 0;
            int k = 0;
            while (p < t.length() && Character.isDigit(t.charAt(p))) {
                k = k * 10 + (t.charAt(p) - '0');
                p++;
            }
            if (p >= t.length()) throw new IllegalArgumentException("Bad move: " + t);
            char c = t.charAt(p++);
            String suffix = t.substring(p);
            boolean prime = false;
            int times = 1;
            if (suffix.equals("'") || suffix.equals("\u2019")) prime = true;
            else if (suffix.equals("2")) times = 2;
            else if (suffix.equals("2'")) times = 2;
            else if (suffix.length() != 0) throw new IllegalArgumentException("Bad move: " + t);

            int face = FACE_LETTERS.indexOf(c);
            if (face >= 0) {
                if (k == 0) k = 1;
                if (k > n) throw new IllegalArgumentException("Layer " + k + " > size in: " + t);
                int[] m = faceMove(face, k, !prime);
                for (int r = 0; r < times; r++) out.add(m);
            } else if (c == 'x' || c == 'y' || c == 'z') {
                if (k != 0) throw new IllegalArgumentException("Bad move: " + t);
                int axis = c - 'x';
                for (int r = 0; r < times; r++) {
                    for (int layer = -(n - 1); layer <= n - 1; layer += 2) {
                        out.add(new int[]{axis, layer, prime ? 1 : -1});
                    }
                }
            } else {
                throw new IllegalArgumentException("Unknown move: " + t);
            }
        }
        return out;
    }

    /** Notation for a single move, e.g. "R", "U'", "2F". Whole-cube turns are not merged. */
    public String format(int[] m) {
        int axis = m[0];
        int layer = m[1];
        boolean positive = layer >= 0;
        int face = axis * 2 + (positive ? 0 : 1);
        int k = positive ? ((n - 1) - layer) / 2 + 1 : (layer + (n - 1)) / 2 + 1;
        boolean clockwise = positive ? (m[2] < 0) : (m[2] > 0);
        StringBuilder sb = new StringBuilder();
        if (k > 1) sb.append(k);
        sb.append(FACE_LETTERS.charAt(face));
        if (!clockwise) sb.append('\'');
        return sb.toString();
    }

    /** Notation for a sequence; two identical consecutive moves are shown as X2. */
    public String format(List<int[]> moves) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < moves.size()) {
            int[] m = moves.get(i);
            String s = format(m);
            boolean pair = i + 1 < moves.size() && same(m, moves.get(i + 1));
            if (sb.length() > 0) sb.append(' ');
            if (pair) {
                sb.append(s.endsWith("'") ? s.substring(0, s.length() - 1) : s).append('2');
                i += 2;
            } else {
                sb.append(s);
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean same(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    // ------------------------------------------------------------------ net helper

    /**
     * Fills out[face][row*n+col] with colour indices as seen on a standard unfolded net
     * (U on top, L F R B across, D below).
     */
    public void faceGrids(int[][] out) {
        for (int i = 0; i < count; i++) {
            int f = faceOf(i);
            int o = i * 3;
            int xi = (pos[o] + (n - 1)) / 2;
            int yi = (pos[o + 1] + (n - 1)) / 2;
            int zi = (pos[o + 2] + (n - 1)) / 2;
            int r;
            int c;
            switch (f) {
                case 2: r = zi; c = xi; break;               // U
                case 3: r = (n - 1) - zi; c = xi; break;     // D
                case 4: r = (n - 1) - yi; c = xi; break;     // F
                case 5: r = (n - 1) - yi; c = (n - 1) - xi; break; // B
                case 0: r = (n - 1) - yi; c = (n - 1) - zi; break; // R
                default: r = (n - 1) - yi; c = zi; break;    // L
            }
            out[f][r * n + c] = col[i];
        }
    }

    // ------------------------------------------------------------------ serialisation

    public static String encode(List<int[]> moves) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < moves.size(); i++) {
            int[] m = moves.get(i);
            if (i > 0) sb.append(';');
            sb.append(m[0]).append(',').append(m[1]).append(',').append(m[2]);
        }
        return sb.toString();
    }

    public static List<int[]> decode(String s) {
        List<int[]> out = new ArrayList<int[]>();
        if (s == null || s.length() == 0) return out;
        for (String part : s.split(";")) {
            String[] p = part.split(",");
            out.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])});
        }
        return out;
    }
}
