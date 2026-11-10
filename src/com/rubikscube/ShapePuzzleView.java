package com.rubikscube;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Interactive Canvas renderer for 1-, 2- and 3-row dodecahedral Megaminx puzzles. */
public final class ShapePuzzleView extends View {
    private static final int BG = 0xFF1B1B22;
    private static final int BODY = 0xFF0E0E13;
    private static final int F_RECORD = 1;
    private static final int F_UNDO = 2;
    private static final int F_SCRAMBLE = 4;
    private static final int F_SOLVE = 8;
    private static final int F_FAST = 16;
    private static final int F_SMART = 32;  // calculated solution from the actual sticker state

    private ShapePuzzleModel model;
    private CubeView.Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final ArrayDeque<int[]> queue = new ArrayDeque<int[]>();
    private final ArrayList<int[]> history = new ArrayList<int[]>();
    private final ArrayList<int[]> redo = new ArrayList<int[]>();
    private final ArrayList<int[]> trail = new ArrayList<int[]>();
    private final Random rnd = new Random();
    // Exact count of the last generated scramble, independent of undo history.
    private int lastScrambleCount;

    private float[][] sx, sy;
    private float[] depth, shade, centreX, centreY;
    private boolean[] visible;
    private int[] order;
    private float[] faceX, faceY;
    private int orderedCount;
    private boolean haptics = true;
    private boolean sound;
    private boolean colorblind;
    private final float density;
    private float slop;
    private float yaw = -0.48f;
    private float pitch = -0.64f;

    private boolean animating;
    private int animMove, animDir, animFlags;
    private long animStart, animLength;
    private float animAngle;
    private float downX, downY, lastX, lastY;
    private long lastTapTime;
    private int picked = -1;
    private boolean consumed;
    private int activePointer = -1;
    private boolean multiTouch;
    private float pinchDistance;
    private float zoomFactor = 1f;
    private int[] preview;
    private final float[] tempXY = new float[2];
    private float[][] bodyX, bodyY;
    private final double[] tmpCam = new double[3];
    private final double[] tmpRot = new double[3];
    private final double[] tmpNormal = new double[3];
    private final double[] tmpVertex = new double[3];
    private final double[] tempAxis = new double[3];
    private double cosYaw, sinYaw, cosPitch, sinPitch;

