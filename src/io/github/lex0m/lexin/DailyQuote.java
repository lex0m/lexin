package io.github.lex0m.lexin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

/**
 * 面板顶部的「每日一言」。
 *
 * 每次打开都换一句，风格是混着来的：
 *   · 每次打开先从本地句库里**随机**挑一句（诗词/古籍/现代/技术/幽默/励志/哲理都有），
 *     保证离线也有、而且每次都不一样；
 *   · 同时后台去公开接口（一言 hitokoto）拿一句新的，分类也是随机挑的，
 *     拿回来就替换显示，并塞进本地环形缓存（最近 10 条），下次打开还能随机取到。
 *   · 8 分钟内不重复请求接口，避免打开一次刷一次。
 *
 * 接口是公开的、不带任何身份信息，也不回传任何本地数据。
 */
final class DailyQuote {

    private static final String T = "svc-quote: ";
    private static final String API = "https://v1.hitokoto.cn/?encode=json&charset=utf-8&c=";
    private static final String API2 = "https://international.v1.hitokoto.cn/?encode=json&c=";
    /** 一言的分类码：文学 / 诗词 / 哲学 / 抖机灵 / 网易云 / 网络 / 影视 / 原创。 */
    private static final String[] CATEGORIES = {"d", "i", "k", "l", "j", "f", "h", "e"};

    private static final String K_HIST = "q_h";       // 最近取到的外部句子（JSON 数组）
    private static final String K_DAY = "q_d";        // 上次取到的天（仅用于统计，不参与节流判断）
    private static final int HIST_MAX = 10;
    private static final long MIN_GAP_MS = 8 * 60 * 1000L;

    /** 本地句库：text / 出处 / 风格（风格只用于内部轮换，不显示）。 */
    private static final String[][] LOCAL = {
            {"路虽远行则将至，事虽难做则必成。", "荀子", "古籍"},
            {"不积跬步，无以至千里；不积小流，无以成江海。", "荀子", "古籍"},
            {"博观而约取，厚积而薄发。", "苏轼", "古籍"},
            {"纸上得来终觉浅，绝知此事要躬行。", "陆游", "诗词"},
            {"会当凌绝顶，一览众山小。", "杜甫", "诗词"},
            {"长风破浪会有时，直挂云帆济沧海。", "李白", "诗词"},
            {"海内存知己，天涯若比邻。", "王勃", "诗词"},
            {"遇酒且呵呵，人生能几何。", "韦庄", "诗词"},
            {"世事洞明皆学问，人情练达即文章。", "曹雪芹", "古籍"},
            {"知之为知之，不知为不知，是知也。", "论语", "古籍"},
            {"君子求诸己，小人求诸人。", "论语", "古籍"},
            {"天下难事，必作于易；天下大事，必作于细。", "老子", "古籍"},
            {"千里之行，始于足下。", "老子", "古籍"},
            {"苟日新，日日新，又日新。", "礼记", "古籍"},
            {"行到水穷处，坐看云起时。", "王维", "诗词"},
            {"心中若有桃花源，何处不是水云间。", "佚名", "现代"},
            {"生活明朗，万物可爱，人间值得。", "佚名", "现代"},
            {"把日子过成自己喜欢的模样。", "佚名", "现代"},
            {"慢慢来，比较快。", "佚名", "现代"},
            {"愿你所有的努力，都不被辜负。", "佚名", "励志"},
            {"热爱可抵岁月漫长。", "佚名", "励志"},
            {"种一棵树最好的时间是十年前，其次是现在。", "佚名", "励志"},
            {"所有的坚持，都是因为值得。", "佚名", "励志"},
            {"把复杂留给自己，把简单留给用户。", "乐信", "技术"},
            {"代码是写给人看的，顺便能在机器上跑。", "SICP", "技术"},
            {"先让它跑起来，再让它跑得优雅。", "工程惯例", "技术"},
            {"能被验证的，才配叫结论。", "乐信", "技术"},
            {"能自动的就别手动，能一次的就别两次。", "乐信", "技术"},
            {"把不确定的事，变成可以复现的步骤。", "乐信", "技术"},
            {"备份是敢于动手的底气。", "乐信", "技术"},
            {"版本一更新，适配能力就见真章。", "乐信", "技术"},
            {"少即是多，慢即是快。", "设计箴言", "哲理"},
            {"好的设计，是让人忘记设计的存在。", "乐信", "哲理"},
            {"工具好不好用，看你愿不愿意天天用它。", "乐信", "哲理"},
            {"细节里的耐心，就是成品的质感。", "乐信", "哲理"},
            {"热闹是他们的，我什么也没有。", "朱自清", "文学"},
            {"我们都在阴沟里，但仍有人仰望星空。", "王尔德", "文学"},
            {"人生如逆旅，我亦是行人。", "苏轼", "文学"},
            {"凡是过往，皆为序章。", "莎士比亚", "文学"},
            {"今天也要好好吃饭，天塌下来先吃饱。", "佚名", "幽默"},
            {"别人写代码靠天赋，我靠重启。", "佚名", "幽默"},
            {"计划赶不上变化，那就先改计划。", "佚名", "幽默"},
            {"早睡是不可能早睡的，这辈子都不可能。", "佚名", "幽默"},
    };

