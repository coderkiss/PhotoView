/**
 * @author dawson dong
 */

package com.kisstools.android.view;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.OverScroller;

public class PhotoView extends ImageView {

    public static final String TAG = "PhotoView";

    private static final boolean DEFAULT_FIT_SPACE = true;

    private static enum State {
        NONE, DRAG, SCALE, FLING, TRANSLATE
    }

    ;

    public static interface SimpleDragListener {

        public void onDragBegin();

        public void onDrag(float deltaX, float deltaY);

        public void onDragEnd();
    }

    private static final float MIN_FACTOR = 0.6f;

    private static final float FIT_FACTOR = 1.0f;

    private static final float ZOOM_FACTOR = 2.0f;

    private static final float MAX_FACTOR = 3.0f;

    private DragDetector dragDetector;

    private ScaleGestureDetector scaleDetector;

    private GestureDetector gestureDetector;

    private GridDetector gridDetector;

    private Matrix matrix;

    private float[] matrixValues;

    private State state;

    private float scaleFactor;

    private float minFactor;

    private float fitScale;

    private float normalFactor;

    private float zoomFactor;

    private float maxFactor;

    private int drawableWidth;

    private int drawableHeight;

    private RectF photoRect;

    private int viewWidth;

    private int viewHeight;

    private boolean photoValid;

    private boolean fitSpace;

    private FlingRunnable flingRunnable;

    private boolean enableScale;

    private boolean enableCrop;

    private boolean cropSquare;

    private OnLongClickListener onLongClickListener;

    private OnClickListener onClickListener;

    private boolean canScale;

    public PhotoView(Context context) {
        this(context, null);
    }

