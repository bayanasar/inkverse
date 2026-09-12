package com.bayanasar.inkverse;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/**
 * Draws the captured window bitmap through a colour matrix.
 *
 * No GL here. The MediaProjection route needed an EGL pipeline because frames arrived
 * on a Surface; window screenshots arrive as Bitmaps, and a ColorMatrixColorFilter
 * does the inversion on the normal draw path — which also sidesteps the EGL alpha
 * config that made the first overlay composite semi-transparent.
 */
public class OverlayView extends View {

    public static final int MODE_PASSTHROUGH = 0;
    public static final int MODE_RGB_INVERT = 1;
    public static final int MODE_LUMA_INVERT = 2;
    public static final int MODE_SHAPED = 3;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap frame;
    private int mode = MODE_LUMA_INVERT;
    private float gamma = 1f, black = 0f, white = 1f;
    private final Rect dst = new Rect();

    public OverlayView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        applyFilter();
    }

    public void setFrame(Bitmap b) {
        Bitmap old = frame;
        frame = b;
        if (old != null && old != b) old.recycle();
        postInvalidate();
    }

    public void setMode(int m) { mode = m; applyFilter(); postInvalidate(); }

    public void setShaping(float g, float bl, float wh) {
        gamma = g; black = bl; white = wh;
        applyFilter(); postInvalidate();
    }

    public int getMode() { return mode; }

    private void applyFilter() {
        ColorMatrix cm;
        switch (mode) {
            case MODE_PASSTHROUGH:
                cm = new ColorMatrix();
                break;
            case MODE_RGB_INVERT:
                cm = new ColorMatrix(new float[]{
                        -1, 0, 0, 0, 255,
                        0, -1, 0, 0, 255,
                        0, 0, -1, 0, 255,
                        0, 0, 0, 1, 0});
                break;
            case MODE_SHAPED: {
                // luminance -> invert, then a linear black/white-point stretch.
                float s = 1f / Math.max(white - black, 0.02f);
                float off = 255f * (1f - black * s);
                cm = new ColorMatrix(new float[]{
                        -0.299f * s, -0.587f * s, -0.114f * s, 0, off,
                        -0.299f * s, -0.587f * s, -0.114f * s, 0, off,
                        -0.299f * s, -0.587f * s, -0.114f * s, 0, off,
                        0, 0, 0, 1, 0});
                break;
            }
            case MODE_LUMA_INVERT:
            default:
                cm = new ColorMatrix(new float[]{
                        -0.299f, -0.587f, -0.114f, 0, 255,
                        -0.299f, -0.587f, -0.114f, 0, 255,
                        -0.299f, -0.587f, -0.114f, 0, 255,
                        0, 0, 0, 1, 0});
                break;
        }
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
    }

    /** Average colour of the centre of the current frame, before and after the filter. */
    public String probe() {
        if (frame == null || frame.isRecycled()) return "frame=none";
        int w = frame.getWidth(), h = frame.getHeight();
        StringBuilder sb = new StringBuilder("frame=" + w + "x" + h + " mode=" + mode + " luma@");
        float[] fx = {0.15f, 0.35f, 0.5f, 0.65f, 0.85f};
        float[] fy = {0.2f, 0.4f, 0.5f, 0.6f, 0.8f};
        for (int i = 0; i < fx.length; i++) {
            int px = frame.getPixel((int)(w * fx[i]), (int)(h * fy[i]));
            int lum = (Color.red(px) * 299 + Color.green(px) * 587 + Color.blue(px) * 114) / 1000;
            sb.append(" (").append((int)(fx[i]*100)).append("%,")
              .append((int)(fy[i]*100)).append("%)=").append(lum);
        }
        return sb.toString();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (frame == null || frame.isRecycled()) {
            canvas.drawColor(Color.BLACK);
            return;
        }
        dst.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(frame, null, dst, paint);
    }
}