    private static final Random RND = new Random();
    private static volatile long lastFetch = 0L;
    private static volatile int lastLocal = -1;

    interface Cb {
        void on(String text, String from, boolean fresh);
    }

    /** 每次打开调用：先给一句随机的，拿到新的再回调一次。 */
    static void load(Context ctx, final Cb cb) {
        if (cb == null) return;

        // 1) 本地随机（每次打开都不一样）
        String[] local = pickLocal();
        cb.on(local[0], local[1], false);

        // 2) 缓存里也有货：一半概率先换成缓存里的外部句子，制造更多变化
        String[] cached = pickCached();
        if (cached != null && RND.nextBoolean()) {
            cb.on(cached[0], cached[1], false);
        }

        // 3) 后台取新句子（8 分钟内不重复请求）
        long now = System.currentTimeMillis();
        if (now - lastFetch < MIN_GAP_MS) return;
        lastFetch = now;

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String[] got = fetch();
                if (got == null) {
                    Log.d(T + "offline, keep local");
                    return;
                }
                pushCache(got[0], got[1]);
                Log.d(T + "fetched from " + got[1]);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        cb.on(got[0], got[1], true);
                    }
                });
            }
        }, "q" + Integer.toHexString((int) (now & 0xFFFF)));
        t.setDaemon(true);
        t.start();
    }

    /** 本地句库：随机，且尽量不跟上一句重复。 */
    private static String[] pickLocal() {
        int i = RND.nextInt(LOCAL.length);
        if (LOCAL.length > 1 && i == lastLocal) {
            i = (i + 1 + RND.nextInt(LOCAL.length - 1)) % LOCAL.length;
        }
        lastLocal = i;
        return LOCAL[i];
    }

    /** 缓存里的外部句子（历史取到过的），没有就返回 null。 */
    private static String[] pickCached() {
        try {
            String s = Storage.str(K_HIST, "");
            if (s == null || s.isEmpty()) return null;
            JSONArray arr = new JSONArray(s);
            int n = arr.length();
            if (n <= 0) return null;
            JSONObject o = arr.getJSONObject(RND.nextInt(n));
            String text = o.optString("t", "");
            if (text.isEmpty()) return null;
            return new String[]{text, o.optString("f", "一言")};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 环形缓存：最近 HIST_MAX 条外部句子。 */
    private static void pushCache(String text, String from) {
        try {
            JSONArray out = new JSONArray();
            out.put(new JSONObject().put("t", text).put("f", from));
            String s = Storage.str(K_HIST, "");
            if (s != null && !s.isEmpty()) {
                JSONArray old = new JSONArray(s);
                for (int i = 0; i < old.length() && out.length() < HIST_MAX; i++) {
                    JSONObject o = old.optJSONObject(i);
                    if (o == null) continue;
                    String t = o.optString("t", "");
                    if (t.isEmpty() || t.equals(text)) continue;
                    out.put(o);
                }
            }
            Storage.setStr(K_HIST, out.toString());
            Storage.setNum(K_DAY, (int) (System.currentTimeMillis() / 86400000L));
        } catch (Throwable ignored) {
            // 缓存失败不影响显示
        }
    }

    /** 接口偶尔真的返回字符串 "null"（from_who 尤其常见），统一当没有处理。 */
    private static String text(String s) {
        if (s == null) return "";
        String v = s.trim();
        return "null".equalsIgnoreCase(v) ? "" : v;
    }

    private static String[] fetch() {
        String c = CATEGORIES[RND.nextInt(CATEGORIES.length)];
        String[] r = tryOne(API + c);
        if (r == null) r = tryOne(API2 + c);
        return r;
    }

    private static String[] tryOne(String url) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36");
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            in = c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(2048);
            byte[] buf = new byte[1024];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0 && total < 32 * 1024) {
                out.write(buf, 0, n);
                total += n;
            }
            JSONObject o = new JSONObject(new String(out.toByteArray(), "UTF-8"));
            String text = text(o.optString("hitokoto", ""));
            if (text.isEmpty()) return null;
            String from = text(o.optString("from_who", ""));
            if (from.isEmpty()) from = text(o.optString("from", ""));
            if (from.isEmpty()) from = "一言";
            return new String[]{text, from};
        } catch (Throwable e) {
            Log.d(T + "fetch failed: " + e.getClass().getSimpleName());
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                if (c != null) c.disconnect();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    private DailyQuote() {}
}
