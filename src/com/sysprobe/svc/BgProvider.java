package com.sysprobe.svc;

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

    public static final String AUTHORITY = "com.sysprobe.svc.bg";
    public static final String FILE_NAME = "bg.img";

    /** content://com.sysprobe.svc.bg/bg */
    public static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/bg");
    }

    /** content://com.sysprobe.svc.bg/config —— 配置镜像（authority 必须与 manifest 一致）。 */
    public static Uri configUri() {
        return Uri.parse("content://" + AUTHORITY + "/config");
    }

    public static final String CONFIG_PATH = "/config";
    private static final String CONFIG_FILE = "cfg.txt";

    public static File file(android.content.Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
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
        File f = CONFIG_PATH.equals(uri.getPath())
                ? new File(getContext().getFilesDir(), CONFIG_FILE)
                : file(getContext());
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
        File f = file(getContext());
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
