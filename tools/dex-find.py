#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
dex-find.py —— 在微信 APK 的 dex 里按关键字找类/方法（不反编译，纯 DEX 规范解析）。

用法：
  python3 tools/dex-find.py <apk> str <关键字> [关键字...]      # 搜字符串池
  python3 tools/dex-find.py <apk> cls <正则>                    # 搜类名
  python3 tools/dex-find.py <apk> m <类名>                      # 列某类的全部方法签名
  python3 tools/dex-find.py <apk> ms <正则>                     # 按方法名正则搜（跨所有类）

为什么自己写：gen-anchors.py 只按槽位规则找固定签名；新功能（拍一拍 / 输入状态上报 / typing）
的锚点还不知道长什么样，需要开放式的"关键字 → 类 → 方法签名"探索。
"""
import re
import struct
import sys
import zipfile


class Dex:
    def __init__(self, data):
        self.d = data
        self._setup()

    # ── 基础读取 ──────────────────────────────────────────────
    def u4(self, o):
        return struct.unpack_from('<I', self.d, o)[0]

    def u2(self, o):
        return struct.unpack_from('<H', self.d, o)[0]

    def uleb(self, o):
        r = 0
        s = 0
        while True:
            b = self.d[o]
            o += 1
            r |= (b & 0x7f) << s
            s += 7
            if not b & 0x80:
                break
        return r, o

    def _setup(self):
        d = self.d
        self.so = self.u4(60)
        self.to = self.u4(68)
        self.po = self.u4(76)
        self.fo = self.u4(84)
        self.mo = self.u4(92)
        self.cdo = self.u4(100)
        self.ssz = self.u4(56)
        self.tsz = self.u4(64)
        self.psz = self.u4(72)
        self.fsz = self.u4(80)
        self.msz = self.u4(88)
        self.cdsz = self.u4(96)

    def string(self, i):
        """
        读第 i 个字符串。

        ★ v1.7.8 修掉的失真：uleb128 读出来的是**UTF-16 码元数**，不是 UTF-8 字节数。
        以前直接切片 d[o:o+n] ⇒ 纯 ASCII 串正常，但含中文的串会被截断成半个字，
        于是 `str 消息防撤回` 一律 0 命中 —— 一度被当成"功能已删"的证据（假阴性）。
        现在按 string_data 的 NUL 结尾取整段；MUTF-8 的 0 编码（C0 80）先还原成 0。
        """
        o = self.u4(self.so + i * 4)
        _n, o = self.uleb(o)                 # 只用来跳过前缀，长度不足信
        e = self.d.find(b'\x00', o)
        if e < 0:
            e = len(self.d)
        raw = self.d[o:e].replace(b'\xc0\x80', b'\x00')
        return raw.decode('utf-8', 'replace')

    def type_name(self, i):
        return self.string(self.u4(self.to + i * 4))

    def proto(self, i):
        o = self.po + i * 12
        ret = self.type_name(self.u4(o + 4))
        palt = self.u4(o + 8)
        params = []
        if palt:
            n = self.u4(palt)
            for k in range(n):
                params.append(self.type_name(self.u2(palt + 4 + k * 2)))
        return ret, params

    def code_insns(self, code_off):
        """返回 code_item 的指令字节（不含 tries）。"""
        if not code_off or code_off + 16 > len(self.d):
            return b''
        # ★ insns_size 的单位是 **u2**（16 位指令数），不是字节数 —— 写成字节会少读一半，漏掉后半段的
        #   const-string（本次就是靠修这里才找到 typingSend / sendPatMsg 的引用点）
        n = self.u4(code_off + 12)
        off = code_off + 16
        if n <= 0 or n > 2 * 1024 * 1024 or off + n * 2 > len(self.d):
            return b''
        return self.d[off:off + n * 2]

    def methods(self):
        """yield (class_descriptor, method_name, ret, params, is_static)"""
        for ci in range(self.cdsz):
            co = self.cdo + ci * 32
            cls = self.type_name(self.u4(co))
            data_off = self.u4(co + 24)
            if not data_off:
                continue
            p = data_off
            sf, p = self.uleb(p)
            inf, p = self.uleb(p)
            dm, p = self.uleb(p)
            vm, p = self.uleb(p)
            # static fields / instance fields 跳过（含 initial value）
            for _ in range(sf):
                _, p = self.uleb(p)
                _, p = self.uleb(p)
            for _ in range(inf):
                _, p = self.uleb(p)
            mids = []
            # ★ method_idx 是**差分编码**（method_idx_diff），必须累加；直接用绝对值会越界
            cur = 0
            for _ in range(dm + vm):
                diff, p = self.uleb(p)
                cur += diff
                acc, p = self.uleb(p)
                code, p = self.uleb(p)
                mids.append((cur, acc, code))
            for idx, acc, code in mids:
                if idx >= self.msz:      # 差分解析越界 ⇒ 这份 class_data 不可信，跳过
                    continue
                mo = self.mo + idx * 8
                name = self.string(self.u4(mo + 4))
                ret, params = self.proto(self.u2(mo + 2))
                yield cls, name, ret, params, (acc & 0x0008) != 0


def load_dexes(apk):
    out = []
    with zipfile.ZipFile(apk) as z:
        names = [n for n in z.namelist() if n.endswith('.dex')]
        names.sort()
        for n in names:
            out.append((n, Dex(z.read(n))))
    return out


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    apk, mode = sys.argv[1], sys.argv[2]
    args = sys.argv[3:]
    dexes = load_dexes(apk)
    print('# dexes:', [n for n, _ in dexes], file=sys.stderr)

    if mode == 'str':
        pats = [re.compile(a) for a in args]
        for dn, dx in dexes:
            for i in range(dx.ssz):
                s = dx.string(i)
                if any(p.search(s) for p in pats):
                    print(f'{dn}\t{s}')
    elif mode == 'cls':
        pats = [re.compile(a) for a in args]
        for dn, dx in dexes:
            for ci in range(dx.cdsz):
                c = dx.type_name(dx.u4(dx.cdo + ci * 32))
                if any(p.search(c) for p in pats):
                    print(f'{dn}\t{c}')
    elif mode == 'sw':
        # 搜字符串 → 找出**引用它的类和方法**（这是找锚点的正路：
        # 微信的日志 tag / 提示文案通常不会改，反查引用点就能定位业务方法）
        pats = [re.compile(a) for a in args]
        for dn, dx in dexes:
            # 1) 目标字符串索引
            want = {}
            for i in range(dx.ssz):
                s = dx.string(i)
                if any(p.search(s) for p in pats):
                    want[i] = s
            if not want:
                continue
            # 3) 扫所有方法的指令，找 const-string 引用
            for ci in range(dx.cdsz):
                co = dx.cdo + ci * 32
                cls = dx.type_name(dx.u4(co))
                data_off = dx.u4(co + 24)
                if not data_off:
                    continue
                p = data_off
                sf, p = dx.uleb(p)
                inf, p = dx.uleb(p)
                dm, p = dx.uleb(p)
                vm, p = dx.uleb(p)
                for _ in range(sf):
                    _, p = dx.uleb(p)
                    _, p = dx.uleb(p)
                for _ in range(inf):
                    _, p = dx.uleb(p)
                cur = 0
                for _ in range(dm + vm):
                    diff, p = dx.uleb(p)
                    cur += diff
                    acc, p = dx.uleb(p)
                    code, p = dx.uleb(p)
                    if cur >= dx.msz:
                        continue
                    mo = dx.mo + cur * 8
                    mname = dx.string(dx.u4(mo + 4))
                    ret, mparams = dx.proto(dx.u2(mo + 2))
                    ins = dx.code_insns(code)
                    if not ins:
                        continue
                    hit = None
                    for pos in range(0, len(ins) - 5):
                        op = ins[pos]
                        if op == 0x1a:            # const-string  vAA, string@BBBB
                            si = ins[pos + 1] | (ins[pos + 2] << 8)
                            if si in want:
                                hit = si
                                break
                        elif op == 0x1b:          # const-string/jumbo vAA, string@BBBBBBBB
                            si = (ins[pos + 2] | (ins[pos + 3] << 8)
                                  | (ins[pos + 4] << 16) | (ins[pos + 5] << 24))
                            if si in want:
                                hit = si
                                break
                    if hit is not None:
                        print(f'{dn}\t{cls}#{mname}({",".join(mparams)}){ret}'
                              f'\t<< {want[hit]}')
    elif mode == 'impl':
        # 找实现了指定接口的类（解析 class_def 的 interfaces_off → type_list）
        wants = set(a if a.startswith('L') else 'L' + a.replace('.', '/') + ';' for a in args)
        for dn, dx in dexes:
            for ci in range(dx.cdsz):
                co = dx.cdo + ci * 32
                io = dx.u4(co + 12)          # interfaces_off
                if not io:
                    continue
                cls = dx.type_name(dx.u4(co))
                n = dx.u4(io)
                hit = False
                for k in range(n):
                    if dx.type_name(dx.u2(io + 4 + k * 2)) in wants:
                        hit = True
                        break
                if hit:
                    print(f'{dn}\t{cls}')
    elif mode == 'm':
        want = set(args)
        for dn, dx in dexes:
            for cls, name, ret, params, st in dx.methods():
                if cls in want or cls.lstrip('L') in want:
                    p = ','.join(params)
                    print(f'{cls}#{name}({p}){ret}{" [static]" if st else ""}')
    elif mode == 'ms':
        pats = [re.compile(a) for a in args]
        for dn, dx in dexes:
            for cls, name, ret, params, st in dx.methods():
                if any(p.search(name) for p in pats):
                    p = ','.join(params)
                    print(f'{dn}\t{cls}#{name}({p}){ret}')
    else:
        print('unknown mode', mode)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
