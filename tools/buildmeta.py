#!/usr/bin/env python3
"""构建元数据：版本解析 + 源码指纹（构建溯源用）。

只依赖标准库，设备上的 python3.14 可直接跑。
"""
import hashlib
import io
import os
import re
import sys

SKIP_SUFFIX = (".bak", ".orig", ".rej", ".tmp", ".swp")
SKIP_NAMES = (".DS_Store", "Thumbs.db")


def read_manifest_version(path):
    s = io.open(path, encoding="utf-8").read()
    name = re.search(r'android:versionName="([^"]+)"', s)
    code = re.search(r'android:versionCode="([^"]+)"', s)
    if not name or not code:
        raise SystemExit("AndroidManifest.xml 里找不到 versionName/versionCode")
    return name.group(1), code.group(1)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def source_digest(project):
    """对 src/ assets/ lib/ 的（相对路径, 内容哈希）排序后取指纹。

    构建产物（assets/build-info.txt）不在源树里，不参与计算；
    任何源码/资源/库变化都会改变 buildId ⇒ 交付包可追溯到具体源码状态。
    """
    items = []
    for top in ("src", "assets", "lib"):
        root = os.path.join(project, top)
        if not os.path.isdir(root):
            continue
        for dirpath, dirs, files in os.walk(root):
            dirs.sort()
            for fn in sorted(files):
                if fn in SKIP_NAMES or ".bak" in fn or fn.endswith(SKIP_SUFFIX) or fn.startswith("."):
                    continue
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, project).replace(os.sep, "/")
                items.append((rel, sha256_file(full)))
    items.sort()
    h = hashlib.sha256()
    for rel, digest in items:
        h.update(rel.encode("utf-8"))
        h.update(b"\0")
        h.update(digest.encode("ascii"))
        h.update(b"\n")
    return h.hexdigest()[:16], len(items)


def main(argv):
    if len(argv) != 2:
        raise SystemExit("usage: buildmeta.py <AndroidManifest.xml>")
    name, code = read_manifest_version(argv[1])
    print("%s %s" % (name, code))


if __name__ == "__main__":
    main(sys.argv)
