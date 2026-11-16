package io.github.lex0m.lexin;

import android.content.ContentValues;

import java.lang.reflect.Method;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 消息库兜底（v1.6.2）。
 *
 * 思路照抄 wcx 的 `AntiMessageRecall.IUpdateListener`：**不依赖任何解析器**，直接挂在
 * WCDB 的 `SQLiteDatabase.update()` 上。微信收到撤回后一定会改 `message` 表里的那一行；
 * 我们在它写进去之前把这次 update **吃掉**（返回 0 行），行内容原样不动，等效阻止撤回。
 *
 * 8.0.80 实测的撤回改写（2026-11-16 18:07 日志）：
 *   ① update message set msgSvrId=?, status=2        where msgId=?
 *   ② update message set content=1                   where msgId=?
 *   ③ update message set isSend=0, type=285222674, content=1 where msgId=?
 * 所以判据是 status=2 / type=285222674 / 已盯上的 msgId，**不是**文案里有"撤回"。
 *
 * 为什么不在 hook 里查库还原：上一版在 update 回调里 rawQuery 原始行，会把微信的写库线程
 * 卡住（实测"正在撤回"转圈几秒），最后它还是写进去了。现在改成直接让这次 update 影响 0 行，
 * 不读库、不持锁、不卡顿。
 */
final class DbGuard {

    private static final String T = "svc-db: ";
    private static final String TABLE = "message";
    private static final String F_CONTENT = "content";
    private static final String F_TYPE = "type";
    private static final String F_STATUS = "status";
    /** 8.0.80 撤回后写入的特殊 type（实测值）。 */
    private static final int TYPE_RECALLED = 285222674;
    /** message.status = 2 实测就是"已撤回"。 */
    private static final int STATUS_RECALLED = 2;

