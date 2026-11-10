package io.github.lex0m.lexin;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Map;

/**
 * 模块侧与微信进程之间**唯一**的跨进程通道（v1.5 加固）。
 *
 * 三件事：
 *   1. {@code /bg}     背景图（可读可写：写由模块进程落盘，微信进程不碰自己的目录）
 *   2. {@code /all}    配置全量：{@code call("getAll")} 读、{@code call("put")} 写
 *   3. {@code /status} 适配状态镜像（微信进程 → 模块 App 的面板）
 *
 * 校验（v1.5）：一律按 {@link Binder#getCallingUid()} 判定，只放行「本模块」与「微信」两个 uid，
 * 判定失败 **fail-closed**（拒绝）。v1.4 的 {@code getCallingPackage()} 判定是 fail-open 的
 * （包名为空或抛异常都放行，且 {@code openFile} 压根没校验），实测任意 shell 能直接读走整份配置。
 */
public class BgProvider extends ContentProvider {

    /** authority 保持原名（改它要动 manifest，收益低、出错面大）。 */
    public static final String AUTHORITY = "io.github.lex0m.lexin.bg";

    public static final String PATH_BG = "/bg";
    /** 聊天条卡片图（与背景图同一条通道，只是换个目标文件）。 */
    public static final String PATH_CARD = "/card";
    public static final String PATH_ALL = "/all";
    public static final String CONFIG_PATH = "/config";
    public static final String PUSH_PATH = "/push";

    public static final String KEY_KV = "kv";
    public static final String KEY_KEY = "k";
    public static final String KEY_VAL = "v";

    public static final String FILE_NAME = "bg.img";
    private static final String CARD_NAME = "card.img";
    private static final String CONFIG_FILE = "cfg.txt";
    private static final String STATUS_FILE = "status.txt";
    static final String PUSH_FILE = "push.txt";

