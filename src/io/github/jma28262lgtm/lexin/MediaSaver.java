package io.github.jma28262lgtm.lexin;

import android.view.View;
import android.widget.Button;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** 本地媒体增强（自动查看原图）：在相册决定是否给「查看原图」入口的地方改判断。 */
final class MediaSaver {

    static void install() throws Throwable {
        Class<?> gallery = Silent.cls(Features.loader,
                "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI");
        if (gallery == null) {
            Log.d("svc-media: gallery class missing");
            return;
        }
        int n = 0;
        n += autoClickAfter(gallery, "y9");
        n += autoClickAfter(gallery, "M9");
        Log.d("svc-media: armed=" + n);
    }

    private static int autoClickAfter(Class<?> owner, String name) {
        int hits = 0;
        try {
            for (Method m : owner.getDeclaredMethods()) {
                if (!name.equals(m.getName()) || m.getParameterCount() != 0) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            pressOriginal(p.thisObject);
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 下一个重载
                }
            }
        } catch (Throwable ignored) {
            // 不存在就跳过
        }
        return hits;
    }

    /** Walks the owner's Button fields and presses the one offering the original. */
    private static void pressOriginal(Object owner) {
        if (owner == null) return;
        try {
            Class<?> c = owner.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Button.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(owner);
                    if (!(v instanceof Button)) continue;
                    Button b = (Button) v;
                    if (!b.isShown() || !b.isEnabled()) continue;
                    if (!looksLikeOriginal(b.getText())) continue;
                    b.performClick();
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
            // 仅外观 - never disturb the gallery
        }
    }

    /** The host's own label for "show me the original". */
    private static boolean looksLikeOriginal(CharSequence text) {
        if (text == null) return false;
        String s = text.toString();
        return s.contains("查看原图") || s.contains("原图") || s.contains("原视频");
    }

    static View unused() {
        return null;
    }
}
