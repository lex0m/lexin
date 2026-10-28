#!/usr/bin/env python3
"""把 aapt2 的 base.apk + R8 的 classes.dex + assets + lib 组装成未签名 APK。

规则（都是踩过坑才定下来的）：
  · `.so` 必须 ZIP_STORED —— 压缩过的 .so 会让 System.loadLibrary 失败/闪退
  · resources.arsc 保持 aapt2 的压缩方式（通常 STORED），targetSdk 30+ 不允许压缩
  · 所有条目用固定时间戳、固定顺序 —— 同样的输入产出同样的 zip 字节
  · assets/build-info.txt 带版本 + buildId（源码指纹），交付包可追溯
"""
import io
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from buildmeta import read_manifest_version, source_digest  # noqa: E402

STAMP = (2026, 1, 1, 0, 0, 0)
# 开发素材不进产品包（logo-src.png 613KB 曾经白白占掉一半体积）
EXCLUDE = {"assets/make-icons.js", "assets/logo-src.png"}
SKIP_SUFFIX = (".bak", ".orig", ".rej", ".tmp", ".swp")
SKIP_NAMES = (".DS_Store", "Thumbs.db")


def wanted(name):
    base = os.path.basename(name)
    return not (base in SKIP_NAMES or ".bak" in base or base.endswith(SKIP_SUFFIX) or base.startswith("."))


def add_bytes(zf, name, data, compress=zipfile.ZIP_DEFLATED):
    zi = zipfile.ZipInfo(name, date_time=STAMP)
    zi.compress_type = compress
    zi.external_attr = 0o644 << 16
    zi.create_system = 3
    zf.writestr(zi, data)


def add_file(zf, name, path, compress=zipfile.ZIP_DEFLATED):
    with open(path, "rb") as fh:
        add_bytes(zf, name, fh.read(), compress)


def main(argv):
    if len(argv) != 6:
        raise SystemExit("usage: package.py <build_dir> <project> <out.apk> <ver> <code>")
    build, project, out, ver, code = argv[1:]
    build_id, _files = source_digest(project)

    base = os.path.join(build, "base.apk")
    dex = os.path.join(build, "dex", "classes.dex")
    if not os.path.isfile(dex) or os.path.getsize(dex) == 0:
        raise SystemExit("classes.dex 不存在或为空: " + dex)

    if os.path.exists(out):
        os.remove(out)
    tmp = out + ".tmp"

    with zipfile.ZipFile(base) as src, zipfile.ZipFile(tmp, "w") as dst:
        # ① 保留 aapt2 产物（manifest / resources.arsc / res/**），压缩方式原样保留
        for item in src.infolist():
            if item.filename.startswith("classes") and item.filename.endswith(".dex"):
                continue
            if not wanted(item.filename):
                continue
            zi = zipfile.ZipInfo(item.filename, date_time=STAMP)
            zi.compress_type = item.compress_type
            zi.external_attr = item.external_attr or (0o644 << 16)
            zi.create_system = 3
            dst.writestr(zi, src.read(item.filename))

        # ② 代码
        add_file(dst, "classes.dex", dex)

        # ③ assets（含 xposed_init；主题素材都在包里，不往微信目录写文件）
        assets = os.path.join(project, "assets")
        shipped = []
        for dirpath, dirs, files in os.walk(assets):
            dirs.sort()
            for fn in sorted(files):
                if not wanted(fn):
                    continue
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, assets).replace(os.sep, "/")
                if "assets/" + rel in EXCLUDE:
                    continue
                add_file(dst, "assets/" + rel, full)
                shipped.append(rel)

        # ④ 构建信息（版本 + 源码指纹）
        info = ("versionName=%s\nversionCode=%s\nbuildId=%s\n" % (ver, code, build_id))
        add_bytes(dst, "assets/build-info.txt", info.encode("utf-8"))

        # ⑤ native 库：必须 STORED
        libdir = os.path.join(project, "lib")
        libs = []
        if os.path.isdir(libdir):
            for dirpath, dirs, files in os.walk(libdir):
                dirs.sort()
                for fn in sorted(files):
                    if not wanted(fn):
                        continue
                    full = os.path.join(dirpath, fn)
                    rel = os.path.relpath(full, libdir).replace(os.sep, "/")
                    add_file(dst, "lib/" + rel, full, zipfile.ZIP_STORED)
                    libs.append(rel)

    os.replace(tmp, out)
    size = os.path.getsize(out)
    print("packed: %s (%d bytes) assets=%d libs=%d" % (out, size, len(shipped), len(libs)))
    print("buildId=%s version=%s(%s)" % (build_id, ver, code))


if __name__ == "__main__":
    main(sys.argv)
