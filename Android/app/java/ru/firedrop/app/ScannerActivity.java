package ru.firedrop.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live QR scanner built on Camera2 and ZXing core (no AndroidX). The old photo of a
 * screen saw one noisy frame; here the receiver feeds ~7 frames per second through
 * the decoder until one is recognised, so a slightly tilted or dim screen still
 * scans. Falls back to a system-camera snapshot when the hardware cannot be opened.
 */
public final class ScannerActivity extends Activity {
    public static final String EXTRA_PAYLOAD = "ru.firedrop.payload";

    private static final int REQUEST_CAMERA = 40;
    private static final int REQUEST_PHOTO = 41;
    private static final int TRY_MAX_WIDTH = 1280;

    private TextureView textureView;
    private TextView statusLine;
    private TextView problemTitle;
    private TextView problemText;
    private LinearLayout problemCard;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraManager cameraManager;
    private String cameraId;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Size previewSize;
    private Size decodeSize;
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private boolean finishing;

    private File pendingPhoto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        finishing = false;
        if (checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    protected void onPause() {
        stopCamera();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_CAMERA) return;

        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            showProblem(L10n.s("scan.permission.title"),
                    L10n.s("scan.permission.message"));
        }
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout top = Ui.row(this);
        top.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12));
        top.setBackgroundColor(Ui.BG);

        LinearLayout titleBody = Ui.column(this);
        titleBody.addView(Ui.text(this, L10n.s("scan.title"), 19, Ui.TEXT, true));
        titleBody.addView(Ui.text(this, L10n.s("scan.subtitle"), 12.5f, Ui.MUTED, false));
        top.addView(titleBody, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = Ui.text(this, L10n.s("scan.close"), 14, Ui.ACCENT, true);
        close.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        close.setClickable(true);
        close.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        top.addView(close);
        root.addView(top);

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);

        textureView = new TextureView(this);
        stage.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        stage.addView(new ScanOverlay(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        problemCard = new LinearLayout(this);
        problemCard.setOrientation(LinearLayout.VERTICAL);
        problemCard.setBackground(Ui.roundRect(0xF70D1424, 20, Ui.BORDER, this));
        problemCard.setPadding(Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        cardParams.leftMargin = Ui.dp(this, 26);
        cardParams.rightMargin = Ui.dp(this, 26);
        problemCard.setLayoutParams(cardParams);
        problemCard.setVisibility(View.GONE);

        problemTitle = Ui.text(this, "", 17, Ui.TEXT, true);
        problemCard.addView(problemTitle);
        problemText = Ui.text(this, "", 13, Ui.MUTED, false);
        problemText.setPadding(0, Ui.dp(this, 8), 0, 0);
        problemCard.addView(problemText);

        TextView photoButton = Ui.button(this, L10n.s("scan.photo_button"), false);
        photoButton.setOnClickListener(v -> startPhoto());
        LinearLayout.LayoutParams photoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        photoParams.topMargin = Ui.dp(this, 18);
        problemCard.addView(photoButton, photoParams);

        TextView retryButton = Ui.button(this, L10n.s("scan.retry_button"), false);
        retryButton.setOnClickListener(v -> {
            problemCard.setVisibility(View.GONE);
            startCamera();
        });
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryParams.topMargin = Ui.dp(this, 10);
        problemCard.addView(retryButton, retryParams);

        stage.addView(problemCard);
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 22), Ui.dp(this, 22));
        bottom.setBackgroundColor(Ui.BG);
        statusLine = Ui.text(this, L10n.s("scan.hint"), 14, Ui.MUTED, false);
        statusLine.setGravity(Gravity.CENTER);
        bottom.addView(statusLine);
        root.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void showStatus(String message, int color) {
        problemCard.setVisibility(View.GONE);
        statusLine.setText(message);
        statusLine.setTextColor(color);
    }

    private void showProblem(String title, String message) {
        stopCamera();
        problemTitle.setText(title);
        problemText.setText(message);
        problemCard.setVisibility(View.VISIBLE);
        statusLine.setText("");
    }

    // --------------------------------------------------------------- camera

    private void startCamera() {
        try {
            if (cameraManager == null) {
                Object service = getSystemService(CAMERA_SERVICE);
                if (!(service instanceof CameraManager)) {
                    throw new IllegalStateException("camera service missing");
                }
                cameraManager = (CameraManager) service;
            }
            pickCameraId();

            if (textureView.isAvailable() && textureView.getSurfaceTexture() != null) {
                openCamera(textureView.getSurfaceTexture());
            } else {
                textureView.setSurfaceTextureListener(new SurfaceTextureListener());
            }
        } catch (Exception error) {
            showProblem(L10n.s("scan.camera_unavailable_title"),
                    L10n.s("scan.camera_unavailable_message"));
        }
    }

    private final class SurfaceTextureListener implements TextureView.SurfaceTextureListener {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera(surface);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            stopCameraQuiet();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    }

    private void pickCameraId() throws Exception {
        if (cameraId != null) return;

        String best = null;
        String fallback = null;
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing == null) continue;
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                best = id;
                break;
            }
            if (fallback == null) fallback = id;
        }
        cameraId = best != null ? best : fallback;
        if (cameraId == null) throw new IllegalStateException("no camera");
    }

    private void openCamera(SurfaceTexture host) {
        ensureThread();
        try {
            chooseSizes();
            host.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            prepareReader();
            Surface previewSurface = new Surface(host);

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    camera = device;
                    try {
                        createSession(previewSurface);
                    } catch (Exception error) {
                        cameraDisconnected(L10n.s("scan.error.stream"));
                    }
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    cameraDisconnected(L10n.s("scan.error.busy"));
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    cameraDisconnected(String.format(L10n.s("scan.error.code"), error));
                }
            }, cameraHandler);
        } catch (Exception error) {
            showProblem(L10n.s("scan.camera_unavailable_title"),
                    L10n.s("scan.camera_unavailable_message"));
        }
    }

    private void chooseSizes() throws CameraAccessException {
        if (previewSize != null && decodeSize != null) return;
        CameraCharacteristics characteristics =
                cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) throw new IllegalStateException("camera has no streams");

        Size[] surfaceSizes = map.getOutputSizes(SurfaceTexture.class);
        Size[] yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (surfaceSizes == null || surfaceSizes.length == 0
                || yuvSizes == null || yuvSizes.length == 0) {
            throw new IllegalStateException("camera has no usable sizes");
        }

        float screenRatio = 16f / 9f;
        try {
            android.view.Display display = getWindowManager().getDefaultDisplay();
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            display.getRealMetrics(metrics);
            if (metrics.heightPixels > 0) {
                screenRatio = metrics.widthPixels / (float) metrics.heightPixels;
            }
        } catch (Exception ignored) {
        }

        Size chosenPreview = null;
        int bestArea = 0;
        float bestFit = Float.MAX_VALUE;
        for (Size size : surfaceSizes) {
            int area = size.getWidth() * size.getHeight();
            if (area > 2560 * 1440) continue;
            float ratio = size.getWidth() / (float) size.getHeight();
            float fit = Math.abs(ratio - screenRatio);
            if (fit < bestFit || (Math.abs(fit - bestFit) < 0.005f && area > bestArea)) {
                chosenPreview = size;
                bestFit = fit;
                bestArea = area;
            }
        }
        if (chosenPreview == null) chosenPreview = surfaceSizes[0];

        // QR of the "new" FireDrop payloads is 33 modules wide; a 1280-wide frame
        // keeps ~10 px per module when the code fills half the frame.
        Size chosenDecode = null;
        int bestScore = -1;
        for (Size size : yuvSizes) {
            if (size.getWidth() > TRY_MAX_WIDTH) continue;
            int score = size.getWidth() * size.getHeight();
            if (score >= 960 * 720 && score > bestScore) {
                chosenDecode = size;
                bestScore = score;
            }
        }
        if (chosenDecode == null) {
            int bestYuvArea = -1;
            for (Size size : yuvSizes) {
                if (size.getWidth() > TRY_MAX_WIDTH) continue;
                int area = size.getWidth() * size.getHeight();
                if (area > bestYuvArea && area >= 320 * 240) {
                    chosenDecode = size;
                    bestYuvArea = area;
                }
            }
        }
        if (chosenDecode == null) {
            Size smallest = yuvSizes[0];
            for (Size size : yuvSizes) {
                if (size.getWidth() <= TRY_MAX_WIDTH) {
                    smallest = size;
                    break;
                }
            }
            chosenDecode = smallest;
        }

        previewSize = chosenPreview;
        decodeSize = chosenDecode;
    }

    private void prepareReader() {
        if (reader != null) {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
        }
        reader = ImageReader.newInstance(
                decodeSize.getWidth(), decodeSize.getHeight(),
                ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(this::onFrameAvailable, cameraHandler);
    }

    private void createSession(Surface previewSurface) {
        CameraDevice device = camera;
        if (device == null || reader == null) return;

        List<Surface> targets = Arrays.asList(previewSurface, reader.getSurface());
        try {
            device.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession configured) {
                session = configured;
                try {
                    CaptureRequest.Builder builder =
                            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    builder.addTarget(previewSurface);
                    builder.addTarget(reader.getSurface());
                    builder.set(CaptureRequest.CONTROL_MODE,
                            CaptureRequest.CONTROL_MODE_AUTO);
                    builder.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                    configured.setRepeatingRequest(builder.build(), null, cameraHandler);
                    mainHandler.post(() -> {
                        if (!finishing) {
                            showStatus(L10n.s("scan.hint"), Ui.SUCCESS);
                        }
                    });
                } catch (Exception error) {
                    cameraDisconnected(L10n.s("scan.error.stream"));
                }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession sessionConfig) {
                cameraDisconnected(L10n.s("scan.error.session"));
            }
        }, cameraHandler);
        } catch (CameraAccessException error) {
            cameraDisconnected(L10n.s("scan.error.stream"));
        }
    }

    /** The camera died or was taken away: switch to the snapshot fallback. */
    private void cameraDisconnected(String reason) {
        if (finishing) return;
        stopCameraQuiet();
        mainHandler.post(() -> {
            if (finishing) return;
            showProblem(L10n.s("scan.unresponsive.title"),
                    String.format(L10n.s("scan.unresponsive.message"), reason));
        });
    }

    private void stopCamera() {
        stopCameraQuiet();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            cameraThread = null;
            cameraHandler = null;
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
            reader = null;
        }
        decoding.set(false);
    }

    private void stopCameraQuiet() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        session = null;
        try {
            if (camera != null) camera.close();
        } catch (Exception ignored) {
        }
        camera = null;
    }

    private void ensureThread() {
        if (cameraThread == null || !cameraThread.isAlive()) {
            cameraThread = new HandlerThread("firedrop-scanner");
            cameraThread.setDaemon(true);
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }
    }

    // ------------------------------------------------------------ decoding

    private void onFrameAvailable(ImageReader source) {
        if (finishing || session == null) return;
        if (!decoding.compareAndSet(false, true)) return;

        try {
            Image image = source.acquireLatestImage();
            if (image == null) return;
            Frame frame;
            try {
                frame = copyLuminance(image);
            } finally {
                image.close();
            }
            if (frame == null) return;

            LuminanceSource luminance = new PlanarYUVLuminanceSource(
                    frame.data, frame.width, frame.height, 0, 0, frame.width, frame.height, false);
            String text = Qr.decodeSource(luminance);
            if (text != null && !text.isEmpty()) deliver(text);
        } catch (Exception ignored) {
            // A broken frame must not end the scan session.
        } finally {
            decoding.set(false);
        }
    }

    private static final class Frame {
        byte[] data;
        int width;
        int height;
    }

    private Frame copyLuminance(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        if (buffer == null || width <= 0 || height <= 0) return null;
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        byte[] data = new byte[width * height];
        if (pixelStride == 1 && rowStride == width) {
            buffer.get(data, 0, Math.min(data.length, buffer.remaining()));
            return frame(data, width, height);
        }

        byte[] row = new byte[Math.max(rowStride, 1)];
        for (int y = 0; y < height; y++) {
            int position = y * rowStride;
            try {
                buffer.position(position);
                if (pixelStride == 1) {
                    buffer.get(row, 0, width);
                    System.arraycopy(row, 0, data, y * width, width);
                } else {
                    int wanted = Math.min(width, (buffer.remaining() - position) / pixelStride);
                    for (int x = 0; x < Math.max(0, wanted); x++) {
                        data[y * width + x] = buffer.get(position + x * pixelStride);
                    }
                }
            } catch (Exception broken) {
                int available = Math.max(0, Math.min(width, buffer.remaining()));
                for (int x = 0; x < available; x++) {
                    data[y * width + x] = pixelStride == 1
                            ? buffer.get() : buffer.get(position + x * pixelStride);
                }
            }
        }
        return frame(data, width, height);
    }

    private static Frame frame(byte[] data, int width, int height) {
        Frame result = new Frame();
        result.data = data;
        result.width = width;
        result.height = height;
        return result;
    }

    private void deliver(String text) {
        if (finishing) return;
        finishing = true;
        mainHandler.post(() -> {
            tone();
            Intent data = new Intent();
            data.putExtra(EXTRA_PAYLOAD, text);
            setResult(RESULT_OK, data);
            finish();
        });
    }

    // -------------------------------------------------- system-camera fallback

    private void startPhoto() {
        try {
            File file = new File(new File(getCacheDir(), "share"),
                    "qr-" + System.currentTimeMillis() + ".jpg");
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            Uri output = FileProviderLite.uriFor(file);
            Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, output);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("firedrop", output));
            pendingPhoto = file;
            startActivityForResult(intent, REQUEST_PHOTO);
        } catch (Exception error) {
            Toast.makeText(this, L10n.s("scan.toast.unavailable"),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PHOTO) return;
        File file = pendingPhoto;
        pendingPhoto = null;
        if (resultCode != RESULT_OK || file == null || !file.exists()) return;

        showStatus(L10n.s("scan.recognizing"), Ui.MUTED);
        new Thread(() -> {
            String text = decodePhoto(file);
            mainHandler.post(() -> {
                if (finishing) return;
                if (text != null && !text.isEmpty()) {
                    deliver(text);
                } else {
                    showProblem(L10n.s("scan.not_recognized.title"),
                            L10n.s("scan.not_recognized.message"));
                }
            });
        }, "firedrop-photo").start();
    }

    private String decodePhoto(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;

        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) return null;
        String text = Qr.decode(bitmap);
        bitmap.recycle();
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return text;
    }

    private void tone() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.os.VibratorManager manager = (android.os.VibratorManager)
                        getSystemService(VIBRATOR_MANAGER_SERVICE);
                if (manager != null) manager.getDefaultVibrator().vibrate(30);
            } else {
                android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (vibrator != null) vibrator.vibrate(30);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------- viewfinder

    /** Dim mask with a rounded scan window and accent corner strokes. */
    private final class ScanOverlay extends View {
        private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        ScanOverlay(android.content.Context context) {
            super(context);
            mask.setColor(0x99050B14);
            mask.setStyle(Paint.Style.FILL);
            stroke.setColor(Ui.ACCENT);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(2.4f));
        }

        private int dp(float value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) return;

            int side = Math.min(width * 82 / 100, height * 62 / 100);
            float top = (height - side) / 2f - height / 12f;
            RectF window = new RectF(
                    (width - side) / 2f, top,
                    (width + side) / 2f, top + side);

            float corner = dp(22);
            Path path = new Path();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRoundRect(new RectF(0, 0, width, height), corner, corner, Path.Direction.CW);
            path.addRoundRect(window, corner, corner, Path.Direction.CW);
            canvas.drawPath(path, mask);

            path.reset();
            addCorners(path, window, corner, side / 6f);
            canvas.drawPath(path, stroke);
        }

        private void addCorners(Path path, RectF window, float corner, float arm) {
            path.moveTo(window.left, window.top + corner + arm);
            path.lineTo(window.left, window.top + corner / 2);
            path.quadTo(window.left, window.top, window.left + corner / 2, window.top);
            path.lineTo(window.left + corner + arm, window.top);

            path.moveTo(window.right - corner - arm, window.top);
            path.lineTo(window.right - corner / 2, window.top);
            path.quadTo(window.right, window.top, window.right, window.top + corner / 2);
            path.lineTo(window.right, window.top + corner + arm);

            path.moveTo(window.right, window.bottom - corner - arm);
            path.lineTo(window.right, window.bottom - corner / 2);
            path.quadTo(window.right, window.bottom, window.right - corner / 2, window.bottom);
            path.lineTo(window.right - corner - arm, window.bottom);

            path.moveTo(window.left + corner + arm, window.bottom);
            path.lineTo(window.left + corner / 2, window.bottom);
            path.quadTo(window.left, window.bottom, window.left, window.bottom - corner / 2);
            path.lineTo(window.left, window.bottom - corner - arm);
        }
    }
}
