package io.github.lex0m.lexin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 朋友圈转发：长按朋友圈里的内容 →「乐信」→ 勾选文字 / 图片 / 视频 → 转发到聊天。
 *
 * 几个刻意的取舍：
 *   · 内容从**当前屏幕上的这条动态**取：文字取最长正文（过滤赞/评论/时间），图片取可见 ImageView 的位图
 *     （控件 ≥60dp 排除头像）；不去猜每版都变的 SnsInfo 字段。
 *   · 视频：朋友圈的短视频在微信自己的缓存里是标准 MP4（`cache/<账号>/sns/<桶>/<桶>/sight_*`），
 *     取最近缓存的那一个（2 小时内），先落 MediaStore 再分享；找不到就提示"先点开播放一下"。
 *   · 微信的分享入口 ShareImgUI：`SEND_MULTIPLE` 只收 image/*，文字/图片/视频不能塞进同一个 Intent，
 *     所以按 文字 → 图片 → 视频 排成队列，分享界面关闭后自动发下一项。
 *   · 勾选控件不用系统 CheckBox：微信的 Activity 主题会把 button 画成一条大绿条（截图里压住文字），
 *     这里自己画「✓ / ○」，位置完全可控。
 */
final class SnsForward {

    private static final String T = "svc-snsf:";

    private static final Handler UI = new Handler(Looper.getMainLooper());
    /** 待发送项（文字 → 图片 → 视频）；ShareImgUI 关闭一次发一项。 */
    private static final ArrayDeque<Intent> QUEUE = new ArrayDeque<Intent>();
    private static volatile long lastStart;

    private SnsForward() {
    }

