package io.github.adblock.tg;

import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import org.luckypray.dexkit.DexKitBridge;

/** Read-only reconnaissance: it logs verbosely, it does not block. */
class AdDiagnostics {

    private final XposedModule x;   // call hook()/log() through the module instance
    private final String tag;

    AdDiagnostics(XposedModule module, String tag) {
        this.x = module;
        this.tag = tag;
    }

    // Keywords that usually mark ad / sponsored surfaces in Telegram source.
    private static final String[] NAME_HINTS = {
        "sponsor", "sponsored", "ad", "ads", "advert", "promo", "promoted",
        "monetiz", "reveue", "revenue"
    };

    // Classes worth inspecting directly (extend as you reverse-engineer).
    private static final String[] CANDIDATE_CLASSES = {
        "org.telegram.messenger.MessageObject",
        "org.telegram.messenger.MessagesController",
        "org.telegram.ui.bots.BotAdView",
        "org.telegram.ui.ChatActivity",
        "org.telegram.ui.Cells.ChatMessageCell",
        "org.telegram.ui.Cells.DialogCell",
        "org.telegram.ui.Adapters.DialogsAdapter",
        "org.telegram.ui.Stories.StoriesController",
    };

    void run(ClassLoader cl, String apkPath) {
        long start = System.currentTimeMillis();
        logInfo("=== Telegram ad diagnostics start (Verbose Mode) ===");
        logInfo("[DIAG-ENV] SDK=" + Build.VERSION.SDK_INT + " | Device=" + Build.MANUFACTURER + " " + Build.MODEL);

        probeCandidateClasses(cl);

        if (DexKitAdFinder.isLibraryLoaded() && apkPath != null) {
            logInfo("[DIAG-DEXKIT] Running DexKit ad string reconnaissance...");
            try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
                DexKitAdFinder.runVerboseDiagnostics(bridge, cl, x, tag);
            } catch (Throwable t) {
                logWarn("[DIAG-DEXKIT] Diagnostics scan failed", t);
            }
        } else {
            logInfo("[DIAG-DEXKIT] Skipping DexKit scan (native library unavailable)");
        }

        installImpressionTracers(cl);

        long elapsed = System.currentTimeMillis() - start;
        logInfo("=== Telegram ad diagnostics end (Elapsed: " + elapsed + " ms) ===");
    }

    /* (A) Reflectively dump ad-ish members of known classes. */
    private void probeCandidateClasses(ClassLoader cl) {
        int foundMethods = 0;
        int foundFields = 0;
        for (String name : CANDIDATE_CLASSES) {
            try {
                Class<?> c = cl.loadClass(name);
                for (Method m : c.getDeclaredMethods()) {
                    if (matches(m.getName())) {
                        logInfo("[DIAG-REFLECT] METHOD  " + c.getName() + "#" + m.getName()
                                + sig(m) + " -> " + m.getReturnType().getSimpleName()
                                + " [modifiers=" + Modifier.toString(m.getModifiers()) + "]");
                        foundMethods++;
                    }
                }
                for (Field f : c.getDeclaredFields()) {
                    if (matches(f.getName())) {
                        logInfo("[DIAG-REFLECT] FIELD   " + c.getName() + "#" + f.getName()
                                + " : " + f.getType().getSimpleName()
                                + " [modifiers=" + Modifier.toString(f.getModifiers()) + "]");
                        foundFields++;
                    }
                }
            } catch (Throwable ignore) {
                // class not present in this build/version — fine.
            }
        }
        logInfo("[DIAG-REFLECT] Candidate class probe completed: " + foundMethods + " methods, " + foundFields + " fields discovered.");
    }

    /* (B) Hook impression/click reporters so we see *where* ads are shown. */
    private void installImpressionTracers(ClassLoader cl) {
        traceMethodsNamed(cl, "org.telegram.ui.ChatActivity", "logSponsoredClicked");
        traceMethodsNamed(cl, "org.telegram.messenger.MessagesController", "markSponsoredAsRead");
        traceMethodsNamed(cl, "org.telegram.messenger.MessagesController", "getSponsoredMessages");
        // BotAdView constructor: anything that builds an ad card.
        traceConstructors(cl, "org.telegram.ui.bots.BotAdView");
    }

    private void traceMethodsNamed(ClassLoader cl, String cls, String method) {
        try {
            Class<?> c = cl.loadClass(cls);
            int count = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(method)) continue;
                x.hook(m)
                 .setPriority(XposedInterface.PRIORITY_LOWEST)
                 .intercept(chain -> {
                     logInfo("[DIAG-TRACE] CALLED " + cls + "#" + method
                             + "\n" + stack());
                     return chain.proceed();   // observe only, do not block
                 });
                count++;
                logInfo("[DIAG-TRACE] Installed tracer on " + cls + "#" + method + sig(m));
            }
            if (count == 0) {
                logInfo("[DIAG-TRACE] No methods matching " + cls + "#" + method + " found to trace");
            }
        } catch (Throwable ignore) {
            logInfo("[DIAG-TRACE] Target class not found for tracing: " + cls);
        }
    }

    private void traceConstructors(ClassLoader cl, String cls) {
        try {
            Class<?> c = cl.loadClass(cls);
            int count = 0;
            for (java.lang.reflect.Constructor<?> ctor : c.getDeclaredConstructors()) {
                x.hook(ctor)
                 .setPriority(XposedInterface.PRIORITY_LOWEST)
                 .intercept(chain -> {
                     logInfo("[DIAG-TRACE] NEW " + cls + "\n" + stack());
                     return chain.proceed();
                 });
                count++;
            }
            logInfo("[DIAG-TRACE] Tracing " + count + " constructors of " + cls);
        } catch (Throwable ignore) {
            logInfo("[DIAG-TRACE] Target class not found for constructor tracing: " + cls);
        }
    }

    /* helpers */
    private static boolean matches(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String h : NAME_HINTS) {
            // word-ish match to avoid matching "thread", "added", "head"...
            if (n.equals(h) || n.startsWith(h) || n.contains(h.length() > 2 ? h : (h + "_"))) {
                if (h.length() <= 2) {            // "ad"/"ads": require boundary
                    if (n.equals("ad") || n.equals("ads")
                            || n.startsWith("ad_") || n.endsWith("ad")
                            || n.contains("sponsoredad")) return true;
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    private static String sig(Method m) {
        StringBuilder b = new StringBuilder("(");
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) b.append(", ");
            b.append(p[i].getSimpleName());
        }
        return b.append(")").toString();
    }

    private static String stack() {
        StringBuilder b = new StringBuilder();
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        // skip the first few VM/hook frames
        for (int i = 3; i < Math.min(st.length, 18); i++) {
            b.append("    at ").append(st[i]).append('\n');
        }
        return b.toString();
    }

    private void logInfo(String msg) {
        x.log(Log.INFO, tag, msg);
        Log.i(tag, msg);
    }

    private void logWarn(String msg, Throwable t) {
        if (t != null) {
            x.log(Log.WARN, tag, msg, t);
            Log.w(tag, msg, t);
        } else {
            x.log(Log.WARN, tag, msg);
            Log.w(tag, msg);
        }
    }
}
