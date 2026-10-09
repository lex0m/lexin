# R8 规则。入口都是靠反射按名字找的，keep 少了会"装得上但功能全无"。

# ── 入口 / 组件：框架按名字调用 ──────────────────────────────
# LSPosed 读 assets/xposed_init 里的类名，加载后调它的 hook
-keep class io.github.lex0m.lexin.Entry { *; }
# Activity、Provider 由框架按名字实例化
-keep class io.github.lex0m.lexin.SettingsActivity { *; }
-keep class io.github.lex0m.lexin.ThemeActivity { *; }
-keep class io.github.lex0m.lexin.BgProvider { *; }

# Xposed 回调方法改名 = 静默失效
-keepclassmembers class * extends de.robv.android.xposed.XC_MethodHook {
    protected void beforeHookedMethod(...);
    protected void afterHookedMethod(...);
}
-keep class * extends de.robv.android.xposed.XC_MethodHook { *; }

# 我们自己在代码里按字符串查的类
-keep class io.github.lex0m.lexin.HostInfo { *; }

# ── 混淆强度 ────────────────────────────────────────────────
# 类名收进根包、抹掉内部类层级线索；被 keep 的类不受影响
-repackageclasses ''
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
