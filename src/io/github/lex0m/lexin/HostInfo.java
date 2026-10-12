package io.github.lex0m.lexin;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

/** 宿主（微信）元数据。真正的版本逻辑在 WxVersion，这里只做一次性读取。 */
final class HostInfo {

    private static int cached = -1;
    private static String cachedName = "";

    static int versionCode(Context ctx) {
        read(ctx);
        return cached;
    }

    static String versionName(Context ctx) {
        read(ctx);
        return cachedName;
    }

    private static void read(Context ctx) {
        if (cached != -1) return;
        cached = 0;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(Entry.HOST, 0);
            cached = Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
            cachedName = pi.versionName == null ? "" : pi.versionName;
        } catch (Throwable t) {
            cached = 0;
        }
    }

    private HostInfo() {}
}
