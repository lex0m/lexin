package io.github.lex0m.lexin;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 把选好的背景图提供给 hook 侧：背景必须放在宿主进程能读到的地方。 */
public class BgProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.lex0m.lexin.bg";
    public static final String FILE_NAME = "bg.img";

    /** content://io.github.lex0m.lexin.bg/bg */
    public static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/bg");
    }

    /** content://io.github.lex0m.lexin.bg/config —— 配置镜像（authority 必须与 manifest 一致）。 */
    public static Uri configUri() {
        return Uri.parse("content://" + AUTHORITY + "/config");
    }

    public static final String CONFIG_PATH = "/config";
    private static final String CONFIG_FILE = "cfg.txt";
    /** 微信进程推上来的「适配状态」镜像（跨进程：Status 是进程内 Map，面板在另一个进程读不到）。 */
    private static final String STATUS_FILE = "status.txt";
    /**
     * 设置页（模块进程）改完开关后写在这里的「待合并配置」，宿主进程启动时拉一次合并。
     *
     * 为什么需要：两边存的是两个文件 —— 模块进程写自己的 svc.xml，微信进程写微信的
     * com.tencent.mm_preferences，读的时候又都先读自己那份，于是**在设置页改的开关
     * 微信侧根本看不到**（用户看到的就是"开关改了没反应"）。这条通道把设置页的改动
     * 递过去，由宿主进程写进它自己那份。
     */
    public static final String PUSH_PATH = "/push";
    static final String PUSH_FILE = "push.txt";

    public static File file(android.content.Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    /**
     * 写操作只认「微信进程」和「模块自己」。
     *
     * 这个 provider 必须 exported=true（微信进程要读背景图），所以不能加签名权限；
     * 代价是任何 App 都能碰到它 ⇒ 至少把**写**堵上：第三方应用既不能伪造适配状态
     * （call pushStatus），也不能删掉用户选好的背景图（delete）。
     * getCallingPackage() 在同进程/无法判定时可能返回 null，那种情况放行（功能优先）。
     */
    private boolean trustedCaller() {
        try {
            String p = getCallingPackage();
            if (p == null || p.isEmpty()) return true;
            if (Entry.HOST.equals(p)) return true;
            android.content.Context c = getContext();
            return c != null && p.equals(c.getPackageName());
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 微信进程调它把适配状态推过来（ContentResolver.call）。
     * 面板在模块 App 进程里读 {@link #readStatus}，这是两者之间唯一可行的通道。
     */
    @Override
    public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        try {
            if (getContext() == null) return null;
            if (!trustedCaller()) return null;
            if ("pushStatus".equals(method)) {
                String text = extras == null ? "" : extras.getString("status", "");
                if (text == null || text.isEmpty()) return null;
                File f = new File(getContext().getFilesDir(), STATUS_FILE);
                java.io.FileWriter w = new java.io.FileWriter(f, false);
                w.write(text);
                w.close();
            }
        } catch (Throwable ignored) {
            // 推送失败不影响微信本身
        }
        return null;
    }

    /** 面板读微信进程推上来的状态文本（"功能=1\n功能=0\n"），没有返回空串。 */
    static String readStatus(android.content.Context ctx) {
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

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Storage.diag("provider openFile uri=" + uri + " path=" + uri.getPath()
                + " ctx=" + (getContext() != null));
        if (getContext() == null) throw new FileNotFoundException("no context");
        String path = uri.getPath();
        File f;
        if (CONFIG_PATH.equals(path)) {
            f = new File(getContext().getFilesDir(), CONFIG_FILE);
        } else if (PUSH_PATH.equals(path)) {
            f = new File(getContext().getFilesDir(), PUSH_FILE);
        } else {
            f = file(getContext());
        }
        Storage.diag("  resolved=" + f.getAbsolutePath() + " exists=" + f.exists());
        if (!f.exists()) throw new FileNotFoundException("not found: " + f.getAbsolutePath());
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
        if (getContext() == null) return 0;
        if (!trustedCaller()) return 0;
        // 按路径删对应的那份，别把 /config、/push 的请求变成「删用户的背景图」
        String path = uri == null ? null : uri.getPath();
        File f;
        if (CONFIG_PATH.equals(path)) {
            f = new File(getContext().getFilesDir(), CONFIG_FILE);
        } else if (PUSH_PATH.equals(path)) {
            f = new File(getContext().getFilesDir(), PUSH_FILE);
        } else {
            f = file(getContext());
        }
        return f.exists() && f.delete() ? 1 : 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }

    /** 把选中的流拷进私有位置，成功返回 true。 */
    static boolean save(android.content.Context ctx, InputStream in) {
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
