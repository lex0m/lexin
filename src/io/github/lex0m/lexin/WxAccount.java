package io.github.lex0m.lexin;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.io.File;

/**
 * 当前登录的微信账号（昵称 / wxid / 头像）。
 *
 * 数据来源都是微信自己的东西，不新开任何存储：
 *   · 昵称、wxid：微信自己的 com.tencent.mm_preferences（键 last_login_nick_name /
 *     login_weixin_username）—— 宿主进程直接读得到，零反射、零风险
 *   · 头像：微信自己的头像缓存 MicroMsg/&lt;账号目录&gt;/avatar/&lt;xx&gt;/&lt;yy&gt;/user_*.png
 *     （user_ 前缀就是"本人头像"，改过头像会有历史文件，取最新的那个）
 *
 * 读不到就返回 null，调用方退回到「欢迎使用乐信」。
 */
final class WxAccount {

    private static final String HOST_PREFS = "com.tencent.mm_preferences";
    private static final String K_NICK = "last_login_nick_name";
    private static final String K_WXID = "login_weixin_username";
    private static final String K_ALIAS = "last_login_alias";


    private static volatile String nick;
    private static volatile String wxid;
    private static volatile Bitmap avatar;
    private static volatile boolean avatarTried = false;

    /** 昵称里常带不可见占位字符（微信昵称允许），显示前清掉。 */
    static String clean(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u3164' || c == '\uFEFF' || (c >= '\u200B' && c <= '\u200F')
                    || c == '\u2060' || c == '\u00A0') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    static String nickname(Context host) {
        sync(host);
        return nick;
    }

    static String wxid(Context host) {
        sync(host);
        return wxid;
    }

    /** 微信号 / 别名，昵称取不到时用得上。 */
    static String alias(Context host) {
        return read(host, K_ALIAS);
    }

    /**
     * 读一次缓存一次，但**登录账号变了要作废**：
     * 否则切号之后面板还顶着上一个号的昵称和头像。
     */
    private static void sync(Context host) {
        String now = read(host, K_WXID);
        if (now != null && !now.isEmpty() && !now.equals(wxid)) {
            avatar = null;
            avatarTried = false;
            wxid = now;
            nick = read(host, K_NICK);
            return;
        }
        if (wxid == null) wxid = now;
        if (nick == null) nick = read(host, K_NICK);
    }

    private static String read(Context host, String key) {
        try {
            SharedPreferences sp = host.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE);
            String v = sp.getString(key, null);
            return clean(v);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 本人头像；找不到返回 null。只解一次，缓存在内存里。 */
    static Bitmap avatar(Context host) {
        if (avatarTried) return avatar;
        avatarTried = true;
        try {
            // 每次都按 wxid 重新算（就是几次 stat，很快）。
            // 以前这里缓存过一次路径，结果把上一版算错的文件缓存了下来 —— 头像就一直不对。
            File f = ownAvatarFile(host);
            if (f == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int size = Math.max(o.outWidth, o.outHeight);
            o.inJustDecodeBounds = false;
            o.inSampleSize = size > 192 ? 2 : 1;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            avatar = bmp;
            Log.d("svc-acc: avatar " + (bmp == null ? "decode-failed" : bmp.getWidth() + "px"));
        } catch (Throwable ignored) {
            // 没头像也能用
        }
        return avatar;
    }

    /**
     * 本人头像的确定路径。
     *
     * 微信的头像缓存布局（8.0.x 实测）：
     *   MicroMsg/<账号目录>/avatar/<md5[0:2]>/<md5[2:4]>/user_<md5>.png
     * 其中 md5 = md5(wxid)。同一层还有 user_hd_<md5>.png（高清版）。
     *
     * v1.1 初版图省事取的是「全盘最新的 user_*.png」—— 那是错的：
     * 别人换头像也会生成 user_* 文件，于是面板上顶了别人的头像。
     * 现在按 wxid 算出唯一路径，算不出来就宁可没有头像。
     */
    private static File ownAvatarFile(Context host) {
        String id = wxid(host);
        if (id == null || id.isEmpty()) return null;
        String md5 = md5(id);
        if (md5 == null || md5.length() != 32) return null;
        String b1 = md5.substring(0, 2);
        String b2 = md5.substring(2, 4);

        try {
            File dataDir = host.getFilesDir().getParentFile();       // /data/data/com.tencent.mm
            if (dataDir == null) return null;
            File[] accounts = new File(dataDir, "MicroMsg").listFiles();
            if (accounts == null) return null;

            for (File acc : accounts) {
                File root = new File(new File(acc, "avatar"), b1 + "/" + b2);
                if (!root.isDirectory()) continue;
                File[] tries = {
                        new File(root, "user_" + md5 + ".png"),
                        new File(root, "user_hd_" + md5 + ".png"),
                        new File(new File(root, md5), "user_" + md5 + ".png"),
                        new File(new File(root, md5), "hd_" + md5),
                        new File(root, "user_hd_" + md5),
                };
                for (File f : tries) {
                    if (f.isFile() && f.length() > 0) return f;
                }
            }
        } catch (Throwable ignored) {
            // 取不到就不显示头像
        }
        return null;
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 圆头像（带一圈白边），空图返回 null。 */
    static Bitmap circle(Bitmap src, int sizePx) {
        if (src == null || sizePx <= 0) return null;
        try {
            Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setFilterBitmap(true);

            int side = Math.min(src.getWidth(), src.getHeight());
            int left = (src.getWidth() - side) / 2;
            int top = (src.getHeight() - side) / 2;
            Rect srcRect = new Rect(left, top, left + side, top + side);

            float ring = sizePx * 0.06f;
            RectF dst = new RectF(ring, ring, sizePx - ring, sizePx - ring);
            c.drawBitmap(src, srcRect, dst, p);

            // 把方图裁成圆：用 DST_IN 刷掉圆外
            Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
            cut.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.DST_IN));
            c.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - ring, cut);
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private WxAccount() {}
}
