package io.github.lex0m.lexin;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 语音转 MP3 + 落到「乐信工作目录」（v1.7 新增）。
 *
 * 为什么要有这个类（旧链路的三个病）：
 *   ① 旧链路在微信进程里先落一个 .silk/.amr 中间文件到 Download/乐信导出，
 *      再让 ConvertActivity 读 —— 而 App 只有 INTERNET 权限根本读不到公共目录，
 *      所以只能靠 byte[] 硬塞，中间文件纯属垃圾。
 *   ② ConvertActivity 是 Activity，一点导出就把前台从微信切走，转完还得手动切回来。
 *   ③ writeVoiceFile 那条分支成功时直接返回 AMR，**根本没转 MP3**。
 *
 * 现在：微信进程只取字节 → 广播递过来 → 这里在**模块进程**里转码并落盘 →
 * 广播回结果（微信侧弹 Toast）。全程零界面、零中间文件、微信进程零 native。
 *
 * 工作目录（用户要的"自建软件工作目录"）：
 *   Download/乐信/语音/2026-11/<时间>_<来源>.mp3      ← 公共可见，MediaStore 写，不要权限
 *   files/work/index.tsv                              ← 索引（时间/文件/字节/码率/来源），面板工作目录页读
 */
final class VoiceExport {

    /** 工作目录根名（公共目录下）。 */
    static final String ROOT = "乐信";
    /** 语音子目录。 */
    static final String VOICE = "语音";
    /** 单次输入上限：Binder 事务 1MB，留一半余量。 */
    static final int MAX_INPUT = 512 * 1024;

    private static volatile boolean libTried = false;
    private static volatile boolean libOk = false;

    /** JNI 只加载一次；失败不抛（ABI 不对时返回 false，由调用方给出人话提示）。 */
    static boolean libReady() {
        if (libTried) return libOk;
        synchronized (VoiceExport.class) {
            if (libTried) return libOk;
            try {
                System.loadLibrary("lexinaudio");
                libOk = true;
            } catch (Throwable t) {
                libOk = false;
            }
            libTried = true;
        }
        return libOk;
    }

    /** 转换结果。 */
    static final class R {
        /** 给用户看的路径（Download/乐信/语音/.../x.mp3）。 */
        String shown;
        /** 真实落盘位置（MediaStore 时是 content uri 的显示名，私有目录时是绝对路径）。 */
        String file;
        long size;
        int seconds;
        String err;

        boolean ok() {
            return err == null && size > 0;
        }
    }

    /**
     * 转码并落盘。
     *
     * @param raw  微信语音原始字节（含 "#!SILK_V3" 头时自动剥）
     * @param name 来源名（会话名/文件名/消息 id 都行，只用来命名与索引）
     * @param kbps MP3 码率；<=0 用 48
     */
    static R convert(Context ctx, byte[] raw, String name, int kbps) {
        R r = new R();
        if (ctx == null) {
            r.err = "没有 context";
            return r;
        }
        if (!libReady()) {
            r.err = "转码库未加载（ABI 不匹配或安装包损坏）";
            return r;
        }
        if (raw == null || raw.length == 0) {
            r.err = "没有收到语音数据";
            return r;
        }
        if (raw.length > MAX_INPUT) {
            r.err = "语音过大：" + raw.length + "B";
            return r;
        }

        File tmp = null;
        try {
            int off = silkPayloadOffset(raw);
            byte[] frames = off > 0 ? slice(raw, off) : raw;
            if (frames.length < 8) {
                r.err = "语音数据为空（" + raw.length + "B）";
                return r;
            }

            int rate = kbps <= 0 ? 48 : Math.max(16, Math.min(192, kbps));
            File dir = new File(ctx.getCacheDir(), "voice");
            if (!dir.exists() && !dir.mkdirs()) {
                r.err = "无法创建临时目录";
                return r;
            }
            tmp = new File(dir, "enc-" + System.nanoTime() + ".mp3");

            int rc = SilkCodec.encode(frames, tmp.getAbsolutePath(), rate);
            if (rc != 0 || !tmp.isFile() || tmp.length() <= 0) {
                r.err = "转码失败 rc=" + rc;
                return r;
            }

            String base = baseName(name);
            r.seconds = (int) Math.max(1, tmp.length() * 8L / Math.max(16, rate) / 1000L);
            r.size = tmp.length();
            String fname = stamp() + "_" + base + ".mp3";

            String shown = publish(ctx, tmp, fname);
            if (shown == null) {
                r.err = "没有可写目录";
                return r;
            }
            r.shown = shown;
            r.file = shown;
            index(ctx, fname, r.size, rate, base, r.seconds);
            Log.a("voice-export: " + fname + " " + r.size + "B rate=" + rate);
            return r;
        } catch (Throwable t) {
            r.err = "转换异常：" + t.getClass().getSimpleName();
            return r;
        } finally {
            // 中间文件一律不留：tmp 只在这一个方法里存在
            try {
                if (tmp != null && tmp.isFile()) tmp.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 落盘：Android 10+ 走 MediaStore（Download/乐信/语音/年月），9 及以下直接写公共目录。 */
    private static String publish(Context ctx, File mp3, String fileName) throws Exception {
        String sub = VOICE + "/" + yearMonth();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + ROOT + "/" + sub);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = null;
            try {
                uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        copy(mp3, os);
                        os.close();
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        ctx.getContentResolver().update(uri, done, null, null);
                        return "Download/" + ROOT + "/" + sub + "/" + fileName;
                    }
                }
            } catch (Throwable t) {
                Log.a("voice-export: mediastore failed " + t.getClass().getSimpleName());
            }
            if (uri != null) {
                try {
                    ctx.getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
        }

        // Android 9（minSdk 28）与 MediaStore 失败时的兜底：公共下载目录（需要存储权限），
        // 再不行退到 App 外部私有目录（任何版本都可写，用户能从文件管理器看到 Android/data）
        File pub = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), ROOT + "/" + sub);
        if (ensureDir(pub)) {
            File out = unique(pub, fileName);
            FileOutputStream os = new FileOutputStream(out);
            try {
                copy(mp3, os);
            } finally {
                os.close();
            }
            return "Download/" + ROOT + "/" + sub + "/" + out.getName();
        }

