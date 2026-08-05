package io.github.adblock.tg;

import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

public class TelegramAdBlocker extends XposedModule {

    private static final String TAG = "TGAdBlock";

    public TelegramAdBlocker() { }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        logInfo("[INIT] TelegramAdBlocker module loaded in process: " + param.getProcessName()
                + " (systemServer=" + param.isSystemServer() + ")");
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        String packageName = param.getPackageName();
        if (!packageName.startsWith("org.telegram")
                && !packageName.equals("org.telegram.plus")
                && !packageName.equals("momo.gram")
                && !packageName.equals("tw.nekomimi.nekogram")) {
            return;
        }
        if (!param.isFirstPackage()) {
            logVerbose("[SKIP] Skipping non-first package load for: " + packageName);
            return;
        }

        ClassLoader cl = param.getDefaultClassLoader();
        String apkPath = param.getApplicationInfo().sourceDir;

        logInfo("[INIT-START] Initializing TGAdPawner for package: " + packageName
                + " | APK: " + apkPath
                + " | Android SDK: " + Build.VERSION.SDK_INT
                + " | Device: " + Build.MANUFACTURER + " " + Build.MODEL);

        long startTime = System.currentTimeMillis();
        Set<Method> hookedMethods = new HashSet<>();
        int dexKitHooks = 0;

        // Attempt primary resolution and hooking via DexKit deobfuscation engine
        if (DexKitAdFinder.isLibraryLoaded() && apkPath != null) {
            logInfo("[DEXKIT-START] Starting DexKit deobfuscation resolution...");
            dexKitHooks = DexKitAdFinder.findAndHookAdSurfaces(cl, apkPath, this, hookedMethods, TAG);
        } else {
            logWarn("[DEXKIT-SKIP] DexKit unavailable (" + DexKitAdFinder.getLoadError()
                    + "). Relying entirely on reflection fallback hooks.", null);
        }

        // Apply fallback reflection hooks for any unhooked surfaces
        logInfo("[REFLECT-START] Running reflection fallback hooks for remaining target surfaces...");
        hookIsSponsored(cl, hookedMethods);
        hookGetSponsoredMessages(cl, hookedMethods);
        hookBotAdView(cl, hookedMethods);
        hookExtraSponsoredSurfaces(cl, hookedMethods);
        hookPromoSponsor(cl, hookedMethods);
        hookInlineBotResultWebp(cl, hookedMethods);

