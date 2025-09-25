package com.example.elrsotg;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class TxRxGraphView extends View {
    private Paint txPaint;
    private Paint rxPaint;
    private Paint gridPaint;
    private Paint backgroundPaint;
    
    private List<Float> txData;
    private List<Float> rxData;
    private int maxDataPoints = 200;
    private float maxValue = 100f;
    
    private long lastUpdateTime = 0;
    private int txPacketCount = 0;
    private int rxPacketCount = 0;
    private float txRate = 0f;
    private float rxRate = 0f;

    public TxRxGraphView(Context context) {
        super(context);
        init();
    }

    public TxRxGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TxRxGraphView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Initialize paint objects
        txPaint = new Paint();
        txPaint.setColor(0xFF00FF00); // Green for TX
        txPaint.setStrokeWidth(3f);
        txPaint.setStyle(Paint.Style.STROKE);
        txPaint.setAntiAlias(true);

        rxPaint = new Paint();
        rxPaint.setColor(0xFF0088FF); // Blue for RX
        rxPaint.setStrokeWidth(3f);
        rxPaint.setStyle(Paint.Style.STROKE);
        rxPaint.setAntiAlias(true);

        gridPaint = new Paint();
        gridPaint.setColor(0x44444444);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        backgroundPaint = new Paint();
        backgroundPaint.setColor(0xFF0A0A0A);
        backgroundPaint.setStyle(Paint.Style.FILL);

        // Initialize data lists
        txData = new ArrayList<>();
        rxData = new ArrayList<>();
        
        // Fill with initial zero values
        for (int i = 0; i < maxDataPoints; i++) {
            txData.add(0f);
            rxData.add(0f);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        int width = getWidth();
        int height = getHeight();
        
        // Draw background
        canvas.drawRect(0, 0, width, height, backgroundPaint);
        
        // Draw grid
        drawGrid(canvas, width, height);
        
        // Draw TX and RX lines
        drawDataLine(canvas, txData, txPaint, width, height);
        drawDataLine(canvas, rxData, rxPaint, width, height);
    }

    private void drawGrid(Canvas canvas, int width, int height) {
        // Horizontal grid lines
        for (int i = 1; i < 4; i++) {
            float y = (height * i) / 4f;
            canvas.drawLine(0, y, width, y, gridPaint);
        }
        
        // Vertical grid lines
        for (int i = 1; i < 10; i++) {
            float x = (width * i) / 10f;
            canvas.drawLine(x, 0, x, height, gridPaint);
        }
    }

    private void drawDataLine(Canvas canvas, List<Float> data, Paint paint, int width, int height) {
        if (data.size() < 2) return;
        
        Path path = new Path();
        boolean first = true;
        
        for (int i = 0; i < data.size(); i++) {
            float x = (float) i * width / (maxDataPoints - 1);
            float y = height - (data.get(i) / maxValue * height);
            
            // Clamp y to bounds
            y = Math.max(0, Math.min(height, y));
            
            if (first) {
                path.moveTo(x, y);
                first = false;
            } else {
                path.lineTo(x, y);
            }
        }
        
        canvas.drawPath(path, paint);
    }

    public void addTxData(float value) {
        synchronized (txData) {
            txData.remove(0);
            txData.add(Math.min(value, maxValue));
            txPacketCount++;
        }
        updateRates();
        invalidate();
    }

    public void addRxData(float value) {
        synchronized (rxData) {
            rxData.remove(0);
            rxData.add(Math.min(value, maxValue));
            rxPacketCount++;
        }
        updateRates();
        invalidate();
    }
    
    public void addChannelData(float roll, float pitch, float yaw, float throttle) {
        // Convert channel values to graph data (0-100 range)
        float txValue = Math.abs(roll) + Math.abs(pitch) + Math.abs(yaw) + Math.abs(throttle);
        txValue = Math.min(txValue / 4f * 100f, 100f); // Normalize to 0-100
        
        addTxData(txValue);
        
        // Simulate RX feedback (in real implementation, this would come from actual RX data)
        float rxValue = txValue * 0.8f + (float)(Math.random() * 10 - 5); // Simulate some noise
        addRxData(Math.max(0, rxValue));
    }

    private void updateRates() {
        long currentTime = System.currentTimeMillis();
        if (lastUpdateTime == 0) {
            lastUpdateTime = currentTime;
            return;
        }
        
        long deltaTime = currentTime - lastUpdateTime;
        if (deltaTime >= 1000) { // Update rates every second
            txRate = txPacketCount * 1000f / deltaTime;
            rxRate = rxPacketCount * 1000f / deltaTime;
            
            txPacketCount = 0;
            rxPacketCount = 0;
            lastUpdateTime = currentTime;
        }
    }

    public float getTxRate() {
        return txRate;
    }

    public float getRxRate() {
        return rxRate;
    }
    
    public void reset() {
        synchronized (txData) {
            for (int i = 0; i < txData.size(); i++) {
                txData.set(i, 0f);
            }
        }
        synchronized (rxData) {
            for (int i = 0; i < rxData.size(); i++) {
                rxData.set(i, 0f);
            }
        }
        txPacketCount = 0;
        rxPacketCount = 0;
        txRate = 0f;
        rxRate = 0f;
        lastUpdateTime = 0;
        invalidate();
    }
}