        File ext = ctx.getExternalFilesDir(null);
        File dir = ext == null ? null : new File(ext, "work/" + sub);
        if (dir == null || !ensureDir(dir)) {
            dir = new File(ctx.getFilesDir(), "work/" + sub);
            if (!ensureDir(dir)) return null;
        }
        File out = unique(dir, fileName);
        FileOutputStream os = new FileOutputStream(out);
        try {
            copy(mp3, os);
        } finally {
            os.close();
        }
        return out.getAbsolutePath();
    }

    /** 索引：一行一条，面板的「工作目录」页读它（TSV 比 JSON 稳，手写不用引号转义）。 */
    private static void index(Context ctx, String file, long size, int rate, String src, int sec) {
        try {
            File dir = new File(ctx.getFilesDir(), "work");
            if (!dir.exists() && !dir.mkdirs()) return;
            File f = new File(dir, "index.tsv");
            FileOutputStream fo = new FileOutputStream(f, true);
            try {
                String line = System.currentTimeMillis() + "\t" + file + "\t" + size + "\t"
                        + rate + "\t" + sec + "\t" + src.replace('\t', ' ').replace('\n', ' ') + "\n";
                fo.write(line.getBytes("UTF-8"));
                fo.flush();
                // 索引别无限长：超过 2000 行就截掉前一半（老文件还在，只是不再列表）
                if (f.length() > 256 * 1024) {
                    trim(f);
                }
            } finally {
                fo.close();
            }
        } catch (Throwable ignored) {
            // 索引写不进去不影响导出本身
        }
    }

    private static void trim(File f) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder keep = new StringBuilder();
            String line;
            int n = 0;
            while ((line = br.readLine()) != null) {
                n++;
                if (n > 1000) keep.append(line).append('\n');
            }
            br.close();
            FileOutputStream fo = new FileOutputStream(f, false);
            try {
                fo.write(keep.toString().getBytes("UTF-8"));
            } finally {
                fo.close();
            }
        } catch (Throwable ignored) {
        }
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    /** 找 SILK 文件头（微信存的是 "\x02#!SILK_V3"），返回帧数据起点；没有头返回 0。 */
    static int silkPayloadOffset(byte[] b) {
        for (int i = 0; i + 9 <= b.length && i < 16; i++) {
            if (b[i] == '#' && b[i + 1] == '!' && b[i + 2] == 'S' && b[i + 3] == 'I'
                    && b[i + 4] == 'L' && b[i + 5] == 'K' && b[i + 6] == '_'
                    && b[i + 7] == 'V' && b[i + 8] == '3') {
                return i + 9;
            }
        }
        return 0;
    }

    private static byte[] slice(byte[] src, int off) {
        byte[] out = new byte[src.length - off];
        System.arraycopy(src, off, out, 0, out.length);
        return out;
    }

    /** 文件名里去掉路径分隔符/扩展名/控制字符，限长 40。 */
    static String baseName(String name) {
        if (name == null) return "voice";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        String low = name.toLowerCase(Locale.ROOT);
        if (low.endsWith(".silk")) name = name.substring(0, name.length() - 5);
        else if (low.endsWith(".amr")) name = name.substring(0, name.length() - 4);
        else if (low.endsWith(".mp3")) name = name.substring(0, name.length() - 4);
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty()) name = "voice";
        if (name.length() > 40) name = name.substring(0, 40);
        return name;
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
    }

    private static String yearMonth() {
        return new SimpleDateFormat("yyyy-MM", Locale.ROOT).format(new Date());
    }

    private static boolean ensureDir(File d) {
        try {
            return d != null && (d.isDirectory() || d.mkdirs());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 同名不覆盖：加 -2/-3 后缀。 */
    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            File g = new File(dir, base + "-" + i + ext);
            if (!g.exists()) return g;
        }
        return new File(dir, base + "-" + System.nanoTime() + ext);
    }

    private static void copy(File src, OutputStream os) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(src);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } finally {
            in.close();
        }
    }

    private VoiceExport() {}
}
