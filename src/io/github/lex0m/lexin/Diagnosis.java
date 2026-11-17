package io.github.lex0m.lexin;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 一键诊断导出（v1.7.11）。
 *
 * 为什么要它：以前出问题只有两条路 —— 让用户去 LSPosed 里翻日志（多数人找不到），
 * 或者我手抄 logcat（真机现场根本拿不到）。结果是每次排查都要先花十几轮问「你到底点没点那个开关」。
 *
 * 现在一次导出把这些打包：环境/版本、整份配置（解码成人话）、锚点解析摘要、
 * 功能状态快照（status.txt）、最近 800 行内存日志、线程栈、模块私有目录清单、视图树 dump（有的话）。
 *
 * 落点：`Download/乐信/诊断/lexin-diag-YYYYMMDD-HHmmss.zip`
 *   - 微信进程（面板）→ 走 MediaStore（微信没有存储权限，这条路已经用语音导出验证过）
 *   - 模块进程 → 直接写公共下载目录（有 MANAGE_EXTERNAL_STORAGE）
 *
 * ★ 包里有会话 id / 群 id（`rl` 键），导出时**打码**，不把用户的关系链装进 zip。
 */
final class Diagnosis {

    private static final String T = "svc-diag: ";
    private static final String ROOT = "乐信";
    private static final String SUB = "诊断";
    /** 打码的键：会话 id / 群 id，对排查无用，对隐私致命。 */
    private static final String[] MASK_KEYS = {"rl"};
    private static final int MAX_DUMP_FILES = 12;

    private Diagnosis() { }

