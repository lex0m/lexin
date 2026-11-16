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

        // 正常路径下 context 就是 Application（Entry 传的是 param.thisObject）；
        // 万一拿到的是普通 Context，也要退到 applicationContext / 自身，绝不留 null。
        if (context instanceof android.app.Application) {
            app = (android.app.Application) context;
        } else {
            android.content.Context ac = context.getApplicationContext();
            app = ac != null ? ac : context;
        }
        // v1.6.2：微信用 Tinker 热修，运行时类可能由**另一个 ClassLoader** 定义
        // （补丁 dex 里那份）。优先用 Application 实例自己的 loader —— 它就是运行时
        // 真正定义 com.tencent.mm.app.Application 的那个 loader。
        ClassLoader runtime = null;
        try {
            runtime = context.getClass().getClassLoader();
        } catch (Throwable ignored) {
            // 拿不到就用框架给的
        }
        loader = runtime != null ? runtime : cl;
        Log.d("svc-core: loader lp=" + cl + " runtime=" + runtime + " use=" + loader);
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
        // ── 隐蔽：最先装 ──
        // 栈帧自检（TinkerCrashProtect）和 Matrix 的 native 自查都可能在启动阶段就跑过一次，
        // 排在功能后面装等于白装，所以它不吃 1.2 秒的延后。
        step(Stealth::install);         // 反检测：每个进程都要（Matrix 自查在哪个进程都可能跑）
        // 下面这些只在主进程有意义：多进程各装一份 = 白增暴露面（:push/:appbrand 里没人用面板/主题）
        if (mainProcess) {
            step(SelfCheck::install);
            step(Settings::install);
            step(FwdProbe::probe);      // 转发增强探针：只在诊断日志打开时输出，用来定位挂点
        }
        // ── 核心：随启动装（撤回 / 朋友圈 / 去广告 / 步数）──
        step(AntiRecall::install);
        step(DbGuard::install);          // 消息库兜底：不依赖解析器，撤回改写前还原原始行
        step(SnsGuard::install);
        step(AdBlock::install);
        step(Steps::install);
        // ── v1.3：自动语音转文字 + 批量标签 ──
        // 这两个都要尽早挂：转文字要 hook 消息 View 绑定（语音来了就转），
        // 批量标签要 hook 微信 coreStorage 的获取点（晚一秒可能已经跑过、拿不到已打开的库）。
        if (mainProcess) {
            step(AutoVoiceText::install);
            step(BatchLabel::install);
        }
        // ── 其余：延后 1.2 秒再装 ──
        // 目的有二：① 打散"启动瞬间装一大串 hook"的时间特征 ② 启动阶段少做事，卡顿更低。
        // 这些功能都只在对应界面才用得上（相册/表情面板/扫码/聊天菜单/外观），晚一秒完全无感。
        if (mainProcess) {
            defer(new Installer[]{
                    ScanUnlock::install,
                    ForwardPlus::install,
                    MediaSaver::install,
                    Limits::install,
                    EmojiGame::install,
                    Beautify::install,
                    Theme::install,
                    FloatingFooter::install,
                    HomeFloatingTab::install,
            });
        }

        // 命中数是运行期才涨的：定时把新快照推给面板，否则面板永远停在"已挂载未命中"
        if (mainProcess) scheduleStatusPush();

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

    private static volatile boolean configRetried = false;
    private static volatile boolean loaderAdopted = false;

    /**
     * 换到**运行时** ClassLoader，并把核心 hook 重挂一遍。
     *
     * 为什么必须重挂：微信用 Tinker 热修，真正执行的类由 {@code DelegateLastClassLoader}
     * 从 {@code tinker_classN.apk} 里加载；而 {@code XC_LoadPackage.classLoader} 拿到的是
     * base.apk 那个 PathClassLoader。用后者 {@code Class.forName} 出来的是**死类**，
     * hook 挂上去永远不触发 —— 这正是"面板全绿、功能全哑"的根因。
     *
     * 旧的 hook 留在 base 类上不会重复执行（那些类没人调用），所以直接再挂一遍是安全的。
     */
    static void adoptLoader(ClassLoader cl) {
        if (cl == null || cl == loader) return;
        loader = cl;
        Loaders.reset();          // 重新收集（补丁 dex 的 loader 此时已可见）
        Anchors.clearCacheOnly();
        if (loaderAdopted) return;
        loaderAdopted = true;
        Log.d("svc-core: adopted runtime loader " + cl);
        step(Stealth::install);
        step(AntiRecall::install);
        step(DbGuard::install);
        step(SnsGuard::install);
        step(AdBlock::install);
        step(Steps::install);
        // 其余"只挂微信类"的功能也重挂：第一批装的时候类来自 base dex，全是死类。
        // 框架类（View/Activity/PopupWindow）由 boot classloader 定义、两次解析是同一个类，
        // 重复挂会重复执行，所以 Beautify / Theme / ForwardPlus 不在这里重跑。
        step(EmojiGame::install);
        step(Limits::install);
        step(ScanUnlock::install);
        step(FloatingFooter::install);
        step(HomeFloatingTab::install);
        step(ForwardPlus::install);
        if (BuildFlags.DEBUG) probeRuntimeHook();
        pushStatusToPanel();
    }

    /**
     * 诊断包专用：在**运行时类**（补丁 dex 里的 LauncherUI）上挂一个探针。
     * 每次微信回到前台就 +1，用来证明"换 loader 之后 hook 真的能命中"。
     */
    private static void probeRuntimeHook() {
        try {
            Class<?> lui = Class.forName("com.tencent.mm.ui.LauncherUI", false, loader);
            for (final java.lang.reflect.Method m : lui.getDeclaredMethods()) {
                if (!"onResume".equals(m.getName()) || m.getParameterCount() != 0) continue;
                m.setAccessible(true);
                de.robv.android.xposed.XposedBridge.hookMethod(m,
                        new de.robv.android.xposed.XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                Probe.hit("hook 活性");
                            }
                        });
                Log.d("svc-core: runtime probe armed on " + lui.getName()
                        + " loader=" + lui.getClassLoader().getClass().getSimpleName());
                break;
            }
        } catch (Throwable t) {
            Log.d("svc-core: runtime probe failed " + t.getClass().getSimpleName());
        }
    }

    /**
     * 配置真正到位后调用（{@link Storage#applyRemote} 里触发）。
     *
     * 懒挂载的闸门在 install 时求值，而配置可能晚到几百毫秒；默认关的功能会被先跳过。
     * 这里在配置到达后补一次 —— 只补一次，且只补「确实没装上」的。
     */
    static void onConfigReady() {
        if (configRetried) return;
        if (!mainProcess) return;       // 只有主进程需要这些功能，别在 :push/:appbrand 里也补一遍
        configRetried = true;
        try {
            if (!Status.ok("悬浮聊天底栏")) step(FloatingFooter::install);
            if (!Status.ok("主页悬浮底栏")) step(HomeFloatingTab::install);
            if (!Status.ok("自动语音转文字")) step(AutoVoiceText::install);
            if (!Status.ok("批量分类标签")) step(BatchLabel::install);
        } catch (Throwable ignored) {
            // 补装失败保持原样
        }
        pushStatusToPanel();
    }

    /** 扫描完成后，把当时因为「类没找到」而没装上的功能补装一次（只补一次）。 */
    private static void retryDeferred() {
        if (retried) return;
        retried = true;
        try {
            // 关键：先把「解析失败」留下的空缓存清掉，否则下面的重装读到的还是空表，
            // 扫描结果一辈子用不上（v1.5 的实际行为）。
            Anchors.clearMisses();
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
                    }, 800 + (int) (Math.random() * 1200));   // 抖动：固定 1.2 秒是可观测的启动特征
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
    private static volatile int pushedHits = -1;

    /**
     * 定时推送快照：3 分钟后第一次，之后每 5 分钟一次。
     * 只有命中数变化才真的发广播 —— 没变化就一个字节都不发（少一次跨进程广播就少一分痕迹）。
     */
    private static void scheduleStatusPush() {
        try {
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 命中数变了、或者有新的诊断备注 → 推一份新快照。
                        // 以前只看命中数，「没命中」的原因永远传不出来。
                        if (mainProcess && (Probe.totalHits() != pushedHits || Status.consumeNoteDirty())) {
                            pushStatusToPanel();
                        }
                    } catch (Throwable ignored) {
                        // 推送失败不影响任何功能
                    }
                    h.postDelayed(this, 300000L);
                }
            }, 180000L);
        } catch (Throwable ignored) {
            // 排不上队就算了
        }
    }

    private static volatile boolean pushScheduled = false;
    private static volatile long lastPush = 0L;

    /**
     * 把一份新快照尽快推出去（2 秒节流）。
     *
     * 为什么需要：定时推送是 3 分钟后首次、之后每 5 分钟一次，而微信一切到后台
     * 就被系统冻结，Handler 不跑 ⇒ 用户在前台做的操作（点骰子、开图片）产生的诊断备注
     * 永远等不到下一次窗口。这里让「有新的诊断信息」立刻推，节流 2 秒防止刷屏。
     */
    static void pushSoon() {
        if (pushScheduled) return;
        long now = System.currentTimeMillis();
        if (now - lastPush < 2000L) return;
        pushScheduled = true;
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    pushScheduled = false;
                    lastPush = System.currentTimeMillis();
                    try {
                        pushStatusToPanel();
                    } catch (Throwable ignored) {
                        // 推不出去不影响功能
                    }
                }
            }, 800L);
        } catch (Throwable ignored) {
            pushScheduled = false;
        }
    }

    static void pushStatusToPanel() {
        try {
            if (app == null || !mainProcess) return;   // 只让主进程写 status.txt
            String sb = Status.render();
            if (sb == null || sb.isEmpty()) return;
            // 走广播（CfgBus.STATUS）：微信看不见模块包，BgProvider.call 在微信侧是
            // Unknown authority（v1.4 起 status.txt 就没更新过，异常被吞了）。
            CfgBus.pushStatus(app, sb.toString());
            pushedHits = Probe.totalHits();
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
