package com.sysprobe.svc;

import android.content.Context;

/** 功能注册表。每个 installer 都包了一层：单个功能失败不影响其它功能，也不向宿主抛异常。 */
final class Features {

    static Context app;
    static ClassLoader loader;
    static int hostVersionCode;

    static void install(Context context, ClassLoader cl) {
        if (app != null) return;
        app = context.getApplicationContext();
        loader = cl;
        try {
            hostVersionCode = HostInfo.versionCode(app);
        } catch (Throwable ignored) {
            // 版本探测尽力而为
        }

        // 配置存在宿主自己的 SharedPreferences，由同样跑在宿主进程里的面板写入：全程无 IPC，这是刻意的——Android 15 上所有跨进程通道都实测被封（模块私有文件 SELinux、媒体卷 FUSE、ContentProvider）
        Prefs.init(app);

        step(Settings::install);
        step(AntiRecall::install);
        step(AntiMoments::install);
        step(AdBlock::install);
        step(MediaSaver::install);
        step(Limits::install);
        step(EmojiGame::install);
        step(Beautify::install);
        step(Theme::install);
        step(Stealth::install);
    }

    private static void step(Installer installer) {
        try {
            installer.install();
        } catch (Throwable t) {
            // 坏掉的功能必须对宿主不可见，但排查期间原因要留档
            Storage.diag("  feature failed: " + t.getClass().getSimpleName()
                    + " " + t.getMessage());
        }
    }

    interface Installer {
        void install() throws Throwable;
    }
}