    public static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + PATH_BG);
    }

    public static Uri uriOf(String path) {
        return Uri.parse("content://" + AUTHORITY + path);
    }

    public static Uri configUri() {
        return uriOf(CONFIG_PATH);
    }

    /** 仅供模块进程内部使用（provider 自己的 context）。外部调用者请走 {@link #uri()}。 */
    static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    static File cardFile(Context ctx) {
        return new File(ctx.getFilesDir(), CARD_NAME);
    }

    /**
     * 调用方判定：本模块 uid 或微信 uid，其余一律拒绝。
     * **fail-closed**：拿不到 uid / 查不到微信 uid 时拒绝，不做"功能优先"的放行。
     */
    /** 微信 uid 只查一次（每次调用都走 PackageManager 太贵，热路径上尤其）。-2=未查。 */
    private static volatile int hostUid = -2;

    private int hostUid() {
        int v = hostUid;
        if (v != -2) return v;
        try {
            Context c = getContext();
            v = c == null ? -1 : c.getPackageManager().getPackageUid(Entry.HOST, 0);
        } catch (Throwable t) {
            v = -1;
        }
        hostUid = v;
        return v;
    }

    private boolean trustedCaller() {
        try {
            int uid = Binder.getCallingUid();
            if (uid == android.os.Process.myUid()) return true;
            int host = hostUid();
            return host > 0 && uid == host;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    // ── 配置通道 ─────────────────────────────────────────────────────────────

    /**
     * getAll：把模块侧的整份配置以 {@code k=encodedValue} 列表返回给微信进程。
     * put：微信进程改配置时调它，由模块进程落盘（微信进程零落盘）。
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            android.util.Log.e("pb", "call " + method + " ctx=" + (getContext() != null));
            if (getContext() == null || !trustedCaller()) { android.util.Log.e("pb", "denied uid=" + Binder.getCallingUid() + " host=" + hostUid()); return null; }
            Storage.init(getContext());
            if ("getAll".equals(method)) return getAll();
            if ("put".equals(method)) return put(extras);
            if ("pushStatus".equals(method)) return pushStatus(extras);
        } catch (Throwable ignored) {
            // 任何异常都不向上抛：调用方拿不到数据就回落默认值
        }
        return null;
    }

    private Bundle getAll() {
        ArrayList<String> kv = new ArrayList<>();
        // Storage 提供只读快照，provider 不直接碰内部 Map
        for (Map.Entry<String, String> e : Storage.snapshot().entrySet()) {
            kv.add(e.getKey() + "=" + e.getValue());
        }
        Bundle out = new Bundle();
        out.putStringArrayList(KEY_KV, kv);
        return out;
    }

    private Bundle put(Bundle in) {
        if (in == null) return null;
        String k = in.getString(KEY_KEY);
        String v = in.getString(KEY_VAL);
        android.util.Log.e("pb", "put k=" + k + " v=" + v);
        if (k == null || v == null) return null;
        Storage.putEncoded(k, v);
        return null;
    }

    private Bundle pushStatus(Bundle extras) {
        String text = extras == null ? "" : extras.getString("status", "");
        if (text == null || text.isEmpty()) return null;
        java.io.FileWriter w = null;
        try {
            File f = new File(getContext().getFilesDir(), STATUS_FILE);
            w = new java.io.FileWriter(f, false);
            w.write(text);
        } catch (Throwable ignored) {
            // 状态推不过来不影响微信本身
        } finally {
            try { if (w != null) w.close(); } catch (Throwable ignored) { }
        }
        return null;
    }

    /** 面板读微信进程推上来的状态文本（"功能=1\n功能=0\n"），没有返回空串。 */
    static String readStatus(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), STATUS_FILE);
            if (!f.exists() || f.length() <= 0) return "";
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            return n > 0 ? new String(b, 0, n, "UTF-8") : "";
        } catch (Throwable t) {
            return "";
        }
    }

    // ── 文件通道（背景图） ───────────────────────────────────────────────────

    /**
     * {@code /bg} 支持两端：
     *   · 微信进程读 → 拿到模块侧那张图（PFD 由模块进程打开，微信不需要任何目录权限）
     *   · 微信进程写 → 面板里选图时走这条路，字节由模块进程写进自己的 files/，**不落微信目录**
     */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("no context");
        if (!trustedCaller()) throw new FileNotFoundException("denied");

        String path = uri == null ? null : uri.getPath();
        File f;
        if (CONFIG_PATH.equals(path)) f = new File(ctx.getFilesDir(), CONFIG_FILE);
        else if (PUSH_PATH.equals(path)) f = new File(ctx.getFilesDir(), PUSH_FILE);
        else if (PATH_CARD.equals(path)) f = cardFile(ctx);
        else f = file(ctx);

        boolean write = mode != null && (mode.contains("w") || mode.contains("a"));
        if (write) {
            // 写入统一走 truncate（避免半截图）；只允许图片类目标
            if (!PATH_BG.equals(path) && !PATH_CARD.equals(path)) throw new FileNotFoundException("denied");
            try {
                FileOutputStream trunc = new FileOutputStream(f, false);
                trunc.close();
            } catch (Throwable t) {
                throw new FileNotFoundException("open failed");
            }
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        if (!f.exists() || f.length() <= 0) throw new FileNotFoundException("not found");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "image/*";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        Context ctx = getContext();
        if (ctx == null || !trustedCaller()) return 0;
        String path = uri == null ? null : uri.getPath();
        File f;
        if (CONFIG_PATH.equals(path)) f = new File(ctx.getFilesDir(), CONFIG_FILE);
        else if (PUSH_PATH.equals(path)) f = new File(ctx.getFilesDir(), PUSH_FILE);
        else if (PATH_CARD.equals(path)) f = cardFile(ctx);
        else f = file(ctx);
        return f.exists() && f.delete() ? 1 : 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }

    /** 把选中的流拷进模块私有位置，成功返回 true（只给模块进程内部用）。 */
    static boolean save(Context ctx, InputStream in) {
        try {
            File out = file(ctx);
            FileOutputStream fos = new FileOutputStream(out, false);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            return out.length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
