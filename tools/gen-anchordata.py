#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把锚点表内嵌进 dex（v1.7）。

为什么要这个（C-01 的最后一块）：
  锚点表现在只存在 APK 的 assets/anchor.bin 里，读取要靠 `Assets.resolvePath()` 拿到模块 APK 路径。
  而在**免 root 内嵌模式（LSPatch）**下：
    · 框架不会调 initZygote ⇒ 拿不到 modulePath；
    · 模块的包在宿主眼里不存在（包可见性）⇒ getApplicationInfo 抛 NameNotFound；
    · LSPatch 只合并 dex、**不合并 assets** ⇒ 宿主 APK 里根本没有 anchor.bin。
  三个条件一齐成立 ⇒ 表永远读不到 ⇒ 全部功能静默哑掉。

  所以把同一份密文再以 base64 常量塞进代码里，和 assets 互为备份：
  assets 读得到就用 assets（体积不进内存常驻），读不到就用内置的。

用法: gen-anchordata.py <anchors.json> <out.java> <hex32>

注意：常量必须与 `tools/package.py` 里写进 assets/anchor.bin 的**完全一致**
（同一个 LEXIN_ASSET_KEY + 同一套 SHA-256 计数器流），否则两边的解密结果对不上。
"""
import base64
import hashlib
import json
import os
import sys

# 单个 Java 字符串常量的长度上限是 65535 字节（UTF-8），这里取 6000 字符一段，
# 既远离上限，也不会让某个常量在 dex 里特别显眼。
CHUNK = 6000


def keystream(key, n):
    """SHA-256 计数器流 —— 必须与 tools/package.py::_keystream 和 Java 侧 AnchorCodec 一致。"""
    out = bytearray()
    i = 0
    while len(out) < n:
        out += hashlib.sha256(key + i.to_bytes(4, "big")).digest()
        i += 1
    return bytes(out[:n])


def main(argv):
    if len(argv) != 4:
        raise SystemExit("usage: gen-anchordata.py <anchors.json> <out.java> <hex32>")
    src, out, hexkey = argv[1], argv[2], argv[3]
    key = bytes.fromhex(hexkey)
    if len(key) != 16:
        raise SystemExit("key must be 16 bytes (32 hex chars), got %d" % len(key))

    raw = open(src, "rb").read()
    # 打包前先校验一遍 JSON，免得把坏表打进代码里（assets 那侧由 package.py 负责）
    json.loads(raw.decode("utf-8"))

    enc = bytes(a ^ b for a, b in zip(raw, keystream(key, len(raw))))
    b64 = base64.b64encode(enc).decode("ascii")

    parts = [b64[i:i + CHUNK] for i in range(0, len(b64), CHUNK)] or [""]
    body = ",\n            ".join('"%s"' % p for p in parts)

    java = (
        "package io.github.lex0m.lexin;\n"
        "\n"
        "/**\n"
        " * 构建期生成的锚点表密文（v1.7）。不要提交、不要手改。\n"
        " *\n"
        " * 为什么要在 dex 里再放一份：免 root 内嵌模式（LSPatch）下宿主 APK 里没有 assets/anchor.bin，\n"
        " * 且拿不到模块 APK 路径 —— 只有内置这一份能救回来。内容与 assets/anchor.bin 完全一致，\n"
        " * 解密走同一个 {@link AnchorCodec}。\n"
        " */\n"
        "final class AnchorData {\n"
        "    /** 密文的 base64 分段（每段 6000 字符，避开字符串常量 64KB 上限）。 */\n"
        "    static final String[] P = {\n"
        "            %s\n"
        "    };\n"
        "\n"
        "    private static volatile byte[] cache;\n"
        "\n"
        "    /** 解出原始密文字节（未解密，交给 AnchorCodec）。 */\n"
        "    static byte[] bytes() {\n"
        "        byte[] c = cache;\n"
        "        if (c != null) return c;\n"
        "        try {\n"
        "            StringBuilder sb = new StringBuilder(8192);\n"
        "            for (String p : P) sb.append(p);\n"
        "            c = android.util.Base64.decode(sb.toString(), android.util.Base64.NO_WRAP);\n"
        "            cache = c;\n"
        "            return c;\n"
        "        } catch (Throwable t) {\n"
        "            return null;\n"
        "        }\n"
        "    }\n"
        "\n"
        "    private AnchorData() {}\n"
        "}\n" % body
    )

    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        fh.write(java)
    print("anchordata: %d bytes plain -> %d bytes enc -> %d chunks -> %s"
          % (len(raw), len(enc), len(parts), out))


if __name__ == "__main__":
    main(sys.argv)
