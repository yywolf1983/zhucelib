package com.reggate.lib;

import android.Manifest;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.Collections;

/**
 * 库自研的竖屏二维码扫码界面(框架 Camera1 + zxing core 解码)。
 *
 * <p>不继承 zxing-android-embedded 的 CaptureActivity, 因为本地 AAR 文件集成时
 * 不携带第三方传递依赖, 引用 embedded 类会在宿主 NoClassDefFoundError 崩溃。
 * Camera API 与 zxing core 均由系统/库自身提供, 保证 AAR 自包含。
 */
public class PortraitCaptureActivity extends Activity {

    /** 扫码成功后通过 setResult 回传的文本 Extra。 */
    public static final String EXTRA_SCAN_RESULT = "reggate_scan_result";

    private static final int REQ_CAMERA = 0x3001;
    private static final String TAG = "RegGateCapture";

    private SurfaceView surfaceView;
    private SurfaceHolder holder;
    private Camera camera;
    private int cameraOrientation = 90;
    private boolean previewing = false;
    private boolean decoding = false;
    private boolean finished = false;

    private HandlerThread decodeThread;
    private Handler decodeHandler;
    private Handler mainHandler = new Handler();
    private ToneGenerator toneGenerator;

    private final QRCodeReader qrReader = new QRCodeReader();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        int layoutId = RegGateResources.getLayoutId(this, "reggate_activity_capture");
        if (layoutId != 0) {
            setContentView(layoutId);
            int hintId = RegGateResources.getId(this, "reggate_capture_hint");
            if (hintId != 0) {
                ((TextView) findViewById(hintId)).setText(
                        RegGateResources.getString(this, "reggate_scan_prompt"));
            }
            int cancelId = RegGateResources.getId(this, "reggate_capture_cancel");
            if (cancelId != 0) {
                findViewById(cancelId).setOnClickListener(v -> cancelScan());
            }
        }

        surfaceView = new SurfaceView(this);
        // 兜底: 布局缺失时直接使用代码创建的预览(正常不会走到)
        if (layoutId == 0) {
            setContentView(surfaceView);
        } else {
            int previewId = RegGateResources.getId(this, "reggate_capture_preview");
            if (previewId != 0) {
                surfaceView = (SurfaceView) findViewById(previewId);
            }
        }

        holder = surfaceView.getHolder();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                tryOpenCamera();
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                releaseCamera();
            }
        });

        decodeThread = new HandlerThread("reggate-qr-decode");
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                tryOpenCamera();
            } else {
                Toast.makeText(this,
                        RegGateResources.getString(this, "reggate_scan_prompt"),
                        Toast.LENGTH_SHORT).show();
                cancelScan();
            }
        }
    }

    private void tryOpenCamera() {
        if (camera != null || finished) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // 权限尚未授予, 等 onRequestPermissionsResult 后再打开
            return;
        }
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int cameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
            int numberOfCameras = Camera.getNumberOfCameras();
            for (int i = 0; i < numberOfCameras; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    cameraId = i;
                    break;
                }
            }
            camera = Camera.open(cameraId);
            Camera.getCameraInfo(cameraId, info);
            cameraOrientation = info.orientation;
            configureCamera();
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            camera.setPreviewCallback(previewCallback);
            previewing = true;
        } catch (Exception e) {
            Log.w(TAG, "打开相机失败: " + e.getMessage());
            releaseCamera();
            Toast.makeText(this,
                    RegGateResources.getString(this, "reggate_qr_load_failed"),
                    Toast.LENGTH_SHORT).show();
            cancelScan();
        }
    }

    private void configureCamera() {
        try {
            Camera.Parameters params = camera.getParameters();
            // 选一个宽 <= 1280 的最大预览尺寸, 兼顾识别率与解码速度
            Camera.Size best = null;
            for (Camera.Size s : params.getSupportedPreviewSizes()) {
                if (s.width <= 1280 && (best == null
                        || s.width * s.height > best.width * best.height)) {
                    best = s;
                }
            }
            if (best == null) {
                best = params.getPreviewSize();
            }
            params.setPreviewSize(best.width, best.height);

            java.util.List<String> modes = params.getSupportedFocusModes();
            if (modes != null) {
                if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } else if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                } else if (modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
            }
            camera.setParameters(params);
        } catch (Exception e) {
            Log.w(TAG, "相机参数设置失败, 使用默认参数: " + e.getMessage());
        }
    }

    private final Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] data, Camera cam) {
            if (data == null || decoding || finished) return;
            Camera.Size size;
            try {
                size = cam.getParameters().getPreviewSize();
            } catch (Exception e) {
                return;
            }
            decoding = true;
            final byte[] frame = data;
            final int w = size.width;
            final int h = size.height;
            decodeHandler.post(() -> decodeFrame(frame, w, h));
        }
    };

    private void decodeFrame(byte[] data, int w, int h) {
        String result = null;
        try {
            byte[] yuv = rotateLuma(data, w, h, cameraOrientation);
            int rotatedW = (cameraOrientation == 90 || cameraOrientation == 270) ? h : w;
            int rotatedH = (cameraOrientation == 90 || cameraOrientation == 270) ? w : h;
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    yuv, rotatedW, rotatedH, 0, 0, rotatedW, rotatedH, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            result = qrReader.decode(bitmap, Collections.singletonMap(
                    DecodeHintType.POSSIBLE_FORMATS,
                    Collections.singletonList(BarcodeFormat.QR_CODE))).getText();
        } catch (ReaderException | RuntimeException | Error ignored) {
            // 本帧无法识别, 继续等下一帧
        } finally {
            final String text = result;
            mainHandler.post(() -> {
                decoding = false;
                if (text != null && !finished) {
                    onDecoded(text);
                }
            });
        }
    }

    /** 按相机传感器方向旋转 YUV 的亮度平面, 使画面在竖屏下正向。 */
    private static byte[] rotateLuma(byte[] src, int w, int h, int degrees) {
        if (degrees == 0) return src;
        byte[] out = new byte[w * h];
        if (degrees == 90) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out[x * h + (h - 1 - y)] = src[y * w + x];
                }
            }
        } else if (degrees == 180) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out[(h - 1 - y) * w + (w - 1 - x)] = src[y * w + x];
                }
            }
        } else if (degrees == 270) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out[(w - 1 - x) * h + y] = src[y * w + x];
                }
            }
        } else {
            return src;
        }
        return out;
    }

    private void onDecoded(String text) {
        finished = true;
        playBeep();
        android.content.Intent it = new android.content.Intent();
        it.putExtra(EXTRA_SCAN_RESULT, text);
        setResult(RESULT_OK, it);
        finish();
    }

    private void cancelScan() {
        if (finished) return;
        finished = true;
        setResult(RESULT_CANCELED);
        finish();
    }

    private void playBeep() {
        try {
            if (toneGenerator == null) {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            }
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
        } catch (RuntimeException ignored) {
            // 部分设备/模拟器不支持音频服务, 提示音非关键功能
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                if (previewing) {
                    camera.setPreviewCallback(null);
                    camera.stopPreview();
                    previewing = false;
                }
                camera.release();
            } catch (RuntimeException ignored) {
            }
            camera = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tryOpenCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
        if (decodeThread != null) {
            decodeThread.quit();
            decodeThread = null;
        }
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
    }

    @Override
    public void onBackPressed() {
        cancelScan();
        super.onBackPressed();
    }
}
