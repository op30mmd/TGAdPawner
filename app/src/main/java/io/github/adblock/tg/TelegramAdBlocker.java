package io.github.adblock.tg;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

public class TelegramAdBlocker extends XposedModule {

    private static final String TAG = "TGAdBlock";

    public TelegramAdBlocker() { }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        if (!param.getPackageName().startsWith("org.telegram")
                && !param.getPackageName().equals("org.telegram.plus")
                && !param.getPackageName().equals("momo.gram")
                && !param.getPackageName().equals("tw.nekomimi.nekogram")) {
            return;
        }
        if (!param.isFirstPackage()) return;

        ClassLoader cl = param.getDefaultClassLoader();
        log(Log.INFO, TAG, "Hooking " + param.getPackageName());

        hookIsSponsored(cl);
        hookGetSponsoredMessages(cl);
        hookBotAdView(cl);
        hookExtraSponsoredSurfaces(cl);
        hookPromoSponsor(cl);
        hookInlineBotResultWebp(cl);
    }

    /* ---------- Layer 1: pretend nothing is sponsored ---------- */
    private void hookIsSponsored(ClassLoader cl) {
        try {
            Class<?> mo = cl.loadClass("org.telegram.messenger.MessageObject");
            Method m = mo.getDeclaredMethod("isSponsored");
            hook(m)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept(chain -> Boolean.FALSE);
            log(Log.INFO, TAG, "Hooked MessageObject.isSponsored");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "isSponsored hook failed", t);
        }
    }

    /* ---------- Layer 2: empty the sponsored fetch ---------- */
    private void hookGetSponsoredMessages(ClassLoader cl) {
        try {
            Class<?> mc = cl.loadClass("org.telegram.messenger.MessagesController");
            for (Method m : mc.getDeclaredMethods()) {
                if (m.getName().equals("getSponsoredMessages")) {
                    hook(m)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .intercept(chain -> null);
                    log(Log.INFO, TAG, "Hooked getSponsoredMessages " + m);
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "getSponsoredMessages hook failed", t);
        }
    }

    /* ---------- Layer 3: collapse the BotAdView card ---------- */
    private void hookBotAdView(ClassLoader cl) {
        try {
            Class<?> botAd = cl.loadClass("org.telegram.ui.bots.BotAdView");
            for (Method m : botAd.getDeclaredMethods()) {
                if (m.getName().equals("set")) {
                    hook(m).intercept(chain -> {
                        Object self = chain.getThisObject();
                        if (self instanceof View) {
                            View v = (View) self;
                            v.post(() -> {
                                v.setVisibility(View.GONE);
                                ViewGroup.LayoutParams lp = v.getLayoutParams();
                                if (lp != null) { lp.height = 0; v.setLayoutParams(lp); }
                            });
                        }
                        return null;
                    });
                    log(Log.INFO, TAG, "Hooked BotAdView.set");
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "BotAdView hook failed", t);
        }
    }

    /**
     * Runs before Telegram consumes inline bot results. Hook covers the legacy 'sendInlineBotResult'
     * alongside new equivalents 'prepareSendingBotContextResult' and 'prepareSendingMedia'.
     */
    private void hookInlineBotResultWebp(ClassLoader cl) {
        try {
            Class<?> helper = cl.loadClass("org.telegram.messenger.SendMessagesHelper");
            int hooks = 0;
            for (Method method : helper.getDeclaredMethods()) {
                String name = method.getName();
                if (!name.equals("sendInlineBotResult") && 
                    !name.equals("prepareSendingBotContextResult") && 
                    !name.equals("prepareSendingMedia")) {
                    continue;
                }
                
                hook(method).setPriority(XposedInterface.PRIORITY_HIGHEST).intercept(chain -> {
                    InlineResultWebpConverter.XposedLog converterLog = new InlineResultWebpConverter.XposedLog() {
                        @Override public void info(String message) { log(Log.INFO, TAG, message); }
                        @Override public void warn(String message, Throwable error) { log(Log.WARN, TAG, message, error); }
                    };

                    for (Object argument : chain.getArgs()) {
                        if (argument == null) continue;
                        
                        // Handled by prepareSendingMedia (passes an ArrayList<SendingMediaInfo>)
                        if (argument instanceof java.util.ArrayList) {
                            for (Object item : (java.util.ArrayList<?>) argument) {
                                if (item != null && hasField(item.getClass(), "inlineResult")) {
                                    InlineResultWebpConverter.prepare(item, converterLog);
                                }
                            }
                        }
                        // Handled by older versions passing SendingMediaInfo directly
                        else if (hasField(argument.getClass(), "inlineResult")) {
                            InlineResultWebpConverter.prepare(argument, converterLog);
                        }
                        // Handled by prepareSendingBotContextResult (passes BotInlineResult directly)
                        // Checking field names natively bypasses class name obfuscation/case mismatch
                        else if (hasField(argument.getClass(), "send_message") && hasField(argument.getClass(), "type")) {
                            InlineResultWebpConverter.prepareBotInlineResult(argument, converterLog);
                        }
                    }
                    return chain.proceed();
                });
                hooks++;
            }
            log(Log.INFO, TAG, hooks == 0 ? "NOT FOUND SendMessagesHelper inline bot methods"
                    : "Hooked SendMessagesHelper inline bot result (" + hooks + " overloads)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Inline WebP hook failed", t);
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

    private void hookExtraSponsoredSurfaces(ClassLoader cl) {
        safeHookByName(cl, "org.telegram.messenger.MessagesController", "isSponsoredDisabled", chain -> Boolean.TRUE);
        safeHookByName(cl, "org.telegram.ui.ChatActivity", "getSponsoredMessagesCount", chain -> Integer.valueOf(0));
        safeHookByName(cl, "org.telegram.ui.ChatActivity", "addSponsoredMessages", chain -> null);
        safeHookByName(cl, "org.telegram.ui.Cells.ChatMessageCell", "setSponsoredMessageVisible", chain -> null);
    }

    private void hookPromoSponsor(ClassLoader cl) {
        safeHookByName(cl, "org.telegram.messenger.MessagesController", "checkPromoInfo", chain -> null);
        safeHookByName(cl, "org.telegram.messenger.MessagesController", "checkPromoInfoInternal", chain -> null);
        safeHookByName(cl, "org.telegram.messenger.MessagesController", "isPromoDialog", chain -> Boolean.FALSE);

        try {
            Class<?> mc = cl.loadClass("org.telegram.messenger.MessagesController");
            java.lang.reflect.Method getInstance = mc.getMethod("getInstance", int.class);
            java.lang.reflect.Method removePromo  = mc.getDeclaredMethod("removePromoDialog");
            for (int a = 0; a < 8; a++) {
                try {
                    Object inst = getInstance.invoke(null, a);
                    if (inst != null) removePromo.invoke(inst);
                } catch (Throwable ignore) { }
            }
            log(Log.INFO, TAG, "Cleared cached promo dialogs");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "removePromoDialog cleanup failed", t);
        }
    }

    private void safeHookByName(ClassLoader cl, String cls, String method, XposedInterface.Hooker hooker) {
        try {
            Class<?> c = cl.loadClass(cls);
            boolean found = false;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(method)) {
                    hook(m).setPriority(XposedInterface.PRIORITY_HIGHEST).intercept(hooker);
                    found = true;
                }
            }
            log(Log.INFO, TAG, (found ? "Hooked " : "NOT FOUND ") + cls + "#" + method);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook failed " + cls + "#" + method, t);
        }
    }
}