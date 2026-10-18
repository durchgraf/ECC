package com.ecc.camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

final class FrameRenderer {
    private FrameRenderer() {}

    static Bitmap renderCropped(Bitmap source, float rotation, boolean mirror) {
        return renderCropped(source, rotation, mirror, source.getWidth(), source.getHeight());
    }

    static Bitmap renderCropped(Bitmap source, float rotation, boolean mirror, int width, int height) {
        width = Math.max(2, width & ~1);
        height = Math.max(2, height & ~1);
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.BLACK);
        canvas.translate(width / 2f, height / 2f);
        canvas.rotate(rotation);
        if (mirror) canvas.scale(-1f, 1f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, null, new RectF(-width / 2f, -height / 2f,
                width / 2f, height / 2f), paint);
        return output;
    }
}
