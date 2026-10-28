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

    /**
     * 模块版本：build.sh 从 AndroidManifest.xml 生成进 BuildFlags（版本号唯一来源）。
     * 以前这里是手写常量，每轮发版都要人工同步一处，漏改就出现「面板显示旧版本」。
     */
    static final String MODULE_VERSION = BuildFlags.VERSION;

    static Context app;
    static ClassLoader loader;
    static int hostVersionCode;
    /**
     * 是不是微信主进程。
     *
     * 微信会同时拉起 :push / :appbrand0、1 / :tools / :xweb_* 好几个进程，
     * 每个进程都会走一遍本模块的 hooks。功能 hook 留着（去广告要 :appbrand），
     * 但**只该在一处做的事**（注册面板广播、推送适配状态、全量扫 dex）必须只在主进程做，
     * 否则就是：面板被小程序进程乱弹、status.txt 被后写的进程覆盖、六个进程各扫一遍 dex。
     */
    static boolean mainProcess = true;

    private static volatile boolean installed = false;
    private static volatile boolean retried = false;

    static void install(Context context, ClassLoader cl) {
        if (installed) return;
        installed = true;

        app = context.getApplicationContext();
        loader = cl;
        mainProcess = isMainProcess(app);
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
        Log.setRuntime(Storage.diag());     // 默认 false：发布版不写日志

        // 锚点表 + 撤回记录需要先就位，后面的功能才有类可挂
        Anchors.init();
        AntiRecall.prepare();

        if (mainProcess) {
            try {
                Bridge.install(app);
            } catch (Throwable ignored) {
                // 通道建不起来不影响其它功能
            }
        }
        step(Settings::install);
        step(FwdProbe::probe);          // 转发增强探针：只在诊断日志打开时输出，用来定位挂点
        // ── 核心：随启动装（撤回 / 朋友圈 / 去广告 / 步数）──
        step(AntiRecall::install);
        step(SnsGuard::install);
        step(AdBlock::install);
        step(Steps::install);
        // ── v1.3：自动语音转文字 + 批量标签 ──
        // 这两个都要尽早挂：转文字要 hook 消息 View 绑定（语音来了就转），
        // 批量标签要 hook 微信 coreStorage 的获取点（晚一秒可能已经跑过、拿不到已打开的库）。
        step(AutoVoiceText::install);
        step(BatchLabel::install);
        // ── 其余：延后 1.2 秒再装 ──
        // 目的有二：① 打散"启动瞬间装一大串 hook"的时间特征 ② 启动阶段少做事，卡顿更低。
        // 这些功能都只在对应界面才用得上（相册/表情面板/扫码/聊天菜单/外观），晚一秒完全无感。
        defer(new Installer[]{
                ScanUnlock::install,
                ForwardPlus::install,
                MediaSaver::install,
                Limits::install,
                EmojiGame::install,
                Beautify::install,
                Theme::install,
                Stealth::install,
                FloatingFooter::install,
                HomeFloatingTab::install,
        });

        // 有槽位没解析出来（多半是微信又更新了）→ 后台扫宿主 dex，扫完补装
        if (mainProcess && AutoScan.needed()) {
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
            pushStatusToPanel();
        } catch (Throwable ignored) {
            // 补装失败就保持原样
        }
    }

    /** 延后安装（主线程 1.2 秒后，一次性）。 */
    private static void defer(final Installer[] more) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            for (Installer i : more) step(i);
                            Log.d("svc-core: deferred installs done");
                            pushStatusToPanel();
                        }
                    }, 1200);
        } catch (Throwable t) {
            for (Installer i : more) step(i);      // 排不上队就当场装，功能不打折
        }
    }

    /**
     * 把适配状态推给模块 App 的 BgProvider（跨进程）。
     *
     * 为什么需要这条：Status 是**进程内**静态 Map，面板（SettingsActivity）跑在模块 App 进程里，
     * 读 Status.all() 永远是空 → 面板一直显示「模块尚未在微信里加载」，而实际上模块早装好了。
     * 这条通道是 BgProvider 的 call()：微信进程写、面板读，跟背景图同一条 provider。
     */
    static void pushStatusToPanel() {
        try {
            if (app == null || !mainProcess) return;   // 只让主进程写 status.txt
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, Boolean> e : Status.all().entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue() ? '1' : '0').append('\n');
            }
            if (sb.length() == 0) return;
            android.os.Bundle b = new android.os.Bundle();
            b.putString("status", sb.toString());
            app.getContentResolver().call(BgProvider.configUri(), "pushStatus", null, b);
            Log.d("svc-core: status pushed to panel (" + Status.armed() + "/" + Status.all().size() + ")");
        } catch (Throwable t) {
            Log.d("svc-core: status push failed: " + t);
        }
    }

    /** 微信主进程判定：拿不到进程名就当主进程（保持原来的行为）。 */
    private static boolean isMainProcess(Context ctx) {
        try {
            String p = android.app.Application.getProcessName();
            if (p == null || p.isEmpty()) p = ctx.getPackageName();
            return p.equals(ctx.getPackageName());
        } catch (Throwable t) {
            return true;
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
