#!/usr/bin/env python3
"""构建后校验：把"装不上/装上了不工作"的几类问题挡在交付之前。

每一项都对应一次真实事故：
  · 组件类被 R8 裁掉（ConvertActivity）→ manifest 里的类必须能在 dex 里找到
  · .so 被压缩 → loadLibrary 失败 / 闪退
  · resources.arsc 被压缩 → targetSdk 30+ 安装失败
  · xposed_init 指向的类不存在 → 模块"装上了但什么都不做"
  · 交付包与源码不一致 → buildId 对不上
"""
import os
import sys
import zipfile
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from buildmeta import read_manifest_version, source_digest  # noqa: E402

ANDROID = "{http://schemas.android.com/apk/res/android}"
COMPONENTS = ("activity", "service", "receiver", "provider", "application")
JNI_METHODS = (("io.github.lex0m.lexin.SilkCodec", "silkToMp3"),)


def fail(msg):
    print("FAIL  " + msg)
    return 1


def ok(msg):
    print("ok    " + msg)
    return 0


def manifest_components(path):
    root = ET.parse(path).getroot()
    pkg = root.get("package")
    names = []
    for tag in COMPONENTS:
        for el in root.iter(tag):
            n = el.get(ANDROID + "name")
            if not n:
                continue
            if n.startswith("."):
                n = pkg + n
            elif "." not in n:
                n = pkg + "." + n
            names.append((tag, n))
    return pkg, names


def main(argv):
    if len(argv) != 4:
        raise SystemExit("usage: verify_apk.py <apk> <project> <expect-name>")
    apk, project, expect_name = argv[1:]
    errs = 0

    ver, code = read_manifest_version(os.path.join(project, "AndroidManifest.xml"))
    if ver != expect_name:
        errs += fail("manifest versionName=%s，期望 %s" % (ver, expect_name))

    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        dup = sorted({n for n in names if names.count(n) > 1})
        if dup:
            errs += fail("APK 内有重复条目: %s" % dup[:5])
        else:
            errs += ok("条目无重复（%d 个）" % len(names))

        missing_req = [n for n in ("AndroidManifest.xml", "resources.arsc", "classes.dex", "assets/xposed_init")
                       if n not in names]
        for n in missing_req:
            errs += fail("缺条目 " + n)
        if missing_req:
            return 1

        if z.getinfo("resources.arsc").compress_type != zipfile.ZIP_STORED:
            errs += fail("resources.arsc 被压缩（targetSdk 30+ 会安装失败）")
        else:
            errs += ok("resources.arsc 未压缩")

        dex = z.read("classes.dex")
        if not dex.startswith(b"dex\n"):
            errs += fail("classes.dex 头不对")
        else:
            errs += ok("classes.dex 正常（%d bytes）" % len(dex))

        entry = z.read("assets/xposed_init").decode("utf-8", "replace").strip()
        if entry != "io.github.lex0m.lexin.Entry":
            errs += fail("xposed_init 指向 %r" % entry)
        else:
            errs += ok("xposed_init -> " + entry)

        pkg, comps = manifest_components(os.path.join(project, "AndroidManifest.xml"))
        missing = []
        for tag, cls in comps:
            desc = ("L" + cls.replace(".", "/") + ";").encode()
            if desc not in dex:
                missing.append("%s %s" % (tag, cls))
        if missing:
            errs += fail("组件类不在 dex 里（被 R8 裁掉？）: " + ", ".join(missing))
        else:
            errs += ok("manifest 组件全部存在于 dex（%d 个）" % len(comps))

        expected_entry = ("L" + entry.replace(".", "/") + ";").encode()
        if expected_entry not in dex:
            errs += fail("xposed 入口类不在 dex 里")
        else:
            errs += ok("xposed 入口类存在于 dex")

        # native 库：逐个与源文件对拍，必须是 STORED
        libsrc = os.path.join(project, "lib")
        src_libs = {}
        for dirpath, dirs, files in os.walk(libsrc):
            for fn in files:
                if ".bak" in fn or fn.endswith((".orig", ".rej")) or fn.startswith("."):
                    continue
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, libsrc).replace(os.sep, "/")
                src_libs["lib/" + rel] = full
        for name, full in sorted(src_libs.items()):
            if name not in names:
                errs += fail("缺 native 库 " + name)
                continue
            zi = z.getinfo(name)
            if zi.compress_type != zipfile.ZIP_STORED:
                errs += fail("%s 被压缩（loadLibrary 会失败）" % name)
            elif zi.file_size != os.path.getsize(full):
                errs += fail("%s 大小与源文件不一致" % name)
            else:
                errs += ok("%s STORED %d bytes" % (name, zi.file_size))
        extra = [n for n in names if n.startswith("lib/") and n not in src_libs]
        if extra:
            errs += fail("APK 里有多余 native 条目: %s" % extra[:5])

        # JNI 绑定：Java 侧类名/方法名必须与 .so 里的符号逐字一致（改名即 UnsatisfiedLinkError）
        symbols = b""
        for name in src_libs:
            if name in names:
                symbols += z.read(name)
        for cls, meth in JNI_METHODS:
            desc = ("L" + cls.replace(".", "/") + ";").encode()
            sym = ("Java_" + cls.replace(".", "_") + "_" + meth).encode()
            if desc not in dex or meth.encode() not in dex:
                errs += fail("JNI 方法 %s.%s 不在 dex 里" % (cls, meth))
            elif sym not in symbols:
                errs += fail("JNI 符号 %s 不在 .so 里（类名/方法名与库不一致）" % sym.decode())
            else:
                errs += ok("JNI 绑定 %s.%s" % (cls, meth))

        if "assets/build-info.txt" not in names:
            errs += fail("缺 assets/build-info.txt（不是用 build.sh v2 构建的包）")
            got = {}
        else:
            info = z.read("assets/build-info.txt").decode("utf-8", "replace")
            got = dict(line.split("=", 1) for line in info.strip().splitlines() if "=" in line)
        digest, count = source_digest(project)
        if got.get("buildId") != digest:
            errs += fail("buildId 不符（包 %s / 源码 %s）—— 产物与源码不一致"
                         % (got.get("buildId"), digest))
        else:
            errs += ok("buildId=%s（%d 个源文件）" % (digest, count))
        if got.get("versionName") != ver or got.get("versionCode") != code:
            errs += fail("build-info 版本 %s(%s) 与 manifest %s(%s) 不一致"
                         % (got.get("versionName"), got.get("versionCode"), ver, code))
        else:
            errs += ok("build-info 版本 %s(%s)" % (ver, code))

    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
