package com.rubikscube;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Android-free geometry and sticker-permutation engine for a dodecahedral puzzle.
 * Pentagon faces can have 1, 2 or 3 concentric rows of cut-lines (Megaminx,
 * Gigaminx-style and higher-order). There are 11, 31 or 61 stickers on each face.
 * Turning a face moves the adjacent outer strip on its five neighbours.
 *
 * Only the colours are mutable.  Geometry is fixed, and each turn is an exact precomputed
 * permutation; repeated turns never introduce floating-point drift in the puzzle state.
 */
public final class ShapePuzzleModel {
    public static final int PENTAGON = 5;
    public static final int MIN_ROWS = 1;
    public static final int MAX_ROWS = 3;

    public static final int[] COLORS = {
        0xFFFFFFFF, 0xFFFFD500, 0xFFC41E3A, 0xFFFF5800,
        0xFF009B48, 0xFF0046AD, 0xFF9D4EDD, 0xFF00B8D9,
        0xFFF08AAE, 0xFF9ED44E, 0xFFB88854, 0xFF607D8B
    };
    public static final int[] COLORS_ACCESSIBLE = {
        0xFFFFFFFF, 0xFFF0E442, 0xFFD55E00, 0xFFCC79A7,
        0xFF009E73, 0xFF0072B2, 0xFF8E63BD, 0xFF26B6C5,
        0xFFDD778D, 0xFFA6C84C, 0xFFB8905B, 0xFF536D7A
    };

    /** All coordinates are in world space; points are counter-clockwise from outside. */
    public static final class Face {
        public final double[] normal;
        public final double[][] outline;
        Face(double[] normal, double[][] outline) {
            this.normal = normal;
            this.outline = outline;
        }
    }

    public static final class Sticker {
        public final int face;
        public final int group; // incident-face bit mask (including own face)
        public final double[] normal;
        public final double[] centre;
        public final double[][] outline;
        Sticker(int face, int group, double[] normal, double[] centre, double[][] outline) {
            this.face = face;
            this.group = group;
            this.normal = normal;
            this.centre = centre;
            this.outline = outline;
        }
    }

    public final int shape;
    public final int rows;
    public final Face[] faces;
    public final Sticker[] stickers;
    public final int[] colors;
    public final int moveCount;
    private final double[][] axes;
    private final double stepAngle;
    private final int[][][] permutations;
    private final boolean[][] moving;
    private final int[] scratch;

    private static final double PHI = (1 + Math.sqrt(5)) / 2;
    private static final double EPS = 0.000001;

    public ShapePuzzleModel(int shape) {
        this(shape, 1);
    }

    public ShapePuzzleModel(int shape, int rows) {
        if (shape != PENTAGON) throw new IllegalArgumentException("Unknown shape");
        if (rows < MIN_ROWS || rows > MAX_ROWS) throw new IllegalArgumentException("Invalid rows");
        this.shape = shape;
        this.rows = rows;
        ArrayList<Face> f = new ArrayList<Face>();
        ArrayList<Sticker> s = new ArrayList<Sticker>();
        buildDodecahedron(f, s);
        faces = f.toArray(new Face[f.size()]);
        stickers = s.toArray(new Sticker[s.size()]);
        colors = new int[stickers.length];
        moveCount = 12 * rows;
        axes = new double[moveCount][];
        for (int i = 0; i < moveCount; i++) axes[i] = faces[i % 12].normal;
        stepAngle = Math.PI * 2 / 5;
        moving = new boolean[moveCount][stickers.length];
        permutations = new int[moveCount][2][stickers.length];
        scratch = new int[stickers.length];
        buildPermutations();
        reset();
    }

    public void reset() {
        for (int i = 0; i < stickers.length; i++) colors[i] = stickers[i].face;
    }

    public int color(int i) {
        return colors[i];
    }

    public boolean isSolved() {
        for (int i = 0; i < stickers.length; i++) {
            if (colors[i] != stickers[i].face) return false;
        }
        return true;
    }

    public boolean movesSticker(int move, int sticker) {
        return moving[move][sticker];
    }

    public double[] axis(int move) {
        return axes[move];
    }

    public double angle(int direction) {
        return stepAngle * direction;
    }

    public String moveName(int move) {
        return "F" + (move % 12 + 1) + (move < 12 ? "" : "L" + (move / 12 + 1));
    }

    /** Shape move: array of {face/layer number, direction +1 / -1}. */
    public void turn(int move, int direction) {
        if (move < 0 || move >= moveCount || (direction != 1 && direction != -1)) {
            throw new IllegalArgumentException("Invalid layer turn");
        }
        int[] perm = permutations[move][direction > 0 ? 0 : 1];
        for (int i = 0; i < colors.length; i++) scratch[perm[i]] = colors[i];
        System.arraycopy(scratch, 0, colors, 0, colors.length);
    }

