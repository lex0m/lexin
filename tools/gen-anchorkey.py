#!/usr/bin/env python3
"""生成锚点表的构建期密钥（v1.6.0）。

每次构建随机；写成 int[] 而不是字符串常量 —— R8 不会把 int[] 折叠进常量池，
静态扫 APK 拿不到"这串是解锚点表的钥匙"这个语义，也扫不到任何微信类名明文。

用法: gen-anchorkey.py <out.java> <hex32>
"""
import sys


def main(argv):
    if len(argv) != 3:
        raise SystemExit("usage: gen-anchorkey.py <out.java> <hex32>")
    out, hexkey = argv[1], argv[2]
    raw = bytes.fromhex(hexkey)
    if len(raw) != 16:
        raise SystemExit("key must be 16 bytes (32 hex chars), got %d" % len(raw))
    ints = [int.from_bytes(raw[i:i + 4], "big") for i in range(0, 16, 4)]
    body = ", ".join("0x%08X" % v for v in ints)
    src = (
        "package io.github.lex0m.lexin;\n"
        "\n"
        "/** 构建期生成的锚点表密钥（每次构建不同）。不要提交、不要手改。 */\n"
        "final class AnchorKey {\n"
        "    static final int[] K = { %s };\n"
        "    private AnchorKey() {}\n"
        "}\n" % body
    )
    with open(out, "w", encoding="utf-8") as fh:
        fh.write(src)
    print("anchorkey: %d bytes -> %s" % (len(raw), out))


if __name__ == "__main__":
    main(sys.argv)
