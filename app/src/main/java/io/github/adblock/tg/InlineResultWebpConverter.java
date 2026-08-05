package io.github.adblock.tg;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Locale;

final class InlineResultWebpConverter {
    private static final String TAG = "TGAdBlock";

    private InlineResultWebpConverter() { }

    static void prepare(Object sendingMediaInfo, XposedLog log) {
        if (sendingMediaInfo == null) return;
        try {
            Object result = getField(sendingMediaInfo, "inlineResult");
            if (result == null || !isImageResult(result)) return;

            // BotInlineResult fields are mutable in Telegram's TL implementation.
            setStringField(result, "mime_type", "image/webp");
            setStringField(result, "type", "sticker");

            // Some versions put the MIME type in result.content instead of the result itself.
            Object content = getField(result, "content");
            if (content != null) setStringField(content, "mime_type", "image/webp");

            // If Telegram has already materialised this inline image, send the WebP file rather
            // than the original. A missing path is normal: Telegram will download it later.
            String path = getStringField(sendingMediaInfo, "path");
            if (path == null || path.length() == 0) path = getStringField(sendingMediaInfo, "filePath");
            if (path != null && replaceWithWebp(sendingMediaInfo, path, log)) {
                log.info("Converted inline bot image to WebP");
            } else {
                log.info("Marked inline bot image as image/webp (download not materialised yet)");
            }
        } catch (Throwable t) {
            log.warn("Inline WebP conversion failed", t);
        }
    }

    static void prepareBotInlineResult(Object result, XposedLog log) {
        if (result == null || !isImageResult(result)) return;

        try {
            // Modifying type to "sticker" directly taps into Telegram's internal check switch to build
            // it natively as a TL_documentAttributeSticker.
            setStringField(result, "mime_type", "image/webp");
            setStringField(result, "type", "sticker");

            Object content = getField(result, "content");
            if (content != null) {
                setStringField(content, "mime_type", "image/webp");
            }

            log.info("Marked BotInlineResult as sticker/webp (direct hook)");
        } catch (Throwable t) {
            log.warn("Inline WebP conversion failed (BotInlineResult)", t);
        }
    }

    private static boolean isImageResult(Object result) {
        String type = getStringField(result, "type");
        String mime = getStringField(result, "mime_type");
        Object content = getField(result, "content");
        if (mime == null && content != null) mime = getStringField(content, "mime_type");
        type = type == null ? "" : type.toLowerCase(Locale.ROOT);
        mime = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        return "photo".equals(type) || "image".equals(type) || "sticker".equals(type) || mime.startsWith("image/");
    }

    private static boolean replaceWithWebp(Object info, String path, XposedLog log) {
        File source = new File(path);
        if (!source.isFile() || source.length() == 0 || path.toLowerCase(Locale.ROOT).endsWith(".webp")) {
            return false;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath());
        if (bitmap == null) return false;
        File target = new File(source.getParentFile(), source.getName() + ".webp");
        try (FileOutputStream out = new FileOutputStream(target)) {
            Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.WEBP;
            if (!bitmap.compress(format, 90, out)) {
                target.delete();
                return false;
            }
            if (!setStringField(info, "path", target.getAbsolutePath())) {
                setStringField(info, "filePath", target.getAbsolutePath());
            }
            return true;
        } catch (Throwable t) {
            target.delete();
            log.warn("Could not write inline WebP", t);
            return false;
        } finally {
            bitmap.recycle();
        }
    }

    private static Object getField(Object object, String name) {
        Field field = findField(object.getClass(), name);
        if (field == null) return null;
        try { field.setAccessible(true); return field.get(object); } catch (Throwable ignored) { return null; }
    }

    private static String getStringField(Object object, String name) {
        Object value = getField(object, name);
        return value instanceof String ? (String) value : null;
    }

    private static boolean setStringField(Object object, String name, String value) {
        Field field = findField(object.getClass(), name);
        if (field == null || field.getType() != String.class) return false;
        try { field.setAccessible(true); field.set(object, value); return true; } catch (Throwable ignored) { return false; }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    interface XposedLog {
        void info(String message);
        void warn(String message, Throwable throwable);
    }
}