package com.rubikscube;

/**
 * Face/layer selection for Megaminx dragging. Pure Java so it can be tested
 * without Android. Mirrors the square cube: the sticker touched identifies
 * the turning layer; the projected drag direction identifies the turn.
 *
 * Crucially, a drag *across* a face must NOT rotate that same face. The old
 * implementation also scored its own face; its rotation was often the best
 * screen-space match and incorrectly won over the adjacent yellow/right face.
 */
final class MegaminxGesture {
    private static final double NEIGHBOR_DOT = 0.40; // dodecahedron: 1/sqrt(5)
    private static final double TINY = 1e-8;

    private MegaminxGesture() { }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] camera(double[] p, double yaw, double pitch) {
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double cx = Math.cos(pitch), sx = Math.sin(pitch);
        double x = p[0] * cy + p[2] * sy;
        double z = -p[0] * sy + p[2] * cy;
        return new double[]{x, p[1] * cx - z * sx, p[1] * sx + z * cx};
    }

    /** First derivative of the exact perspective projection under face rotation. */
    static double[] tangent(double[] point, double[] axis,
                            double yaw, double pitch, double zoom) {
        double[] p = camera(point, yaw, pitch);
        double[] a = camera(axis, yaw, pitch);
        double vx = a[1] * p[2] - a[2] * p[1];
        double vy = a[2] * p[0] - a[0] * p[2];
        double vz = a[0] * p[1] - a[1] * p[0];
        double depth = 6.0 - p[2];
        if (depth < TINY) return new double[]{0, 0};
        double scale = 6.0 * zoom / (depth * depth);
        return new double[]{scale * (vx * depth + p[0] * vz),
                           -scale * (vy * depth + p[1] * vz)};
    }

    /**
     * Returns {face, direction}. The owning face is deliberately excluded
     * (just as square mode does not turn the face painted on the sticker).
     *
     * Prefer adjacent turning layers which include the grabbed sticker. If
     * the touch is on a centre/inner sticker, none of the five outer layers
     * contain it; use the drag to select a neighboring outer face instead
     * of unpredictably turning its own face.
     */
    /**
     * A legal drag must move the sticker actually grabbed. No fallback to some
     * other face: that was the intermittent wrong-face bug in v7. A near-tie is
     * intentionally ignored, letting the player change angle before release.
     * The owning face is reserved for an explicit central face turn.
     */
    static int[] moveFromDrag(ShapePuzzleModel model, int stickerId, float dx,
                              float dy, double yaw, double pitch, double zoom) {
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < TINY || stickerId < 0 || stickerId >= model.stickers.length) return null;
        ShapePuzzleModel.Sticker st = model.stickers[stickerId];
        int best = -1, sign = 1;
        double topScore = -1, runnerUp = -1;
        int eligible = 0;
        for (int target = 0; target < model.moveCount; target++) {
            if (target % 12 == st.face || !model.movesSticker(target, stickerId)) continue;
            eligible++;
            double[] v = tangent(st.centre, model.axis(target), yaw, pitch, zoom);
            double speed = Math.sqrt(v[0]*v[0] + v[1]*v[1]);
            if (speed < 1e-6) continue;
            double along = v[0]*dx + v[1]*dy;
            double score = Math.abs(along) / (speed * length);
            if (score > topScore) {
                runnerUp = topScore;
                topScore = score;
                best = target;
                sign = along >= 0 ? 1 : -1;
            } else if (score > runnerUp) runnerUp = score;
        }
        // Central stickers do not participate in adjacent turns. Never silently
        // switch to rotating the face under the finger (the v7 green/yellow bug).
        // The player can touch a strip nearer the edge to choose that layer.
        if (eligible == 0) return null;
        // Reject ambiguous directions instead of rotating the wrong face.
        if (best < 0 || topScore < .72 || (eligible > 1 && topScore - runnerUp < .085))
            return null;
        return new int[]{best, sign};
    }
}
