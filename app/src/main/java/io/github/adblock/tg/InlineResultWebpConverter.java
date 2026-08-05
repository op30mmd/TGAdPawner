package io.github.adblock.tg;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;

final class InlineResultWebpConverter {
    private static final String TAG = "TGAdBlock";

    private InlineResultWebpConverter() { }

    static void prepare(Object sendingMediaInfo, XposedLog log) {
        if (sendingMediaInfo == null) return;
        if (sendingMediaInfo instanceof String || sendingMediaInfo instanceof Number || sendingMediaInfo instanceof Boolean) {
            return;
        }
        try {
            Object result = resolveInlineResult(sendingMediaInfo);
            if (result == null) {
                return;
            }

            if (!isImageResult(result)) {
                return;
            }

            String originalMime = getStringField(result, "mime_type");
            if (originalMime == null) originalMime = getStringField(result, "mimeType");
            String type = getStringField(result, "type");

            // 1. Mark result MIME type as image/webp in Telegram TL object
            boolean mimeModified = setStringField(result, "mime_type", "image/webp");
            mimeModified |= setStringField(result, "mimeType", "image/webp");
            setStringField(result, "type", "photo");

            // Some versions put the MIME type in result.content, result.thumb, or result.document
            Object content = getField(result, "content");
            if (content != null) {
                mimeModified |= setStringField(content, "mime_type", "image/webp");
                mimeModified |= setStringField(content, "mimeType", "image/webp");
            }
            Object thumb = getField(result, "thumb");
            if (thumb != null) {
                mimeModified |= setStringField(thumb, "mime_type", "image/webp");
                mimeModified |= setStringField(thumb, "mimeType", "image/webp");
            }
            Object document = getField(result, "document");
            if (document != null) {
                mimeModified |= setStringField(document, "mime_type", "image/webp");
                mimeModified |= setStringField(document, "mimeType", "image/webp");
            }

            // 2. Mark SendingMediaInfo boolean and MIME flags
            setBooleanField(sendingMediaInfo, "isWebp", true);
            setBooleanField(sendingMediaInfo, "isSticker", true);
            setStringField(sendingMediaInfo, "mime_type", "image/webp");
            setStringField(sendingMediaInfo, "mimeType", "image/webp");
            setStringField(sendingMediaInfo, "mime", "image/webp");

            // 3. Resolve file path on SendingMediaInfo (path, filePath, originalPath, file)
            String path = getStringField(sendingMediaInfo, "path");
            if (path == null || path.length() == 0) {
                path = getStringField(sendingMediaInfo, "filePath");
            }
            if (path == null || path.length() == 0) {
                path = getStringField(sendingMediaInfo, "originalPath");
            }
            if (path == null || path.length() == 0) {
                path = getStringField(sendingMediaInfo, "file");
            }

            if (path != null && replaceWithWebp(sendingMediaInfo, path, log)) {
                log.info("[WEBP-SUCCESS] Converted inline bot image to WebP (type=" + type
                        + ", oldMime=" + originalMime + " -> image/webp, path=" + path + ")");
            } else {
                log.info("[WEBP-INFO] Marked inline bot image as image/webp (type=" + type
                        + ", oldMime=" + originalMime + ", download not materialised yet or already WebP)");
            }
        } catch (Throwable t) {
            log.warn("[WEBP-ERR] Inline WebP conversion failed: " + t.getMessage(), t);
        }
    }

