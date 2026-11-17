# R8 规则。入口都是靠反射按名字找的，keep 少了会"装得上但功能全无"。

# ── 入口 / 组件：框架按名字调用 ──────────────────────────────
# LSPosed 读 assets/xposed_init 里的类名，加载后调它的 hook
-keep class io.github.lex0m.lexin.Entry { *; }
# Activity、Provider 由框架按名字实例化
-keep class io.github.lex0m.lexin.EntryPointActivity { *; }
-keep class io.github.lex0m.lexin.SettingsActivity { *; }
-keep class io.github.lex0m.lexin.ThemeActivity { *; }
-keep class io.github.lex0m.lexin.BgProvider { *; }
# 配置总线接收器：只在 manifest 里声明，代码里没有引用 —— R8 会当成死代码裁掉
-keep class io.github.lex0m.lexin.CfgReceiver { *; }
# ★ 只被 manifest 和字符串（setClassName）引用的组件：R8 看不到引用，会**整类删掉** ✗
#   2026-10-17 实测：ConvertActivity 在 dex 里连类描述符都不存在 ⇒ 拉起必失败。
#   新增任何组件（activity/service/receiver/provider）都必须在这里补一条 keep。
-keep class io.github.lex0m.lexin.ConvertActivity { *; }
# v1.7.2 新增 Application：只被 AndroidManifest 引用，R8 看不到调用方会直接裁掉
# （verify_apk 报「组件类不在 dex 里」就是这一条）。
-keep class io.github.lex0m.lexin.LexinApp { *; }
-keep class io.github.lex0m.lexin.WorkService { *; }
-keep class io.github.lex0m.lexin.BootReceiver { *; }
# v1.7.11 无界面诊断导出：只被 manifest 引用，R8 会整类裁掉（同 ConvertActivity 那次）
-keep class io.github.lex0m.lexin.DiagActivity { *; }

# JNI：native 方法被改名 = 找不到符号 = UnsatisfiedLinkError
-keepclasseswithmembernames class * {
    native <methods>;
}
# ★ 这个名字是 .so 编译时写死的（Java_io_github_lex0m_lexin_SilkCodec_silkToMp3），
#   重命名类 = 绑定失败。convert 的 native 方法也在这个类里。
-keep class io.github.lex0m.lexin.SilkCodec { *; }

# Xposed 回调方法改名 = 静默失效
-keepclassmembers class * extends de.robv.android.xposed.XC_MethodHook {
    protected void beforeHookedMethod(...);
    protected void afterHookedMethod(...);
}
-keep class * extends de.robv.android.xposed.XC_MethodHook { *; }

# 我们自己在代码里按字符串查的类
-keep class io.github.lex0m.lexin.HostInfo { *; }

# v1.7.10 卡片图真模糊：CardBlur 只在 `Build.VERSION.SDK_INT >= 31` 分支里被调用。
# R8 看不到「一定被调用」，会把它当死代码删掉 ⇒ Android 12+ 上模糊静默失效（没有报错，最坏的那种失效）。
# 同时它是**故意隔离**的类（RenderEffect 是 API 31 类型，写在 Beautify 里会让低版本 VerifyError 连坐）。
-keep class io.github.lex0m.lexin.CardBlur { *; }

# ★ 活性自检：SelfCheck 要 hook 这个方法、再反射调用它。
#   R8 判定 `pulse()` 只往一个没人读的 int 里 +1（`beat` 全仓库无读者）⇒ **整个方法被删掉**，
#   于是运行时按签名也找不到，面板「hook 活性」永远「未适配」——判据本身失真。
#   这里把方法与其计数字段都留住（SelfCheck 的 tick 里也会读一次 beat，双保险）。
-keepclassmembers class io.github.lex0m.lexin.Probe {
    static void pulse();
    static int beat;
}

# ── 混淆强度 ────────────────────────────────────────────────
# 类名收进根包、抹掉内部类层级线索；被 keep 的类不受影响
-repackageclasses 'x'
-allowaccessmodification
# 自造词表，别用 R8 默认的 a/b/c（那种一眼就是混淆过的）
-obfuscationdictionary obf-dict.txt

# ── 属性 / 警告 ─────────────────────────────────────────────
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute S
-dontwarn de.robv.android.xposed.**
-dontwarn android.**
-dontwarn androidx.**

# ── 框架按名字反射的方法：只 keep 类名不够，方法名也得留 ─────
# 改这些名字的后果是组件一起来就死（历史上被 R8 坑过一次）
-keepclassmembers class * extends android.app.Activity {
    protected void onCreate(android.os.Bundle);
    protected void onStart();
    protected void onResume();
    protected void onPause();
    protected void onStop();
    protected void onDestroy();
    protected void onNewIntent(android.content.Intent);
    protected void onActivityResult(int, int, android.content.Intent);
    protected void onSaveInstanceState(android.os.Bundle);
    protected void onRestoreInstanceState(android.os.Bundle);
}

-keepclassmembers class * extends android.content.ContentProvider {
    public boolean onCreate();
    public android.os.ParcelFileDescriptor openFile(android.net.Uri, java.lang.String);
    public android.database.Cursor query(android.net.Uri, java.lang.String[], java.lang.String, java.lang.String[], java.lang.String);
    public java.lang.String getType(android.net.Uri);
    public android.net.Uri insert(android.net.Uri, android.content.ContentValues);
    public int delete(android.net.Uri, java.lang.String, java.lang.String[]);
    public int update(android.net.Uri, java.lang.String, java.lang.String[], android.content.ContentValues);
}

# ── v1.7.6 DexKit（全程反射调用，R8 看不到调用方会整个裁掉）──
# 踩过的坑：不加这几条，dex 里连 DexKitBridge 这个类都没有，
# 而字符串 "luckypray"/"DexKitBridge" 还在 —— 排查时极容易以为是 native 库没加载。
-keep class org.luckypray.dexkit.** { *; }
-keep class com.google.flatbuffers.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.jvm.internal.**
-dontwarn kotlin.**