    public List<int[]> parse(String input) {
        ArrayList<int[]> out = new ArrayList<int[]>();
        if (input == null || input.trim().length() == 0) return out;
        String[] tokens = input.toUpperCase(java.util.Locale.US).split("[\\s,]+");
        for (String t : tokens) {
            if (t.length() == 0) continue;
            int dir = 1;
            if (t.endsWith("'")) {
                dir = -1;
                t = t.substring(0, t.length() - 1);
            }
            int count = 1;
            // F2 is face 2; F2x2 is two turns of face 2.
            if (t.endsWith("X2")) {
                count = 2;
                t = t.substring(0, t.length() - 2);
            }
            int id = -1;
            if (t.startsWith("F")) {
                try {
                    String[] bits = t.substring(1).split("L", -1);
                    int face = Integer.parseInt(bits[0]);
                    int layer = bits.length == 2 ? Integer.parseInt(bits[1]) : 1;
                    if (face >= 1 && face <= 12 && layer >= 1 && layer <= rows)
                        id = (layer - 1) * 12 + face - 1;
                } catch (RuntimeException ex) { id = -1; }
            }
            if (id < 0 || id >= moveCount) throw new IllegalArgumentException("Bad move: " + t);
            for (int i = 0; i < count; i++) out.add(new int[]{id, dir});
        }
        return out;
    }

