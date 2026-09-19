package com.keygen.app;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * 激活码二维码生成(纯 ZXing core)。
 *
 * 内容为无连字符的 Crockford Base32 激活码(420 字符, 数字+大写字母),
 * 走 QR alphanumeric 模式; M 级 15% 纠错, version ~13(约 70 模块),
 * 600px PNG 每模块约 8px, 聊天软件压缩后仍可稳定扫描。
 */
public final class QrCodeUtils {

    private QrCodeUtils() {}

    /** 按指定边长生成黑白二维码位图。 */
    public static Bitmap encode(String content, int sizePx) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);

        BitMatrix matrix = new QRCodeWriter()
                .encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);

        int width = matrix.getWidth();
        int height = matrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }

        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, width, 0, 0, width, height);
        return bmp;
    }
}