    /** 已确认在撤回的 msgId：后续对同一行的改写一律吃掉。 */
    private static final java.util.Set<Long> watch =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());

    static void install() {
        int n = 0;
        Class<?> db = Loaders.load("com.tencent.wcdb.database.SQLiteDatabase");
        if (db == null) db = Loaders.load("android.database.sqlite.SQLiteDatabase");
        if (db == null) {
            Status.armed("数据库兜底", 0);
            Status.note("数据库兜底", "当前微信版本未适配");
            return;
        }
        for (final Method m : db.getDeclaredMethods()) {
            String name = m.getName();
            boolean upd = "update".equals(name) || "updateWithOnConflict".equals(name);
            boolean sql = "execSQL".equals(name);
            if (!upd && !sql) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (upd) {
                if (pt.length != 4 && pt.length != 5) continue;
                if (pt[0] != String.class || pt[1] != ContentValues.class) continue;
                if (pt[2] != String.class || pt[3] != String[].class) continue;
                if (pt.length == 5 && pt[4] != int.class) continue;
            } else {
                if (pt.length != 1 && pt.length != 2) continue;
                if (pt[0] != String.class) continue;
            }
            try {
                m.setAccessible(true);
                final boolean isUpdate = upd;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (isUpdate) onUpdate(p);
                            else onSql(p);
                        } catch (Throwable ignored) {
                            // 数据库路径绝不抛：抛出去会打断微信自己的写库
                        }
                    }
                });
                n++;
            } catch (Throwable ignored) {
                // 下一个重载
            }
        }
        Status.armed("数据库兜底", n);
        Status.note("数据库兜底", n > 0 ? "撤回改写直接吃掉（" + n + " 个重载）"
                : "当前微信版本未适配");
        Log.d(T + "armed=" + n + " cls=" + db.getName());
    }

    private static void onUpdate(XC_MethodHook.MethodHookParam p) {
        if (!Prefs.onDefault(Prefs.K_RECALL)) return;
        Object[] a = p.args;
        if (a == null || a.length < 4) return;
        if (!(a[0] instanceof String) || !TABLE.equals(a[0])) return;
        if (!(a[1] instanceof ContentValues)) return;
        ContentValues values = (ContentValues) a[1];

        String content = values.getAsString(F_CONTENT);
        if (Log.runtimeOn()) {
            StringBuilder sb = new StringBuilder();
            for (String k : values.keySet()) {
                Object v = values.get(k);
                String sv = String.valueOf(v);
                if (sv.length() > 60) sv = sv.substring(0, 60) + "…";
                sb.append(k).append('=').append(sv).append(' ');
            }
            Log.d(T + "upd where=" + a[2] + " vals[" + sb + "]");
        }

        String where = a[2] instanceof String ? (String) a[2] : null;
        String[] whereArgs = a[3] instanceof String[] ? (String[]) a[3] : null;
        if (where == null || where.isEmpty()) return;

        long id = msgIdOf(where, whereArgs);
        // 微信写 ContentValues 的整数常是 Long，getAsInteger() 会返回 null（强转失败被吞）
        // 注意：status=2 实测是**发送状态**，不是撤回标记（按它拦会把消息发不出去）。
        // 撤回只认 type=285222674 / 已盯上的 msgId / 文案特征。
        Object tv = values.get(F_TYPE);
        boolean typeRevoke = tv instanceof Number && ((Number) tv).intValue() == TYPE_RECALLED;
        boolean watched = id > 0 && watch.contains(id);
        boolean textRevoke = content != null
                && (content.contains("撤回")
                || content.toLowerCase(Locale.ROOT).contains("revokemsg"));
        if (!typeRevoke && !watched && !textRevoke) return;

        if (id > 0 && typeRevoke) remember(id);

        // 不吞整条 update（吞掉会把微信的写库事务挂住，实测会卡死界面），
        // 只把"撤回标记"这几个字段从本次写入里摘掉：update 照常执行，行内容不变。
        boolean selfSend = false;
        if (typeRevoke) {
            Object is = values.get("isSend");
            selfSend = is instanceof Number && ((Number) is).intValue() == 1;
            // isSend 也必须摘：微信把它改成 0，消息就会跑到"对方"那一侧（实测 18:51）。
            values.remove(F_TYPE);
            values.remove(F_CONTENT);
            values.remove("isSend");
        }
        if (textRevoke) values.remove(F_CONTENT);
        if (watched) {
            values.remove(F_TYPE);
            values.remove(F_CONTENT);
            values.remove("isSend");
        }
        if (id > 0) AntiRecall.onBlocked(id, selfSend);
        Status.hit("数据库兜底");
        Status.hit("消息防撤回");
        Log.d(T + "stripped revoke fields id=" + id
                + " type=" + typeRevoke);
    }

    /** 有些写库走 SQL 字符串：命中撤回特征就直接跳过执行。 */
    private static void onSql(XC_MethodHook.MethodHookParam p) {
        if (!Prefs.onDefault(Prefs.K_RECALL)) return;
        Object[] a = p.args;
        if (a == null || a.length < 1 || !(a[0] instanceof String)) return;
        String sql = (String) a[0];
        String up = sql.toUpperCase(Locale.ROOT);
        if (!up.contains("UPDATE") || !up.contains("MESSAGE")) return;
        boolean revoke = up.contains("REVOKEMSG") || up.contains("285222674");
        if (Log.runtimeOn()) {
            String s2 = sql.length() > 160 ? sql.substring(0, 160) + "…" : sql;
            Log.d(T + "sql " + s2);
        }
        if (!revoke) return;
        p.setResult(null);
        Status.hit("数据库兜底");
        Status.hit("消息防撤回");
        Log.d(T + "blocked revoke sql");
    }

    private static void remember(long id) {
        if (watch.size() > 500) watch.clear();
        watch.add(id);
    }

    private static long msgIdOf(String where, String[] args) {
        try {
            if (args == null || args.length == 0) return -1L;
            if (!where.contains("msgId")) return -1L;
            return Long.parseLong(args[0]);
        } catch (Throwable t) {
            return -1L;
        }
    }

    private DbGuard() {}
}
