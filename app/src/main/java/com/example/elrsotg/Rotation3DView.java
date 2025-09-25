package com.example.elrsotg;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

public class Rotation3DView extends View {
    private Paint bodyPaint;
    private Paint propellerPaint;
    private Paint axisPaint;
    private Paint backgroundPaint;
    private Paint gridPaint;
    
    private float roll = 0f;
    private float pitch = 0f;
    private float yaw = 0f;
    
    private static final float DRONE_SIZE = 60f;
    private static final float PROP_SIZE = 20f;

    public Rotation3DView(Context context) {
        super(context);
        init();
    }

    public Rotation3DView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public Rotation3DView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Initialize paint objects
        bodyPaint = new Paint();
        bodyPaint.setColor(0xFF00FF00); // Green drone body
        bodyPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        bodyPaint.setStrokeWidth(3f);
        bodyPaint.setAntiAlias(true);

        propellerPaint = new Paint();
        propellerPaint.setColor(0xFF0088FF); // Blue propellers
        propellerPaint.setStyle(Paint.Style.STROKE);
        propellerPaint.setStrokeWidth(2f);
        propellerPaint.setAntiAlias(true);

        axisPaint = new Paint();
        axisPaint.setColor(0xFFFF0000); // Red axis lines
        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(2f);
        axisPaint.setAntiAlias(true);

        backgroundPaint = new Paint();
        backgroundPaint.setColor(0xFF0A0A0A);
        backgroundPaint.setStyle(Paint.Style.FILL);

        gridPaint = new Paint();
        gridPaint.setColor(0x33444444);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        int width = getWidth();
        int height = getHeight();
        int centerX = width / 2;
        int centerY = height / 2;
        
        // Draw background
        canvas.drawRect(0, 0, width, height, backgroundPaint);
        
        // Draw grid
        drawGrid(canvas, width, height);
        
        // Draw coordinate axes
        drawAxes(canvas, centerX, centerY);
        
        // Draw drone with rotation
        drawDrone(canvas, centerX, centerY);
    }

    private void drawGrid(Canvas canvas, int width, int height) {
        // Horizontal grid lines
        for (int i = 1; i < 4; i++) {
            float y = (height * i) / 4f;
            canvas.drawLine(0, y, width, y, gridPaint);
        }
        
        // Vertical grid lines
        for (int i = 1; i < 4; i++) {
            float x = (width * i) / 4f;
            canvas.drawLine(x, 0, x, height, gridPaint);
        }
    }

    private void drawAxes(Canvas canvas, int centerX, int centerY) {
        // X-axis (red)
        axisPaint.setColor(0xFFFF0000);
        canvas.drawLine(centerX - 40, centerY, centerX + 40, centerY, axisPaint);
        
        // Y-axis (green)
        axisPaint.setColor(0xFF00FF00);
        canvas.drawLine(centerX, centerY - 40, centerX, centerY + 40, axisPaint);
        
        // Z-axis indication (blue dot)
        axisPaint.setColor(0xFF0088FF);
        canvas.drawCircle(centerX, centerY, 3f, axisPaint);
    }

    private void drawDrone(Canvas canvas, int centerX, int centerY) {
        canvas.save();
        
        // Apply rotations (simplified 2D representation of 3D rotations)
        canvas.translate(centerX, centerY);
        
        // Apply yaw rotation (rotation around Z-axis)
        canvas.rotate(yaw);
        
        // Apply pitch tilt (forward/backward lean simulation)
        float pitchSkew = pitch * 0.3f; // Limit skew effect
        
        // Apply roll tilt (left/right lean simulation)
        float rollSkew = roll * 0.5f;
        
        // Draw drone body (cross shape)
        bodyPaint.setColor(0xFF00AA00);
        
        // Main arms
        canvas.drawLine(-DRONE_SIZE, rollSkew, DRONE_SIZE, -rollSkew, bodyPaint);
        canvas.drawLine(-rollSkew + pitchSkew, -DRONE_SIZE, rollSkew - pitchSkew, DRONE_SIZE, bodyPaint);
        
        // Center body
        canvas.drawCircle(0, 0, 8f, bodyPaint);
        
        // Front indicator (shows forward direction)
        bodyPaint.setColor(0xFFFF0000);
        canvas.drawCircle(0, -DRONE_SIZE + 10, 5f, bodyPaint);
        
        // Draw propellers
        drawPropeller(canvas, -DRONE_SIZE + 10, -DRONE_SIZE + 10 + rollSkew);
        drawPropeller(canvas, DRONE_SIZE - 10, -DRONE_SIZE + 10 - rollSkew);
        drawPropeller(canvas, -DRONE_SIZE + 10, DRONE_SIZE - 10 + rollSkew);
        drawPropeller(canvas, DRONE_SIZE - 10, DRONE_SIZE - 10 - rollSkew);
        
        canvas.restore();
    }

    private void drawPropeller(Canvas canvas, float x, float y) {
        canvas.drawCircle(x, y, PROP_SIZE, propellerPaint);
        // Propeller blades
        canvas.drawLine(x - PROP_SIZE * 0.8f, y, x + PROP_SIZE * 0.8f, y, propellerPaint);
        canvas.drawLine(x, y - PROP_SIZE * 0.8f, x, y + PROP_SIZE * 0.8f, propellerPaint);
    }

    public void updateRotation(float roll, float pitch, float yaw) {
        this.roll = roll * 45f; // Convert to degrees
        this.pitch = pitch * 45f;
        this.yaw = yaw * 180f; // Yaw gets full rotation
        invalidate();
    }
    
    public void reset() {
        this.roll = 0f;
        this.pitch = 0f;
        this.yaw = 0f;
        invalidate();
    }
}