    private static Object resolveInlineResult(Object item) {
        // 1. Check common field names
        String[] fieldNames = { "inlineResult", "botInlineResult", "botResult", "inline_result", "info", "result" };
        for (String name : fieldNames) {
            Object res = getField(item, name);
            if (res != null && isInlineResultObject(res)) {
                return res;
            }
        }
        // 2. If item itself is an inline result object
        if (isInlineResultObject(item)) {
            return item;
        }
        // 3. Reflectively inspect declared fields across class hierarchy for any BotInlineResult object
        for (Class<?> c = item.getClass(); c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object val = field.get(item);
                    if (val != null && isInlineResultObject(val)) {
                        return val;
                    }
                } catch (Throwable ignore) { }
            }
        }
        return null;
    }

    private static boolean isInlineResultObject(Object obj) {
        if (obj == null) return false;
        String clsName = obj.getClass().getName();
        if (clsName.contains("InlineResult") || clsName.contains("BotInlineResult")
                || clsName.contains("bot_inline_result") || clsName.contains("TL_botInlineResult")
                || clsName.contains("TL_botInlineMediaResult")) {
            return true;
        }
        // Heuristic check: object has "type" field and either "id" or "content" field characteristic of inline bot results
        return findField(obj.getClass(), "type") != null
                && (findField(obj.getClass(), "id") != null || findField(obj.getClass(), "content") != null);
    }

    private static boolean isImageResult(Object result) {
        String type = getStringField(result, "type");
        String mime = getStringField(result, "mime_type");
        if (mime == null) mime = getStringField(result, "mimeType");
        Object content = getField(result, "content");
        if (mime == null && content != null) {
            mime = getStringField(content, "mime_type");
            if (mime == null) mime = getStringField(content, "mimeType");
        }
        Object thumb = getField(result, "thumb");
        if (mime == null && thumb != null) {
            mime = getStringField(thumb, "mime_type");
            if (mime == null) mime = getStringField(thumb, "mimeType");
        }
        type = type == null ? "" : type.toLowerCase(Locale.ROOT);
        mime = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        return "photo".equals(type) || "image".equals(type) || "sticker".equals(type) || "pic".equals(type)
                || type.isEmpty() || mime.startsWith("image/");
    }

    private static boolean replaceWithWebp(Object info, String path, XposedLog log) {
        File source = new File(path);
        if (!source.isFile() || source.length() == 0 || path.toLowerCase(Locale.ROOT).endsWith(".webp")) {
            return false;
        }
        long startMs = System.currentTimeMillis();
        long originalSize = source.length();
        Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath());
        if (bitmap == null) {
            log.warn("[WEBP-WARN] Could not decode source bitmap at: " + path, null);
            return false;
        }
        File target = new File(source.getParentFile(), source.getName() + ".webp");
        try (FileOutputStream out = new FileOutputStream(target)) {
            Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.WEBP;
            if (!bitmap.compress(format, 90, out)) {
                target.delete();
                log.warn("[WEBP-WARN] Bitmap compression returned false for format: " + format, null);
                return false;
            }
            // Update all string path fields on info to point to target
            boolean pathUpdated = setStringField(info, "path", target.getAbsolutePath());
            pathUpdated |= setStringField(info, "filePath", target.getAbsolutePath());
            pathUpdated |= setStringField(info, "originalPath", target.getAbsolutePath());
            pathUpdated |= setStringField(info, "file", target.getAbsolutePath());
            if (!pathUpdated) {
                log.warn("[WEBP-WARN] Could not set path field on SendingMediaInfo", null);
            }
            long newSize = target.length();
            long elapsed = System.currentTimeMillis() - startMs;
            log.info("[WEBP-CONVERT] Transcoded " + source.getName() + " -> " + target.getName()
                    + " (" + (originalSize / 1024) + " KB -> " + (newSize / 1024) + " KB, "
                    + format + " format, duration: " + elapsed + " ms)");
            return true;
        } catch (Throwable t) {
            target.delete();
            log.warn("[WEBP-ERR] Could not write inline WebP to " + target.getAbsolutePath(), t);
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

    private static boolean setBooleanField(Object object, String name, boolean value) {
        Field field = findField(object.getClass(), name);
        if (field == null || field.getType() != boolean.class) return false;
        try { field.setAccessible(true); field.setBoolean(object, value); return true; } catch (Throwable ignored) { return false; }
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
