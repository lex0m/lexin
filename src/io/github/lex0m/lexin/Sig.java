package io.github.lex0m.lexin;

import java.security.MessageDigest;

/**
 * 广播防伪（v1.6.0）。
 *
 * 问题：`CfgReceiver` 是静态注册、exported 的（微信和模块是不同 uid，不 export 收不到），
 * 于是**任何第三方 App 都能往 `x.p` 发一条改配置、往 `x.s` 发一条假状态** ——
 * 面板显示"已生效"可能压根不是微信说的。这既是功能问题，也是隐蔽性问题（别人可以拿它钓鱼）。
 *
 * 做法：所有跨进程消息带一个 MAC，密钥是构建期注入的 {@link AnchorKey}（只有本 APK 里才有）。
 * 平台签名不同的第三方算不出来，伪造直接丢弃。不是加密，只解决"谁能写"。
 */
final class Sig {

    /** 广播里带 MAC 的 extra 名（与 CfgBus 共用一套语义）。 */
    static final String EXTRA = "g";

    static String of(String payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(AnchorCodec.key());
            md.update((payload == null ? "" : payload).getBytes("UTF-8"));
            return hex(md.digest());
        } catch (Throwable t) {
            return "";
        }
    }

    static boolean ok(String payload, String sig) {
        if (sig == null || sig.isEmpty()) return false;
        String want = of(payload);
        if (want.isEmpty() || want.length() != sig.length()) return false;
        // 定长比较，不提前 return
        int diff = 0;
        for (int i = 0; i < want.length(); i++) diff |= want.charAt(i) ^ sig.charAt(i);
        return diff == 0;
    }

    private static String hex(byte[] b) {
        char[] h = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = h[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = h[b[i] & 0xF];
        }
        return new String(out);
    }

    private Sig() {}
}