    public ShapePuzzleView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        slop = 28f * density;
        paint.setStrokeJoin(Paint.Join.ROUND);
        setShape(ShapePuzzleModel.PENTAGON);
    }

    public void setListener(CubeView.Listener value) { listener = value; }
    public ShapePuzzleModel getModel() { return model; }
    public int getShape() { return model.shape; }
    public int getRows() { return model.rows; }
    public int getMoves() { return history.size(); }
    public int getLastScrambleCount() { return lastScrambleCount; }
    public int getHistorySolveCount() { return trail.size(); }
    public boolean isBusy() { return animating || !queue.isEmpty(); }
    public boolean isSolved() { return model.isSolved(); }
    public boolean canUndo() { return !history.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public void setSlopDp(float dp) { slop = dp * density; }
    private boolean smartLocked;
    private boolean holdScrambling;   // open-ended scramble while the Scramble button is held
    private int holdPrev = -1;
    public void setFeedback(boolean doHaptics, boolean doSound) { haptics = doHaptics; sound = doSound; }
    public void setColorblind(boolean on) { colorblind = on; invalidate(); }

    public void resetView() {
        yaw = -0.48f;
        pitch = -0.64f;
        zoomFactor = 1f;
        invalidate();
    }

    public void setShape(int shape) {
        setShape(shape, 1);
    }

    public void setShape(int shape, int rows) {
        model = new ShapePuzzleModel(shape, rows);
        int count = model.stickers.length;
        sx = new float[count][];
        sy = new float[count][];
        for (int i = 0; i < count; i++) {
            sx[i] = new float[model.stickers[i].outline.length];
            sy[i] = new float[model.stickers[i].outline.length];
        }
        depth = new float[count];
        shade = new float[count];
        centreX = new float[count];
        centreY = new float[count];
        faceX = new float[model.faces.length];
        faceY = new float[model.faces.length];
        visible = new boolean[count];
        order = new int[count];
        queue.clear();
        holdScrambling = false;
        history.clear();
        redo.clear();
        trail.clear();
        lastScrambleCount = 0;
        animating = false;
        picked = -1;
        preview = null;
        bodyX = new float[12][5];
        bodyY = new float[12][5];
        resetView();
        notifyChanged();
    }

    public void reset() {
        holdScrambling = false;
        if (isBusy()) {
            animating = false;
            queue.clear();
        }
        model.reset();
        history.clear();
        redo.clear();
        trail.clear();
        lastScrambleCount = 0;
        notifyChanged();
        invalidate();
    }

    /** Starts a scramble that keeps going until {@link #endHoldScramble()} is called. */
    public boolean beginHoldScramble() {
        if (isBusy()) return false;
        history.clear();
        redo.clear();
        lastScrambleCount = 0;
        holdScrambling = true;
        holdPrev = -1;
        feedHoldScramble();
        feedHoldScramble();
        notifyChanged();
        startNext();
        invalidate();
        return true;
    }

    /** Stops adding moves; the few already queued finish, then the puzzle is ready to solve. */
    public void endHoldScramble() {
        holdScrambling = false;
    }

    private void feedHoldScramble() {
        int move;
        do { move = rnd.nextInt(model.moveCount); } while (move == holdPrev);
        holdPrev = move;
        queue.add(new int[]{move, rnd.nextBoolean() ? 1 : -1, F_SCRAMBLE});
        lastScrambleCount++;
    }

    public void scramble(int length) {
        if (isBusy()) return;
        history.clear();
        redo.clear();
        int total = length > 0 ? length : 45;
        lastScrambleCount = total;
        int instant = Math.max(0, total - 16);
        int prev = -1;
        for (int i = 0; i < total; i++) {
            int move;
            do { move = rnd.nextInt(model.moveCount); } while (move == prev);
            prev = move;
            int dir = rnd.nextBoolean() ? 1 : -1;
            if (i < instant) {
                model.turn(move, dir);
                trail.add(new int[]{move, dir});
            } else {
                queue.add(new int[]{move, dir, F_SCRAMBLE});
            }
        }
        notifyChanged();
        startNext();
        invalidate();
    }

    public void undo() {
        if (isBusy() || history.isEmpty()) return;
        int[] m = history.remove(history.size() - 1);
        redo.add(m);
        queue.add(new int[]{m[0], -m[1], F_UNDO});
        notifyChanged();
        startNext();
    }

    public void redo() {
        if (isBusy() || redo.isEmpty()) return;
        int[] m = redo.remove(redo.size() - 1);
        queue.add(new int[]{m[0], m[1], F_RECORD});
        startNext();
    }

    public boolean autoSolve() {
        if (isBusy() || trail.isEmpty()) return false;
        history.clear();
        redo.clear();
        int count = trail.size();
        int tail = Math.min(25, count);
        for (int i = 0; i < count - tail; i++) {
            int[] m = trail.remove(trail.size() - 1);
            model.turn(m[0], -m[1]);
        }
        for (int i = 0; i < tail; i++) {
            int[] m = trail.get(trail.size() - 1 - i);
            queue.add(new int[]{m[0], -m[1], F_SOLVE});
        }
        notifyChanged();
        startNext();
        return true;
    }

    /** Prevent gestures from changing the puzzle while a background solver reads a snapshot. */
    public void setSmartLocked(boolean locked) {
        smartLocked = locked;
        picked = -1;
        preview = null;
        consumed = false;
        invalidate();
    }

    /** Sticker colours for the Smart Solve worker; it must never read the live, changing model. */
    public int[] smartSnapshot() {
        if (isBusy()) throw new IllegalStateException("Finish animation first");
        return model.colors.clone();
    }

    /** Play verified solver moves (pairs of {move, direction}) with the standard animations. */
    public boolean playSmartSolution(List<int[]> moves) {
        if (isBusy() || moves == null || moves.isEmpty()) return false;
        history.clear();
        redo.clear();
        for (int[] m : moves) queue.add(new int[]{m[0], m[1], F_SMART});
        startNext();
        return true;
    }

    public void applyAlgorithm(String input) {
        if (isBusy()) return;
        List<int[]> moves = model.parse(input);
        if (moves.isEmpty()) return;
        redo.clear();
        for (int[] m : moves) queue.add(new int[]{m[0], m[1], F_RECORD | F_FAST});
        if (listener != null) listener.onUserMove();
        startNext();
    }

    public String hint(int count) {
        if (trail.isEmpty()) return null;
        ArrayList<int[]> hints = new ArrayList<int[]>();
        for (int i = trail.size() - 1; i >= 0 && hints.size() < count; i--) {
            int[] m = trail.get(i);
            hints.add(new int[]{m[0], -m[1]});
        }
        return model.format(hints);
    }

    public String lastMovesNotation(int count) {
        if (history.isEmpty()) return "";
        return model.format(history.subList(Math.max(0, history.size() - count), history.size()));
    }

    public String saveState() {
        flushPendingMoves();
        return "PENTROWS:" + model.rows + "|" + pack(trail)
                + "|" + pack(history) + "|" + pack(redo) + "|" + lastScrambleCount;
    }

    public boolean restoreState(String input) {
        try {
            int rows;
            String[] parts = input.split("\\|", -1);
            if (parts.length != 4 && parts.length != 5) return false;
            int restoredScramble = parts.length == 5 ? Integer.parseInt(parts[4]) : 0;
            if (restoredScramble < 0 || restoredScramble > 100000) return false;
            if (parts[0].startsWith("PENTROWS:")) {
                rows = Integer.parseInt(parts[0].substring(9));
            } else if (parts[0].equals("5")) {
                rows = 1; // Migration of original 11-sticker Megaminx save.
            } else return false;
            if (rows < ShapePuzzleModel.MIN_ROWS || rows > ShapePuzzleModel.MAX_ROWS) return false;
            // Validate all moves before affecting the view.
            List<int[]> moves = unpack(parts[1]);
            List<int[]> oldHistory = unpack(parts[2]);
            List<int[]> oldRedo = unpack(parts[3]);
            for (List<int[]> list : new List[]{moves, oldHistory, oldRedo}) {
                for (int[] m : list) {
                    if (m[0] < 0 || m[0] >= 12 * rows || (m[1] != 1 && m[1] != -1)) return false;
                }
            }
            setShape(ShapePuzzleModel.PENTAGON, rows);
            for (int[] mv : moves) {
                model.turn(mv[0], mv[1]);
                trail.add(mv);
            }
            history.addAll(oldHistory);
            redo.addAll(oldRedo);
            lastScrambleCount = restoredScramble;
            notifyChanged();
            invalidate();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String pack(List<int[]> moves) {
        StringBuilder b = new StringBuilder();
        for (int[] move : moves) {
            if (b.length() > 0) b.append(';');
            b.append(move[0]).append(',').append(move[1]);
        }
        return b.toString();
    }
    private static List<int[]> unpack(String text) {
        ArrayList<int[]> result = new ArrayList<int[]>();
        if (text.length() == 0) return result;
        for (String part : text.split(";")) {
            String[] m = part.split(",");
            if (m.length != 2) throw new IllegalArgumentException("Invalid saved move");
            result.add(new int[]{Integer.parseInt(m[0]), Integer.parseInt(m[1])});
        }
        return result;
    }

    /** Commit all queued moves synchronously before Android persists the state.
     * This avoids saving half a turn or half a scramble when the app backgrounds.
     */
    public void flushPendingMoves() {
        holdScrambling = false;
        if (!animating && queue.isEmpty()) return;
        boolean didScramble = false;
        if (animating) {
            applyCompletedMove(animMove, animDir, animFlags);
            didScramble = (animFlags & F_SCRAMBLE) != 0;
            animating = false;
        }
        while (!queue.isEmpty()) {
            int[] m = queue.remove();
            applyCompletedMove(m[0], m[1], m[2]);
            didScramble |= (m[2] & F_SCRAMBLE) != 0;
        }
        animAngle = 0;
        notifyChanged();
        if (didScramble && listener != null) listener.onScrambleFinished();
        invalidate();
    }

    private void applyCompletedMove(int move, int dir, int flags) {
        model.turn(move, dir);
        int[] m = new int[]{move, dir};
        if ((flags & F_RECORD) != 0) {
            history.add(m);
            trail.add(m);
        } else if ((flags & F_SCRAMBLE) != 0) trail.add(m);
        else if ((flags & (F_UNDO | F_SOLVE)) != 0 && !trail.isEmpty())
            trail.remove(trail.size() - 1);
        else if ((flags & F_SMART) != 0) {
            // Keep a replayable state until solving finishes (including an app pause).
            trail.add(m);
            if (model.isSolved()) trail.clear();
        }
    }

    private void notifyChanged() {
        boolean solved = model.isSolved();
        setContentDescription("Pentagon Megaminx, " + model.rows + " rows, "
                + (solved ? "solved" : "scrambled") + ", " + history.size() + " moves");
        if (listener != null) listener.onCubeChanged(solved, history.size());
    }

    private void startNext() {
        if (animating) return;
        int[] next = queue.poll();
        if (next == null) return;
        animMove = next[0];
        animDir = next[1];
        animFlags = next[2];
        animStart = System.currentTimeMillis();
        animLength = ((animFlags & F_SMART) != 0)
                ? (queue.size() > 20 ? 22 : 60)
                : ((animFlags & (F_SCRAMBLE | F_SOLVE)) != 0)
                ? (queue.size() > 20 ? 28 : (holdScrambling ? 45 : 70)) : ((animFlags & F_FAST) != 0 ? 100 : 240);
        animating = true;
        if ((animFlags & (F_RECORD | F_UNDO)) != 0) {
            if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (sound) playSoundEffect(SoundEffectConstants.CLICK);
        }
        invalidate();
    }

    private void completeMove() {
        applyCompletedMove(animMove, animDir, animFlags);
        if (holdScrambling) feedHoldScramble();
        boolean scrambled = (animFlags & F_SCRAMBLE) != 0
                && (queue.isEmpty() || (queue.peek()[2] & F_SCRAMBLE) == 0);
        animating = false;
        animAngle = 0;
        notifyChanged();
        if (scrambled && listener != null) listener.onScrambleFinished();
        startNext();
    }

    private void updateAnimation() {
        animAngle = 0;
        if (!animating) return;
        float t = (System.currentTimeMillis() - animStart) / (float) animLength;
        if (t >= 1) completeMove();
        else {
            float ease = t * t * (3 - 2 * t);
            animAngle = (float) model.angle(animDir) * ease;
        }
    }

    /** Camera transform using reusable buffers; avoids per-sticker allocations. */
    private void cameraInto(double[] p, double[] out) {
        double x = p[0] * cosYaw + p[2] * sinYaw;
        double z = -p[0] * sinYaw + p[2] * cosYaw;
        out[0] = x;
        out[1] = p[1] * cosPitch - z * sinPitch;
        out[2] = p[1] * sinPitch + z * cosPitch;
    }

    private static void rotateInto(double[] p, double[] axis, double angle, double[] out) {
        double c=Math.cos(angle), s=Math.sin(angle), t=1-c;
        double d=p[0]*axis[0]+p[1]*axis[1]+p[2]*axis[2];
        out[0]=p[0]*c+(axis[1]*p[2]-axis[2]*p[1])*s+axis[0]*d*t;
        out[1]=p[1]*c+(axis[2]*p[0]-axis[0]*p[2])*s+axis[1]*d*t;
        out[2]=p[2]*c+(axis[0]*p[1]-axis[1]*p[0])*s+axis[2]*d*t;
    }

    private float zoom() {
        return (float) (Math.min(getWidth(), getHeight()) * 0.54 / 1.75) * zoomFactor;
    }

    public void zoomBy(float ratio) {
        if (ratio <= 0 || Float.isNaN(ratio) || Float.isInfinite(ratio)) return;
        zoomFactor = Math.max(.65f, Math.min(2.2f, zoomFactor * ratio));
        invalidate();
    }

    private void toScreen(double[] p, float[] xy) {
        double perspective = 6.0 / (6.0 - p[2]);
        float zoom = zoom();
        xy[0] = (float) (getWidth() * .5 + p[0] * perspective * zoom);
        xy[1] = (float) (getHeight() * .5 - p[1] * perspective * zoom);
    }

    private void projection() {
        cosYaw=Math.cos(yaw);sinYaw=Math.sin(yaw);
        cosPitch=Math.cos(pitch);sinPitch=Math.sin(pitch);
        float[] xy = tempXY;
        for (int f = 0; f < model.faces.length; f++) {
            cameraInto(model.faces[f].normal,tmpCam);
            toScreen(tmpCam, xy);
            faceX[f] = xy[0]; faceY[f] = xy[1];
        }
        int visibleCount = 0;
        double[] axis=animating ? model.axis(animMove) : null;
        for (int i = 0; i < model.stickers.length; i++) {
            ShapePuzzleModel.Sticker sticker = model.stickers[i];
            boolean turn=animating && model.movesSticker(animMove,i);
            if(turn) {
                rotateInto(sticker.centre,axis,animAngle,tmpRot);
                rotateInto(sticker.normal,axis,animAngle,tmpNormal);
            } else {
                System.arraycopy(sticker.centre,0,tmpRot,0,3);
                System.arraycopy(sticker.normal,0,tmpNormal,0,3);
            }
            cameraInto(tmpRot,tmpCam);
            depth[i]=(float)tmpCam[2];
            double cx=tmpCam[0],cy=tmpCam[1],cz=tmpCam[2];
            cameraInto(tmpNormal,tmpCam);
            shade[i]=(float)Math.max(0,tmpCam[2]);
            visible[i]=tmpCam[0]*-cx+tmpCam[1]*-cy+tmpCam[2]*(6.0-cz)>0;
            if(!visible[i])continue;
            order[visibleCount++]=i;
            tmpCam[0]=cx;tmpCam[1]=cy;tmpCam[2]=cz;
            toScreen(tmpCam,xy);
            centreX[i]=xy[0];centreY[i]=xy[1];
            for(int k=0;k<sticker.outline.length;k++) {
                double[] point=sticker.outline[k];
                if(turn) {
                    rotateInto(point,axis,animAngle,tmpVertex);
                    point=tmpVertex;
                }
                cameraInto(point,tmpCam);
                toScreen(tmpCam,xy);
                sx[i][k]=xy[0];sy[i][k]=xy[1];
            }
        }
        orderedCount=visibleCount;
        // Insertion sort on indexed depth values, no boxing or allocations.
        for(int i=1;i<orderedCount;i++) {
            int key=order[i],j=i-1;
            while(j>=0 && depth[order[j]]>depth[key]) {
                order[j+1]=order[j];j--;
            }
            order[j+1]=key;
        }
    }

    private void buildPath(float[] xs, float[] ys) {
        path.reset();
        for (int i = 0; i < xs.length; i++) {
            if (i == 0) path.moveTo(xs[i], ys[i]);
            else path.lineTo(xs[i], ys[i]);
        }
        path.close();
    }

    private static int darken(int color, float amount) {
        int r = (int) (((color >>> 16) & 255) * amount);
        int g = (int) (((color >>> 8) & 255) * amount);
        int b = (int) ((color & 255) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(BG);
        updateAnimation();
        projection();
        // Dark body behind the face stickers, to make the gaps and cuts visible.
        float[] tmp = tempXY;
        for (int fi = 0; fi < model.faces.length; fi++) {
            ShapePuzzleModel.Face f = model.faces[fi];
            cameraInto(f.normal,tmpCam);
            if (tmpCam[2] <= -0.05) continue;
            float[] xs = bodyX[fi];
            float[] ys = bodyY[fi];
            for (int i = 0; i < f.outline.length; i++) {
                cameraInto(f.outline[i],tmpCam);
                toScreen(tmpCam,tmp);
                xs[i] = tmp[0]; ys[i] = tmp[1];
            }
            buildPath(xs, ys);
            paint.setColor(BODY);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(path, paint);
        }
        int[] palette = colorblind ? ShapePuzzleModel.COLORS_ACCESSIBLE : ShapePuzzleModel.COLORS;
        float border = Math.max(1.0f, density * 1.5f);
        for (int k = 0; k < orderedCount; k++) {
            int i = order[k];
            buildPath(sx[i], sy[i]);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(darken(palette[model.color(i)], 0.73f + shade[i] * 0.27f));
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(border);
            paint.setColor(BODY);
            canvas.drawPath(path, paint);
            if (preview != null && model.movesSticker(preview[0], i)) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x55FFFFFF);
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(border * 1.6f);
                paint.setColor(0xFFF7DB55);
                canvas.drawPath(path, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        if (preview != null) {
            int face = preview[0] % 12;
            paint.setTextSize(15f * density);
            paint.setColor(0xFFFFFFFF);
            canvas.drawText("Turn " + model.moveName(preview[0]) +
                (preview[1] < 0 ? " reverse" : " forward"),
                12f * density, 25f * density, paint);
        }
        if (isBusy()) postInvalidateOnAnimation();
    }

    private boolean hit(int i, float x, float y) {
        boolean inside = false;
        int len = sx[i].length;
        for (int a = 0, b = len - 1; a < len; b = a++) {
            float xa = sx[i][a], ya = sy[i][a], xb = sx[i][b], yb = sy[i][b];
            if (((ya > y) != (yb > y)) &&
                    (x < (xb - xa) * (y - ya) / (yb - ya) + xa)) inside = !inside;
        }
        return inside;
    }

    private int pickSticker(float x, float y) {
        if (isBusy()) return -1;
        // Painter's list goes from farthest to nearest; check front first.
        for (int k = orderedCount - 1; k >= 0; k--) {
            int i = order[k];
            if (hit(i, x, y)) return i;
        }
        return -1;
    }

    /** Use the same grabbed-sticker / projected-direction rule as square mode. */
    private int[] moveFromDrag(int stickerId, float dx, float dy) {
        return MegaminxGesture.moveFromDrag(model, stickerId, dx, dy,
                yaw, pitch, zoom());
    }

    private void orbitView(float dx, float dy) {
        // Flipped view: adjust yaw so horizontal movement still follows finger.
        float side = Math.cos(pitch) >= 0 ? 1f : -1f;
        yaw += dx * 0.010f * side;
        pitch += dy * 0.010f;
        float period = (float) (2 * Math.PI);
        if (pitch > period) pitch -= period;
        if (pitch < -period) pitch += period;
        invalidate();
    }

    private void playerMove(int[] move) {
        if (isBusy() || move == null) return;
        redo.clear();
        queue.add(new int[]{move[0], move[1], F_RECORD});
        if (listener != null) listener.onUserMove();
        startNext();
        invalidate();
    }

    private static float pointerDistance(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0;
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx*dx + dy*dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (smartLocked) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            activePointer = event.getPointerId(0);
            downX = lastX = event.getX(0);
            downY = lastY = event.getY(0);
            picked = pickSticker(downX, downY);
            multiTouch = false;
            consumed = false;
            preview = null;
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouch = true;
            picked = -1;
            preview = null;
            pinchDistance = pointerDistance(event);
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (multiTouch) {
                if (event.getPointerCount() >= 2) {
                    float d = pointerDistance(event);
                    if (pinchDistance > 1 && d > 1) zoomBy(d / pinchDistance);
                    pinchDistance = d;
                }
                return true;
            }
            int index = event.findPointerIndex(activePointer);
            if (index < 0) return true;
            float x = event.getX(index), y = event.getY(index);
            if (picked >= 0 && !isBusy()) {
                float dx = x - downX, dy = y - downY;
                int[] newPreview = dx*dx + dy*dy >= slop*slop
                        ? moveFromDrag(picked, dx, dy) : null;
                if (preview == null || newPreview == null ||
                        preview[0] != newPreview[0] || preview[1] != newPreview[1]) {
                    preview = newPreview;
                    invalidate();
                }
            } else if (picked < 0) orbitView(x-lastX, y-lastY);
            lastX = x;
            lastY = y;
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            multiTouch = true; // Suppress accidental face turn after a pinch.
            pinchDistance = 0;
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (!multiTouch && picked >= 0 && !isBusy()) {
                float dx = event.getX() - downX, dy = event.getY() - downY;
                if (dx*dx + dy*dy >= slop*slop) {
                    // Use the full gesture on release, never its first few pixels.
                    int[] chosen = moveFromDrag(picked, dx, dy);
                    playerMove(chosen);
                }
            } else if (!multiTouch && picked < 0) {
                float dx = event.getX()-downX, dy = event.getY()-downY;
                if (dx*dx+dy*dy < slop*slop) {
                    if (event.getEventTime()-lastTapTime < 300) {
                        resetView();
                        lastTapTime = 0;
                    } else lastTapTime = event.getEventTime();
                }
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            picked = -1;
            activePointer = -1;
            multiTouch = false;
            preview = null;
            invalidate();
            return true;
        }
        return super.onTouchEvent(event);
    }
}
