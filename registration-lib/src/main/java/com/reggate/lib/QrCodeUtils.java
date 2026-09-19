package com.reggate.lib;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码编码工具(仅依赖 zxing core)。
 * 与 keygen-app 中实现保持一致: M 级纠错、2 模块静区、黑白 ARGB_8888。
 */
final class QrCodeUtils {

    private static final int MARGIN = 2;

    private QrCodeUtils() {}

    /**
     * 将文本编码为正方形二维码位图。
     *
     * @param content 二维码内容
     * @param sizePx  输出图片边长(像素)
     */
    static Bitmap encode(String content, int sizePx) throws Exception {
        if (content == null || content.length() == 0) {
            throw new IllegalArgumentException("empty content");
        }
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION,
                com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, MARGIN);

        BitMatrix matrix = new MultiFormatWriter()
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
