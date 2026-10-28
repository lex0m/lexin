package io.github.lex0m.lexin;

import android.database.Cursor;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 批量分类标签（v1.3）。
 *
 * 思路照抄 CrackerCat/wcx 的 WeContactLabelApi + BatchAddLabel：
 *   读标签/联系人 → 走微信数据库（进程内已打开的 WCDB 连接，零加密破解）
 *   改标签 → 走微信官方 netscene（CGI /cgi-bin/micromsg-bin/modifycontactlabellist），
 *           复用微信自己的网络请求类，不伪造上行报文、不碰 XML/type 篡改。
 *
 * 坐标（wcx DexKit 字符串特征 → anchors 版本表）：
 *   mm_kernel            MicroMsg.MMKernel / "Kernel not null, has initialized."
 *   core_storage         MMKernel.CoreStorage / "CheckData path[...]..."
 *   sqlite_db_wrapper    MicroMsg.SqliteDB / "sql is null "
 *   net_scene_queue      MicroMsg.NetSceneQueue / "forbid in waiting: type="
 *   add_contact_label    /cgi-bin/micromsg-bin/addcontactlabel   (ctor String)
 *   modify_contact_label /cgi-bin/micromsg-bin/modifycontactlabellist (ctor LinkedList)
 *   contact_label_pb     运行时扫 dex：找引用 "UserLabelInfoList" 且读 2 个 String 字段的类
 */
final class BatchLabel {

    private static final String T = "svc-blbl: ";

    private static volatile Class<?> cMmKernel;
    private static volatile Class<?> cCoreStorage;
    private static volatile Class<?> cSqliteDbWrapper;
    private static volatile Class<?> cNetSceneQueue;
    private static volatile Class<?> cAddContactLabel;
    private static volatile Class<?> cModifyContactLabel;
    private static volatile Class<?> cContactLabelPb;

    /** 微信进程内已打开的 WCDB SQLiteDatabase（懒初始化，等 getStorage 跑完）。 */
    private static volatile Object db;
    private static volatile boolean dbHooked = false;

    static void install() {
        cMmKernel = Anchors.cls(Anchors.MM_KERNEL);
        cCoreStorage = Anchors.cls(Anchors.CORE_STORAGE);
        cSqliteDbWrapper = Anchors.cls(Anchors.SQLITE_DB_WRAPPER);
        cNetSceneQueue = Anchors.cls(Anchors.NET_SCENE_QUEUE);
        cAddContactLabel = Anchors.cls(Anchors.ADD_CONTACT_LABEL);
        cModifyContactLabel = Anchors.cls(Anchors.MODIFY_CONTACT_LABEL);

        boolean ok = cMmKernel != null && cCoreStorage != null && cSqliteDbWrapper != null
                && cNetSceneQueue != null && cAddContactLabel != null && cModifyContactLabel != null;
        if (ok) ok = hookGetStorage();
        Status.set("批量分类标签", ok);
        Status.note("批量分类标签", ok ? "可批量给好友打标签（官方接口）" : "当前微信版本未适配");
        Log.d(T + "install ok=" + ok);
    }

