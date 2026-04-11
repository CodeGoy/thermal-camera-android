package com.breyt.thermalcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.Locale;

/**
 * Custom View that renders thermal camera frames with colormap,
 * temperature markers, crosshairs, and HUD overlay.
 */
public class ThermalView extends View {

    // Rendering
    private Bitmap bitmap;
    private int[] bitmapPixels;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // Colormap
    private int colormapIndex = 0;
    private int[][] currentColormap = Colormaps.TURBO;

    // Thermal data
    private ThermalData thermalData;
    private float contrast = 1.0f;
    private float brightness = 0.0f;

    // Zoom and pan
    private float scaleFactor = 1.0f;
    private float translateX = 0f;
    private float translateY = 0f;
    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 5.0f;

    // Rotation (0, 90, 180, 270 degrees)
    private int rotationDegrees = 0;

    // Mirror mode (horizontal flip for selfie mode)
    private boolean mirrored = false;

    // Gesture detectors
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    // Paints
    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crosshairOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // FPS tracking
    private long lastFrameTime = 0;
    private float fps = 0f;
    private int frameCount = 0;

    // Tap temperature
    private float tapTemp = Float.NaN;
    private int tapX = -1, tapY = -1;
    private long tapTime = 0;

    public ThermalView(Context context) {
        super(context);
        init();
    }

    public ThermalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ThermalView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Initialize bitmap
        bitmap = Bitmap.createBitmap(ThermalData.WIDTH, ThermalData.HEIGHT, Bitmap.Config.ARGB_8888);
        bitmapPixels = new int[ThermalData.WIDTH * ThermalData.HEIGHT];

        // Crosshair paints
        crosshairPaint.setColor(Color.WHITE);
        crosshairPaint.setStrokeWidth(2f);
        crosshairPaint.setStyle(Paint.Style.STROKE);

        crosshairOutlinePaint.setColor(Color.BLACK);
        crosshairOutlinePaint.setStrokeWidth(4f);
        crosshairOutlinePaint.setStyle(Paint.Style.STROKE);

        // Marker paint
        markerPaint.setStyle(Paint.Style.FILL);