    static void install() {
        try {
            Class<?> c = Class.forName("com.tencent.mm.ui.tools.ShareImgUI", false, Features.loader);
            XposedBridge.hookAllMethods(c, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        fireNext();
                    } catch (Throwable ignored) {
                        // 绝不打断微信
                    }
                }
            });
            Log.d(T + "ShareImgUI hook armed");
        } catch (Throwable t) {
            Log.d(T + "ShareImgUI hook failed: " + t.getClass().getSimpleName());
        }
    }

    private static synchronized Intent poll() {
        return QUEUE.poll();
    }

    private static void fireNext() {
        long dt = System.currentTimeMillis() - lastStart;
        if (dt < 800L) {                       // 同一个分享界面别连发；太近就稍后再试
            UI.postDelayed(new Runnable() {
                @Override
                public void run() {
                    fireNext();
                }
            }, 800L - dt);
            return;
        }
        final Intent next = poll();
        if (next == null) return;
        UI.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = Features.app;
                    if (ctx == null) return;
                    next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    lastStart = System.currentTimeMillis();
                    ctx.startActivity(next);
                    Log.d(T + "next share fired");
                } catch (Throwable t) {
                    Log.d(T + "next share failed: " + t);
                }
            }
        }, 600L);
    }

    static boolean isSnsContext(Context ctx) {
        Activity a = activityOf(ctx);
        if (a == null) return false;
        String n = a.getClass().getName().toLowerCase();
        return n.contains("sns") || n.contains("moment");
    }

    private static Activity activityOf(Context c) {
        int guard = 0;
        while (c instanceof ContextWrapper && guard++ < 10) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** 入口：在朋友圈菜单里点了「乐信」。 */
    static void show(final View anchor) {
        try {
            final Context ctx = anchor.getContext();
            final Activity act = activityOf(ctx);
            if (act == null) {
                toast(ctx, "没拿到朋友圈界面");
                return;
            }
            final View item = itemRoot(anchor);
            String text = pickText(item);
            boolean video = hasVideo(item);
            File videoFile = video ? findRecentVideo() : null;
            // 兜底：有的版本视频位只是普通 ImageView + 播放角标，类名不含 video。
            //   若最近 5 分钟内刚缓存过 sight_（说明刚加载/播放过视频）且这条动态只有一张大图 → 按视频处理。
            if (!video) {
                File recent = findRecentVideo();
                if (recent != null && System.currentTimeMillis() - recent.lastModified() < 5L * 60 * 1000
                        && hasSingleLargeImage(item)) {
                    video = true;
                    videoFile = recent;
                    Log.d(T + "video inferred from recent sight cache: " + recent.getName());
                }
            }
            List<Bitmap> images = video ? new ArrayList<Bitmap>() : pickImages(item);
            Log.d(T + "capture: item=" + item.getClass().getName() + " text=" + (text == null ? -1 : text.length())
                    + " imgs=" + images.size() + " video=" + video + " file=" + (videoFile == null ? "null" : videoFile.getAbsolutePath()));
            if (video && videoFile == null) {
                toast(ctx, "视频还没缓存，先点开播放一下再长按");
            }
            if ((text == null || text.trim().isEmpty()) && images.isEmpty() && videoFile == null) {
                if (!video) toast(ctx, "这条朋友圈没识别到文字/图片/视频");
                return;
            }
            buildDialog(act, text, images, videoFile).show();
        } catch (Throwable t) {
            Log.d(T + "show failed: " + t);
        }
    }

    // ───────────────────────── 内容提取 ─────────────────────────

    private static View itemRoot(View v) {
        View cur = v;
        for (int i = 0; i < 12; i++) {
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) break;
            String pn = p.getClass().getName();
            if (pn.contains("ListView") || pn.contains("RecyclerView") || pn.contains("AbsListView")) return cur;
            View pv = (View) p;
            if (pv.getHeight() > cur.getResources().getDisplayMetrics().heightPixels * 0.8) break;
            cur = pv;
        }
        return cur;
    }

    private static String pickText(View root) {
        List<String> out = new ArrayList<>();
        collectText(root, out, 0);
        String best = null;
        for (String s : out) if (best == null || s.length() > best.length()) best = s;
        return best;
    }

    private static void collectText(View v, List<String> out, int depth) {
        if (v == null || depth > 12 || !v.isShown()) return;
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null) {
                String s = cs.toString().trim();
                if (s.length() >= 2 && !isChrome(s)) out.add(s);
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectText(g.getChildAt(i), out, depth + 1);
        }
    }

    private static boolean isChrome(String s) {
        String[] bad = {"赞", "评论", "全文", "收起", "翻译", "举报", "删除", "复制",
                "收藏", "分享", "更多", "详情", "刚刚", "昨天", "前天", "小时前", "分钟前", "视频"};
        for (String b : bad) if (s.equals(b) || s.startsWith(b)) return true;
        if (s.matches("^\\d+[秒分时天].*")) return true;
        if (s.matches("^\\d{1,2}月\\d{1,2}日.*")) return true;
        if (s.matches("^\\d{4}年.*")) return true;
        return false;
    }

    private static List<Bitmap> pickImages(View root) {
        List<Bitmap> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        collectImages(root, out, seen, 0);
        return out.size() > 9 ? new ArrayList<Bitmap>(out.subList(0, 9)) : out;
    }

    private static void collectImages(View v, List<Bitmap> out, Set<Integer> seen, int depth) {
        if (v == null || depth > 12 || !v.isShown() || out.size() >= 9) return;
        int w = v.getWidth(), h = v.getHeight();
        if (w >= dp(v.getContext(), 60) && h >= dp(v.getContext(), 60)) {
            Drawable d = v instanceof ImageView ? ((ImageView) v).getDrawable() : v.getBackground();
            Bitmap b = bitmapOf(d);
            if (b != null && !b.isRecycled() && b.getWidth() >= 120 && b.getHeight() >= 120) {
                if (seen.add(System.identityHashCode(b))) out.add(b);
                return;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectImages(g.getChildAt(i), out, seen, depth + 1);
        }
    }

    /** 从任意 Drawable 抠出 Bitmap：不限于 BitmapDrawable（微信自绘/异步加载的图常不是标准 BitmapDrawable）。 */
    private static Bitmap bitmapOf(Drawable d) {
        if (d == null) return null;
        try {
            if (d instanceof BitmapDrawable) {
                Bitmap b = ((BitmapDrawable) d).getBitmap();
                if (b != null && !b.isRecycled()) return b;
            }
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) return null;
            Bitmap out = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            d.setBounds(0, 0, iw, ih);
            d.draw(c);
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 这条动态里是否只有一张大图（视频封面通常是这样；普通多图/小图不算）。 */
    private static boolean hasSingleLargeImage(View root) {
        final int[] count = {0};
        final int screenW = root.getResources().getDisplayMetrics().widthPixels;
        countLargeImages(root, count, screenW, 0);
        return count[0] == 1;
    }

    private static void countLargeImages(View v, int[] count, int screenW, int depth) {
        if (v == null || depth > 12 || !v.isShown() || count[0] > 1) return;
        if (v instanceof ImageView) {
            if (v.getWidth() >= screenW / 2 && v.getHeight() >= dp(v.getContext(), 120)) count[0]++;
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) countLargeImages(g.getChildAt(i), count, screenW, depth + 1);
        }
    }

    /** 这条动态里有没有视频（播放器/贴图/播放按钮）。 */
    private static boolean hasVideo(View v) {
        return findVideoView(v, 0) != null;
    }

    private static View findVideoView(View v, int depth) {
        if (v == null || depth > 14 || !v.isShown()) return null;
        String cn = v.getClass().getName().toLowerCase();
        if (cn.contains("video") || cn.contains("sight")
                || v instanceof android.view.TextureView || v instanceof android.view.SurfaceView) {
            return v;
        }
        CharSequence cd = v.getContentDescription();
        if (cd != null) {
            String s = cd.toString();
            if (s.contains("视频") || s.contains("播放")) return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findVideoView(g.getChildAt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /**
     * 朋友圈短视频在 `cache/<账号>/sns/<桶>/<桶>/sight_*`（标准 MP4）。
     * 取 2 小时内最新的一个；找不到就当没缓存。
     */
    private static File findRecentVideo() {
        File best = null;
        long now = System.currentTimeMillis();
        for (String base : new String[]{"/data/data/com.tencent.mm/cache", "/data/user/0/com.tencent.mm/cache"}) {
            File root = new File(base);
            File[] accts = root.listFiles();
            if (accts == null) continue;
            for (File acct : accts) {
                File sns = new File(acct, "sns");
                if (!sns.isDirectory()) continue;
                best = scanSight(sns, best, now, 0);
            }
        }
        return best;
    }

    private static File scanSight(File dir, File best, long now, int depth) {
        if (dir == null || depth > 5) return best;
        File[] fs = dir.listFiles();
        if (fs == null) return best;
        for (File f : fs) {
            if (f.isDirectory()) {
                best = scanSight(f, best, now, depth + 1);
                continue;
            }
            if (!f.getName().startsWith("sight_")) continue;
            long len = f.length();
            long mt = f.lastModified();
            if (len < 200 * 1024 || now - mt > 2L * 3600 * 1000) continue;
            if (best == null || mt > best.lastModified()) best = f;
        }
        return best;
    }

    // ───────────────────────── 选择对话框 ─────────────────────────

    private static final class Toggle {
        final View row;
        final TextView mark;
        boolean on = true;

        Toggle(View row, TextView mark) {
            this.row = row;
            this.mark = mark;
            render();
        }

        void render() {
            mark.setText(on ? "✓" : "○");
            mark.setTextColor(on ? 0xFF07C160 : 0xFF9E9E9E);
        }

        void flip() {
            on = !on;
            render();
        }
    }

    private static Toggle addRow(LinearLayout parent, Context ctx, String label, Bitmap thumb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10));

        if (thumb != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageBitmap(thumb);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(ctx, 56), dp(ctx, 56));
            row.addView(iv, ilp);
        }
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTextColor(0xFF1A1A1A);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(ctx, 10);
        row.addView(tv, tlp);

        TextView mark = new TextView(ctx);
        mark.setTextSize(20);
        mark.setGravity(Gravity.CENTER);
        mark.setWidth(dp(ctx, 40));
        row.addView(mark);

        final Toggle t = new Toggle(row, mark);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                t.flip();
            }
        });
        parent.addView(row);
        return t;
    }

    private static AlertDialog buildDialog(final Activity act, final String text,
                                           final List<Bitmap> images, final File video) {
        final Context ctx = act;
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 14);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        final List<Toggle> toggles = new ArrayList<Toggle>();
        final Toggle textT = (text != null && !text.trim().isEmpty())
                ? addRow(root, ctx, "文字（" + text.length() + " 字）", null) : null;
        if (textT != null) {
            TextView preview = new TextView(ctx);
            preview.setText(text.length() > 120 ? text.substring(0, 120) + "…" : text);
            preview.setTextSize(12);
            preview.setTextColor(0xFF666666);
            preview.setPadding(dp(ctx, 10), 0, dp(ctx, 10), dp(ctx, 8));
            root.addView(preview);
            toggles.add(textT);
        }

        final List<Toggle> imgToggles = new ArrayList<Toggle>();
        for (int i = 0; i < images.size(); i++) {
            Toggle t = addRow(root, ctx, "图片 " + (i + 1), images.get(i));
            imgToggles.add(t);
            toggles.add(t);
        }
        final Toggle videoT;
        if (video != null) {
            videoT = addRow(root, ctx, "视频（" + (video.length() / 1024 / 1024 + 1) + " MB）", null);
            toggles.add(videoT);
        } else {
            videoT = null;
        }

        // ★ 一键全部转发：跳过勾选，直接把这条动态的文字 + 全部图片 + 视频一次发完。
        final AlertDialog[] dlgHolder = new AlertDialog[1];
        TextView allBtn = new TextView(ctx);
        allBtn.setText("一键全部转发（" + toggles.size() + " 项）");
        allBtn.setTextSize(15);
        allBtn.setTextColor(0xFF07C160);
        allBtn.setGravity(Gravity.CENTER);
        allBtn.setPadding(0, dp(ctx, 12), 0, dp(ctx, 2));
        root.addView(allBtn);
        allBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send(act, text, new ArrayList<Bitmap>(images), video);
                if (dlgHolder[0] != null) dlgHolder[0].dismiss();
            }
        });

        AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                .setTitle("转发朋友圈内容")
                .setView(scroll)
                .setPositiveButton("转发", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String selText = (textT != null && textT.on) ? text : null;
                        List<Bitmap> selImgs = new ArrayList<Bitmap>();
                        for (int i = 0; i < imgToggles.size(); i++) {
                            if (imgToggles.get(i).on) selImgs.add(images.get(i));
                        }
                        File selVideo = (videoT != null && videoT.on) ? video : null;
                        if (selText == null && selImgs.isEmpty() && selVideo == null) {
                            toast(ctx, "什么都没选");
                            return;
                        }
                        send(act, selText, selImgs, selVideo);
                    }
                })
                .setNegativeButton("取消", null);
        if (toggles.size() > 1) {
            b.setNeutralButton("全选/全不选", null);
        }
        final AlertDialog dlg = b.create();
        dlgHolder[0] = dlg;
        if (toggles.size() > 1) {
            dlg.setOnShowListener(new DialogInterface.OnShowListener() {
                @Override
                public void onShow(DialogInterface d) {
                    dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            boolean all = true;
                            for (Toggle t : toggles) if (!t.on) all = false;
                            for (Toggle t : toggles) {
                                t.on = !all;
                                t.render();
                            }
                        }
                    });
                }
            });
        }
        return dlg;
    }

    // ───────────────────────── 发送 ─────────────────────────

    private static void send(final Activity act, final String text,
                             final List<Bitmap> images, final File video) {
        try {
            toast(act, "正在准备…");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final ArrayDeque<Intent> q = new ArrayDeque<Intent>();
                    if (text != null) q.add(textIntent(text));
                    if (!images.isEmpty()) {
                        Intent i = imageIntent(images);
                        if (i != null) q.add(i);
                    }
                    if (video != null) {
                        Intent i = videoIntent(video);
                        if (i != null) q.add(i);
                    }
                    UI.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (q.isEmpty()) {
                                    toast(act, "没有可发送的内容");
                                    return;
                                }
                                synchronized (SnsForward.class) {
                                    QUEUE.clear();
                                    QUEUE.addAll(q);
                                }
                                Intent first = poll();
                                lastStart = System.currentTimeMillis();
                                act.startActivity(first);
                                if (!q.isEmpty()) {
                                    toast(act, "共 " + (q.size() + 1) + " 项，依次选聊天发送");
                                }
                            } catch (Throwable t) {
                                Log.d(T + "start share failed: " + t);
                                toast(act, "转发失败：" + t.getClass().getSimpleName());
                            }
                        }
                    });
                }
            }, "lexin-sns-share").start();
        } catch (Throwable t) {
            Log.d(T + "send failed: " + t);
            toast(act, "转发失败：" + t.getClass().getSimpleName());
        }
    }

    private static Intent textIntent(String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        i.setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static Intent imageIntent(List<Bitmap> images) {
        ArrayList<Uri> uris = new ArrayList<Uri>();
        ContentResolver cr = Features.app.getContentResolver();
        for (int i = 0; i < images.size(); i++) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME,
                        "lexin_sns_" + System.currentTimeMillis() + "_" + (i + 1) + ".png");
                v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
                if (Build.VERSION.SDK_INT >= 29) {
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/乐信导出");
                }
                Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) continue;
                OutputStream os = cr.openOutputStream(uri);
                if (os == null) continue;
                images.get(i).compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
                uris.add(uri);
            } catch (Throwable t) {
                Log.d(T + "save image " + i + " failed: " + t);
            }
        }
        Log.d(T + "image intent uris=" + uris.size());
        if (uris.isEmpty()) return null;
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
        i.setType("image/png");
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        i.setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /** 视频先落到 MediaStore.Video（Movies/乐信导出），再以 video/mp4 分享。 */
    private static Intent videoIntent(File video) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, "lexin_sns_" + System.currentTimeMillis() + ".mp4");
            v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= 29) {
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/乐信导出");
            }
            ContentResolver cr = Features.app.getContentResolver();
            Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) return null;
            FileInputStream in = new FileInputStream(video);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            in.close();
            os.close();
            Log.d(T + "video saved uri=" + uri + " size=" + video.length());
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("video/mp4");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        } catch (Throwable t) {
            Log.d(T + "videoIntent failed: " + t);
            return null;
        }
    }

    private static void toast(Context ctx, String s) {
        try {
            Toast.makeText(ctx, s, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
