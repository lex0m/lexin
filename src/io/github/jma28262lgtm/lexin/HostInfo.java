package io.github.jma28262lgtm.lexin;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

/** Host (WeChat) metadata reader. No storage, no logging. */
final class HostInfo {

    private static int cached = -1;

    static int versionCode(Context ctx) {
        if (cached != -1) return cached;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(Entry.HOST, 0);
            cached = Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable t) {
            cached = 0;
        }
        return cached;
    }
}
