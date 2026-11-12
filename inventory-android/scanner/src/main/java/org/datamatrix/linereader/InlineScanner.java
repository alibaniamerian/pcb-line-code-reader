package org.datamatrix.linereader;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.MeteringRectangle;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.DisplayMetrics;
import android.util.Size;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Camera reader embedded in the task screen. Uses the same capture size, sensor crop,
 * square-only luminance sampling and decoders (LineDecoder, then MarkedDecoder for
 * DataMatrix) as the standalone reader; only the hosting changed from an Activity to a View.
 * The host decides what a decoded code means and sets the frame colour with {@link #setState}.
 */
public final class InlineScanner extends LinearLayout {
    public interface Listener { void onCode(String payload, String format); void onCameraProblem(String message); }
    public static final int IDLE = 0, OK = 1, BAD = 2, WAIT = 3;

    private final TextureView texture; private final FrameLayout viewport; private final Overlay overlay;
    private final TextView light, sizeButton, badge; private Runnable onClose;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean analyzing = new AtomicBoolean();
    private ExecutorService decoder;
    private HandlerThread thread; private Handler cameraHandler;
    private CameraDevice camera; private CameraCaptureSession session; private ImageReader images; private CaptureRequest.Builder request;
    private CameraCharacteristics info; private Size size; private Rect activeArray; private Surface previewSurface;
    private float sensorZoom = 1.5f; private volatile Rect crop; private volatile boolean running, torch, qr;
    private volatile int generation, epoch; private long lastFrame; private boolean opening, wanted;
    private int squareScale, state = IDLE; private Listener listener;

    public InlineScanner(Context context) {
        super(context);
        setOrientation(VERTICAL); setLayoutDirection(LAYOUT_DIRECTION_LTR);
        squareScale = Math.max(1, Math.min(3, context.getSharedPreferences("scanner", 0).getInt("square_scale", 1)));
        viewport = new FrameLayout(context); texture = new TextureView(context); overlay = new Overlay(context);
        viewport.setBackgroundColor(0xFF151A22);
        final float radius = dp(22);
        viewport.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); } });
        viewport.setClipToOutline(true);
        viewport.addView(texture); viewport.addView(overlay);
        badge = pill("Scanner"); badge.setTextSize(11.5f);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START); bp.setMargins(dp(10), dp(10), 0, 0);
        viewport.addView(badge, bp);
        TextView close = pill("✕"); close.setGravity(Gravity.CENTER); close.setPadding(0, 0, 0, 0);
        close.setOnClickListener(v -> { if (onClose != null) onClose.run(); });
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP | Gravity.END); cp.setMargins(0, dp(8), dp(8), 0);
        viewport.addView(close, cp);
        LinearLayout tools = new LinearLayout(context); tools.setGravity(Gravity.CENTER);
        TextView focus = pill("◎  Focus"); focus.setOnClickListener(v -> focus());
        light = pill("☀  Light");
        light.setOnClickListener(v -> { torch = !torch; updateRequest(); styleLight(); });
        sizeButton = pill("▢  " + squareScale + "×");
        sizeButton.setOnClickListener(v -> {
            squareScale = squareScale % 3 + 1; sizeButton.setText("▢  " + squareScale + "×"); epoch++;
            getContext().getSharedPreferences("scanner", 0).edit().putInt("square_scale", squareScale).apply(); mapping();
        });
        for (TextView t : new TextView[]{focus, light, sizeButton}) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.setMargins(dp(4), 0, dp(4), 0); tools.addView(t, p); }
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); tp.bottomMargin = dp(10);
        viewport.addView(tools, tp);
        addView(viewport, new LayoutParams(-1, dp(250)));
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) { open(); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) { mapping(); }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t) { return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture t) {}
        });
        viewport.addOnLayoutChangeListener((v, a, b, c, d, e, f, g, h) -> mapping());
    }

    public void setListener(Listener l) { listener = l; }
    /** Small label on the preview, e.g. "QR · reel label". */
    public void setBadge(String text) { badge.setText(text); }
    /** Called when the ✕ on the preview is tapped. */
    public void setOnClose(Runnable r) { onClose = r; }
    public void setPreviewHeightDp(int h) { viewport.getLayoutParams().height = dp(h); viewport.requestLayout(); }
    private TextView pill(String s) {
        TextView t = new TextView(getContext()); t.setText(s); t.setTextColor(Color.WHITE); t.setTextSize(12.5f);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(); g.setColor(0x47000000); g.setCornerRadius(dp(99));
        t.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), g, null));
        t.setPadding(dp(13), dp(7), dp(13), dp(7)); return t;
    }
    private void styleLight() {
        light.setText(torch ? "☀  Light on" : "☀  Light");
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(); g.setColor(torch ? 0xE6F59E0B : 0x47000000); g.setCornerRadius(dp(99));
        light.setBackground(g);
    }
    public boolean isRunning() { return wanted; }

    /** Start (or switch) scanning for exactly one format: "DATA_MATRIX" or "QR_CODE". */
    public void start(String format) {
        qr = "QR_CODE".equals(format); epoch++; wanted = true; setState(IDLE); resume();
    }
    /** Stop scanning and release the camera. */
    public void stop() { wanted = false; pause(); }
    /** Host lifecycle: call from Activity.onResume / onPause. */
    public void resume() {
        if (!wanted || running) return;
        running = true; if (decoder == null) decoder = Executors.newSingleThreadExecutor();
        thread = new HandlerThread("InlineCamera"); thread.start(); cameraHandler = new Handler(thread.getLooper()); open();
    }
    public void pause() {
        running = false; generation++; opening = false; crop = null; torch = false; styleLight();
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (images != null) { images.close(); images = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        if (thread != null) { thread.quitSafely(); thread = null; cameraHandler = null; }
    }
    public void release() { stop(); if (decoder != null) { decoder.shutdownNow(); decoder = null; } }
    /** Frame colour: IDLE cyan, OK green, BAD red, WAIT amber. */
    public void setState(int s) { state = s; overlay.invalidate(); }

    private int dp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
    private void problem(String message) { ui.post(() -> { if (listener != null) listener.onCameraProblem(message); }); }

    private void open() {
        if (!running || cameraHandler == null || !texture.isAvailable() || camera != null || opening) return;
        if (getContext().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { problem("Camera permission required"); return; }
        opening = true; final int ticket = ++generation;
        try {
            CameraManager manager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE); String id = null;
            for (String candidate : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(candidate);
                if (Objects.equals(c.get(CameraCharacteristics.LENS_FACING), CameraCharacteristics.LENS_FACING_BACK)) { id = candidate; info = c; break; }
            }
            if (id == null) throw new IllegalStateException("No rear camera");
            Size[] supported = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.YUV_420_888);
            List<Size> previewSizes = Arrays.asList(info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(SurfaceTexture.class));
            size = null; long distance = Long.MAX_VALUE;
            for (Size value : supported) { long diff = Math.abs((long) value.getWidth() * value.getHeight() - 1920L * 1080); if (previewSizes.contains(value) && diff < distance) { size = value; distance = diff; } }
            if (size == null) throw new IllegalStateException("No compatible preview size");
            activeArray = info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float maxZoom = info.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM); sensorZoom = Math.min(1.5f, maxZoom == null ? 1f : maxZoom);
            light.setEnabled(Boolean.TRUE.equals(info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)));
            images = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
            images.setOnImageAvailableListener(r -> frame(r, ticket), cameraHandler);
            manager.openCamera(id, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice c) { if (!running || ticket != generation) { c.close(); return; } camera = c; opening = false; createSession(ticket); }
                public void onDisconnected(CameraDevice c) { c.close(); if (c == camera) camera = null; opening = false; problem("Camera disconnected"); }
                public void onError(CameraDevice c, int code) { onDisconnected(c); problem("Camera error " + code); }
            }, cameraHandler);
        } catch (Exception e) { opening = false; problem("Camera: " + e.getMessage()); }
    }
    private void createSession(int ticket) {
        try {
            texture.getSurfaceTexture().setDefaultBufferSize(size.getWidth(), size.getHeight()); previewSurface = new Surface(texture.getSurfaceTexture());
            request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); request.addTarget(previewSurface); request.addTarget(images.getSurface());
            camera.createCaptureSession(Arrays.asList(previewSurface, images.getSurface()), new CameraCaptureSession.StateCallback() {
                public void onConfigured(CameraCaptureSession s) { if (!running || ticket != generation) { s.close(); return; } session = s; updateRequest(); ui.post(InlineScanner.this::mapping); }
                public void onConfigureFailed(CameraCaptureSession s) { problem("Camera configuration failed"); }
            }, cameraHandler);
        } catch (Exception e) { problem("Camera: " + e.getMessage()); }
    }
    private void updateRequest() {
        Handler handler = cameraHandler; if (handler == null) return;
        handler.post(() -> {
            if (session == null || !running) return;
            try {
                request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                int cw = (int) (activeArray.width() / sensorZoom), ch = (int) (activeArray.height() / sensorZoom); cw -= cw % 2; ch -= ch % 2;
                request.set(CaptureRequest.SCALER_CROP_REGION, new Rect(activeArray.centerX() - cw / 2, activeArray.centerY() - ch / 2, activeArray.centerX() + cw / 2, activeArray.centerY() + ch / 2));
                if (Build.VERSION.SDK_INT >= 31) { int[] available = info.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES); if (available != null) for (int mode : available) if (mode == CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) request.set(CaptureRequest.SCALER_ROTATE_AND_CROP, mode); }
                int[] modes = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES); boolean continuous = false; for (int m : modes) if (m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) continuous = true;
                request.set(CaptureRequest.CONTROL_AF_MODE, continuous ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE : CaptureRequest.CONTROL_AF_MODE_AUTO);
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                request.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                Integer count = info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
                if (count != null && count > 0) { int side = Math.min(activeArray.width(), activeArray.height()) / 5; request.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{new MeteringRectangle(activeArray.centerX() - side / 2, activeArray.centerY() - side / 2, side, side, 1000)}); }
                session.setRepeatingRequest(request.build(), null, cameraHandler);
            } catch (Exception e) { problem("Camera: " + e.getMessage()); }
        });
    }
    private void focus() {
        Handler handler = cameraHandler; if (handler == null) return;
        handler.post(() -> {
            if (session == null) return;
            try {
                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO); request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                session.capture(request.build(), null, cameraHandler);
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE); session.setRepeatingRequest(request.build(), null, cameraHandler);
            } catch (Exception e) { problem("Camera: " + e.getMessage()); }
        });
    }
    /** Same physical square size as the full-screen reader: 13.5% of the screen's short side per 1x, capped to the preview. */
    private RectF square() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        float vw = viewport.getWidth(), vh = viewport.getHeight();
        float side = Math.min(Math.min(m.widthPixels, m.heightPixels) * .135f * squareScale, Math.min(vw, vh) * .92f);
        return new RectF((vw - side) / 2, (vh - side) / 2, (vw + side) / 2, (vh + side) / 2);
    }
    private void mapping() {
        if (size == null || info == null || viewport.getWidth() == 0 || viewport.getHeight() == 0) return;
        Display d = getDisplay(); int display = d == null ? 0 : d.getRotation() * 90;
        int rotation = (info.get(CameraCharacteristics.SENSOR_ORIENTATION) - display + 360) % 360;
        float w = size.getWidth(), h = size.getHeight(), vw = viewport.getWidth(), vh = viewport.getHeight();
        Matrix bufferToView = new Matrix(); bufferToView.postTranslate(-w / 2, -h / 2); bufferToView.postRotate(rotation);
        float scale = Math.max(vw / (rotation % 180 == 0 ? w : h), vh / (rotation % 180 == 0 ? h : w)); bufferToView.postScale(scale, scale); bufferToView.postTranslate(vw / 2, vh / 2);
        int baseRotation = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
        float rw = baseRotation % 180 == 0 ? w : h, rh = baseRotation % 180 == 0 ? h : w;
        Matrix surface = new Matrix(); surface.postTranslate(-w / 2, -h / 2); surface.postRotate(baseRotation); surface.postScale(vw / rw, vh / rh); surface.postTranslate(vw / 2, vh / 2);
        float[] src = {0, 0, w, 0, 0, h}, dst = src.clone(); surface.mapPoints(src); bufferToView.mapPoints(dst);
        Matrix transform = new Matrix(); transform.setPolyToPoly(src, 0, dst, 0, 3); texture.setTransform(transform);
        Matrix inverse = new Matrix(); if (!bufferToView.invert(inverse)) return;
        RectF roi = square(); inverse.mapRect(roi);
        crop = new Rect(Math.max(0, (int) Math.ceil(roi.left)), Math.max(0, (int) Math.ceil(roi.top)), Math.min((int) w, (int) Math.floor(roi.right)), Math.min((int) h, (int) Math.floor(roi.bottom)));
        overlay.invalidate();
    }
    private void frame(ImageReader reader, int ticket) {
        Image image = null;
        try {
            image = reader.acquireLatestImage(); if (image == null) return;
            Rect roi = crop; long now = SystemClock.elapsedRealtime();
            if (ticket != generation || !running || roi == null || roi.width() < 32 || roi.height() < 32 || now - lastFrame < 200) return;
            if (!analyzing.compareAndSet(false, true)) return;
            lastFrame = now;
            byte[] pixels = new byte[roi.width() * roi.height()]; Image.Plane plane = image.getPlanes()[0]; ByteBuffer source = plane.getBuffer();
            int stride = plane.getRowStride(), pixelStride = plane.getPixelStride();
            for (int y = 0; y < roi.height(); y++) for (int x = 0; x < roi.width(); x++) pixels[y * roi.width() + x] = source.get((roi.top + y) * stride + (roi.left + x) * pixelStride);
            final boolean scanQr = qr; final int scanEpoch = epoch; ExecutorService work = decoder;
            if (work == null) { analyzing.set(false); return; }
            work.execute(() -> {
                try {
                    String value = LineDecoder.decode(pixels, roi.width(), roi.height(), scanQr ? com.google.zxing.BarcodeFormat.QR_CODE : com.google.zxing.BarcodeFormat.DATA_MATRIX);
                    if (value == null && !scanQr) value = MarkedDecoder.decode(getContext(), pixels, roi.width(), roi.height());
                    if (value != null) {
                        final String decoded = value;
                        ui.post(() -> { if (running && ticket == generation && scanEpoch == epoch && listener != null) listener.onCode(decoded, scanQr ? "QR_CODE" : "DATA_MATRIX"); });
                    }
                } catch (Exception e) { problem("Decode failed. Adjust focus or lighting and try again."); }
                finally { analyzing.set(false); }
            });
        } catch (Exception e) { analyzing.set(false); problem("Frame: " + e.getMessage()); }
        finally { if (image != null) image.close(); }
    }
    private final class Overlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Overlay(Context c) { super(c); }
        /** Soft dim outside the reading square and rounded corner brackets coloured by state. */
        @Override protected void onDraw(Canvas canvas) {
            RectF box = square(); int tint = state == OK ? 0x3316A34A : state == BAD ? 0x40DC2626 : state == WAIT ? 0x33F59E0B : 0x66000000;
            paint.setStyle(Paint.Style.FILL); paint.setColor(tint);
            canvas.drawRect(0, 0, getWidth(), box.top, paint); canvas.drawRect(0, box.bottom, getWidth(), getHeight(), paint);
            canvas.drawRect(0, box.top, box.left, box.bottom, paint); canvas.drawRect(box.right, box.top, getWidth(), box.bottom, paint);
            int color = state == OK ? 0xFF22C55E : state == BAD ? 0xFFEF4444 : state == WAIT ? 0xFFF59E0B : Color.WHITE;
            float d = getResources().getDisplayMetrics().density, arm = Math.min(box.width() * 0.28f, 28 * d), r = 6 * d;
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4 * d); paint.setStrokeCap(Paint.Cap.ROUND);
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(box.left, box.top + arm); p.lineTo(box.left, box.top + r); p.quadTo(box.left, box.top, box.left + r, box.top); p.lineTo(box.left + arm, box.top);
            p.moveTo(box.right - arm, box.top); p.lineTo(box.right - r, box.top); p.quadTo(box.right, box.top, box.right, box.top + r); p.lineTo(box.right, box.top + arm);
            p.moveTo(box.right, box.bottom - arm); p.lineTo(box.right, box.bottom - r); p.quadTo(box.right, box.bottom, box.right - r, box.bottom); p.lineTo(box.right - arm, box.bottom);
            p.moveTo(box.left + arm, box.bottom); p.lineTo(box.left + r, box.bottom); p.quadTo(box.left, box.bottom, box.left, box.bottom - r); p.lineTo(box.left, box.bottom - arm);
            canvas.drawPath(p, paint);
        }
    }
}