    public PhotoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private void initPhotoView() {
        this.setScaleType(ScaleType.MATRIX);
        this.scaleFactor = 1;
        this.fitSpace = DEFAULT_FIT_SPACE;
        this.photoValid = false;
        this.matrix = new Matrix();
        this.state = State.NONE;
        this.photoRect = new RectF();
        this.matrixValues = new float[9];
        Context context = getContext();
        dragDetector = new DragDetector(new DragListener());
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());
        this.setEnableCrop(false);
        this.setClickable(true);
        this.setEnableScale(true);
    }

    public void setFitSpace(boolean fitSpace) {
        this.fitSpace = fitSpace;
    }

    public void setEnableScale(boolean enableScale) {
        Log.d(TAG, "setEnableScale " + enableScale);
        this.enableScale = enableScale;
    }

    public void setCropSquare(boolean cropSquare) {
        this.cropSquare = cropSquare;
    }

    public boolean getEnableCrop() {
        return this.enableCrop;
    }

    public void setEnableCrop(boolean enableCrop) {
        Log.d(TAG, "setEnableCrop " + enableCrop);
        this.enableCrop = enableCrop;
        if (enableCrop) {
            gridDetector = new GridDetector();
            gridDetector.initRect();
        } else {
            gridDetector = null;
        }
        invalidate();
    }

    public Bitmap applyCrop() {
        if (matrix == null || !photoValid || !enableCrop) {
            return null;
        }

        Drawable drawable = getDrawable();
        if (!(drawable instanceof BitmapDrawable)) {
            return null;
        }

        Bitmap origin = ((BitmapDrawable) drawable).getBitmap();
        RectF rect = gridDetector.getRect();
        int width = (int) (rect.right - rect.left);
        int height = (int) (rect.bottom - rect.top);

        Bitmap bitmap = Bitmap.createBitmap(width, height,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(-rect.left, -rect.top);
        canvas.drawBitmap(origin, matrix, null);
        return bitmap;
    }

    @Override
    public void setImageResource(int resId) {
        super.setImageResource(resId);
        initMatrix();
    }

    @Override
    public void setImageBitmap(Bitmap bitmap) {
        super.setImageBitmap(bitmap);
        initMatrix();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        initMatrix();
    }

    @Override
    public void setImageURI(Uri uri) {
        super.setImageURI(uri);
        initMatrix();
    }

    private void applyMatrix() {
        Log.d(TAG, "apply matrix " + matrix.toString());
        setImageMatrix(matrix);
    }

    private void setState(State state) {
        Log.d(TAG, "setState " + state);
        this.state = state;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Log.d(TAG, "onDraw " + canvas);
        if (enableCrop) {
            gridDetector.draw(canvas);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        boolean handled = false;
        if (enableCrop) {
            handled = gridDetector.onTouchEvent(event);
        }

        int action = event.getAction() & MotionEvent.ACTION_MASK;
        Log.d(TAG, "dispatchTouchEvent " + action);

        if (action == MotionEvent.ACTION_UP) {
            canScale = false;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            canScale = true;
        }

        if (!handled && enableScale) {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            dragDetector.onTouchEvent(event);
        }

        return super.dispatchTouchEvent(event);
    }

    private class GridDetector {

        private static final int COVER_COLOR = 0x66000000;

        private static final int BORDER_COLOR = Color.WHITE;

        private static final int DRAG_NONE = 0;

        private static final int DRAG_LEFT = 1;

        private static final int DRAG_TOP = 2;

        private static final int DRAG_RIGHT = 4;

        private static final int DRAG_BOTTOM = 8;

        private static final int DRAG_CENTER = 16;

        private static final int STROKE_SIZE = 4;

        private static final int EDGE_SIZE = 30;

        private PointF lastPoint;

        private Paint rectPaint;

        private int dragType;

        private RectF rectF;

        private RectF innerRectF;

        private int borderSize;

        private int edgeSize;

        private float minSize;

        public GridDetector() {
            this.rectF = new RectF();
            this.innerRectF = new RectF();
            this.lastPoint = new PointF();
            this.rectPaint = new Paint();
            edgeSize = dp2px(EDGE_SIZE);
            minSize = 4f * edgeSize;
            rectPaint.setColor(BORDER_COLOR);
            rectPaint.setStyle(Style.STROKE);
        }

        public RectF getRect() {
            return rectF;
        }

        public void draw(Canvas canvas) {
            borderSize = dp2px(STROKE_SIZE);
            int centerSize = dp2px(6);
            int sideSize = centerSize * 2;
            int size = borderSize / 2;
            int lineSize = borderSize / 3;
            rectPaint.setStrokeWidth(lineSize);
            innerRectF.set(rectF.left + size, rectF.top + size,
                    rectF.right - size, rectF.bottom - size);
            Log.d(TAG, "draw " + innerRectF.toString());
            canvas.drawLine(rectF.centerX(), rectF.top, rectF.centerX(), rectF.bottom, rectPaint);
            canvas.drawLine(rectF.left, rectF.centerY(), rectF.right, rectF.centerY(), rectPaint);

            rectPaint.setStyle(Style.FILL);
            Path path = new Path();
            path.moveTo(rectF.centerX() - centerSize, rectF.centerY());
            path.lineTo(rectF.centerX(), rectF.centerY() - centerSize);
            path.lineTo(rectF.centerX() + centerSize, rectF.centerY());
            path.lineTo(rectF.centerX(), rectF.centerY() + centerSize);
            path.close();
            canvas.drawPath(path, rectPaint);
            path.reset();
            path.moveTo(rectF.left, rectF.top);
            path.lineTo(rectF.left + sideSize, rectF.top);
            path.lineTo(rectF.left, rectF.top + sideSize);
            path.close();
            canvas.drawPath(path, rectPaint);
            path.reset();
            path.moveTo(rectF.right, rectF.top);
            path.lineTo(rectF.right - sideSize, rectF.top);
            path.lineTo(rectF.right, rectF.top + sideSize);
            path.close();
            canvas.drawPath(path, rectPaint);
            path.reset();
            path.moveTo(rectF.left, rectF.bottom);
            path.lineTo(rectF.left + sideSize, rectF.bottom);
            path.lineTo(rectF.left, rectF.bottom - sideSize);
            path.close();
            canvas.drawPath(path, rectPaint);
            path.reset();
            path.moveTo(rectF.right, rectF.bottom);
            path.lineTo(rectF.right - sideSize, rectF.bottom);
            path.lineTo(rectF.right, rectF.bottom - sideSize);
            path.close();
            canvas.drawPath(path, rectPaint);

            canvas.clipRect(innerRectF, Region.Op.XOR);
            canvas.drawColor(COVER_COLOR);
            rectPaint.setStrokeWidth(borderSize);
            rectPaint.setStyle(Style.STROKE);
            canvas.drawRect(innerRectF, rectPaint);
        }

        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getAction() & MotionEvent.ACTION_MASK;
            if (action == MotionEvent.ACTION_DOWN) {
                dragType = getDrag(event);
                lastPoint.set(event.getX(), event.getY());
            } else if (action == MotionEvent.ACTION_POINTER_DOWN
                    || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_POINTER_UP) {
                dragType = DRAG_NONE;
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (dragType != DRAG_NONE) {
                    dragRect(event);
                    invalidate();
                }
            }
            return dragType != DRAG_NONE;
        }

        public void initRect() {
            float width = viewWidth > photoRect.width() ? photoRect.width() : viewWidth;
            float height = viewHeight > photoRect.height() ? photoRect.height() : viewHeight;

            float rectSize = Math.min(width, height) * 2 / 3;
            rectF.set((viewWidth - rectSize) / 2, (viewHeight - rectSize) / 2,
                    (viewWidth + rectSize) / 2, (viewHeight + rectSize) / 2);
        }

        private int getDrag(MotionEvent event) {
            int type = DRAG_NONE;

            float px = event.getX();
            float py = event.getY();

            if ((py >= (rectF.top - edgeSize) && py <= (rectF.bottom + edgeSize))) {
                if (Math.abs(px - rectF.left) < edgeSize) {
                    type |= DRAG_LEFT;
                }
                if (Math.abs(px - rectF.right) < edgeSize) {
                    type |= DRAG_RIGHT;
                }
            }
            if ((px >= (rectF.left - edgeSize) && (px <= (rectF.right + edgeSize)))) {
                if (Math.abs(py - rectF.top) < edgeSize) {
                    type |= DRAG_TOP;
                }
                if (Math.abs(py - rectF.bottom) < edgeSize) {
                    type |= DRAG_BOTTOM;
                }
            }

            if (Math.abs(px - (rectF.left + rectF.right) / 2) < edgeSize
                    && Math.abs(py - (rectF.top + rectF.bottom) / 2) < edgeSize) {
                type = DRAG_CENTER;
            }

            if (cropSquare && isPowerOfTwo(type)) {
                type = DRAG_CENTER;
            }

            Log.d(TAG, "getDrag type " + type);
            return type;
        }

        private void dragRect(MotionEvent event) {
            float deltaX = event.getX() - lastPoint.x;
            float deltaY = event.getY() - lastPoint.y;
            lastPoint.set(event.getX(), event.getY());

            float left = photoRect.left > 0 ? photoRect.left : 0;
            float top = photoRect.top > 0 ? photoRect.top : 0;
            float right = photoRect.right > viewWidth ? viewWidth : photoRect.right;
            float bottom = photoRect.bottom > viewHeight ? viewHeight : photoRect.bottom;

            if ((dragType & DRAG_CENTER) != 0) {
                RectF temp = new RectF(rectF);
                temp.offset(deltaX, deltaY);
                if (temp.top >= top && temp.left >= left && temp.right <= right && temp.bottom <= bottom) {
                    rectF.offset(deltaX, deltaY);
                }
                return;
            }

            if (cropSquare && dragType != DRAG_CENTER) {
                float leftSpace = rectF.left - left;
                float topSpace = rectF.top - top;
                float rightSpace = right - rectF.right;
                float bottomSpace = bottom - rectF.bottom;
                if (deltaX > 0) {
                    deltaX = deltaX < rightSpace ? deltaX : rightSpace;
                } else {
                    deltaX = -deltaX < leftSpace ? deltaX : -leftSpace;
                }

                if (deltaY > 0) {
                    deltaY = deltaY < bottomSpace ? deltaY : bottomSpace;
                } else {
                    deltaY = -deltaY < topSpace ? deltaY : -topSpace;
                }

                float delta = Math.min(Math.abs(deltaX), Math.abs(deltaY));
                if (delta == 0) {
                    return;
                }
                deltaX = delta * (deltaX / Math.abs(deltaX));
                deltaY = delta * (deltaY / Math.abs(deltaY));
                Log.d(TAG, "cropSquare " + deltaX + " " + deltaY);
            }

            if ((dragType & DRAG_LEFT) != 0) {
                deltaX = deltaX + rectF.left < left ? left - rectF.left : deltaX;
                deltaX = rectF.left + deltaX + minSize > rectF.right ? rectF.right - rectF.left - minSize : deltaX;
                rectF.left += deltaX;
            }
            if ((dragType & DRAG_TOP) != 0) {
                deltaY = deltaY + rectF.top < top ? top - rectF.top : deltaY;
                deltaY = rectF.top + deltaY + minSize > rectF.bottom ? rectF.bottom - rectF.top - minSize : deltaY;
                rectF.top += deltaY;
            }
            if ((dragType & DRAG_RIGHT) != 0) {
                deltaX = rectF.right + deltaX > right ? right - rectF.right : deltaX;
                deltaX = (rectF.right + deltaX - minSize) < rectF.left ? (minSize - rectF.right + rectF.left) : deltaX;
                rectF.right += deltaX;
            }
            if ((dragType & DRAG_BOTTOM) != 0) {
                deltaY = rectF.bottom + deltaY > bottom ? bottom - rectF.bottom : deltaY;
                deltaY = (rectF.bottom + deltaY - minSize) < rectF.top ? (minSize - rectF.bottom + rectF.top)
                        : deltaY;
                rectF.bottom += deltaY;
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right,
            int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!changed) {
            return;
        }

        viewWidth = right - left - getPaddingLeft() - getPaddingRight();
        viewHeight = bottom - top - getPaddingTop() - getPaddingBottom();

        Log.d(TAG, "onLayout viewWidth " + viewWidth + " viewHeight " + viewHeight);
        initMatrix();
    }

    private class DragListener implements SimpleDragListener {

        @Override
        public void onDragBegin() {
            Log.d(TAG, "onDragBegin");
            setState(State.DRAG);
        }

        @Override
        public void onDrag(float deltaX, float deltaY) {
            Log.d(TAG, "onDrag deltaX " + deltaX + " deltaY " + deltaY);
            if (state != State.DRAG) {
                Log.d(TAG, "cancel drag for current state " + state);
                return;
            }
            float left = photoRect.left;
            float top = photoRect.top;
            float bottom = photoRect.bottom;
            float right = photoRect.right;

            deltaX = checkTranslage(deltaX, viewWidth, left, right);
            deltaY = checkTranslage(deltaY, viewHeight, top, bottom);
            postTranslate(deltaX, deltaY);
        }

        @Override
        public void onDragEnd() {
            Log.d(TAG, "onDragEnd");
            if (state == State.DRAG) {
                setState(State.NONE);
            }
        }

    }

    private float checkTranslage(float delta, float viewSize, float min, float max) {
        Log.d(TAG, "checkTranslage delta " + delta + " viewSize " + viewSize + " min " + min
                + " max " + max);
        if ((max - min) <= viewSize || delta == 0) {
            return 0;
        }

        if (delta > 0 && (min + delta) > 0) {
            return 0 - min > delta ? delta : 0 - min;
        } else if (delta < 0 && (max + delta) < viewSize) {
            return viewSize - max < delta ? delta : viewSize - max;
        } else {
            // delta < 0 && (max + delta) >= viewSize
            // delta > 0 && (min + delta) <= 0
            return delta;
        }
    }

    private class GestureListener extends
            GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            Log.d(TAG, "onSingleTapConfirmed");
            if (onClickListener != null) {
                onClickListener.onClick(PhotoView.this);
            }
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            Log.d(TAG, "onLongPress");
            if (onLongClickListener != null) {
                onLongClickListener.onLongClick(PhotoView.this);
            }
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
                float velocityY) {
            Log.d(TAG, "onFling vx " + velocityX + " vy " + velocityY + " state " + state);
            if (state == State.NONE || state == State.FLING || state == State.DRAG) {
                if (flingRunnable != null) {
                    flingRunnable.cancelFling();
                }
                flingRunnable = new FlingRunnable((int) velocityX, (int) velocityY);
                postAnimation(flingRunnable);
            } else {
                Log.d(TAG, "cancel fling for current state " + state);
            }
            return super.onFling(e1, e2, velocityX, velocityY);
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (state != State.NONE) {
                return false;
            }
            float targetScale = (scaleFactor == normalFactor) ? zoomFactor
                    : normalFactor;
            Log.d(TAG, "onDoubleTap targetScale " + targetScale);
            PointF point = new PointF(e.getX(), e.getY());
            point = calScaleFocus(targetScale, point);
            ScaleRunnable scaleRunnable = new ScaleRunnable(targetScale, point);
            postAnimation(scaleRunnable);
            return true;
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            Log.d(TAG, "onDoubleTapEvent");
            return false;
        }
    }

    private class ScaleListener extends SimpleOnScaleGestureListener {

        private boolean overZoom;

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            Log.d(TAG, "onScaleBegin canScale " + canScale);
            if (!canScale) {
                return true;
            }
            overZoom = scaleFactor >= zoomFactor;
            setState(State.SCALE);
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (state != State.NONE && state != State.SCALE) {
                Log.d(TAG, "cancel scale for current state " + state);
                return true;
            }
            float factor = detector.getScaleFactor();
            float px = detector.getFocusX();
            float py = detector.getFocusY();
            float nextScale = scaleFactor * factor;
            if (nextScale > maxFactor) {
                factor = maxFactor / scaleFactor;
            } else if (!overZoom && nextScale > zoomFactor) {
                factor = zoomFactor / scaleFactor;
            } else if (nextScale < minFactor) {
                factor = minFactor / scaleFactor;
            }
            Log.d(TAG, "onScale current " + scaleFactor + " factor " + factor + " px " + px + " py " + py);
            postScale(factor, px, py);
            applyMatrix();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            super.onScaleEnd(detector);
            Log.d(TAG, "onScaleEnd");
            overZoom = false;
            if (state != State.SCALE) {
                return;
            }

            setState(State.NONE);

            float px = detector.getFocusX();
            float py = detector.getFocusY();
            PointF pointF = new PointF(px, py);
            if (adjustToScale(pointF)) {
                Log.d(TAG, "adjustToScale");
            } else if (adjustToBounds()) {
                Log.d(TAG, "adjustToBounds");
            }
        }
    }

    private boolean adjustToBounds() {
        float left = photoRect.left;
        float top = photoRect.top;
        float right = photoRect.right;
        float bottom = photoRect.bottom;

        float px = 0;
        float py = 0;

        float horSpace = (viewWidth - photoRect.width()) / 2;
        float verSpace = (viewHeight - photoRect.height()) / 2;

        if (left > 0) {
            px = horSpace > 0 ? horSpace - left : -left;
        }
        if (top > 0) {
            py = verSpace > 0 ? verSpace - top : -top;
        }
        if (right < viewWidth) {
            px = horSpace > 0 ? viewWidth - right - horSpace : viewWidth - right;
        }
        if (bottom < viewHeight) {
            py = verSpace > 0 ? viewHeight - bottom - verSpace : viewHeight - bottom;
        }
        boolean translate = px != 0 || py != 0;
        if (!translate) {
            return false;
        }

        Log.d(TAG, "adjustToBounds px " + px + " py " + py);
        TransRunnable translateRunnbale = new TransRunnable(px, py);
        post(translateRunnbale);
        return true;
    }

    private boolean adjustToScale(PointF point) {
        Log.d(TAG, "adjustToScale " + scaleFactor);
        float targetScale = -1;
        if (scaleFactor > maxFactor) {
            targetScale = maxFactor;
        } else if (scaleFactor > zoomFactor) {
            targetScale = zoomFactor;
        } else if (scaleFactor < normalFactor) {
            targetScale = normalFactor;
        }

        if (targetScale == -1) {
            return false;
        }

        point = calScaleFocus(targetScale, point);
        ScaleRunnable scaleRunnable = new ScaleRunnable(targetScale, point);
        postAnimation(scaleRunnable);
        return true;
    }

    /**
     * get the focus point to scale the image
     */
    private PointF calScaleFocus(float targetScale, PointF point) {
        // set default focus point
        if (point == null) {
            point = new PointF(viewWidth / 2, viewHeight / 2);
        }

        Matrix preMatrix = new Matrix(matrix);
        float factor = targetScale / scaleFactor;
        preMatrix.postScale(factor, factor, point.x, point.y);
        float[] values = new float[9];
        preMatrix.getValues(values);
        float left = values[Matrix.MTRANS_X];
        float top = values[Matrix.MTRANS_Y];
        float right = left + drawableWidth * targetScale;
        float bottom = top + drawableHeight * targetScale;
        if (left <= 0 && top <= 0 && right >= viewWidth && bottom >= viewHeight) {
            return point;
        }

        float targetWidth = drawableWidth * targetScale;
        float targetHeight = drawableHeight * targetScale;
        float drawableX = photoRect.left;
        float drawableY = photoRect.top;
        float targetLeft = left < 0 ? left : 0;
        float targetTop = top < 0 ? top : 0;

        // after scale, the photo will display within the view
        if (targetScale <= fitScale) {
            targetLeft = (viewWidth - targetWidth) / 2;
            targetTop = (viewHeight - targetHeight) / 2;
        } else {
            if (targetWidth > viewWidth) {
                targetLeft = right < viewWidth ? viewWidth - targetWidth : targetLeft;
            } else {
                targetLeft = (viewWidth - targetWidth) / 2;
            }
            if (targetHeight > viewHeight) {
                targetTop = bottom < viewHeight ? targetTop = viewHeight - targetHeight : targetTop;
            } else {
                targetTop = (viewHeight - targetHeight) / 2;
            }
        }

        float px = (drawableX * targetWidth - targetLeft * photoRect.width())
                / (targetWidth - photoRect.width());
        float py = (drawableY * targetHeight - targetTop * photoRect.height())
                / (targetHeight - photoRect.height());
        Log.d(TAG, "scale focus px " + px + " py " + py);
        point = new PointF(px, py);
        return point;
    }

    @TargetApi(VERSION_CODES.JELLY_BEAN)
    private void postAnimation(Runnable runnable) {
        if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) {
            postOnAnimation(runnable);
        } else {
            postDelayed(runnable, 1000 / 60);
        }
    }

    private class TransRunnable implements Runnable {

        private static final float TRANSLATE_DURATION = 300;

        private float px, py;

        private float lastInterpolate;

        private PhotoInter interpolator;

        public TransRunnable(float px, float py) {
            Log.d(TAG, "translate runnable px " + px + " py " + py);
            this.interpolator = new PhotoInter(new LinearInterpolator(), TRANSLATE_DURATION);
            this.px = px;
            this.py = py;
            this.lastInterpolate = 0;
            setState(State.TRANSLATE);
        }

        @Override
        public void run() {
            if (state != State.NONE && state != State.TRANSLATE) {
                Log.d(TAG, "cancel translate for current state " + state);
                return;
            }

            final float interpolate = interpolator.calInterpolate();
            Log.d(TAG, "interpolate " + interpolate);
            final float delta = interpolate - lastInterpolate;
            lastInterpolate = interpolate;
            float deltaX = px * delta;
            float deltaY = py * delta;
            Log.d(TAG, "deltaX " + deltaX + " deltaY " + deltaY);
            postTranslate(deltaX, deltaY);

            if (interpolate < 1f) {
                postAnimation(this);
            } else {
                setState(State.NONE);
            }
        }

    }

    private class PhotoInter {

        private Interpolator inter;

        private long time;

        private float duration;

        public PhotoInter(Interpolator inter, float duration) {
            this.inter = inter;
            this.time = System.currentTimeMillis();
            this.duration = duration;
        }

        public float calInterpolate() {
            long currentTime = System.currentTimeMillis();
            float elapsed = (currentTime - time) / duration;
            elapsed = Math.min(1f, elapsed);
            return inter.getInterpolation(elapsed);
        }

    }

    private class ScaleRunnable implements Runnable {

        private static final float SCALE_DURATION = 300;

        private float startScale, targetScale;

        private PhotoInter interpolator;

        private PointF foucsPoint;

        ScaleRunnable(float targetScale, PointF focusPoint) {
            setState(State.SCALE);
            this.interpolator = new PhotoInter(new LinearInterpolator(), SCALE_DURATION);
            this.startScale = scaleFactor;
            this.targetScale = targetScale;
            this.foucsPoint = focusPoint;
        }

        @Override
        public void run() {
            float interpolate = interpolator.calInterpolate();
            float deltaScale = calculateDeltaScale(interpolate);
            Log.d(TAG, "deltaScale " + deltaScale + " interpolate " + interpolate);
            postScale(deltaScale, foucsPoint.x, foucsPoint.y);
            applyMatrix();

            if (interpolate < 1f) {
                postAnimation(this);
            } else {
                setState(State.NONE);
            }
        }

        private float calculateDeltaScale(float interpolate) {
            float deltaScale = targetScale - startScale;
            float nextScale = startScale + interpolate * deltaScale;
            return nextScale / scaleFactor;
        }

    }

    private void initMatrix() {
        if (matrix == null) {
            initPhotoView();
        }

        Drawable drawable = getDrawable();
        if (drawable == null) {
            photoValid = false;
            return;
        }

        matrix.reset();
        scaleFactor = 1;
        state = State.NONE;

        drawableWidth = drawable.getIntrinsicWidth();
        drawableHeight = drawable.getIntrinsicHeight();
        Log.d(TAG, "drawableWidth " + drawableWidth + " drawableHeight " + drawableHeight);
        if (drawableWidth <= 0 || drawableWidth <= 0) {
            photoValid = false;
            return;
        }

        photoValid = true;
        viewWidth = getMeasuredWidth();
        viewHeight = getMeasuredHeight();
        Log.d(TAG, "viewWidth " + viewWidth + " viewHeight " + viewHeight);
        if (viewWidth == 0 || viewHeight == 0) {
            return;
        }

        // update scale to fit view size
        float scaleX = (float) viewWidth / drawableWidth;
        float scaleY = (float) viewHeight / drawableHeight;
        fitScale = scaleX > scaleY ? scaleY : scaleX;
        if (fitSpace || fitScale < 1) {
            normalFactor = fitScale * FIT_FACTOR;
        } else {
            normalFactor = 1;
        }
        minFactor = normalFactor * MIN_FACTOR;
        zoomFactor = normalFactor * ZOOM_FACTOR;
        maxFactor = normalFactor * MAX_FACTOR;

        Log.d(TAG, "initMatrix minScale " + minFactor + " fitScale " + normalFactor + " maxScale " + maxFactor);
        postScale(normalFactor);

        // update translate to fit view center
        float px = (viewWidth - photoRect.width()) / 2;
        float py = (viewHeight - photoRect.height()) / 2;
        Log.d(TAG, "initMatrix px " + px + " py " + py);
        postTranslate(px, py);

        if (enableCrop) {
            gridDetector.initRect();
        }
    }

    private void postTranslate(float px, float py) {
        Log.d(TAG, "postTranslate px " + px + " py " + py);
        if (!photoValid) {
            Log.d(TAG, "invalid photo content");
            return;
        }
        matrix.postTranslate(px, py);
        updatePhotoRect();
        applyMatrix();
    }

    private void postScale(float scale) {
        Log.d(TAG, "postScale scale " + scale);
        if (!photoValid) {
            Log.d(TAG, "invalid photo content");
            return;
        }

        scaleFactor *= scale;
        matrix.postScale(scale, scale);
        updatePhotoRect();
        applyMatrix();
    }

    private void postScale(float scale, float px, float py) {
        Log.d(TAG, "postScale scale " + scale + " px " + px + " py " + py);
        if (!photoValid) {
            Log.d(TAG, "invalid photo content");
            return;
        }
        scaleFactor *= scale;
        matrix.postScale(scale, scale, px, py);
        updatePhotoRect();
        applyMatrix();
    }

    public void postRotate(float degrees) {
        Log.d(TAG, "postRotate degrees " + degrees);
        if (!photoValid) {
            Log.d(TAG, "invlaid photo content!");
            return;
        }

        Drawable drawable = getDrawable();
        if (!(drawable instanceof BitmapDrawable)) {
            return;
        }

        Bitmap origin = ((BitmapDrawable) drawable).getBitmap();
        Matrix m = new Matrix();
        m.postRotate(degrees, drawableWidth / 2, drawableHeight / 2);
        Bitmap bitmap = Bitmap.createBitmap(origin, 0, 0, drawableWidth, drawableHeight, m, true);
        setImageBitmap(bitmap);
    }

    private void updatePhotoRect() {
        matrix.getValues(matrixValues);
        float left = matrixValues[Matrix.MTRANS_X];
        float top = matrixValues[Matrix.MTRANS_Y];
        float width = scaleFactor * drawableWidth;
        float height = scaleFactor * drawableHeight;
        photoRect.set(left, top, left + width, top + height);
    }

    private class DragDetector {

        private SimpleDragListener dragListener;

        private float lastX, lastY;

        public DragDetector(SimpleDragListener listener) {
            this.dragListener = listener;
        }

        public boolean onTouchEvent(MotionEvent event) {
            if (dragListener == null) {
                return false;
            }

            if (state != State.NONE && state != State.DRAG) {
                Log.d(TAG, "current state " + state);
                return false;
            }

            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                lastX = event.getX();
                lastY = event.getY();
                dragListener.onDragBegin();
            } else if (action == MotionEvent.ACTION_MOVE) {
                float deltaX = event.getX() - lastX;
                float deltaY = event.getY() - lastY;
                lastX = event.getX();
                lastY = event.getY();
                if (deltaX == 0 && deltaY == 0) {
                    return false;
                }
                dragListener.onDrag(deltaX, deltaY);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                dragListener.onDragEnd();
                lastX = 0;
                lastY = 0;
            }
            return true;
        }
    }

    private class FlingRunnable implements Runnable {

        private OverScroller scroller;

        private int currX, currY;

        FlingRunnable(int velX, int velY) {
            Log.d(TAG, "fling velX " + velX + " velY " + velY);
            setState(State.FLING);
            scroller = new OverScroller(getContext());

            int startX = (int) photoRect.left;
            int startY = (int) photoRect.top;
            int minX, maxX, minY, maxY;

            float photoWidth = photoRect.width();
            float photoHeight = photoRect.height();
            if (photoWidth > viewWidth) {
                maxX = 0;
                minX = viewWidth - (int) photoWidth;
            } else {
                minX = maxX = startX;
            }

            if (photoHeight > viewHeight) {
                maxY = 0;
                minY = viewHeight - (int) photoHeight;
            } else {
                minY = maxY = startY;
            }

            scroller.fling(startX, startY, velX, velY,
                    minX, maxX, minY, maxY, 1, 1);
            currX = startX;
            currY = startY;
        }

        public void cancelFling() {
            Log.d(TAG, "cancelFling");
            if (scroller != null) {
                setState(State.NONE);
                scroller.forceFinished(true);
            }
        }

        @Override
        public void run() {
            if (scroller == null || scroller.isFinished() || !scroller.computeScrollOffset()) {
                if (state == State.FLING) {
                    setState(State.NONE);
                }
                scroller = null;
                return;
            }

            int newX = scroller.getCurrX();
            int newY = scroller.getCurrY();
            int px = newX - currX;
            int py = newY - currY;
            currX = newX;
            currY = newY;
            Log.d(TAG, "fling px " + px + " py " + py);
            postTranslate(px, py);
            postAnimation(this);
        }
    }

    @Override
    public boolean canScrollHorizontally(int direction) {
        if (!photoValid) {
            Log.d(TAG, "canScrollHorizontally " + false);
            return false;
        }

        float horizontalTranslate = photoRect.left;
        float photoWidth = photoRect.width();

        boolean canScroll = true;
        if (photoWidth <= viewWidth) {
            canScroll = false;
        } else if (direction < 0 && horizontalTranslate >= 0) {
            canScroll = false;
        } else if (direction > 0 && horizontalTranslate + photoWidth <= viewWidth) {
            canScroll = false;
        }
        Log.d(TAG, "canScrollHorizontally " + canScroll);
        return canScroll;
    }

    @Override
    public void setOnLongClickListener(OnLongClickListener listener) {
        this.onLongClickListener = listener;
    }

    @Override
    public void setOnClickListener(OnClickListener listener) {
        this.onClickListener = listener;
    }

    private int dp2px(int dip) {
        Resources resources = getContext().getResources();
        int px = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dip, resources.getDisplayMetrics()));
        return px;
    }

    public static boolean isPowerOfTwo(int num) {
        return num > 0 & (num & (num - 1)) == 0;
    }

}
