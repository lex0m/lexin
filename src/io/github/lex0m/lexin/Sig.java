package io.github.lex0m.lexin;

import java.security.MessageDigest;

/**
 * 广播防伪（v1.6.0，v1.7 修密钥生命周期）。
 *
 * 问题：`CfgReceiver` 是静态注册、exported 的（微信和模块是不同 uid，不 export 收不到），
 * 于是**任何第三方 App 都能往 `x.p` 发一条改配置、往 `x.s` 发一条假状态** ——
 * 面板显示"已生效"可能压根不是微信说的。这既是功能问题，也是隐蔽性问题（别人可以拿它钓鱼）。
 *
 * 做法：所有跨进程消息带一个 MAC。平台签名不同的第三方算不出来，伪造直接丢弃。
 * 不是加密，只解决"谁能写"。
 *
 * ★ v1.7 修的坑（C-02 的一半，比防伪本身重要）：
 *   以前密钥 = {@link AnchorCodec#key()} = 构建期随机生成的 {@link AnchorKey}，
 *   **每次构建都不同**。于是：模块升级后只要微信没重启，微信进程里跑的还是旧代码（旧 key），
 *   而新装的模块 App 是 new key ⇒ CfgBus 每一条广播 MAC 全对不上 ⇒ 全部判伪丢弃
 *   ⇒ 表现为「面板改了、重启微信就没了」。
 *   现在改成**跨版本固定的编译期常量**：第三方拿得到它也无所谓（它跟 AnchorKey 一样就躺在 dex 里，
 *   真正的隔离靠的是"只有本机装了模块"，不是密钥保密）。
 */
final class Sig {

    /** 广播里带 MAC 的 extra 名（与 CfgBus 共用一套语义）。 */
    static final String EXTRA = "g";

    /**
     * 固定 MAC 密钥（16 字节）。**禁止**改成随构建/版本变化的东西。
     * 值的来源：随便挑的一组常量，不带任何语义（反编译看到也说明不了什么）。
     */
    private static final byte[] MAC_KEY = {
            (byte) 0x9E, (byte) 0x3B, (byte) 0x71, (byte) 0xC4,
            (byte) 0x05, (byte) 0xDA, (byte) 0x48, (byte) 0x2F,
            (byte) 0xB6, (byte) 0x1C, (byte) 0xE3, (byte) 0x87,
            (byte) 0x52, (byte) 0xAF, (byte) 0x09, (byte) 0x6D,
    };

    static String of(String payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(MAC_KEY);
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
