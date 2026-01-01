package com.example.smartshopping;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class OverlayView extends View {

    private final Paint paint = new Paint();
    private final List<RectF> boxes = new ArrayList<>();

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(Color.GREEN);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6f);
    }

    public void setBoxes(List<RectF> newBoxes, int imgW, int imgH) {
        boxes.clear();

        float scaleX = getWidth() / (float) imgW;
        float scaleY = getHeight() / (float) imgH;

        for (RectF b : newBoxes) {
            boxes.add(new RectF(
                    b.left * scaleX,
                    b.top * scaleY,
                    b.right * scaleX,
                    b.bottom * scaleY
            ));
        }
        invalidate();
    }

    public void clear() {
        boxes.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (RectF b : boxes) {
            canvas.drawRect(b, paint);
        }
    }
}