        long totalElapsed = System.currentTimeMillis() - startTime;
        logInfo("[INIT-SUMMARY] TelegramAdBlocker initialization complete for " + packageName
                + ". Total unique methods hooked: " + hookedMethods.size()
                + " (" + dexKitHooks + " via DexKit, " + (hookedMethods.size() - dexKitHooks) + " via Reflection fallback). "
                + "Total initialization time: " + totalElapsed + " ms");
    }

    /* ---------- Layer 1: pretend nothing is sponsored ---------- */
    private void hookIsSponsored(ClassLoader cl, Set<Method> hookedMethods) {
        try {
            Class<?> mo = cl.loadClass("org.telegram.messenger.MessageObject");
            Method m = mo.getDeclaredMethod("isSponsored");
            if (installReflectHook(m, hookedMethods, chain -> {
                logVerbose("[INTERCEPT-REFLECT] MessageObject#isSponsored -> forced false");
                return Boolean.FALSE;
            }, "MessageObject.isSponsored")) {
                logInfo("[REFLECT-SUCCESS] Hooked MessageObject.isSponsored: " + sig(m));
            }
        } catch (Throwable t) {
            logWarn("[REFLECT-MISS] isSponsored reflection hook failed or class not found", t);
        }
    }

    /* ---------- Layer 2: empty the sponsored fetch ---------- */
    private void hookGetSponsoredMessages(ClassLoader cl, Set<Method> hookedMethods) {
        try {
            Class<?> mc = cl.loadClass("org.telegram.messenger.MessagesController");
            int found = 0;
            for (Method m : mc.getDeclaredMethods()) {
                if (m.getName().equals("getSponsoredMessages")) {
                    if (installReflectHook(m, hookedMethods, chain -> {
                        logVerbose("[INTERCEPT-REFLECT] MessagesController#getSponsoredMessages -> suppressed (null)");
                        return null;
                    }, "MessagesController.getSponsoredMessages")) {
                        logInfo("[REFLECT-SUCCESS] Hooked MessagesController.getSponsoredMessages: " + sig(m));
                        found++;
                    }
                }
            }
            if (found == 0) {
                logInfo("[REFLECT-INFO] No unhooked getSponsoredMessages overloads found in MessagesController");
            }
        } catch (Throwable t) {
            logWarn("[REFLECT-MISS] getSponsoredMessages reflection hook failed", t);
        }
    }

    /* ---------- Layer 3: collapse the BotAdView card ---------- */
    private void hookBotAdView(ClassLoader cl, Set<Method> hookedMethods) {
        try {
            Class<?> botAd = cl.loadClass("org.telegram.ui.bots.BotAdView");
            int found = 0;
            for (Method m : botAd.getDeclaredMethods()) {
                if (m.getName().equals("set")) {
                    if (installReflectHook(m, hookedMethods, chain -> {
                        Object self = chain.getThisObject();
                        if (self instanceof View) {
                            View v = (View) self;
                            v.post(() -> {
                                v.setVisibility(View.GONE);
                                ViewGroup.LayoutParams lp = v.getLayoutParams();
                                if (lp != null) { lp.height = 0; v.setLayoutParams(lp); }
                            });
                            logVerbose("[INTERCEPT-REFLECT] BotAdView#set -> collapsed ad container to height 0");
                        }
                        return null;
                    }, "BotAdView.set")) {
                        logInfo("[REFLECT-SUCCESS] Hooked BotAdView.set: " + sig(m));
                        found++;
                    }
                }
            }
            if (found == 0) {
                logInfo("[REFLECT-INFO] No unhooked BotAdView#set overloads found");
            }
        } catch (Throwable t) {
            logWarn("[REFLECT-MISS] BotAdView reflection hook failed", t);
        }
    }

    /**
     * Runs before Telegram consumes SendingMediaInfo lists. Hook covers the legacy 'sendInlineBotResult'
     * alongside the modern equivalent 'prepareSendingMedia'.
     */
    private void hookInlineBotResultWebp(ClassLoader cl, Set<Method> hookedMethods) {
        try {
            Class<?> helper = cl.loadClass("org.telegram.messenger.SendMessagesHelper");
            int hooks = 0;
            for (Method method : helper.getDeclaredMethods()) {
                String name = method.getName();
                if (!name.equals("sendInlineBotResult") && !name.equals("prepareSendingMedia")) {
                    continue;
                }

                if (installReflectHook(method, hookedMethods, chain -> {
                    InlineResultWebpConverter.XposedLog converterLog = new InlineResultWebpConverter.XposedLog() {
                        @Override public void info(String message) { logInfo("[WEBP-INFO] " + message); }
                        @Override public void warn(String message, Throwable error) { logWarn("[WEBP-WARN] " + message, error); }
                    };

                    for (Object argument : chain.getArgs()) {
                        if (argument == null) continue;
                        if (argument instanceof java.util.ArrayList) {
                            for (Object item : (java.util.ArrayList<?>) argument) {
                                if (item != null && hasField(item.getClass(), "inlineResult")) {
                                    InlineResultWebpConverter.prepare(item, converterLog);
                                }
                            }
                        } else if (hasField(argument.getClass(), "inlineResult")) {
                            InlineResultWebpConverter.prepare(argument, converterLog);
                        }
                    }
                    return chain.proceed();
                }, "SendMessagesHelper." + name)) {
                    hooks++;
                    logInfo("[REFLECT-SUCCESS] Hooked inline WebP method: " + sig(method));
                }
            }
            logInfo(hooks == 0
                    ? "[REFLECT-INFO] No unhooked SendMessagesHelper inline bot methods found"
                    : "[REFLECT-SUCCESS] Hooked SendMessagesHelper inline bot result (" + hooks + " overloads via Reflection)");
        } catch (Throwable t) {
            logWarn("[REFLECT-MISS] Inline WebP reflection hook failed", t);
        }
    }

    private static boolean hasField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                c.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException ignored) { }
        }
        return false;
    }

    private void hookExtraSponsoredSurfaces(ClassLoader cl, Set<Method> hookedMethods) {
        safeHookByName(cl, "org.telegram.messenger.MessagesController", "isSponsoredDisabled",
                chain -> {
                    logVerbose("[INTERCEPT-REFLECT] MessagesController#isSponsoredDisabled -> forced true");
                    return Boolean.TRUE;
                }, hookedMethods);
        safeHookByName(cl, "org.telegram.ui.ChatActivity", "getSponsoredMessagesCount",
                chain -> Integer.valueOf(0), hookedMethods);
        safeHookByName(cl, "org.telegram.ui.ChatActivity", "addSponsoredMessages",
                chain -> null, hookedMethods);
        safeHookByName(cl, "org.telegram.ui.Cells.ChatMessageCell", "setSponsoredMessageVisible",
                chain -> null, hookedMethods);
    }

    private void hookPromoSponsor(ClassLoader cl, Set<Method> hookedMethods) {
        safeHookByName(cl, "org.telegram.messenger.MessagesController", "checkPromoInfo",
                chain -> null, hookedMethods);
        safeHookByName(cl, "org.telegram.messenger.MessagesController", "checkPromoInfoInternal",
                chain -> null, hookedMethods);
        safeHookByName(cl, "org.telegram.messenger.MessagesController", "isPromoDialog",
                chain -> Boolean.FALSE, hookedMethods);

        try {
            Class<?> mc = cl.loadClass("org.telegram.messenger.MessagesController");
            Method getInstance = mc.getMethod("getInstance", int.class);
            Method removePromo = mc.getDeclaredMethod("removePromoDialog");
            int clearedCount = 0;
            for (int a = 0; a < 8; a++) {
                try {
                    Object inst = getInstance.invoke(null, a);
                    if (inst != null) {
                        removePromo.invoke(inst);
                        clearedCount++;
                    }
                } catch (Throwable ignore) { }
            }
            logInfo("[REFLECT-CLEANUP] Cleared cached promo dialogs on " + clearedCount + " controller instances");
        } catch (Throwable t) {
            logWarn("[REFLECT-MISS] removePromoDialog cleanup failed", t);
        }
    }

    private void safeHookByName(ClassLoader cl, String cls, String method, XposedInterface.Hooker hooker, Set<Method> hookedMethods) {
        try {
            Class<?> c = cl.loadClass(cls);
            boolean found = false;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(method)) {
                    if (installReflectHook(m, hookedMethods, hooker, cls + "#" + method)) {
                        logInfo("[REFLECT-SUCCESS] Hooked " + cls + "#" + method + sig(m));
                        found = true;
                    }
                }
            }
            if (!found) {
                logVerbose("[REFLECT-INFO] No unhooked method " + cls + "#" + method + " needed");
            }
        } catch (Throwable t) {
            logVerbose("[REFLECT-MISS] Target class/method not found or failed: " + cls + "#" + method + " (" + t.getMessage() + ")");
        }
    }

    private boolean installReflectHook(Method m, Set<Method> hookedMethods, XposedInterface.Hooker hooker, String desc) {
        if (hookedMethods.contains(m)) {
            logVerbose("[REFLECT-SKIP] Already hooked via DexKit: " + sig(m));
            return false;
        }
        try {
            hook(m).setPriority(XposedInterface.PRIORITY_HIGHEST).intercept(hooker);
            hookedMethods.add(m);
            return true;
        } catch (Throwable t) {
            logWarn("[REFLECT-ERR] Failed to hook " + desc + ": " + sig(m), t);
            return false;
        }
    }

    private static String sig(Method m) {
        StringBuilder b = new StringBuilder("(");
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) b.append(", ");
            b.append(p[i].getSimpleName());
        }
        return b.append(") -> ").append(m.getReturnType().getSimpleName()).toString();
    }

    private void logInfo(String msg) {
        log(Log.INFO, TAG, msg);
        Log.i(TAG, msg);
    }

    private void logWarn(String msg, Throwable t) {
        if (t != null) {
            log(Log.WARN, TAG, msg, t);
            Log.w(TAG, msg, t);
        } else {
            log(Log.WARN, TAG, msg);
            Log.w(TAG, msg);
        }
    }

    private void logVerbose(String msg) {
        log(Log.INFO, TAG, msg);
        Log.d(TAG, msg);
    }
}
