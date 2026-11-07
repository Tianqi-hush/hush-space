package com.hush.space.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 正方形取景框：支持双指缩放、单指拖动、双击复位。
 *
 * <p>裁剪逻辑在 {@link #crop()} 中完成：把 {@link Matrix} 累积的变换重新作用到原图，
 * 以取景框对应的源区域绘制到目标 512×512 位图上，保证所见即所得。
 */
public class CropImageView extends View {

    public interface OnTransformListener {
        void onTransformChanged();
    }

    private static final float MAX_SCALE = 6f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF frame = new RectF();
    private final float[] values = new float[9];

    private Bitmap bitmap;
    private float minScale = 1f;
    private float currentScale = 1f;
    private float baseScale = 1f;
    private OnTransformListener listener;

    private float lastX;
    private float lastY;
    private float downX;
    private float downY;
    private float lastSpan;
    private boolean dragging;

    public CropImageView(Context context) {
        this(context, null);
    }

    public CropImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0x66FFFFFF);
        borderPaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 1f);
    }

    public void setOnTransformListener(OnTransformListener l) {
        this.listener = l;
    }

    public void setImage(Drawable drawable) {
        Bitmap bmp = null;
        if (drawable instanceof BitmapDrawable) {
            bmp = ((BitmapDrawable) drawable).getBitmap();
        }
        if (bmp == null && drawable != null && drawable.getIntrinsicWidth() > 0) {
            int w = Math.min(drawable.getIntrinsicWidth(), 2048);
            int h = Math.min(drawable.getIntrinsicHeight(), 2048);
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            drawable.setBounds(0, 0, w, h);
            drawable.draw(c);
        }
        setImage(bmp);
    }

    public void setImage(Bitmap bmp) {
        this.bitmap = bmp;
        requestLayout();
        invalidate();
    }

    public boolean hasImage() {
        return bitmap != null && !bitmap.isRecycled();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float size = Math.min(w, h);
        float left = (w - size) / 2f;
        float top = (h - size) / 2f;
        frame.set(left, top, left + size, top + size);
        resetTransform();
    }

    private void resetTransform() {
        if (!hasImage() || frame.width() <= 0) return;
        matrix.reset();
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        // 先按短边铺满取景框（cover 效果）
        baseScale = Math.max(frame.width() / bw, frame.height() / bh);
        minScale = baseScale;
        currentScale = baseScale;
        matrix.postScale(baseScale, baseScale);
        float dx = frame.centerX() - (bw * baseScale) / 2f;
        float dy = frame.centerY() - (bh * baseScale) / 2f;
        matrix.postTranslate(dx, dy);
        clamp();
        invalidate();
        notifyChanged();
    }

    /** 限制缩放范围并保证取景框内始终有图。 */
    private void clamp() {
        if (!hasImage()) return;
        matrix.getValues(values);
        float scale = values[Matrix.MSCALE_X];
        float limitMax = baseScale * MAX_SCALE;
        if (scale < minScale) {
            float ratio = minScale / scale;
            matrix.postScale(ratio, ratio, frame.centerX(), frame.centerY());
        } else if (scale > limitMax) {
            float ratio = limitMax / scale;
            matrix.postScale(ratio, ratio, frame.centerX(), frame.centerY());
        }
        matrix.getValues(values);
        float tx = values[Matrix.MTRANS_X];
        float ty = values[Matrix.MTRANS_Y];
        float sw = bitmap.getWidth() * values[Matrix.MSCALE_X];
        float sh = bitmap.getHeight() * values[Matrix.MSCALE_Y];

        float maxTx = frame.left;
        float minTx = frame.right - sw;
        float maxTy = frame.top;
        float minTy = frame.bottom - sh;

        float fixX = tx > maxTx ? maxTx - tx : (tx < minTx ? minTx - tx : 0);
        float fixY = ty > maxTy ? maxTy - ty : (ty < minTy ? minTy - ty : 0);
        if (fixX != 0 || fixY != 0) {
            matrix.postTranslate(fixX, fixY);
        }
        currentScale = values[Matrix.MSCALE_X];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasImage()) return;
        canvas.save();
        canvas.clipRect(frame);
        canvas.drawBitmap(bitmap, matrix, paint);
        canvas.restore();
        canvas.drawRect(frame, borderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!hasImage()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                downX = lastX;
                downY = lastY;
                dragging = true;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                lastSpan = spacing(event);
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() >= 2) {
                    float span = spacing(event);
                    if (lastSpan > 0) {
                        float ratio = span / lastSpan;
                        matrix.postScale(ratio, ratio, frame.centerX(), frame.centerY());
                        clamp();
                        invalidate();
                        notifyChanged();
                    }
                    lastSpan = span;
                } else if (dragging) {
                    float x = event.getX();
                    float y = event.getY();
                    matrix.postTranslate(x - lastX, y - lastY);
                    lastX = x;
                    lastY = y;
                    clamp();
                    invalidate();
                    notifyChanged();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                lastSpan = 0;
                if (event.getPointerCount() > 2) break;
                // 抬起一根手指后重置基准，避免跳动
                lastX = event.getX(0);
                lastY = event.getY(0);
                break;
            case MotionEvent.ACTION_UP:
                dragging = false;
                if (Math.abs(event.getX() - downX) < 8 && Math.abs(event.getY() - downY) < 8) {
                    performClick();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        // 双击在 1x 与 2x 之间切换
        float target = currentScale > baseScale * 1.2f ? baseScale : baseScale * 2f;
        float ratio = target / currentScale;
        matrix.postScale(ratio, ratio, frame.centerX(), frame.centerY());
        clamp();
        invalidate();
        notifyChanged();
        return true;
    }

    private float spacing(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0;
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void notifyChanged() {
        if (listener != null) listener.onTransformChanged();
    }

    /** 复位到初始居中状态。 */
    public void reset() {
        resetTransform();
    }

    /**
     * 生成裁剪结果。
     *
     * @param output 输出边长（px）
     */
    public Bitmap crop(int output) {
        if (!hasImage()) return null;
        matrix.getValues(values);
        float scale = values[Matrix.MSCALE_X];
        float tx = values[Matrix.MTRANS_X];
        float ty = values[Matrix.MTRANS_Y];

        // 取景框左上角在原图中的坐标
        float sx = (frame.left - tx) / scale;
        float sy = (frame.top - ty) / scale;
        float sw = frame.width() / scale;
        float sh = frame.height() / scale;

        // 越界保护
        sx = Math.max(0, Math.min(sx, bitmap.getWidth() - 1));
        sy = Math.max(0, Math.min(sy, bitmap.getHeight() - 1));
        sw = Math.min(sw, bitmap.getWidth() - sx);
        sh = Math.min(sh, bitmap.getHeight() - sy);

        Bitmap out = Bitmap.createBitmap(output, output, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        Matrix m = new Matrix();
        m.setScale(output / sw, output / sh);
        m.postTranslate(-sx * output / sw, -sy * output / sh);
        p.getShader().setLocalMatrix(m);
        canvas.drawRect(0, 0, output, output, p);
        return out;
    }
}
