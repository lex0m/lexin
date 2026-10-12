package io.github.lex0m.lexin;

import android.content.Context;

/**
 * 功能注册表。每个 installer 都包了一层：单个功能失败不影响其它功能，也不向宿主抛异常。
 *
 * 安装顺序有讲究：先拿到宿主版本（WxVersion），再加载锚点表（Anchors），
 * 然后才让各功能去解析自己要挂的类。装完检查有没有槽位没解析出来 —— 有就开后台扫描，
 * 扫到的结果写进本机缓存，扫完再把当时没装上的功能补装一次。
 */
final class Features {

    static Context app;
    static ClassLoader loader;
    static int hostVersionCode;

    private static volatile boolean installed = false;
    private static volatile boolean retried = false;

    static void install(Context context, ClassLoader cl) {
        if (installed) return;
        installed = true;

        app = context.getApplicationContext();
        loader = cl;
        try {
            hostVersionCode = HostInfo.versionCode(app);
            WxVersion.init(context);
        } catch (Throwable ignored) {
            // 版本探测尽力而为
        }

        // 配置先就位：Anchors 要读本机学习缓存、撤回记录要读历史，都走 Storage，
        // 顺序反了就会永远读到空值（学习缓存每次冷启丢失）。
        // 配置存在宿主自己的 SharedPreferences，由同样跑在宿主进程里的面板写入：全程无 IPC，
        // 这是刻意的——Android 15 上所有跨进程通道都实测被封（模块私有文件 SELinux、媒体卷 FUSE、ContentProvider）
        Prefs.init(app);

        // 锚点表 + 撤回记录需要先就位，后面的功能才有类可挂
        Anchors.init();
        AntiRecall.prepare();

        try {
            Bridge.install(app);
        } catch (Throwable ignored) {
            // 通道建不起来不影响其它功能
        }
        step(Settings::install);
        step(ScanUnlock::install);
        step(Steps::install);
        step(AntiRecall::install);
        step(SnsGuard::install);
        step(AdBlock::install);
        step(MediaSaver::install);
        step(Limits::install);
        step(EmojiGame::install);
        step(Beautify::install);
        step(Theme::install);
        step(Stealth::install);

        // 有槽位没解析出来（多半是微信又更新了）→ 后台扫宿主 dex，扫完补装
        if (AutoScan.needed()) {
            AutoScan.whenFinished(new Runnable() {
                @Override
                public void run() {
                    retryDeferred();
                }
            });
            AutoScan.schedule();
        }
    }

    /** 扫描完成后，把当时因为「类没找到」而没装上的功能补装一次（只补一次）。 */
    private static void retryDeferred() {
        if (retried) return;
        retried = true;
        try {
            if (!Status.ok("消息防撤回")) step(AntiRecall::install);
            if (!Status.ok("朋友圈保留")) step(SnsGuard::install);
            if (!Status.ok("去广告")) step(AdBlock::install);
            if (!Status.ok("自动查看原图")) step(MediaSaver::install);
            if (!Status.ok("骰子/猜拳")) step(EmojiGame::install);
            Log.d("svc-core: retried after scan, armed=" + Status.armed());
        } catch (Throwable ignored) {
            // 补装失败就保持原样
        }
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
