package com.rubikscube;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Draws the cube as a flat cross-shaped net (U on top; L F R B across; D below). */
public class NetView extends View {

    // grid position (column, row) of each face index 0=R 1=L 2=U 3=D 4=F 5=B in a 4x3 layout
    private static final int[] FACE_COL = {2, 0, 1, 1, 1, 3};
    private static final int[] FACE_ROW = {1, 1, 0, 2, 1, 1};

    private CubeModel model;
    private int[] palette;
    private int[][] grids = new int[6][];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public NetView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.FILL);
        setContentDescription("Flat view of all six cube faces");
    }

    public void bind(CubeModel model, int[] palette) {
        this.model = model;
        this.palette = palette;
        invalidate();
    }

    public void setPalette(int[] palette) {
        this.palette = palette;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (model == null || palette == null) return;
        int n = model.n;
        for (int f = 0; f < 6; f++) {
            if (grids[f] == null || grids[f].length != n * n) grids[f] = new int[n * n];
        }
        model.faceGrids(grids);

        float w = getWidth();
        float h = getHeight();
        float cell = Math.min(w / (4f * n + 3f * 0.15f * n), h / (3f * n + 2f * 0.15f * n));
        float gapFace = cell * n * 0.15f;
        float totalW = 4 * n * cell + 3 * gapFace;
        float totalH = 3 * n * cell + 2 * gapFace;
        float ox = (w - totalW) / 2f;
        float oy = (h - totalH) / 2f;
        float pad = cell * 0.06f;

        for (int f = 0; f < 6; f++) {
            float fx = ox + FACE_COL[f] * (n * cell + gapFace);
            float fy = oy + FACE_ROW[f] * (n * cell + gapFace);
            for (int r = 0; r < n; r++) {
                for (int c = 0; c < n; c++) {
                    paint.setColor(palette[grids[f][r * n + c]]);
                    float x = fx + c * cell;
                    float y = fy + r * cell;
                    canvas.drawRect(x + pad, y + pad, x + cell - pad, y + cell - pad, paint);
                }
            }
        }
    }
}
