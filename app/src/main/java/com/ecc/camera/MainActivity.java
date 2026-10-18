package com.ecc.camera;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(9, 11, 16);
    private static final int PANEL = Color.rgb(17, 21, 28);
    private static final int BORDER = Color.rgb(42, 49, 61);
    private static final int TEXT = Color.rgb(238, 241, 246);
    private static final int MUTED = Color.rgb(137, 147, 163);
    private static final int MINT = Color.rgb(180, 242, 220);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final ExecutorService storage = Executors.newSingleThreadExecutor();
    private final AtomicReference<FrameData> pendingFrame = new AtomicReference<>();
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private final AtomicReference<DecodedFrame> pendingDisplay = new AtomicReference<>();
    private final AtomicBoolean displaying = new AtomicBoolean(false);
    private LinearLayout root;
    private ImageView imageView;
    private TextView statusView;
    private Button connectButton, mirrorButton, alignButton, calibrateButton, photoButton, recordButton;
    private volatile CameraClient cameraClient;
    private Bitmap currentBitmap;
    private Bitmap previewBitmap;
    private SurfaceVideoRecorder recorder;
    private boolean mirrored, aligning;
    private Float lastAngle, unwrappedAngle, referenceAngle;
    private float displayedRotation;
    private long lastRecordFrameNanos;
    private long lastPreviewFrameMillis;
    private int recordWidth, recordHeight;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildInterface();
    }

    private void buildInterface() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(16);
        root.setPadding(pad, dp(12), pad, dp(10));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("ECC  Live");
        title.setTextColor(TEXT);
        title.setTextSize(19);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        statusView = new TextView(this);
        statusView.setText("Connect to the camera’s Wi-Fi, then tap Connect");
        statusView.setTextColor(MUTED);
        statusView.setTextSize(12);
        statusView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(statusView, new LinearLayout.LayoutParams(0, dp(48), 1.4f));
        root.addView(header);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setBackground(makePanel(Color.rgb(16, 19, 26), BORDER, 14));
        imageView.setPadding(dp(8), dp(8), dp(8), dp(8));
        imageView.setContentDescription("Live camera image");
        LinearLayout.LayoutParams stage = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        stage.setMargins(0, dp(8), 0, dp(10));
        root.addView(imageView, stage);

        LinearLayout rowOne = row();
        connectButton = button("Connect", true);
        mirrorButton = button("Mirror: off", false);
        rowOne.addView(connectButton, weightButton());
        rowOne.addView(mirrorButton, weightButton());
        root.addView(rowOne);

        LinearLayout rowTwo = row();
        alignButton = button("Align: off", false);
        calibrateButton = button("Calibrate down", false);
        calibrateButton.setEnabled(false);
        rowTwo.addView(alignButton, weightButton());
        rowTwo.addView(calibrateButton, weightButton());
        root.addView(rowTwo);

        LinearLayout rowThree = row();
        photoButton = button("Take photo", false);
        recordButton = button("Start recording", true);
        photoButton.setEnabled(false);
        recordButton.setEnabled(false);
        rowThree.addView(photoButton, weightButton());
        rowThree.addView(recordButton, weightButton());
        root.addView(rowThree);

        connectButton.setOnClickListener(v -> toggleConnection());
        mirrorButton.setOnClickListener(v -> toggleMirror());
        alignButton.setOnClickListener(v -> toggleAlignment());
        calibrateButton.setOnClickListener(v -> calibrateDown());
        photoButton.setOnClickListener(v -> takePhoto());
        recordButton.setOnClickListener(v -> toggleRecording());
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams weightButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        return params;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(13);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        button.setTextColor(primary ? Color.rgb(16, 35, 28) : Color.rgb(203, 210, 222));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setMinHeight(dp(44));
        button.setBackground(makePanel(primary ? MINT : PANEL, primary ? MINT : BORDER, 10));
        return button;
    }

    private GradientDrawable makePanel(int color, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private void toggleConnection() {
        if (cameraClient != null) {
            cameraClient.stop();
            cameraClient = null;
            connectButton.setText("Connect");
            setStatus("Disconnected");
            return;
        }
        final CameraClient[] holder = new CameraClient[1];
        CameraClient client = new CameraClient(new CameraClient.Listener() {
            @Override public void onState(String message, boolean isConnected) {
                mainHandler.post(() -> {
                    if (cameraClient != holder[0]) return;
                    setStatus(message);
                    if (message.equals("Disconnected")) {
                        cameraClient = null;
                        connectButton.setText("Connect");
                    } else if (isConnected || message.startsWith("Connecting") || message.startsWith("Starting")) {
                        connectButton.setText("Disconnect");
                    } else {
                        cameraClient = null;
                        connectButton.setText("Connect");
                    }
                });
            }

            @Override public void onFrame(byte[] jpeg, Float angle, int width, int height) {
                if (cameraClient != holder[0]) return;
                pendingFrame.set(new FrameData(jpeg, angle, width, height));
                scheduleDecode();
            }
        });
        holder[0] = client;
        cameraClient = client;
        connectButton.setText("Connecting…");
        setStatus("Connect to the microscope’s Wi-Fi if needed");
        client.start();
    }

    private void scheduleDecode() {
        if (decoding.compareAndSet(false, true)) decoder.execute(() -> {
            try {
                FrameData data;
                while ((data = pendingFrame.getAndSet(null)) != null) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(data.jpeg, 0, data.jpeg.length);
                    if (bitmap == null) continue;
                    DecodedFrame old = pendingDisplay.getAndSet(new DecodedFrame(bitmap, data));
                    if (old != null && !old.bitmap.isRecycled()) old.bitmap.recycle();
                    scheduleDisplay();
                }
            } finally {
                decoding.set(false);
                if (pendingFrame.get() != null) scheduleDecode();
            }
        });
    }

    private void scheduleDisplay() {
        if (!displaying.compareAndSet(false, true)) return;
        mainHandler.post(() -> {
            try {
                DecodedFrame frame = pendingDisplay.getAndSet(null);
                if (frame != null) showFrame(frame.bitmap, frame.data);
            } finally {
                displaying.set(false);
                if (pendingDisplay.get() != null) scheduleDisplay();
            }
        });
    }

    private void showFrame(Bitmap bitmap, FrameData frame) {
        if (isFinishing() || isDestroyed()) { bitmap.recycle(); return; }
        Bitmap previous = currentBitmap;
        currentBitmap = bitmap;
        if (previous != null && previous != bitmap && !previous.isRecycled()) previous.recycle();
        photoButton.setEnabled(true);
        recordButton.setEnabled(true);
        if (frame.angle != null && !frame.angle.isNaN() && !frame.angle.isInfinite()) updateOrientation(frame.angle);
        long nowMillis = SystemClock.elapsedRealtime();
        if (nowMillis - lastPreviewFrameMillis >= 55) {
            drawPreview();
            lastPreviewFrameMillis = nowMillis;
        }
        if (recorder != null) {
            long now = SystemClock.elapsedRealtimeNanos();
            if (now - lastRecordFrameNanos >= 1_000_000_000L / 8) {
                lastRecordFrameNanos = now;
                recorder.submit(FrameRenderer.renderCropped(bitmap, displayedRotation, mirrored,
                        recordWidth, recordHeight));
            }
        }
    }

    private void updateOrientation(float next) {
        if (lastAngle == null) unwrappedAngle = next;
        else unwrappedAngle += ((next - lastAngle + 540f) % 360f) - 180f;
        lastAngle = next;
        if (aligning && referenceAngle == null) referenceAngle = unwrappedAngle;
        float target = aligning && referenceAngle != null
                ? (mirrored ? 1f : -1f) * (unwrappedAngle - referenceAngle) : 0f;
        displayedRotation = target;
        setStatus(String.format(Locale.US, "Connected · orientation %.1f°", next));
        calibrateButton.setEnabled(true);
    }

    private void drawPreview() {
        if (currentBitmap == null) return;
        Bitmap previous = previewBitmap;
        previewBitmap = FrameRenderer.renderCropped(currentBitmap, displayedRotation, mirrored);
        imageView.setImageBitmap(previewBitmap);
        if (previous != null && !previous.isRecycled()) previous.recycle();
    }

    private void toggleMirror() {
        mirrored = !mirrored;
        mirrorButton.setText(mirrored ? "Mirror: on" : "Mirror: off");
        mirrorButton.setTextColor(mirrored ? Color.rgb(138, 226, 195) : Color.rgb(203, 210, 222));
        mirrorButton.setBackground(makePanel(mirrored ? Color.rgb(18, 33, 29) : PANEL,
                mirrored ? Color.rgb(55, 100, 81) : BORDER, 10));
        if (aligning && unwrappedAngle != null && referenceAngle != null) {
            displayedRotation = (mirrored ? 1f : -1f) * (unwrappedAngle - referenceAngle);
        }
        drawPreview();
    }

    private void toggleAlignment() {
        aligning = !aligning;
        if (aligning && referenceAngle == null && unwrappedAngle != null) referenceAngle = unwrappedAngle;
        alignButton.setText(aligning ? "Align: on" : "Align: off");
        alignButton.setTextColor(aligning ? Color.rgb(138, 226, 195) : Color.rgb(203, 210, 222));
        alignButton.setBackground(makePanel(aligning ? Color.rgb(18, 33, 29) : PANEL,
                aligning ? Color.rgb(55, 100, 81) : BORDER, 10));
        displayedRotation = aligning && referenceAngle != null && unwrappedAngle != null
                ? (mirrored ? 1f : -1f) * (unwrappedAngle - referenceAngle) : 0f;
        drawPreview();
    }

    private void calibrateDown() {
        if (unwrappedAngle == null) return;
        referenceAngle = unwrappedAngle;
        displayedRotation = 0f;
        drawPreview();
        setStatus("Calibrated — this direction is down");
    }

    private void takePhoto() {
        if (currentBitmap == null) return;
        Bitmap output = render(currentBitmap, displayedRotation, mirrored, false);
        photoButton.setEnabled(false);
        storage.execute(() -> {
            Uri saved = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "ECC-" + timestamp() + ".jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ECC");
                saved = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (saved == null) throw new IllegalStateException("Could not create photo in gallery.");
                try (OutputStream stream = getContentResolver().openOutputStream(saved)) {
                    if (stream == null || !output.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                        throw new IllegalStateException("Could not save photo.");
                    }
                }
            } catch (Exception error) {
                if (saved != null) getContentResolver().delete(saved, null, null);
                saved = null;
            } finally {
                output.recycle();
            }
            Uri result = saved;
            mainHandler.post(() -> {
                photoButton.setEnabled(currentBitmap != null);
                Toast.makeText(this, result == null ? "Could not save photo" : "Photo saved to Pictures/ECC",
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void toggleRecording() {
        if (recorder != null) {
            recordButton.setText("Finishing…");
            recordButton.setEnabled(false);
            recorder.stop();
            return;
        }
        if (currentBitmap == null) return;
        float scale = Math.min(1f, 960f / Math.max(currentBitmap.getWidth(), currentBitmap.getHeight()));
        recordWidth = Math.max(2, ((int) (currentBitmap.getWidth() * scale)) & ~1);
        recordHeight = Math.max(2, ((int) (currentBitmap.getHeight() * scale)) & ~1);
        try {
            recorder = new SurfaceVideoRecorder(getContentResolver(), recordWidth, recordHeight, (uri, error) -> mainHandler.post(() -> {
                recorder = null;
                recordButton.setEnabled(currentBitmap != null);
                recordButton.setText("Start recording");
                recordButton.setTextColor(Color.rgb(16, 35, 28));
                recordButton.setBackground(makePanel(MINT, MINT, 10));
                if (error != null) {
                    setStatus(error);
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                } else {
                    setStatus("Video saved to Movies/ECC");
                    Toast.makeText(this, "Video saved to Movies/ECC", Toast.LENGTH_SHORT).show();
                }
            }));
            lastRecordFrameNanos = 0;
            recordButton.setText("Stop recording");
            recordButton.setTextColor(Color.rgb(255, 171, 178));
            recordButton.setBackground(makePanel(Color.rgb(50, 25, 29), Color.rgb(115, 58, 67), 10));
            setStatus("Recording video…");
        } catch (Exception error) {
            recorder = null;
            setStatus("Could not start video recording");
            Toast.makeText(this, "Could not start video recording", Toast.LENGTH_LONG).show();
        }
    }

    private Bitmap render(Bitmap source, float rotation, boolean mirror, boolean square) {
        int width = source.getWidth(), height = source.getHeight();
        double radians = Math.toRadians(rotation);
        int outWidth = square ? (int) Math.ceil(Math.hypot(width, height))
                : (int) Math.ceil(Math.abs(width * Math.cos(radians)) + Math.abs(height * Math.sin(radians)));
        int outHeight = square ? outWidth
                : (int) Math.ceil(Math.abs(height * Math.cos(radians)) + Math.abs(width * Math.sin(radians)));
        outWidth = Math.max(2, (outWidth + 1) & ~1);
        outHeight = Math.max(2, (outHeight + 1) & ~1);
        Bitmap output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.BLACK);
        canvas.translate(outWidth / 2f, outHeight / 2f);
        canvas.rotate(rotation);
        if (mirror) canvas.scale(-1f, 1f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, null, new RectF(-width / 2f, -height / 2f, width / 2f, height / 2f), paint);
        return output;
    }


    private void setStatus(String status) {
        if (statusView != null) statusView.setText(status);
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onDestroy() {
        if (cameraClient != null) cameraClient.stop();
        if (recorder != null) recorder.stop();
        if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle();
        if (previewBitmap != null && !previewBitmap.isRecycled()) previewBitmap.recycle();
        DecodedFrame pending = pendingDisplay.getAndSet(null);
        if (pending != null && !pending.bitmap.isRecycled()) pending.bitmap.recycle();
        decoder.shutdownNow();
        storage.shutdown();
        super.onDestroy();
    }

    private static final class FrameData {
        final byte[] jpeg;
        final Float angle;
        final int width, height;
        FrameData(byte[] jpeg, Float angle, int width, int height) {
            this.jpeg = jpeg;
            this.angle = angle;
            this.width = width;
            this.height = height;
        }
    }

    private static final class DecodedFrame {
        final Bitmap bitmap;
        final FrameData data;
        DecodedFrame(Bitmap bitmap, FrameData data) {
            this.bitmap = bitmap;
            this.data = data;
        }
    }
}