        // Text paints
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28f);
        textPaint.setStyle(Paint.Style.FILL);

        textOutlinePaint.setColor(Color.BLACK);
        textOutlinePaint.setTextSize(28f);
        textOutlinePaint.setStyle(Paint.Style.STROKE);
        textOutlinePaint.setStrokeWidth(3f);

        // HUD paints
        hudBackgroundPaint.setColor(Color.argb(160, 0, 0, 0));
        hudBackgroundPaint.setStyle(Paint.Style.FILL);

        hudTextPaint.setColor(Color.WHITE);
        hudTextPaint.setTextSize(32f);

        // Gesture detectors
        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleListener());
        gestureDetector = new GestureDetector(getContext(), new GestureListener());
    }

    /**
     * Sets new thermal data and triggers a redraw.
     * Called from the camera callback (may be on a different thread).
     */
    public void setThermalData(ThermalData data) {
        this.thermalData = data;
        updateFps();
        post(this::invalidate);
    }

    /**
     * Cycles to the next colormap.
     */
    public void nextColormap() {
        colormapIndex = (colormapIndex + 1) % Colormaps.ALL.length;
        currentColormap = Colormaps.ALL[colormapIndex];
        invalidate();
    }

    /**
     * Returns the current colormap name.
     */
    public String getColormapName() {
        return Colormaps.NAMES[colormapIndex];
    }

    /**
     * Adjusts contrast (-1.0 to decrease, +1.0 to increase).
     */
    public void adjustContrast(float delta) {
        contrast = Math.max(0.5f, Math.min(3.0f, contrast + delta));
        invalidate();
    }

    /**
     * Gets the current contrast value.
     */
    public float getContrast() {
        return contrast;
    }

    /**
     * Sets the contrast value directly.
     */
    public void setContrast(float value) {
        contrast = Math.max(0.5f, Math.min(3.0f, value));
        invalidate();
    }

    /**
     * Gets the current colormap index.
     */
    public int getColormapIndex() {
        return colormapIndex;
    }

    /**
     * Sets the colormap by index.
     */
    public void setColormapIndex(int index) {
        if (index >= 0 && index < Colormaps.ALL.length) {
            colormapIndex = index;
            currentColormap = Colormaps.ALL[colormapIndex];
            invalidate();
        }
    }

    /**
     * Returns representative colors from the current colormap for gradient preview.
     * Returns colors at positions 0, 127, and 255 (start, middle, end).
     */
    public int[] getColormapColors() {
        return new int[]{
            Colormaps.apply(currentColormap, 0),
            Colormaps.apply(currentColormap, 127),
            Colormaps.apply(currentColormap, 255)
        };
    }

    /**
     * Sets the rotation directly.
     */
    public void setRotation(int degrees) {
        rotationDegrees = degrees % 360;
        invalidate();
    }

    /**
     * Sets the mirror mode directly.
     */
    public void setMirrored(boolean mirror) {
        mirrored = mirror;
        invalidate();
    }

    /**
     * Resets zoom to default.
     */
    public void resetZoom() {
        scaleFactor = 1.0f;
        translateX = 0f;
        translateY = 0f;
        invalidate();
    }

    public float getZoomLevel() {
        return scaleFactor;
    }

    /**
     * Rotates the image by 90 degrees clockwise.
     * @return The new rotation in degrees (0, 90, 180, or 270)
     */
    public int rotate() {
        rotationDegrees = (rotationDegrees + 90) % 360;
        invalidate();
        return rotationDegrees;
    }

    public int getImageRotation() {
        return rotationDegrees;
    }

    /**
     * Toggles mirrored (selfie) mode.
     * @return The new mirror state
     */
    public boolean toggleMirror() {
        mirrored = !mirrored;
        invalidate();
        return mirrored;
    }

    public boolean isMirrored() {
        return mirrored;
    }

    private void updateFps() {
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastFrameTime >= 1000) {
            fps = frameCount * 1000f / (now - lastFrameTime);
            frameCount = 0;
            lastFrameTime = now;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (thermalData == null || thermalData.imageData == null) {
            // Draw placeholder background only - status text is handled by MainActivity
            canvas.drawColor(Color.DKGRAY);
            return;
        }

        // Apply colormap to grayscale image
        applyColormap();

        // Calculate scaling to fit view while maintaining aspect ratio
        float viewWidth = getWidth();
        float viewHeight = getHeight();

        // For 90/270 rotation, swap the aspect ratio
        boolean isRotated90or270 = (rotationDegrees == 90 || rotationDegrees == 270);
        float imageAspect = isRotated90or270
                ? (float) ThermalData.HEIGHT / ThermalData.WIDTH
                : (float) ThermalData.WIDTH / ThermalData.HEIGHT;
        float viewAspect = viewWidth / viewHeight;

        float drawWidth, drawHeight;
        if (viewAspect > imageAspect) {
            drawHeight = viewHeight;
            drawWidth = viewHeight * imageAspect;
        } else {
            drawWidth = viewWidth;
            drawHeight = viewWidth / imageAspect;
        }

        float left = (viewWidth - drawWidth) / 2f;
        float top = (viewHeight - drawHeight) / 2f;

        // Apply zoom and pan
        canvas.save();
        canvas.translate(viewWidth / 2f, viewHeight / 2f);
        canvas.scale(scaleFactor, scaleFactor);
        canvas.translate(-viewWidth / 2f + translateX, -viewHeight / 2f + translateY);

        // Apply rotation and horizontal flip around center of the image area
        float centerX = left + drawWidth / 2f;
        float centerY = top + drawHeight / 2f;
        if (!mirrored) {
            canvas.scale(-1f, 1f, centerX, centerY);  // Flip horizontally to correct camera mirror
        }
        canvas.rotate(rotationDegrees, centerX, centerY);

        // Adjust draw rect for rotation (swap dimensions for 90/270)
        RectF destRect;
        if (isRotated90or270) {
            float rotatedWidth = drawHeight;
            float rotatedHeight = drawWidth;
            float rotatedLeft = centerX - rotatedWidth / 2f;
            float rotatedTop = centerY - rotatedHeight / 2f;
            destRect = new RectF(rotatedLeft, rotatedTop, rotatedLeft + rotatedWidth, rotatedTop + rotatedHeight);
        } else {
            destRect = new RectF(left, top, left + drawWidth, top + drawHeight);
        }

        // Draw thermal image
        canvas.drawBitmap(bitmap, null, destRect, bitmapPaint);

        // Scale factors for converting image coords to view coords (use destRect)
        float scaleX = destRect.width() / ThermalData.WIDTH;
        float scaleY = destRect.height() / ThermalData.HEIGHT;

        // Draw crosshairs at center
        float crossCenterX = destRect.left + (ThermalData.WIDTH / 2f) * scaleX;
        float crossCenterY = destRect.top + (ThermalData.HEIGHT / 2f) * scaleY;
        float crossSize = 20f;

        // Inner
        canvas.drawLine(crossCenterX - crossSize, crossCenterY,
                crossCenterX + crossSize, crossCenterY, crosshairPaint);
        canvas.drawLine(crossCenterX, crossCenterY - crossSize,
                crossCenterX, crossCenterY + crossSize, crosshairPaint);

        // Draw max temperature marker (red circle)
        float maxViewX = destRect.left + thermalData.maxCol * scaleX;
        float maxViewY = destRect.top + thermalData.maxRow * scaleY;
        markerPaint.setColor(Color.RED);
        canvas.drawCircle(maxViewX, maxViewY, 12f, markerPaint);

        // Draw min temperature marker (blue circle)
        float minViewX = destRect.left + thermalData.minCol * scaleX;
        float minViewY = destRect.top + thermalData.minRow * scaleY;
        markerPaint.setColor(Color.BLUE);
        canvas.drawCircle(minViewX, minViewY, 12f, markerPaint);

        // Draw tap temperature marker if recent
        float tapViewX = 0, tapViewY = 0;
        boolean showTap = !Float.isNaN(tapTemp) && System.currentTimeMillis() - tapTime < 3000;
        if (showTap) {
            tapViewX = destRect.left + tapX * scaleX;
            tapViewY = destRect.top + tapY * scaleY;
            markerPaint.setColor(Color.GREEN);
            canvas.drawCircle(tapViewX, tapViewY, 10f, markerPaint);
        }

        // Get current transformation matrix to convert marker positions to screen coordinates
        android.graphics.Matrix matrix = canvas.getMatrix();

        canvas.restore();

        // Transform marker positions to screen coordinates and draw labels
        float[] pts = new float[6];
        pts[0] = maxViewX; pts[1] = maxViewY;
        pts[2] = minViewX; pts[3] = minViewY;
        pts[4] = tapViewX; pts[5] = tapViewY;
        matrix.mapPoints(pts);

        String maxLabel = String.format(Locale.US, "%.1f\u00B0", thermalData.maxTemp);
        drawMarkerLabelAtScreen(canvas, maxLabel, pts[0], pts[1]);

        String minLabel = String.format(Locale.US, "%.1f\u00B0", thermalData.minTemp);
        drawMarkerLabelAtScreen(canvas, minLabel, pts[2], pts[3]);

        if (showTap) {
            String tapLabel = String.format(Locale.US, "%.1f\u00B0", tapTemp);
            drawMarkerLabelAtScreen(canvas, tapLabel, pts[4], pts[5]);
        }

        // Draw HUD overlay (not affected by zoom)
        drawHud(canvas);
    }

    private void applyColormap() {
        byte[] data = thermalData.imageData;
        float minVal = 0f, maxVal = 255f;

        // Apply contrast/brightness normalization
        // Find actual min/max in the image data for better contrast
        int imgMin = 255, imgMax = 0;
        for (byte b : data) {
            int v = b & 0xFF;
            if (v < imgMin) imgMin = v;
            if (v > imgMax) imgMax = v;
        }

        float range = Math.max(1, imgMax - imgMin);

        for (int i = 0; i < data.length; i++) {
            // Normalize to 0-1 based on image range
            float normalized = ((data[i] & 0xFF) - imgMin) / range;

            // Apply contrast
            normalized = 0.5f + (normalized - 0.5f) * contrast;
            normalized = Math.max(0f, Math.min(1f, normalized));

            bitmapPixels[i] = Colormaps.applyNormalized(currentColormap, normalized);
        }

        bitmap.setPixels(bitmapPixels, 0, ThermalData.WIDTH, 0, 0,
                ThermalData.WIDTH, ThermalData.HEIGHT);
    }

    private void drawTextWithOutline(Canvas canvas, String text, float x, float y) {
        // Ensure both paints have matching alignment
        textOutlinePaint.setTextAlign(textPaint.getTextAlign());
        canvas.drawText(text, x, y, textOutlinePaint);
        canvas.drawText(text, x, y, textPaint);
    }

    /**
     * Draws a label near a marker at screen coordinates (after canvas.restore()).
     * Adjusts position to stay within view bounds.
     */
    private void drawMarkerLabelAtScreen(Canvas canvas, String text, float screenX, float screenY) {
        float textWidth = textPaint.measureText(text);
        float textHeight = textPaint.getTextSize();
        float offsetX = 16f;
        float offsetY = 8f;

        // View bounds with margin
        float viewRight = getWidth() - 8f;
        float viewBottom = getHeight() - 8f;
        float viewLeft = 8f;
        float viewTop = 8f;

        float x, y;

        // Position horizontally: prefer right, but go left if near right edge
        if (screenX + offsetX + textWidth > viewRight) {
            x = screenX - offsetX - textWidth;
        } else {
            x = screenX + offsetX;
        }

        // Position vertically: prefer below-center, but go above if near bottom
        if (screenY + offsetY + textHeight > viewBottom) {
            y = screenY - offsetY;
        } else {
            y = screenY + offsetY;
        }

        // Clamp to view bounds
        x = Math.max(viewLeft, Math.min(viewRight - textWidth, x));
        y = Math.max(viewTop + textHeight, Math.min(viewBottom, y));

        drawTextWithOutline(canvas, text, x, y);
    }

    private void drawHud(Canvas canvas) {
        float padding = 16f;
        float lineHeight = 36f;
        float hudWidth = 280f;
        float hudHeight = lineHeight * 4 + padding * 2;
        float hudLeft = padding;
        float hudTop = getHeight() - hudHeight - padding;

        // Background
        canvas.drawRoundRect(hudLeft, hudTop, hudLeft + hudWidth, hudTop + hudHeight,
                8f, 8f, hudBackgroundPaint);

        float textX = hudLeft + padding;
        float textY = hudTop + padding + lineHeight - 8f;

        // Center temperature
        hudTextPaint.setColor(Color.WHITE);
        canvas.drawText(String.format(Locale.US, "Center: %.1f\u00B0C", thermalData.centerTemp),
                textX, textY, hudTextPaint);

        // Range
        textY += lineHeight;
        canvas.drawText(String.format(Locale.US, "Range: %.1f - %.1f\u00B0C",
                thermalData.minTemp, thermalData.maxTemp), textX, textY, hudTextPaint);

        // Average
        textY += lineHeight;
        canvas.drawText(String.format(Locale.US, "Avg: %.1f\u00B0C", thermalData.avgTemp),
                textX, textY, hudTextPaint);

        // Status line: colormap, zoom, fps
        textY += lineHeight;
        String status = String.format(Locale.US, "%s | %.1fx | %.0f fps",
                getColormapName(), scaleFactor, fps);
        canvas.drawText(status, textX, textY, hudTextPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            scaleFactor *= detector.getScaleFactor();
            scaleFactor = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scaleFactor));
            invalidate();
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (scaleFactor > 1.0f) {
                translateX -= distanceX / scaleFactor;
                translateY -= distanceY / scaleFactor;
                invalidate();
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            resetZoom();
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            // Calculate which pixel was tapped
            if (thermalData == null) return false;

            float viewWidth = getWidth();
            float viewHeight = getHeight();

            // Account for rotation in aspect ratio
            boolean isRotated90or270 = (rotationDegrees == 90 || rotationDegrees == 270);
            float imageAspect = isRotated90or270
                    ? (float) ThermalData.HEIGHT / ThermalData.WIDTH
                    : (float) ThermalData.WIDTH / ThermalData.HEIGHT;
            float viewAspect = viewWidth / viewHeight;

            float drawWidth, drawHeight;
            if (viewAspect > imageAspect) {
                drawHeight = viewHeight;
                drawWidth = viewHeight * imageAspect;
            } else {
                drawWidth = viewWidth;
                drawHeight = viewWidth / imageAspect;
            }

            float left = (viewWidth - drawWidth) / 2f;
            float top = (viewHeight - drawHeight) / 2f;
            float centerX = left + drawWidth / 2f;
            float centerY = top + drawHeight / 2f;

            // Transform tap coordinates back to image space (undo zoom/pan)
            float tapViewX = (e.getX() - viewWidth / 2f) / scaleFactor + viewWidth / 2f - translateX;
            float tapViewY = (e.getY() - viewHeight / 2f) / scaleFactor + viewHeight / 2f - translateY;

            // Undo rotation (rotate in opposite direction around center)
            float dx = tapViewX - centerX;
            float dy = tapViewY - centerY;
            double radians = Math.toRadians(-rotationDegrees);
            float rotatedDx = (float) (dx * Math.cos(radians) - dy * Math.sin(radians));
            float rotatedDy = (float) (dx * Math.sin(radians) + dy * Math.cos(radians));
            tapViewX = centerX + rotatedDx;
            tapViewY = centerY + rotatedDy;

            // Undo horizontal flip (only if not in mirror/selfie mode)
            if (!mirrored) {
                tapViewX = centerX - (tapViewX - centerX);
            }

            // Convert to image coordinates
            // For rotated images, the destRect dimensions are swapped
            float imgWidth, imgHeight;
            if (isRotated90or270) {
                imgWidth = drawHeight;
                imgHeight = drawWidth;
            } else {
                imgWidth = drawWidth;
                imgHeight = drawHeight;
            }
            float imgLeft = centerX - imgWidth / 2f;
            float imgTop = centerY - imgHeight / 2f;

            int imgX = (int) ((tapViewX - imgLeft) / imgWidth * ThermalData.WIDTH);
            int imgY = (int) ((tapViewY - imgTop) / imgHeight * ThermalData.HEIGHT);

            if (imgX >= 0 && imgX < ThermalData.WIDTH && imgY >= 0 && imgY < ThermalData.HEIGHT) {
                tapX = imgX;
                tapY = imgY;
                tapTime = System.currentTimeMillis();

                // Approximate temperature based on min/max range and pixel intensity
                int pixelVal = thermalData.imageData[imgY * ThermalData.WIDTH + imgX] & 0xFF;
                float normalized = pixelVal / 255f;
                tapTemp = thermalData.minTemp + normalized * (thermalData.maxTemp - thermalData.minTemp);

                invalidate();
            }
            return true;
        }
    }
}
