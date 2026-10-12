package io.github.lex0m.lexin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 朋友圈保留（评论 + 动态）。
 *
 * 旧做法是「把这些类里所有 A/H 调用一律答否」，太粗暴 —— 那两个方法是朋友圈存储层的
 * SQL 执行口，一律拒绝会连带影响正常的读写。这里改成**看 SQL 文本**：
 * 只有 DELETE 语句且目标表是 Sns 系列表（评论/动态/媒体）时才拒绝，
 * 其它语句原样放行。效果就是「别人删掉的评论和动态，本机留副本」，
 * 而不是「朋友圈炸掉」。
 *
 * 类名每个版本都不一样（8.0.78 是 kh5.z / kh5.b0），所以走锚点表 + 自动扫描，
 * 一个版本里可能同时有多个库对象，全部挂上。
 */
final class SnsGuard {

    private static final String T = "svc-sns: ";

    /** 只拦这些表上的删除：评论、动态正文、动态媒体、扩展信息。 */
    private static final String[] PROTECTED = {
            "SNSCOMMENT", "SNSINFO", "SNSMEDIA", "SNSEXT",
    };

    /** 整库级联/建表语句放过：朋友圈下拉刷新会用到，拦了反而出问题。 */
    private static final String[] ALLOW = {
            "DROP TABLE", "CREATE TABLE", "TRUNCATE",
    };

    static void install() {
        int n = 0;
        n += hook(Anchors.SNS_DB, "A", 2);
        n += hook(Anchors.SNS_DB2, "H", 3);
        Status.set("朋友圈保留", n > 0);
        Status.note("朋友圈保留", n > 0 ? "只拦朋友圈表上的 DELETE，其它 SQL 原样放行"
                : "当前微信版本未适配");
        Log.d(T + "armed=" + n);
    }

    /** 挂上槽位里所有类的同名 boolean 方法；SQL 不匹配就放行。 */
    private static int hook(String slot, String methodName, int params) {
        List<Class<?>> list = Anchors.all(slot);
        int hits = 0;
        for (final Class<?> c : list) {
            for (final Method m : c.getDeclaredMethods()) {
                if (!methodName.equals(m.getName())) continue;
                if (m.getReturnType() != boolean.class) continue;
                if (m.getParameterCount() != params) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (!Prefs.onDefault(Prefs.K_MOMENTS)) return;
                                if (!isProtectedDelete(p.args[0])) return;
                                p.setResult(Boolean.FALSE);      // 「没删掉」，本地副本留下
                                Log.d(T + "blocked delete on " + c.getSimpleName());
                            } catch (Throwable ignored) {
                                // 有疑问就放行
                            }
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 下一个重载
                }
            }
        }
        return hits;
    }

    /** 判定：DELETE 语句 + 目标是朋友圈的表。 */
    static boolean isProtectedDelete(Object sqlArg) {
        if (!(sqlArg instanceof String)) return false;
        String sql = ((String) sqlArg).trim().toUpperCase(Locale.ROOT);
        if (sql.isEmpty()) return false;
        if (!sql.startsWith("DELETE")) return false;
        for (String a : ALLOW) {
            if (sql.startsWith(a)) return false;
        }
        for (String t : PROTECTED) {
            if (sql.contains(t)) return true;
        }
        return false;
    }

    private SnsGuard() {}
}
