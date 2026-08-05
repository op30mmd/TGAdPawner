package io.github.adblock.tg;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

/**
 * High-performance deobfuscating resolution and hooking engine using DexKit (https://github.com/LuckyPray/DexKit).
 * Provides both exact signature lookups and string-based heuristic discoveries for target ad surfaces.
 */
public final class DexKitAdFinder {

    private static boolean libraryLoaded = false;
    private static String loadError = null;

    static {
        try {
            System.loadLibrary("dexkit");
            libraryLoaded = true;
        } catch (Throwable t) {
            libraryLoaded = false;
            loadError = t.getMessage();
            Log.w("TGAdBlock", "DexKit native library not loaded: " + loadError);
        }
    }

    private DexKitAdFinder() { }

    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    public static String getLoadError() {
        return loadError;
    }

    /**
     * Resolves and hooks Telegram ad surfaces using DexKit deobfuscation queries.
     *
     * @param cl            The package ClassLoader
     * @param apkPath       Source APK file path
     * @param module        XposedModule instance for hook registration and logging
     * @param hookedMethods Shared set of already-hooked methods to prevent duplicate hooking
     * @param tag           Logging tag
     * @return Number of successful hooks installed via DexKit in this session
     */
    public static int findAndHookAdSurfaces(@NonNull ClassLoader cl,
                                            @NonNull String apkPath,
                                            @NonNull XposedModule module,
                                            @NonNull Set<Method> hookedMethods,
                                            @NonNull String tag) {
        if (!libraryLoaded) {
            module.log(Log.WARN, tag, "[DEXKIT] Cannot run DexKit queries: native library unavailable (" + loadError + ")");
            return 0;
        }

        long start = System.currentTimeMillis();
        int hooksInstalled = 0;

        module.log(Log.INFO, tag, "[DEXKIT] Opening DexKitBridge for APK: " + apkPath);
        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
            long openElapsed = System.currentTimeMillis() - start;
            module.log(Log.INFO, tag, "[DEXKIT] DexKitBridge initialized in " + openElapsed + " ms");

            hooksInstalled += hookMessageObject(bridge, cl, module, hookedMethods, tag);
            hooksInstalled += hookMessagesController(bridge, cl, module, hookedMethods, tag);
            hooksInstalled += hookBotAdView(bridge, cl, module, hookedMethods, tag);
            hooksInstalled += hookChatActivityAndCells(bridge, cl, module, hookedMethods, tag);
            hooksInstalled += hookInlineWebpConverter(bridge, cl, module, hookedMethods, tag);

            long totalElapsed = System.currentTimeMillis() - start;
            module.log(Log.INFO, tag, "[DEXKIT] DexKit resolution completed in " + totalElapsed + " ms. Total DexKit hooks: " + hooksInstalled);
        } catch (Throwable t) {
            module.log(Log.WARN, tag, "[DEXKIT] Error during DexKit query resolution", t);
        }

