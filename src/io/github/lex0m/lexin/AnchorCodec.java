package io.github.lex0m.lexin;

import java.security.MessageDigest;

/**
 * 锚点表解密（v1.6.0）。
 *
 * 为什么要加密：`assets/anchors.json` 是 30 KB 明文，里面 40 个槽位全是微信类名
 * （`com.tencent.mm.storage.f9` 这种）。任何静态扫描、字符串比对、人工翻包，
 * 第一眼就能看出"这个 APK 是冲微信去的、挂的是哪个类"。这比 hook 本身更容易被抓。
 *
 * 做法：构建期用一次性随机密钥（{@link AnchorKey}，每次构建都不同，写成 int[] 而不是字符串，
 * R8 不会把它折叠进常量池）做 SHA-256 计数器流加密；运行期只在内存里还原。
 * 挡不住有人拿着 dex 硬逆，但能挡掉"扫字符串就定位"这一整类检测。
 */
final class AnchorCodec {

    private AnchorCodec() {}

    /** 解密 anchor.bin；失败返回 null（调用方退到明文表或未适配）。 */
    static String decrypt(byte[] data) {
        try {
            if (data == null || data.length == 0) return null;
            byte[] key = key();
            byte[] plain = new byte[data.length];
            byte[] block = new byte[0];
            int off = 0;
            int counter = 0;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            while (off < data.length) {
                if (off % 32 == 0) {
                    md.reset();
                    md.update(key);
                    md.update(new byte[]{
                            (byte) (counter >>> 24), (byte) (counter >>> 16),
                            (byte) (counter >>> 8), (byte) counter});
                    block = md.digest();
                    counter++;
                }
                int k = block[off % 32] & 0xFF;
                plain[off] = (byte) (data[off] ^ k);
                off++;
            }
            return new String(plain, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从构建期注入的 int[] 还原 16 字节密钥。 */
    static byte[] key() {
        int[] k = AnchorKey.K;
        byte[] out = new byte[16];
        for (int i = 0; i < 4 && i < k.length; i++) {
            out[i * 4] = (byte) (k[i] >>> 24);
            out[i * 4 + 1] = (byte) (k[i] >>> 16);
            out[i * 4 + 2] = (byte) (k[i] >>> 8);
            out[i * 4 + 3] = (byte) k[i];
        }
        return out;
    }
}