    /** 导出；返回落盘路径或 null（失败只写日志，不打扰用户）。 */
    static String export(Context ctx) {
        if (ctx == null) return null;
        File tmp = null;
        try {
            String name = "lexin-diag-" + stamp() + ".zip";
            tmp = new File(ctx.getCacheDir(), name);
            ZipOutputStream z = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tmp)));
            try {
                put(z, "info.txt", info(ctx));
                put(z, "cfg.txt", cfg());
                put(z, "anchors.txt", anchors());
                String st = statusText(ctx);
                if (st != null) put(z, "status.txt", st);
                put(z, "log.txt", Log.dumpRing());
                put(z, "threads.txt", threads());
                put(z, "files.txt", files(ctx));
                putDumps(z, ctx);
            } finally {
                z.close();
            }
            String where = publish(ctx, tmp, name);
            Log.d(T + "exported -> " + where + " (" + tmp.length() + "B)");
            return where;
        } catch (Throwable t) {
            Log.d(T + "export failed: " + t.getClass().getSimpleName()
                    + " " + t.getMessage());
            return null;
        } finally {
            try { if (tmp != null) tmp.delete(); } catch (Throwable ignored) { }
        }
    }

    // ── 收集 ─────────────────────────────────────────────────────────────────

    private static String info(Context ctx) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# 乐信诊断包 · v").append(Features.MODULE_VERSION).append('\n');
        sb.append("生成时间: ").append(now()).append('\n');
        sb.append("侧: ").append(Storage.isHostSide() ? "宿主进程（微信，面板跑在这里）" : "模块进程").append('\n');
        sb.append("进程: pid=").append(android.os.Process.myPid());
        try {
            String pn = null;
            if (Build.VERSION.SDK_INT >= 28) pn = android.app.Application.getProcessName();
            if (pn != null && !pn.isEmpty()) sb.append(" name=").append(pn);
        } catch (Throwable ignored) {
            // 进程名读不到不影响
        }
        sb.append('\n');
        sb.append("模块版本: v").append(Features.MODULE_VERSION).append('\n');
        try {
            String wx = HostInfo.versionName(ctx);
            sb.append("微信版本: ").append(wx == null ? "?" : wx).append('\n');
        } catch (Throwable ignored) {
            sb.append("微信版本: 读取失败\n");
        }
        try {
            sb.append("环境: ").append(Compat.snapshot(ctx)).append('\n');
        } catch (Throwable ignored) {
            // 环境自述失败不阻塞
        }
        sb.append("系统: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("ABI: ");
        try {
            sb.append(android.text.TextUtils.join(",", Build.SUPPORTED_ABIS));
        } catch (Throwable ignored) {
            sb.append(Build.CPU_ABI);
        }
        sb.append('\n');
        try {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            sb.append("屏幕: ").append(dm.widthPixels).append('x').append(dm.heightPixels)
                    .append(" density=").append(dm.density).append(" dpi=").append(dm.densityDpi)
                    .append('\n');
        } catch (Throwable ignored) {
            // 屏幕信息缺失不阻塞
        }
        sb.append("诊断日志开关: ").append(Log.runtimeOn() ? "开" : "关").append('\n');
        sb.append("内存日志行数: ").append(countLines(Log.dumpRing())).append('\n');
        if (Storage.isHostSide()) {
            try {
                sb.append("\n## 锚点摘要\n").append(Anchors.summary()).append('\n');
                sb.append("已知版本: ").append(Anchors.knownVersions()).append('\n');
            } catch (Throwable ignored) {
                sb.append("\n## 锚点摘要\n读取失败\n");
            }
        } else {
            sb.append("\n## 锚点摘要\n");
            sb.append("（模块进程解析不到微信的 dex，本项没有可信结果；真实适配状态见 status.txt）\n");
        }
        if (Storage.isHostSide()) {
            try {
                String snap = Status.render();
                sb.append("\n## 功能状态快照（微信进程，本包生成地）\n");
                sb.append(snap == null || snap.isEmpty() ? "（空）\n" : snap + "\n");
            } catch (Throwable ignored) {
                sb.append("\n## 功能状态快照\n读取失败\n");
            }
        } else {
            sb.append("\n## 功能状态快照\n");
            sb.append("（本包由模块进程生成，功能状态在微信进程里；见同包 status.txt）\n");
        }
        return sb.toString();
    }

    private static String cfg() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# 整份配置（已解码；打码项见文件末尾说明）\n");
        Map<String, String> all = Storage.dumpDecoded();
        Set<String> masked = new TreeSet<String>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (isMasked(k)) {
                masked.add(k);
                v = mask(v);
            }
            sb.append(k).append(" = ").append(v).append('\n');
        }
        sb.append("\n# 打码: ").append(masked.isEmpty() ? "无" : masked.toString()).append('\n');
        return sb.toString();
    }

    private static String anchors() {
        StringBuilder sb = new StringBuilder(1024);
        if (!Storage.isHostSide()) {
            // ★ 这里必须说清：锚点是拿微信的 dex 解析的，模块进程里永远解析不到。
            //   第一版在模块侧直接打印 detail()，结果是一排 ✘「全部未适配」——比没有信息更糟，
            //   下一个人只会拿着它去查一个不存在的故障。
            sb.append("# 本包由模块进程生成：锚点解析发生在微信进程，这里没有可信结果。\n");
            sb.append("# 微信侧的真实适配状态请看同包的 status.txt（微信进程推上来的快照）。\n");
            try {
                sb.append("knownVersions: ").append(Anchors.knownVersions()).append('\n');
            } catch (Throwable ignored) {
                // 版本表读不到也不影响
            }
            return sb.toString();
        }
        try {
            sb.append("summary: ").append(Anchors.summary()).append('\n');
            sb.append("knownVersions: ").append(Anchors.knownVersions()).append('\n');
            sb.append('\n');
            sb.append(Anchors.detail());
        } catch (Throwable t) {
            sb.append("锚点详情读取失败: ").append(t.getClass().getSimpleName()).append('\n');
        }
        return sb.toString();
    }

    /** 模块侧的 status.txt（微信进程推过来的那份）；没有就返回 null。 */
    private static String statusText(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), "status.txt");
            if (!f.isFile() || f.length() <= 0) return null;
            byte[] buf = new byte[(int) Math.min(f.length(), 512 * 1024)];
            FileInputStream in = new FileInputStream(f);
            try {
                int off = 0;
                int n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
                return new String(buf, 0, off, "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String threads() {
        StringBuilder sb = new StringBuilder(4096);
        try {
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            sb.append("# 线程栈（共 ").append(all.size()).append(" 个线程）\n");
            for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                Thread t = e.getKey();
                sb.append("\n= \"").append(t.getName()).append("\" ")
                        .append(t.getState()).append(" daemon=").append(t.isDaemon()).append('\n');
                for (StackTraceElement s : e.getValue()) sb.append("    at ").append(s).append('\n');
            }
        } catch (Throwable t) {
            sb.append("线程栈读取失败: ").append(t.getClass().getSimpleName()).append('\n');
        }
        return sb.toString();
    }

    private static String files(Context ctx) {
        StringBuilder sb = new StringBuilder(1024);
        try {
            sb.append("# 应用私有目录\n");
            list(sb, ctx.getFilesDir(), 0);
            File cache = ctx.getCacheDir();
            sb.append("\n# cache 文件数: ")
                    .append(cache != null && cache.list() != null ? cache.list().length : 0)
                    .append('\n');
            sb.append("\n# Download/").append(ROOT).append(" 目录\n");
            File pub = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), ROOT);
            list(sb, pub, 0);
        } catch (Throwable t) {
            sb.append("目录清单失败: ").append(t.getClass().getSimpleName()).append('\n');
        }
        return sb.toString();
    }

    private static void list(StringBuilder sb, File dir, int depth) {
        if (dir == null || depth > 2) return;
        try {
            File[] kids = dir.listFiles();
            if (kids == null) return;
            for (File f : kids) {
                for (int i = 0; i < depth; i++) sb.append("  ");
                sb.append(f.isDirectory() ? "[D] " : "[F] ").append(f.getName());
                if (!f.isDirectory()) sb.append("  ").append(f.length()).append("B");
                sb.append('\n');
                if (f.isDirectory() && depth < 2) list(sb, f, depth + 1);
            }
        } catch (Throwable ignored) {
            // 单个目录失败继续
        }
    }

    /** 视图树 dump（Beautify 在调试开关打开时写的 P01-page.txt / L01-xxx.txt）。 */
    private static void putDumps(ZipOutputStream z, Context ctx) {
        try {
            File[] kids = ctx.getFilesDir().listFiles();
            if (kids == null) return;
            int n = 0;
            for (File f : kids) {
                if (n >= MAX_DUMP_FILES) break;
                String name = f.getName();
                if (!f.isFile()) continue;
                if (!(name.matches("^P\\d\\d-page\\.txt$") || name.matches("^L\\d\\d-.+\\.txt$"))) continue;
                n++;
                putFile(z, "dumps/" + name, f);
            }
        } catch (Throwable ignored) {
            // dump 有没有都不影响导出
        }
    }

    // ── 落盘 ─────────────────────────────────────────────────────────────────

    private static String publish(Context ctx, File zip, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            Uri uri = null;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + ROOT + "/" + SUB);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        copy(zip, os);
                        os.close();
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        ctx.getContentResolver().update(uri, done, null, null);
                        return "Download/" + ROOT + "/" + SUB + "/" + name;
                    }
                }
            } catch (Throwable t) {
                Log.d(T + "mediastore failed: " + t.getClass().getSimpleName());
            }
            if (uri != null) {
                try { ctx.getContentResolver().delete(uri, null, null); } catch (Throwable ignored) { }
            }
        }
        // 模块进程（有 MANAGE_EXTERNAL_STORAGE）与 Android 9 走这条
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), ROOT + "/" + SUB);
            if (dir.isDirectory() || dir.mkdirs()) {
                File out = new File(dir, name);
                FileOutputStream os = new FileOutputStream(out);
                try {
                    copy(zip, os);
                } finally {
                    os.close();
                }
                return "Download/" + ROOT + "/" + SUB + "/" + name;
            }
        } catch (Throwable t) {
            Log.d(T + "public dir failed: " + t.getClass().getSimpleName());
        }
        // 最后一道：私有目录（用户能从文件管理器看到 Android/data）
        File fallback = new File(ctx.getFilesDir(), name);
        FileOutputStream os = new FileOutputStream(fallback);
        try {
            copy(zip, os);
        } finally {
            os.close();
        }
        return fallback.getAbsolutePath();
    }

    // ── 小工具 ───────────────────────────────────────────────────────────────

    private static void put(ZipOutputStream z, String name, String text) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(text == null ? new byte[0] : text.getBytes("UTF-8"));
        z.closeEntry();
    }

    private static void putFile(ZipOutputStream z, String name, File f) {
        FileInputStream in = null;
        try {
            z.putNextEntry(new ZipEntry(name));
            in = new FileInputStream(f);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
            z.closeEntry();
        } catch (Throwable ignored) {
            // 单个 dump 失败不影响整包
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    private static void copy(File src, OutputStream os) throws Exception {
        FileInputStream in = new FileInputStream(src);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } finally {
            in.close();
        }
    }

    private static boolean isMasked(String key) {
        for (String k : MASK_KEYS) if (k.equals(key)) return true;
        return false;
    }

    private static String mask(String v) {
        if (v == null || v.isEmpty()) return "";
        int keep = Math.min(3, v.length() / 4);
        return v.substring(0, keep) + "…（" + v.length() + " 字符，已打码）";
    }

    private static int countLines(String s) {
        if (s == null) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static String now() {
        return android.text.format.DateFormat.format(
                "yyyy-MM-dd HH:mm:ss", System.currentTimeMillis()).toString();
    }

    private static String stamp() {
        return android.text.format.DateFormat.format(
                "yyyyMMdd-HHmmss", System.currentTimeMillis()).toString();
    }
}
