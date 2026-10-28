package io.github.lex0m.lexin;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 语音转 MP3（在**乐信自己的进程**里做）。
 *
 * 两个必须遵守的约束（都是上一版踩出来的）：
 *  ① 微信进程保持零 native：解码/编码只在 App 进程做，微信侧只把字节递过来。
 *  ② App 只有 INTERNET 权限（targetSdk 35），**读写不了 /sdcard/Download** ✗
 *     ⇒ 输入用 Intent 里的 byte[]（语音文件都很小，几十 KB），
 *       输出走 MediaStore（Android 10+ 无需存储权限），写不进才退到 App 私有目录。
 *
 * 调用方：微信里的模块把 SILK 字节放进 `silk_bytes`；老路径 `silk`（绝对路径）保留作兜底。
 */
public class ConvertActivity extends Activity {

    /** 线程池：一次只转一个，避免连点几次把 CPU 打满。 */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final int MAX_INPUT = 4 * 1024 * 1024;

    private static boolean libLoaded = true;

    static {
        try {
            System.loadLibrary("lexinaudio");
        } catch (Throwable t) {
            libLoaded = false;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent it = getIntent();
        final byte[] bytes = it == null ? null : it.getByteArrayExtra("silk_bytes");
        final String path = it == null ? null : it.getStringExtra("silk");
        final String name = it == null ? null : it.getStringExtra("name");
        Log.a("conv: bytes=" + (bytes == null ? -1 : bytes.length) + " path=" + path + " name=" + name);

        // ★ 文件 IO / 转码一律在后台线程（UI 线程做 IO 会 ANR，也会被系统判成卡死 ✗）
        IO.execute(new Runnable() {
            @Override
            public void run() {
                String tip;
                try {
                    tip = convert(bytes, path, name);
                } catch (Throwable t) {
                    tip = "转换失败：" + t.getClass().getSimpleName();
                }
                final String msg = tip;
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Toast.makeText(ConvertActivity.this, msg, Toast.LENGTH_LONG).show();
                        } catch (Throwable ignored) {
                        }
                        finish();
                    }
                });
            }
        });
    }

    private String convert(byte[] bytes, String path, String name) throws Exception {
        if (!libLoaded) return "转码库未加载（ABI 不匹配或安装包损坏）";

        byte[] raw = bytes;
        if (raw == null || raw.length == 0) {
            if (path == null || path.isEmpty()) return "没有收到语音数据";
            File f = new File(path);
            if (!f.isFile()) return "语音文件不存在：" + f.getName();
            if (f.length() <= 0 || f.length() > MAX_INPUT) return "语音文件大小异常：" + f.length() + "B";
            raw = readAll(f);
            if (name == null || name.isEmpty()) name = f.getName();
        }
        if (raw.length > MAX_INPUT) return "语音数据过大：" + raw.length + "B";

        // SILK 文件 = 可选的自定义头 + "#!SILK_V3" + 帧数据；解码器只吃帧数据
        int off = silkPayloadOffset(raw);
        byte[] frames;
        if (off > 0) {
            frames = new byte[raw.length - off];
            System.arraycopy(raw, off, frames, 0, frames.length);
        } else {
            frames = raw;
        }
        if (frames.length < 8) return "语音数据为空（" + raw.length + "B）";

        String base = cleanName(name);
        File temp = new File(getCacheDir(), "mp3");
        if (!temp.exists() && !temp.mkdirs()) return "无法创建临时目录";
        File mp3 = new File(temp, base + "-" + System.currentTimeMillis() + ".mp3");

        // ★ JNI 符号必须与 .so 导出的一致：Java_io_github_lex0m_lexin_SilkCodec_silkToMp3
        //   （当前 .so 是按 SilkCodec 这个名字编的；改成别的类名会 UnsatisfiedLinkError ✗）
        int rc = SilkCodec.encode(frames, mp3.getAbsolutePath(), 32);
        Log.a("conv: frames=" + frames.length + " rc=" + rc + " mp3=" + mp3.length());
        if (rc != 0 || !mp3.isFile() || mp3.length() <= 0) {
            deleteQuietly(mp3);
            return "转码失败 rc=" + rc + "（输入 " + frames.length + "B）";
        }

        try {
            String where = publish(mp3, base + ".mp3");
            Log.a("conv: published " + where);
            return "语音已转成 MP3：" + where + "（" + Math.max(1, mp3.length() / 1024) + "KB）";
        } finally {
            deleteQuietly(mp3);
        }
    }

    /** Android 10+ 走 MediaStore（无需任何存储权限）；写不进去再退到 App 私有目录。 */
    private String publish(File mp3, String fileName) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/乐信导出");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = null;
            try {
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        copy(mp3, os);
                        os.close();
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        getContentResolver().update(uri, done, null, null);
                        return "Download/乐信导出/" + fileName;
                    }
                }
            } catch (Throwable ignored) {
                // 落到下面的私有目录
            }
            if (uri != null) {
                try {
                    getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
        }

        File ext = getExternalFilesDir(null);
        File dir = ext == null ? null : new File(ext, "乐信导出");
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            dir = new File(getFilesDir(), "乐信导出");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("没有可写目录");
        }
        File out = new File(dir, fileName);
        FileOutputStream os = new FileOutputStream(out);
        try {
            copy(mp3, os);
        } finally {
            os.close();
        }
        return out.getAbsolutePath();
    }

    private static int silkPayloadOffset(byte[] b) {
        for (int i = 0; i + 9 <= b.length && i < 16; i++) {
            if (b[i] == '#' && b[i + 1] == '!' && b[i + 2] == 'S' && b[i + 3] == 'I'
                    && b[i + 4] == 'L' && b[i + 5] == 'K' && b[i + 6] == '_'
                    && b[i + 7] == 'V' && b[i + 8] == '3') {
                return i + 9;
            }
        }
        return 0;
    }

    private static String cleanName(String name) {
        if (name == null) return "voice";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        String low = name.toLowerCase();
        if (low.endsWith(".silk")) name = name.substring(0, name.length() - 5);
        else if (low.endsWith(".amr")) name = name.substring(0, name.length() - 4);
        else if (low.endsWith(".mp3")) name = name.substring(0, name.length() - 4);
        if (name.isEmpty()) name = "voice";
        return name;
    }

    private static byte[] readAll(File f) throws Exception {
        byte[] all = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int n = 0, r;
            while (n < all.length && (r = in.read(all, n, all.length - n)) > 0) n += r;
            if (n != all.length) throw new Exception("读取不完整");
            return all;
        } finally {
            in.close();
        }
    }

    private static void copy(File src, OutputStream os) throws Exception {
        InputStream in = new FileInputStream(src);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } finally {
            in.close();
        }
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.isFile()) f.delete();
        } catch (Throwable ignored) {
        }
    }
}