        return hooksInstalled;
    }

    /**
     * Performs read-only diagnostics scanning for sponsored and advertisement method signatures in target classes.
     */
    public static void runVerboseDiagnostics(@NonNull DexKitBridge bridge,
                                             @NonNull ClassLoader cl,
                                             @NonNull XposedModule module,
                                             @NonNull String tag) {
        String[] targetClasses = {
                "org.telegram.messenger.MessageObject",
                "org.telegram.messenger.MessagesController",
                "org.telegram.ui.ChatActivity",
                "org.telegram.ui.bots.BotAdView",
                "org.telegram.ui.Cells.ChatMessageCell",
                "org.telegram.ui.Cells.DialogCell"
        };

        String[] adStrings = { "sponsored", "sponsor", "ad_view", "promo", "monetiz" };

        module.log(Log.INFO, tag, "[DEXKIT-DIAG] Scanning target classes for ad string references via DexKit...");
        for (String className : targetClasses) {
            try {
                long qStart = System.currentTimeMillis();
                Collection<MethodData> matchedMethods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass(className)
                                .usingStrings(adStrings)
                        )
                );
                long qElapsed = System.currentTimeMillis() - qStart;
                module.log(Log.INFO, tag, "[DEXKIT-DIAG] Class " + className + " -> " + matchedMethods.size()
                        + " methods referencing ad strings (query time: " + qElapsed + " ms)");
                for (MethodData md : matchedMethods) {
                    module.log(Log.INFO, tag, "[DEXKIT-DIAG]   -> " + md.getClassName() + "#"
                            + md.getName() + " descriptor: " + md.getDescriptor()
                            + " | returnType: " + md.getReturnType());
                }
            } catch (Throwable t) {
                module.log(Log.WARN, tag, "[DEXKIT-DIAG] Query failed for class: " + className, t);
            }
        }
    }

    /* ---------- Layer 1: MessageObject.isSponsored ---------- */
    private static int hookMessageObject(DexKitBridge bridge, ClassLoader cl, XposedModule module,
                                         Set<Method> hookedMethods, String tag) {
        int count = 0;
        try {
            long qStart = System.currentTimeMillis();
            Collection<MethodData> results = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass("org.telegram.messenger.MessageObject")
                            .name("isSponsored")
                    )
            );
            long qElapsed = System.currentTimeMillis() - qStart;
            module.log(Log.INFO, tag, "[DEXKIT] MessageObject.isSponsored lookup: " + results.size()
                    + " matches (" + qElapsed + " ms)");

            for (MethodData md : results) {
                count += installHook(md, cl, module, hookedMethods, tag,
                        chain -> {
                            Log.d(tag, "[INTERCEPT-DEXKIT] MessageObject#isSponsored -> forced false");
                            return Boolean.FALSE;
                        }, "MessageObject.isSponsored");
            }

            // Fallback heuristics: check any no-arg boolean method in MessageObject referencing "isSponsored" string
            if (results.isEmpty()) {
                module.log(Log.INFO, tag, "[DEXKIT] Performing heuristic search for obfuscated MessageObject.isSponsored...");
                Collection<MethodData> heuristic = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass("org.telegram.messenger.MessageObject")
                                .returnType("boolean")
                                .usingStrings("isSponsored")
                        )
                );
                for (MethodData md : heuristic) {
                    count += installHook(md, cl, module, hookedMethods, tag,
                            chain -> {
                                Log.d(tag, "[INTERCEPT-DEXKIT] Obfuscated MessageObject#isSponsored (" + md.getName() + ") -> forced false");
                                return Boolean.FALSE;
                            }, "MessageObject.isSponsored(heuristic)");
                }
            }
        } catch (Throwable t) {
            module.log(Log.WARN, tag, "[DEXKIT] Failed to resolve MessageObject hooks", t);
        }
        return count;
    }

    /* ---------- Layer 2: MessagesController.getSponsoredMessages & promo ---------- */
    private static int hookMessagesController(DexKitBridge bridge, ClassLoader cl, XposedModule module,
                                              Set<Method> hookedMethods, String tag) {
        int count = 0;
        try {
            long qStart = System.currentTimeMillis();
            Collection<MethodData> sponsoredMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass("org.telegram.messenger.MessagesController")
                            .name("getSponsoredMessages")
                    )
            );
            module.log(Log.INFO, tag, "[DEXKIT] MessagesController.getSponsoredMessages lookup: "
                    + sponsoredMethods.size() + " matches (" + (System.currentTimeMillis() - qStart) + " ms)");

            for (MethodData md : sponsoredMethods) {
                count += installHook(md, cl, module, hookedMethods, tag,
                        chain -> {
                            Log.d(tag, "[INTERCEPT-DEXKIT] MessagesController#getSponsoredMessages -> suppressed (null)");
                            return null;
                        }, "MessagesController.getSponsoredMessages");
            }

            // isSponsoredDisabled
            Collection<MethodData> disabledFlag = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass("org.telegram.messenger.MessagesController")
                            .name("isSponsoredDisabled")
                    )
            );
            for (MethodData md : disabledFlag) {
                count += installHook(md, cl, module, hookedMethods, tag,
                        chain -> {
                            Log.d(tag, "[INTERCEPT-DEXKIT] MessagesController#isSponsoredDisabled -> forced true");
                            return Boolean.TRUE;
                        }, "MessagesController.isSponsoredDisabled");
            }

            // checkPromoInfo / checkPromoInfoInternal / isPromoDialog
            String[] promoMethods = { "checkPromoInfo", "checkPromoInfoInternal" };
            for (String mName : promoMethods) {
                Collection<MethodData> promo = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass("org.telegram.messenger.MessagesController")
                                .name(mName)
                        )
                );
                for (MethodData md : promo) {
                    count += installHook(md, cl, module, hookedMethods, tag,
                            chain -> null, "MessagesController." + mName);
                }
            }

            Collection<MethodData> isPromoDialog = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass("org.telegram.messenger.MessagesController")
                            .name("isPromoDialog")
                    )
            );
            for (MethodData md : isPromoDialog) {
                count += installHook(md, cl, module, hookedMethods, tag,
                        chain -> Boolean.FALSE, "MessagesController.isPromoDialog");
            }
        } catch (Throwable t) {
            module.log(Log.WARN, tag, "[DEXKIT] Failed to resolve MessagesController hooks", t);
        }
        return count;
    }

    /* ---------- Layer 3: BotAdView.set ---------- */
    private static int hookBotAdView(DexKitBridge bridge, ClassLoader cl, XposedModule module,
                                     Set<Method> hookedMethods, String tag) {
        int count = 0;
        try {
            Collection<MethodData> botAdMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass("org.telegram.ui.bots.BotAdView")
                            .name("set")
                    )
            );
            for (MethodData md : botAdMethods) {
                count += installHook(md, cl, module, hookedMethods, tag,
                        chain -> {
                            Object self = chain.getThisObject();
                            if (self instanceof View) {
                                View v = (View) self;
                                v.post(() -> {
                                    v.setVisibility(View.GONE);
                                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                                    if (lp != null) {
                                        lp.height = 0;
                                        v.setLayoutParams(lp);
                                    }
                                });
                                Log.d(tag, "[INTERCEPT-DEXKIT] BotAdView#set -> collapsed ad container to height 0");
                            }
                            return null;
                        }, "BotAdView.set");
            }
        } catch (Throwable t) {
            module.log(Log.WARN, tag, "[DEXKIT] Failed to resolve BotAdView hooks", t);
        }
        return count;
    }

    /* ---------- Layer 4: ChatActivity & ChatMessageCell surfaces ---------- */
    private static int hookChatActivityAndCells(DexKitBridge bridge, ClassLoader cl, XposedModule module,
                                                Set<Method> hookedMethods, String tag) {
        int count = 0;
        try {
            Collection<MethodData> getCount = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass("org.telegram.ui.ChatActivity")
                            .name("getSponsoredMessagesCount")
                    )
            );
            for (MethodData md : getCount) {
                count += installHook(md, cl, module, hookedMethods, tag,
                        chain -> Integer.valueOf(0), "ChatActivity.getSponsoredMessagesCount");
            }

            Collection<MethodData> addSponsored = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass("org.telegram.ui.ChatActivity")
                            .name("addSponsoredMessages")
                    )
            );
            for (MethodData md : addSponsored) {
                count += installHook(md, cl, module, hookedMethods, tag,
                        chain -> null, "ChatActivity.addSponsoredMessages");
            }

            Collection<MethodData> setVisible = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass("org.telegram.ui.Cells.ChatMessageCell")
                            .name("setSponsoredMessageVisible")
                    )
            );
            for (MethodData md : setVisible) {
                count += installHook(md, cl, module, hookedMethods, tag,
                        chain -> null, "ChatMessageCell.setSponsoredMessageVisible");
            }
        } catch (Throwable t) {
            module.log(Log.WARN, tag, "[DEXKIT] Failed to resolve ChatActivity/Cell hooks", t);
        }
        return count;
    }

    /* ---------- Layer 5: Inline Bot Result WebP Converter ---------- */
    private static int hookInlineWebpConverter(DexKitBridge bridge, ClassLoader cl, XposedModule module,
                                               Set<Method> hookedMethods, String tag) {
        int count = 0;
        try {
            String[] methods = { "sendInlineBotResult", "prepareSendingMedia" };
            for (String mName : methods) {
                Collection<MethodData> helperMethods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass("org.telegram.messenger.SendMessagesHelper")
                                .name(mName)
                        )
                );
                for (MethodData md : helperMethods) {
                    count += installHook(md, cl, module, hookedMethods, tag,
                            chain -> {
                                InlineResultWebpConverter.XposedLog converterLog = new InlineResultWebpConverter.XposedLog() {
                                    @Override
                                    public void info(String message) {
                                        module.log(Log.INFO, tag, "[WEBP-INFO] " + message);
                                    }

                                    @Override
                                    public void warn(String message, Throwable error) {
                                        module.log(Log.WARN, tag, "[WEBP-WARN] " + message, error);
                                    }
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
                            }, "SendMessagesHelper." + mName);
                }
            }
        } catch (Throwable t) {
            module.log(Log.WARN, tag, "[DEXKIT] Failed to resolve inline WebP converter hooks", t);
        }
        return count;
    }

    private static int installHook(MethodData methodData, ClassLoader cl, XposedModule module,
                                   Set<Method> hookedMethods, String tag,
                                   XposedInterface.Hooker hooker, String desc) {
        try {
            Method m = methodData.getMethodInstance(cl);
            if (m == null) {
                module.log(Log.WARN, tag, "[DEXKIT-HOOK] Resolved null Method for: " + methodData);
                return 0;
            }
            if (hookedMethods.contains(m)) {
                module.log(Log.INFO, tag, "[DEXKIT-SKIP] Already hooked method: " + mdSignature(m));
                return 0;
            }
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(hooker);
            hookedMethods.add(m);
            module.log(Log.INFO, tag, "[DEXKIT-HOOK] Successfully hooked " + desc + ": " + mdSignature(m));
            return 1;
        } catch (Throwable t) {
            module.log(Log.WARN, tag, "[DEXKIT-ERR] Failed to hook " + desc + " (" + methodData + ")", t);
            return 0;
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

    private static String mdSignature(Method m) {
        StringBuilder b = new StringBuilder(m.getDeclaringClass().getName()).append("#").append(m.getName()).append("(");
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) b.append(", ");
            b.append(params[i].getSimpleName());
        }
        return b.append(") -> ").append(m.getReturnType().getSimpleName()).toString();
    }
}