    /** hook 微信 coreStorage 的获取点，拿到进程内已打开的数据库。 */
    private static boolean hookGetStorage() {
        if (dbHooked) return true;
        int n = 0;
        // mm_kernel 里 static 无参、返回 core_storage 类型的方法 = getCoreStorage
        for (Method m : cMmKernel.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (!sameOrAssignable(m.getReturnType(), cCoreStorage)) continue;
            m.setAccessible(true);
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object storage = p.getResult();
                            if (storage != null) ensureDb(storage);
                        } catch (Throwable ignored) {
                            // 拿不到就不打扰
                        }
                    }
                });
                n++;
            } catch (Throwable ignored) {
                // 下一个
            }
        }
        dbHooked = true;
        Log.d(T + "getStorage hooked=" + n);
        return n > 0;
    }

    /** 从 storage 对象拿 db（storage -> sqlite wrapper -> WCDB SQLiteDatabase）。 */
    private static synchronized void ensureDb(Object storage) {
        if (db != null || storage == null) return;
        try {
            Object wrapper = fieldOfType(storage, cSqliteDbWrapper);
            if (wrapper == null) return;
            for (Class<?> c = wrapper.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterTypes().length != 0) continue;
                    if (!"com.tencent.wcdb.database.SQLiteDatabase".equals(m.getReturnType().getName())
                            && !"android.database.sqlite.SQLiteDatabase".equals(m.getReturnType().getName())) {
                        continue;
                    }
                    m.setAccessible(true);
                    Object d = m.invoke(wrapper);
                    if (d != null) {
                        db = d;
                        Log.d(T + "db ready: " + d.getClass().getName());
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            Log.d(T + "ensureDb fail: " + t.getClass().getSimpleName());
        }
    }

    // ── 数据库查询 ──────────────────────────────────────────────────────────

    /** 所有标签：labelID -> labelName。 */
    static List<String[]> getAllLabels() {
        Cursor cur = rawQuery("SELECT labelID, labelName FROM ContactLabel ORDER BY labelName");
        List<String[]> out = new ArrayList<>();
        if (cur == null) return out;
        try {
            int cid = cur.getColumnIndexOrThrow("labelID");
            int cn = cur.getColumnIndexOrThrow("labelName");
            while (cur.moveToNext()) {
                out.add(new String[]{String.valueOf(cur.getInt(cid)), cur.getString(cn)});
            }
        } catch (Throwable ignored) {
            // 读不到就空
        } finally {
            try { cur.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    /** 好友列表：username + 昵称（面板选人用）。 */
    static List<String[]> getFriends() {
        Cursor cur = rawQuery(
                "SELECT username, nickname FROM rcontact WHERE (type & 1) != 0 "
                        + "AND username NOT LIKE '%@chatroom' AND verifyFlag = 0 "
                        + "ORDER BY nickname");
        List<String[]> out = new ArrayList<>();
        if (cur == null) return out;
        try {
            int cu = cur.getColumnIndexOrThrow("username");
            int cn = cur.getColumnIndexOrThrow("nickname");
            while (cur.moveToNext()) {
                String u = cur.getString(cu);
                String n = cur.getString(cn);
                if (u == null || u.isEmpty()) continue;
                out.add(new String[]{u, n == null ? u : n});
            }
        } catch (Throwable ignored) {
            // 读不到就空
        } finally {
            try { cur.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    private static Cursor rawQuery(String sql) {
        return rawQuery(sql, null);
    }

    private static Cursor rawQuery(String sql, String[] args) {
        if (db == null) return null;
        try {
            for (Class<?> c = db.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!"rawQuery".equals(m.getName())) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length != 2) continue;
                    if (pt[0] != String.class) continue;
                    if (pt[1] != String[].class && pt[1] != Object[].class) continue;
                    m.setAccessible(true);
                    Object r = m.invoke(db, sql, args);
                    if (r instanceof Cursor) return (Cursor) r;
                }
            }
        } catch (Throwable t) {
            Log.d(T + "rawQuery fail: " + t.getClass().getSimpleName());
        }
        return null;
    }

    // ── 标签操作 ────────────────────────────────────────────────────────────

    /** 新建标签（走官方 netscene），返回 labelId 或 null。轮询数据库等服务器回写。 */
    static Integer createLabel(String name) {
        if (name == null || name.isEmpty()) return null;
        Integer exist = labelId(name);
        if (exist != null) return exist;
        try {
            Constructor<?> ctor = cAddContactLabel.getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            Object scene = ctor.newInstance(name);
            sendNetScene(scene);
        } catch (Throwable t) {
            Log.d(T + "createLabel dispatch fail: " + t.getClass().getSimpleName());
            return null;
        }
        long deadline = System.currentTimeMillis() + 10000L;
        while (System.currentTimeMillis() < deadline) {
            Integer id = labelId(name);
            if (id != null) return id;
            try { Thread.sleep(200); } catch (Throwable ignored) { }
        }
        return null;
    }

    /** 批量给一个好友打标签（替换其全部标签）。labelNames 里不存在的名字会被跳过。 */
    static boolean setLabels(String username, List<String> labelNames) {
        List<String> ids = new ArrayList<>();
        for (String n : labelNames) {
            Integer id = labelId(n);
            if (id != null) ids.add(String.valueOf(id));
        }
        return setLabelsByIds(username, ids);
    }

    /** 给一个好友追加一个标签（保留原有标签）。 */
    static boolean addLabel(String username, String labelName) {
        List<String> ids = getContactLabelIds(username);
        Integer id = labelId(labelName);
        if (id == null) return false;
        String idStr = String.valueOf(id);
        if (!ids.contains(idStr)) ids.add(idStr);
        return setLabelsByIds(username, ids);
    }

    /** 读好友当前的全部标签 id（rcontact.contactLabelIds，逗号分隔）。 */
    static List<String> getContactLabelIds(String username) {
        List<String> out = new ArrayList<>();
        Cursor cur = rawQuery("SELECT contactLabelIds FROM rcontact WHERE username = ?", new String[]{username});
        if (cur == null) return out;
        try {
            if (cur.moveToFirst()) {
                String raw = cur.getString(0);
                if (raw != null && !raw.isEmpty()) {
                    for (String s : raw.split(",")) {
                        s = s.trim();
                        if (!s.isEmpty()) out.add(s);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读不到就空
        } finally {
            try { cur.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    /** 核心：用 labelId 列表构造 protobuf + netscene。 */
    private static boolean setLabelsByIds(String username, List<String> ids) {
        try {
            Class<?> pbCls = contactLabelPb();
            if (pbCls == null) {
                Log.d(T + "contactLabelPb resolve failed");
                return false;
            }
            Object pb = pbCls.newInstance();
            List<Field> strFields = stringFields(pbCls);
            if (strFields.size() < 2) {
                Log.d(T + "contactLabelPb has <2 String fields");
                return false;
            }
            strFields.get(0).set(pb, username);
            strFields.get(1).set(pb, join(ids, ","));

            LinkedList<Object> list = new LinkedList<>();
            list.add(pb);
            Constructor<?> ctor = cModifyContactLabel.getDeclaredConstructor(LinkedList.class);
            ctor.setAccessible(true);
            Object scene = ctor.newInstance(list);
            sendNetScene(scene);
            Log.d(T + "setLabelsByIds dispatched: " + username + " <- " + ids);
            return true;
        } catch (Throwable t) {
            Log.d(T + "setLabelsByIds fail: " + t.getClass().getSimpleName());
            return false;
        }
    }

    private static Integer labelId(String name) {
        Cursor cur = rawQuery("SELECT labelID FROM ContactLabel WHERE labelName = ?", new String[]{name});
        if (cur == null) return null;
        try {
            if (cur.moveToFirst()) return cur.getInt(0);
        } catch (Throwable ignored) {
            // 读不到
        } finally {
            try { cur.close(); } catch (Throwable ignored) { }
        }
        return null;
    }

    // ── netscene 发送 ───────────────────────────────────────────────────────

    /** 发 netscene（微信官方网络请求队列）。 */
    private static void sendNetScene(Object scene) {
        try {
            Object queue = netSceneQueue();
            if (queue == null) {
                Log.d(T + "netSceneQueue null");
                return;
            }
            Method add = findAddNetScene(queue.getClass());
            if (add == null) {
                Log.d(T + "addNetSceneToQueue method missing");
                return;
            }
            add.invoke(queue, scene, 0);
            Log.d(T + "netscene sent via " + add.getName());
        } catch (Throwable t) {
            Log.d(T + "sendNetScene fail: " + t.getClass().getSimpleName());
        }
    }

    /** 运行时定位 addNetSceneToQueue：优先按名字，找不到按签名 (任意类, int) -> 非 void。 */
    private static Method findAddNetScene(Class<?> cls) {
        // 1) 按名字（微信该 API 方法名通常未混淆）
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName();
                if ("addNetSceneToQueue".equals(n) || "addNetScene".equals(n) || "add".equals(n)) {
                    if (m.getParameterTypes().length == 2 && m.getParameterTypes()[1] == int.class) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
        }
        // 2) 兜底：任何 (任意类, int) -> void/boolean/int
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 2 || pt[1] != int.class) continue;
                Class<?> r = m.getReturnType();
                if (r != void.class && r != boolean.class && r != int.class) continue;
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /** mm_kernel 里 static 无参、返回 net_scene_queue 类型的方法 = getNetSceneQueue。 */
    private static Object netSceneQueue() {
        try {
            for (Method m : cMmKernel.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 0) continue;
                if (!sameOrAssignable(m.getReturnType(), cNetSceneQueue)) continue;
                m.setAccessible(true);
                Object r = m.invoke(null);
                if (r != null) return r;
            }
        } catch (Throwable ignored) {
            // 拿不到
        }
        return null;
    }

    // ── protobuf 定位（运行时扫 dex，照抄 wcx resolveDex 的 UserLabelInfoList 法） ──

    /** 定位 ContactLabelPb 类：恰好 2 个 String 字段、被引用 "UserLabelInfoList" 的方法读。 */
    private static synchronized Class<?> contactLabelPb() {
        if (cContactLabelPb != null) return cContactLabelPb;
        cContactLabelPb = scanContactLabelPb();
        Log.d(T + "contactLabelPb -> " + (cContactLabelPb == null ? "null" : cContactLabelPb.getName()));
        return cContactLabelPb;
    }

    private static Class<?> scanContactLabelPb() {
        String apk = Features.app == null ? null : Features.app.getApplicationInfo().sourceDir;
        if (apk == null) return null;
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            Collections.sort(entries, new java.util.Comparator<ZipEntry>() {
                @Override
                public int compare(ZipEntry a, ZipEntry b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            for (ZipEntry e : entries) {
                String n = e.getName();
                if (!n.startsWith("classes") || !n.endsWith(".dex")) continue;
                if (e.getSize() <= 0 || e.getSize() > 64L * 1024 * 1024) continue;
                byte[] data = read(zip, e);
                if (data == null) continue;
                String hit = findContactLabelPb(data);
                if (hit != null) {
                    return Silent.cls(Features.loader, hit);
                }
            }
        } catch (Throwable t) {
            Log.d(T + "scanContactLabelPb fail: " + t.getClass().getSimpleName());
        } finally {
            try { if (zip != null) zip.close(); } catch (Throwable ignored) { }
        }
        return null;
    }

    /**
     * 在单个 dex 里找 ContactLabelPb：
     *   1) 遍历方法指令，找 const-string "UserLabelInfoList"
     *   2) 该方法的 iget-object 读的字段里，找「声明类恰好 2 个 String 字段」的类
     */
    private static String findContactLabelPb(byte[] d) {
        try {
            int nString = u4(d, 0x38), oString = u4(d, 0x3C);
            int nType = u4(d, 0x40), oType = u4(d, 0x44);
            int nField = u4(d, 0x50), oField = u4(d, 0x54);
            int nMethod = u4(d, 0x58), oMethod = u4(d, 0x5C);
            int nClass = u4(d, 0x60), oClass = u4(d, 0x64);

            // 先找 string id == "UserLabelInfoList"
            int targetStrId = -1;
            for (int i = 0; i < nString; i++) {
                if ("UserLabelInfoList".equals(strAt(d, oString, i))) {
                    targetStrId = i;
                    break;
                }
            }
            if (targetStrId < 0) return null;

            // 遍历 class_def，找含 "UserLabelInfoList" 的方法，收集其 iget-object 读的 String 字段
            Set<Integer> candidateClasses = new HashSet<>();
            for (int k = 0; k < nClass; k++) {
                int base = oClass + k * 32;
                int dataOff = u4(d, base + 24);   // class_data_off
                if (dataOff == 0) continue;
                int[] p = {dataOff};
                int sf = uleb(d, p), inf = uleb(d, p), dm = uleb(d, p), vm = uleb(d, p);
                // 跳过 field 段
                int idx = 0;
                for (int i = 0; i < sf + inf; i++) {
                    idx += uleb(d, p); uleb(d, p);   // field_idx_diff, access
                }
                // 方法段
                idx = 0;
                for (int i = 0; i < dm + vm; i++) {
                    int diff = uleb(d, p);
                    uleb(d, p);                      // access
                    int codeOff = uleb(d, p);        // code_off
                    idx = step(idx, diff, nMethod);
                    if (codeOff == 0) continue;
                    if (!codeUsesString(d, codeOff, targetStrId)) continue;
                    collectReadStringFields(d, codeOff, nField, oField, nType, oType, candidateClasses);
                }
            }

            // 候选类里找「恰好 2 个 String 字段」
            for (int clsIdx : candidateClasses) {
                String clsName = typeName(d, oType, nType, clsIdx);
                if (clsName == null) continue;
                if (stringFieldCount(d, oClass, nClass, oField, nField, oType, nType, clsName) == 2) {
                    return clsName;
                }
            }
        } catch (Throwable t) {
            Log.d(T + "findContactLabelPb err: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private static boolean codeUsesString(byte[] d, int codeOff, int strId) {
        int insnsSize = u4(d, codeOff + 12);
        int base = codeOff + 16;
        for (int i = 0; i < insnsSize; i++) {
            int off = base + i * 2;
            if (off + 6 > d.length) break;
            int u = u2(d, off);
            int op = u & 0xFF;
            if (op == 0x1A) {                       // const-string
                int idx = u2(d, off + 2);
                if (idx == strId) return true;
            } else if (op == 0x1B) {                // const-string/jumbo
                int idx = u4(d, off + 2);
                if (idx == strId) return true;
            }
        }
        return false;
    }

    private static void collectReadStringFields(byte[] d, int codeOff, int nField, int oField,
                                                int nType, int oType, Set<Integer> out) {
        int insnsSize = u4(d, codeOff + 12);
        int base = codeOff + 16;
        for (int i = 0; i < insnsSize; i++) {
            int off = base + i * 2;
            if (off + 4 > d.length) break;
            int u = u2(d, off);
            if ((u & 0xFF) == 0x54) {               // iget-object
                int fieldIdx = u2(d, off + 2);
                if (fieldIdx >= nField) continue;
                int fbase = oField + fieldIdx * 8;
                int typeIdx = u2(d, fbase + 2);     // field 类型
                if (!isStringType(d, oType, nType, typeIdx)) continue;
                int clsIdx = u2(d, fbase);          // field 声明类
                out.add(clsIdx);
            }
        }
    }

    private static boolean isStringType(byte[] d, int oType, int nType, int typeIdx) {
        String t = typeName(d, oType, nType, typeIdx);
        return "java.lang.String".equals(t);
    }

    private static String typeName(byte[] d, int oType, int nType, int typeIdx) {
        if (typeIdx < 0 || typeIdx >= nType) return null;
        int tbase = oType + typeIdx * 4;
        int strIdx = u4(d, tbase);
        String desc = strAt(d, u4(d, 0x3C), strIdx);
        if (desc == null) return null;
        if (desc.startsWith("L") && desc.endsWith(";")) return desc.substring(1, desc.length() - 1).replace('/', '.');
        return desc;
    }

    private static int stringFieldCount(byte[] d, int oClass, int nClass, int oField, int nField,
                                        int oType, int nType, String clsName) {
        for (int k = 0; k < nClass; k++) {
            int base = oClass + k * 32;
            int clsIdx = u4(d, base);
            String cn = typeName(d, oType, nType, clsIdx);
            if (!clsName.equals(cn)) continue;
            int dataOff = u4(d, base + 24);
            if (dataOff == 0) return 0;
            int[] p = {dataOff};
            int sf = uleb(d, p), inf = uleb(d, p), dm = uleb(d, p), vm = uleb(d, p);
            int idx = 0, count = 0;
            for (int i = 0; i < sf + inf; i++) {
                idx += uleb(d, p); uleb(d, p);
                int fbase = oField + idx * 8;
                int typeIdx = u2(d, fbase + 2);
                if (isStringType(d, oType, nType, typeIdx)) count++;
            }
            return count;
        }
        return 0;
    }

    private static String strAt(byte[] d, int oString, int i) {
        int off = u4(d, oString + i * 4);
        int[] p = {off};
        uleb(d, p);                                  // utf16_size
        int s = p[0];
        int e = s;
        while (e < d.length && d[e] != 0) e++;
        if (e > d.length) return null;
        try {
            return new String(d, s, e - s, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static int uleb(byte[] d, int[] p) {
        int r = 0, shift = 0;
        while (true) {
            int b = d[p[0]++] & 0xFF;
            r |= (b & 0x7F) << shift;
            shift += 7;
            if ((b & 0x80) == 0) break;
        }
        return r;
    }

    private static int step(int prev, int diff, int limit) {
        int nxt = prev + diff;
        if (nxt >= limit) nxt = diff < limit ? diff : nxt;
        return nxt;
    }

    private static int u2(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    private static int u4(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    private static byte[] read(ZipFile zip, ZipEntry e) {
        InputStream in = null;
        try {
            in = zip.getInputStream(e);
            byte[] buf = new byte[(int) e.getSize()];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r <= 0) break;
                off += r;
            }
            return off == buf.length ? buf : null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    // ── 反射助手 ────────────────────────────────────────────────────────────

    private static Object fieldOfType(Object obj, Class<?> type) {
        if (obj == null || type == null) return null;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (sameOrAssignable(f.getType(), type)) {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v != null) return v;
                    }
                }
            } catch (Throwable ignored) {
                // 继续
            }
        }
        return null;
    }

    private static List<Field> stringFields(Class<?> cls) {
        List<Field> out = new ArrayList<>();
        for (Field f : cls.getDeclaredFields()) {
            if (f.getType() == String.class) {
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out;
    }

    private static boolean sameOrAssignable(Class<?> a, Class<?> b) {
        return a == b || a.isAssignableFrom(b) || b.isAssignableFrom(a);
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private BatchLabel() {}
}