    public String format(List<int[]> moves) {
        StringBuilder sb = new StringBuilder();
        for (int[] mv : moves) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(moveName(mv[0]));
            if (mv[1] < 0) sb.append('\'');
        }
        return sb.toString();
    }

    private void buildPermutations() {
        // Each face has an outer face turn and (rows-1) *independent* internal
        // slice turns. A ring on the five adjacent faces has a fixed axial depth;
        // this is invariant under 72-degree rotation and gives exact permutations.
        for (int move = 0; move < moveCount; move++) {
            int face = move % 12;
            int layer = move / 12;
            if (layer == 0) {
                for (int i = 0; i < stickers.length; i++) {
                    Sticker st = stickers[i];
                    moving[move][i] = st.face == face || containsIncident(face, st);
                }
            } else {
                double[] axis = axes[move];
                double[] levels = new double[rows];
                int found = 0;
                for (int i = 0; i < stickers.length; i++) {
                    Sticker st = stickers[i];
                    if (st.face == face || dot(faces[st.face].normal, axis) < 0.40) continue;
                    double depth = dot(st.centre, axis);
                    if (depth < 0.65) continue;
                    boolean seen = false;
                    for (int k = 0; k < found; k++) if (Math.abs(levels[k] - depth) < 0.00001) seen = true;
                    if (!seen) {
                        if (found < rows) levels[found++] = depth;
                        else {
                            int last = 0;
                            for (int k = 1; k < rows; k++) if (levels[k] < levels[last]) last = k;
                            if (depth > levels[last]) levels[last] = depth;
                        }
                    }
                }
                if (found != rows) throw new IllegalStateException("Missing Megaminx slice levels");
                java.util.Arrays.sort(levels);
                double targetDepth = levels[rows - 1 - layer];
                for (int i = 0; i < stickers.length; i++) {
                    Sticker st = stickers[i];
                    moving[move][i] = st.face != face
                            && dot(faces[st.face].normal, axis) > 0.40
                            && Math.abs(dot(st.centre, axis) - targetDepth) < 0.00001;
                }
            }
            for (int q = 0; q < 2; q++) {
                double angle = stepAngle * (q == 0 ? 1 : -1);
                int[] perm = permutations[move][q];
                boolean[] occupied = new boolean[stickers.length];
                for (int i = 0; i < stickers.length; i++) {
                    if (!moving[move][i]) {
                        perm[i] = i;
                        occupied[i] = true;
                    }
                }
                for (int i = 0; i < stickers.length; i++) {
                    if (!moving[move][i]) continue;
                    double[] p = rotate(stickers[i].centre, axes[move], angle);
                    double[] n = rotate(stickers[i].normal, axes[move], angle);
                    int best = -1;
                    double error = Double.MAX_VALUE;
                    for (int j = 0; j < stickers.length; j++) {
                        if (!moving[move][j] || occupied[j]) continue;
                        double dis = squaredDistance(p, stickers[j].centre)
                                + squaredDistance(n, stickers[j].normal);
                        if (dis < error) { error = dis; best = j; }
                    }
                    if (best < 0 || error > 0.00001) {
                        throw new IllegalStateException("Cannot match " + shape + " move " + move
                                + " sticker " + i + ", error=" + error);
                    }
                    perm[i] = best;
                    occupied[best] = true;
                }
            }
        }
    }

    /**
     * In a dodecahedron all stickers belonging to the vertex or edge cubie incident to
     * the turned face move together.  Centre stickers on other faces stay fixed.
     * The group's membership is encoded as a bit mask of faces (up to 12).
     */
    private boolean containsIncident(int face, Sticker s) {
        return (s.group & (1 << face)) != 0;
    }

    // --------------------------- dodecahedron, the dual of an icosahedron
    private void buildDodecahedron(ArrayList<Face> out, ArrayList<Sticker> tiles) {
        double[][] raw = {
            {0, -1, -PHI}, {0, -1, PHI}, {0, 1, -PHI}, {0, 1, PHI},
            {-1, -PHI, 0}, {-1, PHI, 0}, {1, -PHI, 0}, {1, PHI, 0},
            {-PHI, 0, -1}, {-PHI, 0, 1}, {PHI, 0, -1}, {PHI, 0, 1}
        };
        final double[][] normals = new double[12][];
        for (int i = 0; i < 12; i++) normals[i] = normalized(raw[i]);
        // Each triangular face of the icosahedron becomes a vertex of the dodecahedron.
        ArrayList<int[]> triples = new ArrayList<int[]>();
        final ArrayList<double[]> verts = new ArrayList<double[]>();
        double closest = Double.MAX_VALUE;
        for (int a = 0; a < 12; a++) for (int b = a + 1; b < 12; b++) {
            double dis = squaredDistance(normals[a], normals[b]);
            if (dis > EPS && dis < closest) closest = dis;
        }
        for (int a = 0; a < 12; a++) for (int b = a + 1; b < 12; b++)
            for (int c = b + 1; c < 12; c++) {
                if (Math.abs(squaredDistance(normals[a], normals[b]) - closest) > EPS
                        || Math.abs(squaredDistance(normals[b], normals[c]) - closest) > EPS
                        || Math.abs(squaredDistance(normals[a], normals[c]) - closest) > EPS) continue;
                double[] v = add(add(normals[a], normals[b]), normals[c]);
                v = scale(v, 1.0 / dot(v, normals[a]));
                triples.add(new int[]{a, b, c});
                verts.add(v);
            }
        if (triples.size() != 20) throw new IllegalStateException("Dodecahedron vertices invalid");
        for (int face = 0; face < 12; face++) {
            final double[] normal = normals[face];
            final ArrayList<Integer> ids = new ArrayList<Integer>();
            for (int t = 0; t < triples.size(); t++) if (has(triples.get(t), face)) ids.add(t);
            if (ids.size() != 5) throw new IllegalStateException("Dodecahedron face invalid");
            final double[] base = normalized(sub(verts.get(ids.get(0)), normal));
            final double[] perpendicular = cross(normal, base);
            Collections.sort(ids, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    double[] va = sub(verts.get(a), normal);
                    double[] vb = sub(verts.get(b), normal);
                    double aa = Math.atan2(dot(va, perpendicular), dot(va, base));
                    double bb = Math.atan2(dot(vb, perpendicular), dot(vb, base));
                    return aa < bb ? -1 : (aa > bb ? 1 : 0);
                }
            });
            double[][] outline = new double[5][];
            for (int a = 0; a < 5; a++) outline[a] = verts.get(ids.get(a));
            out.add(new Face(normal, outline));
            // Cut the pentagon by rows of chords parallel to all five sides.
            // One row produces 11 stickers; two rows produce 31; three produce 61.
            // This produces the characteristic Gigaminx criss-cross lines, not
            // a set of radial concentric pentagons.
            ArrayList<double[][]> parts = new ArrayList<double[][]>();
            parts.add(outline);
            for (int side = 0; side < 5; side++) {
                double[] sideMid = mix(outline[side], outline[(side + 1) % 5], 0.5);
                double[] inward = normalized(sub(normal, sideMid));
                for (int layer = 1; layer <= rows; layer++) {
                    // Stagger cuts towards the middle. Keeping all cuts <= .60
                    // avoids tiny triangular slivers on the face.
                    double t = 0.60 * layer / rows;
                    if (rows == 1) t = 0.42;
                    double[] cutPoint = mix(sideMid, normal, t);
                    ArrayList<double[][]> next = new ArrayList<double[][]>();
                    for (double[][] part : parts) {
                        double[][] positive = clipped(part, cutPoint, inward, true);
                        double[][] negative = clipped(part, cutPoint, inward, false);
                        if (positive.length >= 3 && polygonArea(positive) > EPS) next.add(positive);
                        if (negative.length >= 3 && polygonArea(negative) > EPS) next.add(negative);
                    }
                    parts = next;
                }
            }
            for (double[][] polygon : parts) {
                double[] centroid = new double[3];
                for (double[] point : polygon) centroid = add(centroid, point);
                centroid = scale(centroid, 1.0 / polygon.length);
                int groups = (1 << face);
                for (int side = 0; side < 5; side++) {
                    // A tile touching a dodecahedron boundary is in the outer
                    // turning layer of the face across that side.
                    double[] a = outline[side], b = outline[(side + 1) % 5];
                    double[] along = sub(b, a);
                    for (double[] point : polygon) {
                        double[] rel = sub(point, a);
                        double position = dot(rel, along) / dot(along, along);
                        double[] onLine = add(a, scale(along, position));
                        if (position >= -EPS && position <= 1 + EPS
                                && squaredDistance(point, onLine) < 0.00000001) {
                            groups |= incident(triples.get(ids.get(side)),
                                               triples.get(ids.get((side + 1) % 5)));
                            break;
                        }
                    }
                }
                tiles.add(new Sticker(face, groups, normal, centroid, polygon));
            }

        }
    }

    /** Convex polygon half-plane clip, retaining the desired side of a chord. */
    private static double[][] clipped(double[][] polygon, double[] point,
                                       double[] inward, boolean positive) {
        ArrayList<double[]> out = new ArrayList<double[]>();
        for (int i = 0; i < polygon.length; i++) {
            double[] a = polygon[i];
            double[] b = polygon[(i + 1) % polygon.length];
            double da = dot(sub(a, point), inward) * (positive ? 1 : -1);
            double db = dot(sub(b, point), inward) * (positive ? 1 : -1);
            boolean keepA = da >= -1e-9;
            boolean keepB = db >= -1e-9;
            if (keepA && keepB) {
                addUnique(out, b);
            } else if (keepA && !keepB) {
                addUnique(out, mix(a, b, da / (da - db)));
            } else if (!keepA && keepB) {
                addUnique(out, mix(a, b, da / (da - db)));
                addUnique(out, b);
            }
        }
        // Remove duplicate closing point as well as consecutive duplicate points.
        if (out.size() > 1 && squaredDistance(out.get(0), out.get(out.size() - 1)) < 1e-16) {
            out.remove(out.size() - 1);
        }
        return out.toArray(new double[out.size()][]);
    }

    private static void addUnique(ArrayList<double[]> points, double[] v) {
        if (points.isEmpty() || squaredDistance(points.get(points.size() - 1), v) > 1e-16)
            points.add(v);
    }

    private static double polygonArea(double[][] poly) {
        if (poly.length < 3) return 0;
        double[] a = poly[0];
        double area = 0;
        for (int i = 1; i + 1 < poly.length; i++) {
            double[] cross = cross(sub(poly[i], a), sub(poly[i + 1], a));
            area += Math.sqrt(dot(cross, cross)) * 0.5;
        }
        return area;
    }

    private static boolean has(int[] a, int value) {
        for (int v : a) if (v == value) return true;
        return false;
    }
    private static int mask(int[] a) {
        int v = 0;
        for (int f : a) v |= 1 << f;
        return v;
    }
    private static int incident(int[] a, int[] b) {
        return mask(a) & mask(b);
    }
    private static double[][] inset(double[][] polygon, double[] centre, double factor) {
        double[][] result = new double[polygon.length][];
        for (int i = 0; i < polygon.length; i++) result[i] = mix(centre, polygon[i], factor);
        return result;
    }
    public static double[] rotate(double[] point, double[] unitAxis, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        double d = dot(unitAxis, point);
        double[] cross = cross(unitAxis, point);
        return new double[]{
            point[0] * c + cross[0] * s + unitAxis[0] * d * (1 - c),
            point[1] * c + cross[1] * s + unitAxis[1] * d * (1 - c),
            point[2] * c + cross[2] * s + unitAxis[2] * d * (1 - c)
        };
    }
    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }
    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }
    private static double[] scale(double[] a, double t) {
        return new double[]{a[0] * t, a[1] * t, a[2] * t};
    }
    private static double[] mix(double[] a, double[] b, double t) {
        return add(scale(a, 1 - t), scale(b, t));
    }
    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
    private static double[] normalized(double[] a) {
        return scale(a, 1 / Math.sqrt(dot(a, a)));
    }
    private static double squaredDistance(double[] a, double[] b) {
        double x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return x * x + y * y + z * z;
    }
}
