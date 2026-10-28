package io.github.lex0m.lexin;

/**
 * JNI 绑定：SILK 解码 + LAME 编码（liblexinaudio.so，487KB，APK 内 ZIP_STORED）。
 *
 * ★ 类名与方法名是 .so 编译时写死的：Java_io_github_lex0m_lexin_SilkCodec_silkToMp3
 *   改类名/方法名 = UnsatisfiedLinkError（构建后由 tools/verify_apk.py 校验）。
 * ★ 微信进程**不加载**本库；只有乐信 App 的 ConvertActivity 调用（微信进程保持零 native）。
 */
final class SilkCodec {

    private SilkCodec() {
    }

    static native int silkToMp3(byte[] silk, String outPath, int bitrateKbps);

    /** 直接转码；调用方负责先 System.loadLibrary("lexinaudio")。 */
    static int encode(byte[] frames, String outPath, int bitrateKbps) {
        return silkToMp3(frames, outPath, bitrateKbps);
    }
}
