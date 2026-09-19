package com.reggate.lib;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.ReaderException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * 从相册图片中解码二维码(纯 ZXing core 解码, 不涉及相机, 无需任何权限)。
 *
 * 处理:
 * - 大图降采样, 避免 {@link BitmapFactory} 解码 OOM
 * - EXIF 方向校正(部分相机/相册保存的 JPEG 带旋转标记)
 * - 两种二值化策略重试, 提升截图/压缩图识别率
 */
final class QrImageDecoder {

    /** 解码后最长边限制: 1600px 对 QR 识别绰绰有余, 同时控制内存峰值。 */
    private static final int MAX_DIMENSION = 1600;

    private QrImageDecoder() {}

    /**
     * 解码图片中的二维码文本。
     *
     * @throws NotFoundException 图片中未找到二维码
     * @throws Exception         图片读取/解码失败
     */
    static String decode(Context context, Uri uri) throws Exception {
        File tmp = copyToTemp(context, uri);
        Bitmap bitmap = null;
        try {
            Bitmap sampled = decodeSampled(tmp.getAbsolutePath());
            bitmap = applyExifRotation(sampled, tmp.getAbsolutePath());
            return decodeBitmap(bitmap);
        } finally {
            if (bitmap != null) bitmap.recycle();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static File copyToTemp(Context context, Uri uri) throws Exception {
        File tmp = File.createTempFile("reggate_qr_", ".img", context.getCacheDir());
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(tmp)) {
            if (is == null) throw new Exception("cannot open uri: " + uri);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        return tmp;
    }

    private static Bitmap decodeSampled(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);

        int sample = 1;
        int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxDim / sample > MAX_DIMENSION) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(path, opts);
    }

    private static Bitmap applyExifRotation(Bitmap bitmap, String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int degrees = 0;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:  degrees = 90;  break;
                case ExifInterface.ORIENTATION_ROTATE_180: degrees = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: degrees = 270; break;
                default: return bitmap;
            }
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (Exception e) {
            return bitmap;
        }
    }

    private static String decodeBitmap(Bitmap bitmap) throws NotFoundException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        QRCodeReader reader = new QRCodeReader();
        try {
            return reader.decode(new BinaryBitmap(new HybridBinarizer(source)), hints).getText();
        } catch (ReaderException first) {
            // 低对比/复杂背景图(含校验失败/格式失败): 全局直方图二值化再试一次
            reader.reset();
            try {
                return reader.decode(
                        new BinaryBitmap(new GlobalHistogramBinarizer(source)), hints).getText();
            } catch (ReaderException e) {
                throw NotFoundException.getNotFoundInstance();
            }
        }
    }
}
