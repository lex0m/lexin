/*
 * SILK -> MP3（自带解码器 + LAME 编码器），供乐信 App 的 ConvertActivity 通过 JNI 调用。
 *
 * ★ 2026-10-17 修：旧版把 &dec（void** 的地址）传给 SKP_Silk_SDK_InitDecoder()，
 *   而该 API 要的是**调用方分配好的解码器状态缓冲区**（SKP_Silk_SDK_Get_Decoder_Size 给出大小）。
 *   结果解码器初始化直接写坏栈上的 8 字节指针 ⇒ 真机一调用就 SIGSEGV（signal 11）。
 *   正确姿势见下：Get_Decoder_Size → malloc → InitDecoder(dec)。
 *
 * 编译（见 tools/build-audio.sh）：
 *   clang --target=aarch64-linux-android21 -O2 -shared -fPIC ...
 */
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "SKP_Silk_SDK_API.h"
#include "lame.h"

static int silk_to_mp3(const unsigned char *in, int inLen, const char *path, int bitrateKbps) {
    if (!in || inLen <= 0 || !path) return -1;

    /* 容忍两种输入：带 0x02+"#!SILK_V3" 头的微信原文，或已去头的纯包流 */
    if (inLen >= 10 && memcmp(in + 1, "#!SILK_V3", 9) == 0) {
        in += 10;
        inLen -= 10;
    } else if (inLen >= 9 && memcmp(in, "#!SILK_V3", 9) == 0) {
        in += 9;
        inLen -= 9;
    }

    SKP_int32 decSize = 0;
    if (SKP_Silk_SDK_Get_Decoder_Size(&decSize) != 0 || decSize <= 0) return -2;
    void *dec = malloc((size_t) decSize);
    if (!dec) return -2;
    memset(dec, 0, (size_t) decSize);
    if (SKP_Silk_SDK_InitDecoder(dec) != 0) {
        free(dec);
        return -2;
    }

    SKP_SILK_SDK_DecControlStruct ctl;
    memset(&ctl, 0, sizeof(ctl));
    ctl.API_sampleRate = 24000;   /* 微信语音按 24kHz 输出 */
    ctl.framesPerPacket = 1;      /* 首包前丢包补偿的默认值（与官方 Decoder.c 一致） */

    FILE *fp = fopen(path, "wb");
    if (!fp) {
        free(dec);
        return -3;
    }

    lame_t gfp = lame_init();
    if (!gfp) {
        fclose(fp);
        free(dec);
        return -4;
    }
    lame_set_num_channels(gfp, 1);
    lame_set_in_samplerate(gfp, ctl.API_sampleRate);
    lame_set_brate(gfp, bitrateKbps > 0 ? bitrateKbps : 32);
    lame_set_mode(gfp, MONO);
    lame_set_quality(gfp, 5);
    if (lame_init_params(gfp) < 0) {
        lame_close(gfp);
        fclose(fp);
        free(dec);
        return -5;
    }

    int rc = -6;
    int total = 0;
    int decodedFrames = 0;
    const unsigned char *p = in;
    int remain = inLen;
    unsigned char mp3buf[16384];

    /* ★ SILK v3 文件体是「int16 长度前缀 + 一个包（1~5 个 20ms 帧）」的序列，
     *   不是裸帧流。旧版把整段当帧喂进去 ⇒ 只解出第一帧（MP3 只有 300 字节）。
     *   这里按官方 Decoder.c 的读法：读长度 → 喂这一包 → 循环到 moreInternalDecoderFrames=0。 */
    while (remain >= 2) {
        int nBytes = (int) p[0] | ((int) p[1] << 8);
        p += 2;
        remain -= 2;
        if (nBytes <= 0 || nBytes > remain) break;
        {
            const unsigned char *payload = p;
            int frames = 0;
            do {
                short pcm[2000];
                SKP_int16 nOut = 0;
                int ret = SKP_Silk_SDK_Decode(dec, &ctl, 0, payload, nBytes, pcm, &nOut);
                if (ret != 0) break;
                if (nOut > 0) {
                    int n = lame_encode_buffer(gfp, pcm, pcm, nOut, mp3buf, (int) sizeof(mp3buf));
                    if (n > 0) {
                        fwrite(mp3buf, 1, (size_t) n, fp);
                        total += n;
                        decodedFrames++;
                    }
                }
                frames++;
            } while (ctl.moreInternalDecoderFrames && frames < 8);
        }
        p += nBytes;
        remain -= nBytes;
    }

    {
        int n = lame_encode_flush(gfp, mp3buf, (int) sizeof(mp3buf));
        if (n > 0) {
            fwrite(mp3buf, 1, (size_t) n, fp);
            total += n;
        }
    }

#ifdef SILK2MP3_CLI
    fprintf(stderr, "packets/frames=%d total_bytes=%d remain=%d ctl.more=%d\n",
            decodedFrames, total, remain, (int) ctl.moreInternalDecoderFrames);
#endif
    /* 只写了个 Xing 头也算失败：必须真的解出帧 */
    rc = (decodedFrames > 0 && total > 0) ? 0 : -6;
    lame_close(gfp);
    fclose(fp);
    free(dec);
    return rc;
}

JNIEXPORT jint JNICALL
Java_io_github_lex0m_lexin_SilkCodec_silkToMp3(JNIEnv *env, jclass clz,
                                               jbyteArray silkData, jstring outPath,
                                               jint bitrateKbps) {
    (void) clz;
    if (!silkData || !outPath) return -1;
    jsize inLen = (*env)->GetArrayLength(env, silkData);
    jbyte *in = (*env)->GetByteArrayElements(env, silkData, NULL);
    const char *path = (*env)->GetStringUTFChars(env, outPath, NULL);
    if (!in || !path) {
        if (in) (*env)->ReleaseByteArrayElements(env, silkData, in, JNI_ABORT);
        if (path) (*env)->ReleaseStringUTFChars(env, outPath, path);
        return -1;
    }
    int rc = silk_to_mp3((const unsigned char *) in, (int) inLen, path, (int) bitrateKbps);
    (*env)->ReleaseByteArrayElements(env, silkData, in, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, outPath, path);
    return (jint) rc;
}

/* 命令行自测：silk2mp3_cli in.silk out.mp3 [kbps] —— 不依赖 JVM，先在设备上验解码/编码链路 */
#ifdef SILK2MP3_CLI
int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s in.silk out.mp3 [kbps]\n", argv[0]);
        return 2;
    }
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror("open"); return 3; }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char *buf = (unsigned char *) malloc((size_t) n);
    if (!buf || fread(buf, 1, (size_t) n, f) != (size_t) n) { fclose(f); return 3; }
    fclose(f);
    int rc = silk_to_mp3(buf, (int) n, argv[2], argc > 3 ? atoi(argv[3]) : 32);
    free(buf);
    fprintf(stderr, "rc=%d\n", rc);
    return rc == 0 ? 0 : 1;
}
#endif
