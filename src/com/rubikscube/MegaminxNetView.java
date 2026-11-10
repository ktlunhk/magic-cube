package com.rubikscube;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A compact, live 12-face Megaminx atlas; F1...F12 match algorithm notation. */
public final class MegaminxNetView extends View {
    private ShapePuzzleModel model;
    private boolean colorblind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float[][] localX, localY;
    private float[] minX, maxX, minY, maxY;

    public MegaminxNetView(Context c) {
        super(c);
        setContentDescription("Flat map of all twelve Megaminx faces");
    }

    private static double dot(double[] a, double[] b) {
        return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];
    }
    private static double[] unit(double[] a) {
        double d = Math.sqrt(dot(a,a));
        return new double[]{a[0]/d,a[1]/d,a[2]/d};
    }
    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};
    }

    public void bind(ShapePuzzleModel m) {
        model = m;
        if (m == null) return;
        localX = new float[m.stickers.length][];
        localY = new float[m.stickers.length][];
        minX = new float[12]; maxX = new float[12];
        minY = new float[12]; maxY = new float[12];
        for (int f=0; f<12; f++) {
            ShapePuzzleModel.Face face = m.faces[f];
            double[] e = unit(new double[]{face.outline[0][0]-face.normal[0],
                face.outline[0][1]-face.normal[1],face.outline[0][2]-face.normal[2]});
            double[] v = cross(face.normal,e);
            minX[f]=minY[f]=Float.POSITIVE_INFINITY;
            maxX[f]=maxY[f]=Float.NEGATIVE_INFINITY;
            for (int s=0; s<m.stickers.length; s++) {
                ShapePuzzleModel.Sticker tile=m.stickers[s];
                if (tile.face != f) continue;
                localX[s]=new float[tile.outline.length];
                localY[s]=new float[tile.outline.length];
                for (int k=0; k<tile.outline.length; k++) {
                    float x=(float)dot(tile.outline[k],e);
                    float y=(float)dot(tile.outline[k],v);
                    localX[s][k]=x;localY[s][k]=y;
                    minX[f]=Math.min(minX[f],x);maxX[f]=Math.max(maxX[f],x);
                    minY[f]=Math.min(minY[f],y);maxY[f]=Math.max(maxY[f],y);
                }
            }
        }
        invalidate();
    }

    public void setColorblind(boolean v) {colorblind=v;invalidate();}

    @Override protected void onDraw(Canvas c) {
        if (model==null) return;
        int[] palette=colorblind ? ShapePuzzleModel.COLORS_ACCESSIBLE:ShapePuzzleModel.COLORS;
        float cellW=getWidth()/4f,cellH=getHeight()/3f;
        float margin=Math.min(cellW,cellH)*.13f;
        float scale;
        paint.setTextAlign(Paint.Align.CENTER);
        for (int f=0; f<12; f++) {
            float cx=(f%4+.5f)*cellW;
            float cy=(f/4+.5f)*cellH;
            float width=maxX[f]-minX[f],height=maxY[f]-minY[f];
            scale=Math.min((cellW-2*margin)/width,(cellH-2*margin-12)/height);
            float offsetX=(minX[f]+maxX[f])*.5f;
            float offsetY=(minY[f]+maxY[f])*.5f;
            for (int s=0; s<model.stickers.length; s++) {
                if (model.stickers[s].face!=f) continue;
                path.reset();
                for (int k=0;k<localX[s].length;k++) {
                    float x=cx+(localX[s][k]-offsetX)*scale;
                    float y=cy+(localY[s][k]-offsetY)*scale;
                    if(k==0)path.moveTo(x,y);else path.lineTo(x,y);
                }
                path.close();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(palette[model.color(s)]);
                c.drawPath(path,paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1f);
                paint.setColor(0xFF111116);
                c.drawPath(path,paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            paint.setTextSize(Math.max(9f,Math.min(15f,cellH*.15f)));
            c.drawText("F"+(f+1),cx,(f/4+1)*cellH-3,paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }
}
