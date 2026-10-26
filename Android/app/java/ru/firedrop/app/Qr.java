package ru.firedrop.app;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

public final class Qr {
    private Qr() {
    }

    public static Bitmap encode(String text, int sizePx) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        try {
            com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Exception error) {
            return null;
        }
    }

    /**
     * Decodes a QR code from a bitmap using several strategies. A single photo of a
     * screen is noisy: the binarizer that works for one frame often fails for the
     * next, so we sweep binarizers, inverted sources, centre crops and scales.
     */
    public static String decode(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return null;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        LuminanceSource base = new RGBLuminanceSource(width, height, pixels);

        String text = decodeSource(base);
        if (text != null) return text;

        // Small codes in a large frame: upscale the tiny centre crops of the same
        // bitmap once. Keeps the sweep bounded but rescues 2-4 px "per module" shots.
        if (Math.min(width, height) >= 800) {
            Bitmap small = Bitmap.createScaledBitmap(bitmap, width / 3, height / 3, true);
            if (small != null && !small.isRecycled()) {
                int[] down = new int[small.getWidth() * small.getHeight()];
                small.getPixels(down, 0, small.getWidth(), 0, 0, small.getWidth(), small.getHeight());
                text = decodeSource(new RGBLuminanceSource(
                        small.getWidth(), small.getHeight(), down));
                small.recycle();
            }
        }

        // Tiny source? Upscaling adds JPEG noise but can also un-blur 1 px modules.
        if (text == null && Math.min(width, height) < 240) {
            Bitmap big = Bitmap.createScaledBitmap(bitmap, width * 3, height * 3, true);
            if (big != null && !big.isRecycled()) {
                int[] up = new int[big.getWidth() * big.getHeight()];
                big.getPixels(up, 0, big.getWidth(), 0, 0, big.getWidth(), big.getHeight());
                text = decodeSource(new RGBLuminanceSource(
                        big.getWidth(), big.getHeight(), up));
                big.recycle();
            }
        }
        return text;
    }

    /**
     * Sweeps one luminance source with binarizer, inversion and crop variants.
     * Used both for bitmaps and for live Camera2 frames.
     */
    public static String decodeSource(LuminanceSource source) {
        if (source == null) return null;

        String text = sweep(source);
        if (text != null) return text;

        // The code usually sits near the centre of the frame; a smaller field makes
        // the binarizer see the code instead of table/room gradients.
        int width = source.getWidth();
        int height = source.getHeight();
        for (float fraction : new float[]{0.72f, 0.5f}) {
            int cw = Math.max(1, Math.round(width * fraction));
            int ch = Math.max(1, Math.round(height * fraction));
            LuminanceSource crop = safeCrop(source, (width - cw) / 2, (height - ch) / 2, cw, ch);
            if (crop == null) continue;
            text = sweep(crop);
            if (text != null) return text;
        }
        return null;
    }

    /** Tries: hybrid, global, hybrid-inverted, global-inverted, pure barcode. */
    private static String sweep(LuminanceSource source) {
        String text;
        if ((text = attempt(source, false, false, false)) != null) return text;
        if ((text = attempt(source, true, false, false)) != null) return text;
        if ((text = attempt(source, false, true, false)) != null) return text;
        if ((text = attempt(source, true, true, false)) != null) return text;
        if ((text = attempt(source, false, false, true)) != null) return text;
        return null;
    }

    private static LuminanceSource safeCrop(LuminanceSource source, int left, int top, int width, int height) {
        try {
            return source.crop(left, top, width, height);
        } catch (Exception error) {
            return null;
        }
    }

    private static String attempt(LuminanceSource source, boolean global, boolean inverted, boolean pure) {
        try {
            LuminanceSource channel = inverted ? source.invert() : source;
            BinaryBitmap binary = new BinaryBitmap(global
                    ? new GlobalHistogramBinarizer(channel)
                    : new HybridBinarizer(channel));

            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(BarcodeFormat.QR_CODE));
            hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
            if (pure) hints.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);

            Result result = new MultiFormatReader().decode(binary, hints);
            if (result == null) return null;
            String content = result.getText();
            return content == null || content.isEmpty() ? null : content;
        } catch (Exception error) {
            return null;
        }
    }
}
