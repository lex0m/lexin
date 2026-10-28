#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从微信 APK 生成锚点表（assets/anchors.json）。

原理：dex 里类名/方法名/字段名都是明文，且签名由 dex 自己的 proto 表给出。
本脚本直接按 DEX 规范解析出「类 -> 声明的方法/字段签名」，再按槽位规则匹配，
不需要反编译，一个 280MB 的 APK 约 20-40 秒。

用法：
  python3 tools/gen-anchors.py <wxstealth目录> <版本名>=<apk路径> [<版本名>=<apk路径> ...]
例：
  python3 tools/gen-anchors.py . 8.0.78=/storage/emulated/0/Download/weixin8078...apk

产物：<wxstealth>/assets/anchors.json —— slots(规则) + versions(每版命中类名)
"""
import json
import os
import re
import struct
import sys
import zipfile

# ── 槽位规则 ────────────────────────────────────────────────────────────────
# pkg     : 命中类必须在这个包（含子包）下；空串表示不限包（走混淆类宽松扫描）
# simple  : 类名末段的正则；空串表示不限
# method  : 必须声明的方法签名 name(params):ret，* 表示任意
# method2 : 第二个必须存在的方法签名（可选）
# fields  : 必须存在的字段（沿父类链找），逗号分隔（可选）
# scan    : 是否允许运行时自动扫描（签名可能撞车/无法唯一定位时置 false）
SLOTS = {
    # ── 防撤回：撤回通知的解析入口（抹掉 $type 就不再改写原消息） ──
    "sysmsg_parser": {
        "pkg": "com.tencent.mm.sdk.platformtools",
        "simple": r"^[a-z0-9]{1,4}$",
        "method": "d(java.lang.String,java.lang.String,java.lang.String):java.util.Map",
        "scan": True,
        "note": "8.0.70-8.0.79 均为 platformtools 下的短名类",
    },
    # ── 防撤回：消息实体（记 talker+svrId 才能把撤回标记贴回原消息） ──
    "msg": {
        "pkg": "com.tencent.mm.storage",
        "simple": r"^[a-z0-9]{1,4}$",
        "fields": "field_msgSvrId,field_talker,field_isSend",
        "scan": True,
    },
    # ── 防撤回：聊天行绑定完成点（给行加「已撤回」标记） ──
    "chat_binder": {
        "pkg": "",
        "simple": r"^[a-z0-9]{1,4}$",
        "method": "h(*,*,int,int,boolean,java.util.List):void",
        # go.b = 8.0.79(android3200) 里按 method 指纹唯一命中的真实类名；
        # xl5.g 是旧种子（在 8.0.79 上是别的类，会 fingerprint mismatch）
        "seed": ["go.b", "xl5.g"],
        "note": "同签名的绑定器很多，只认版本表/种子，不自动扫",
        "scan": False,
    },
    # ── 防撤回兜底：聊天列表适配器（按 position 取消息） ──
    "chat_adapter": {
        "pkg": "com.tencent.mm.ui.chatting.adapter",
        "simple": r"^[a-z0-9]{1,3}$",
        "seed": ["com.tencent.mm.ui.chatting.adapter.k"],
        "scan": False,
    },
    # ── 骰子/猜拳：随机数入口 + 表情面板发送入口 ──
    "rng": {
        "pkg": "com.tencent.mm.sdk.platformtools",
        "simple": r"^[a-z0-9]{1,4}$",
        "method": "Q(int,int):int",
        "scan": True,
    },
    "emoji_panel": {
        "pkg": "",
        "simple": r"^[a-z0-9]{1,4}$",
        "method": "a(android.view.View,android.content.Context,int,*):void",
        "seed": ["vr.p"],
        "scan": True,
    },
    # ── 自动查看原图：图片浏览页（类名一直没混淆） ──
    "gallery": {
        "exact": "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI",
        "scan": False,
    },
    # ── 隐蔽入口：关怀模式介绍页（类名一直没混淆） ──
    "care_mode": {
        "exact": "com.tencent.mm.plugin.setting.ui.setting.SettingsCareModeIntro",
        "scan": False,
    },
    # ── 朋友圈保留：SNS 存储层的 SQL 执行口（SQL 文本里判 DELETE，不误伤读操作） ──
    "sns_db": {
        "pkg": "",
        "simple": r"^[a-z0-9]{1,4}$",
        "method": "A(java.lang.String,java.lang.String):boolean",
        "multi": True,
        "scan": True,
        "note": "一个版本里可能同时存在多个库对象，全挂上",
    },
    "sns_db2": {
        "pkg": "",
        "simple": r"^[a-z0-9]{1,4}$",
        "method": "H(java.lang.String,java.lang.String,java.lang.Object[]):boolean",
        "multi": True,
        "scan": False,
        "note": "与 sns_db 同一批类，靠种子/表定位",
    },
    # ── 去广告：小程序开屏广告判定 ──
    "ad_appbrand": {
        "pkg": "com.tencent.mm.plugin.appbrand",
        "simple": r"^[a-z0-9]{1,3}$",
        "method": "K2(*):boolean",
        "scan": True,
    },
    "ad_open": {
        "pkg": "",
        "simple": r"^[a-z0-9]{1,4}$",
        "method": "a(com.tencent.mm.plugin.appbrand.config.AppBrandInitConfigWC):boolean",
        # vc1.j = 8.0.79(android3200) 里按 method 指纹唯一命中的真实类名；lc1.j 是旧种子
        "seed": ["vc1.j", "lc1.j"],
        "scan": False,
    },
    # ── 转发增强：三个字符串特征槽位（WeKit 同款定位法） ──
    # 微信自己的 MM 菜单 API：方法名没混淆 → 按名字定位，天然跨版本
    # 照抄成品 WAuxiliary 的配方（★4037，就是设备上的 me.hd.wauxv）：
    #   searchPackages("com.tencent.mm.ui.chatting.viewitems")
    #   usingStrings("MicroMsg.ChattingItem", "msg is null!")            ← 菜单构造点
    # 它的匹配是"包含"语义，字符串真实形式是 "msg is null! pos:%s"。
    # 微信自己的 MM 菜单 API：onCreateMMMenu / onMMMenuItemSelected 这两个名字**没混淆**
    # （日志实测：8.0.78 上是 ui.chatting.component.ag 与 ui.chatting.presenter.l6）
    "chat_menu": {
        "pkg": "com.tencent.mm.ui.chatting",
        "simple": r"^.+$",
        "method": "onCreateMMMenu(*):void",
        "multi": True,
        "allHits": True,
        "maxHits": 40,
        "scan": True,
        "note": "长按菜单构造点（按未混淆方法名定位）",
    },
    "chat_menu_select": {
        "pkg": "com.tencent.mm.ui.chatting",
        "simple": r"^.+$",
        "method": "onMMMenuItemSelected(android.view.MenuItem,int):void",
        "multi": True,
        "allHits": True,
        "maxHits": 40,
        "scan": True,
        "note": "菜单点击回调（按未混淆方法名定位）",
    },
    "chat_quick_select": {
        "pkg": "",
        "simple": r"^.+$",
        "strings": ["MicroMsg.ChatItemQuickSelectComponent"],
        "scan": False,
        "note": "多选组件（解除多选上限用）",
    },
    # ── 微信运动：三个字符串特征槽位（插件包名不混淆，但类名混淆，靠日志 tag / provider URI 认） ──
    "sport_util": {
        "pkg": "",
        "simple": r"^.+$",
        "strings": ["content://com.miui.providers.steps/item"],
        "scan": False,
        "note": "SportUtil：小米机型上微信从这里取步数",
    },
    "sport_detector": {
        "pkg": "",
        "simple": r"^.+$",
        "strings": ["MicroMsg.Sport.MMSportStepDetector"],
        "scan": False,
        "note": "MMSportStepDetector：自己听计步器的那条路",
    },
    "sport_push_detector": {
        "pkg": "",
        "simple": r"^.+$",
        "strings": ["MicroMsg.Sport.PushSportStepDetector"],
        "scan": False,
        "note": "PushSportStepDetector：推送链路听计步器的那条路",
    },
    # ── 解除扫码限制：按"方法里引用的字符串"定位（混淆名靠不住，字符串稳） ──
    "qr_scan": {
        "pkg": "",
        "simple": r"^.+$",
        # key_offline_scan_show_tips 是字段初值里的常量、不在方法体里，扫不到；
        # 日志 tag 这条就够——一个类里同时有 tag + 15/16 参 Activity/String 方法，只此一个。
        "strings": ["MicroMsg.QBarStringHandler"],
        "shape": "activity_string_15_16",
        "scan": False,
        "note": "WeKit 同款：改 QBarString 的 source/a8KeyScene，把相册/长按图片当相机处理",
    },
    # ── 朋友圈广告（特征命中：类名 + 广告常量 + (String) 构造器三重校验） ──
    "sns_ad": {
        "pkg": "com.tencent.mm.plugin.sns.storage",
        "simple": r"^[A-Za-z0-9_$]{2,24}$",
        "fields": "AD_ACTION_TYPE_LANDING_PAGE,AD_ACTION_TYPE_WEB",
        "method": "<init>(java.lang.String):void",
        "seed": ["com.tencent.mm.plugin.sns.storage.ADInfo"],
        "scan": True,
        "note": "WeKit 同款思路：掐掉广告对象的构造，条目就不会进时间线",
    },
    # ── v1.3 自动语音转文字：调微信官方「转文字」组件（低风险官方接口，不碰第三方 ASR） ──
    # 坐标照抄 CrackerCat/wcx 的 AutoSpeechToText（DexKit usingEqStrings → 这里 strings 类级匹配）。
    "transform_component": {
        "pkg": "com.tencent.mm.ui.chatting.component",
        "simple": r"^.+$",
        "strings": ["MicroMsg.TransformComponent", "[onChattingPause]"],
        "scan": False,
        "note": "转文字组件：收到语音自动调它转文字",
    },
    "chat_bind_mvvm": {
        "pkg": "",
        "simple": r"^.+$",
        "strings": ["MicroMsg.MvvmChattingItem", "[onBindView]"],
        "emitMethod": "[onBindView]",
        "scan": False,
        "note": "消息 View 绑定点（识别新进语音消息）",
    },
    "chatting_context": {
        "pkg": "",
        "simple": r"^.+$",
        "strings": ["MicroMsg.ChattingContext", "[notifyDataSetChange]"],
        "scan": False,
        "note": "聊天上下文（取 apiManager 拿组件实例）",
    },
    "chatting_adapter_v3": {
        "pkg": "",
        "simple": r"^.+$",
        "strings": ["MicroMsg.ChattingDataAdapterV3", "[handleMsgChange] isLockNotify:"],
        "scan": False,
        "note": "聊天数据适配器（getItem 取消息）",
    },
    "api_manager": {
        "pkg": "com.tencent.mm.ui.chatting.manager",
        "simple": r"^.+$",
        "strings": ["[get] ", " is not a interface!"],
        "scan": False,
        "note": "聊天组件管理器（getApi 按接口拿组件）",
    },
    # ── v1.3 悬浮聊天底栏：ChatFooter 类名长期未混淆，但走锚点统一适配状态 ──
    "chat_footer": {
        "exact": "com.tencent.mm.pluginsdk.ui.chat.ChatFooter",
        "scan": False,
        "note": "聊天底部输入栏（悬浮卡片化：圆角/阴影/侧边距）",
    },
    # ── v1.3 批量分类标签：走微信官方 netscene（CGI 路径），不伪造上行报文 ──
    "net_scene_queue": {
        "pkg": "",
        "simple": r"^.+$",
        "strings": ["MicroMsg.NetSceneQueue", "forbid in waiting: type=", "forbid in running: type="],
        "scan": False,
        "note": "网络请求队列（addNetSceneToQueue 发 netscene；方法在运行时按签名定位）",
    },
    "mm_kernel": {
        "pkg": "",
        "simple": r"^.+$",
        "strings": ["MicroMsg.MMKernel", "Kernel not null, has initialized."],
        "scan": False,
        "note": "MMKernel（拿 netSceneQueue / coreStorage）",
    },
    "add_contact_label": {
        "pkg": "",
        "simple": r"^.+$",
        "strings": ["/cgi-bin/micromsg-bin/addcontactlabel"],
        "ctor": "<init>(java.lang.String):void",
        "scan": False,
        "note": "新建标签 netscene（构造器收标签名）",
    },
    "modify_contact_label": {
        "pkg": "",
        "simple": r"^.+$",
        "strings": ["/cgi-bin/micromsg-bin/modifycontactlabellist"],
        "ctor": "<init>(java.util.LinkedList):void",
        "scan": False,
        "note": "批量改标签 netscene（构造器收联系人标签列表）",
    },
    "core_storage": {
        "pkg": "",
        "simple": r"^.+$",
        "strings": ["MMKernel.CoreStorage", "CheckData path[%s] blocksize:%s blockcount:%s availcount:%s"],
        "scan": False,
        "note": "核心存储（拿进程内已打开的微信数据库）",
    },
    "sqlite_db_wrapper": {
        "pkg": "",
        "simple": r"^.+$",
        "strings": ["MicroMsg.SqliteDB", "sql is null "],
        "scan": False,
        "note": "SQLite 包装（拿 WCDB SQLiteDatabase 实例）",
    },
}

# 版本名 -> versionCode（写进表里，运行时用来对齐「装的是哪一版」）
EXPECT_CODE = {
    "8.0.79": 3200, "8.0.78": 3180, "8.0.77": 3160,
    "8.0.76": 3140, "8.0.74": 3120, "8.0.72": 3100,
    "8.0.71": 3080, "8.0.70": 3060, "8.0.60": 2900, "8.0.56": 2800,
}

# ── DEX 解析 ────────────────────────────────────────────────────────────────


PRIMITIVE = {
    "V": "void", "Z": "boolean", "B": "byte", "S": "short",
    "C": "char", "I": "int", "J": "long", "F": "float", "D": "double",
}


def desc_to_type(desc):
    """dex 描述符 -> 规则里写的 Java 类型名。"""
    if not desc:
        return ""
    if desc in PRIMITIVE:
        return PRIMITIVE[desc]
    if desc.startswith("["):
        return desc_to_type(desc[1:]) + "[]"
    if desc.startswith("L") and desc.endswith(";"):
        return desc[1:-1].replace("/", ".")
    return desc


def uleb128(buf, off):
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if not (b & 0x80):
            break
    return result, off


class Dex:
    """只解析「类 -> 声明方法/字段」需要的表，不碰字节码。"""

    def __init__(self, data):
        self.d = data
        if data[:4] != b"dex\n":
            raise ValueError("not a dex")
        self.n_string, self.o_string = struct.unpack_from("<II", data, 0x38)
        self.n_type, self.o_type = struct.unpack_from("<II", data, 0x40)
        self.n_proto, self.o_proto = struct.unpack_from("<II", data, 0x48)
        self.n_field, self.o_field = struct.unpack_from("<II", data, 0x50)
        self.n_method, self.o_method = struct.unpack_from("<II", data, 0x58)
        self.n_class, self.o_class = struct.unpack_from("<II", data, 0x60)
        self._strings = {}
        self._types = {}

    def string(self, i):
        v = self._strings.get(i)
        if v is not None:
            return v
        off = struct.unpack_from("<I", self.d, self.o_string + i * 4)[0]
        _, off = uleb128(self.d, off)
        end = self.d.index(b"\x00", off)
        v = self.d[off:end].decode("utf-8", "replace")
        self._strings[i] = v
        return v

    def type_desc(self, i):
        v = self._types.get(i)
        if v is None:
            idx = struct.unpack_from("<I", self.d, self.o_type + i * 4)[0]
            v = self.string(idx)
            self._types[i] = v
        return v

    def proto(self, i):
        shorty, ret, params_off = struct.unpack_from("<III", self.d, self.o_proto + i * 12)
        ret = self.type_desc(ret)
        params = []
        if params_off:
            size = struct.unpack_from("<I", self.d, params_off)[0]
            for k in range(size):
                params.append(self.type_desc(
                    struct.unpack_from("<H", self.d, params_off + 4 + k * 2)[0]))
        # 规则里写的是 Java 名字（java.util.Map / int / java.lang.Object[]），
        # 这里把 dex 的描述符（Ljava/util/Map; / I / [Ljava/lang/Object;）翻译过去
        return desc_to_type(ret), [desc_to_type(p) for p in params]

    def method_id(self, i):
        cls, proto, name = struct.unpack_from("<HHI", self.d, self.o_method + i * 8)
        return self.type_desc(cls), self.string(name), proto

    def field_id(self, i):
        cls, typ, name = struct.unpack_from("<HHI", self.d, self.o_field + i * 8)
        return self.type_desc(cls), self.string(name)

    def method_strings(self, code_off):
        """
        粗扫一个方法的指令流，收集 const-string / const-string/jumbo 引用的字符串。
        不做完整的指令长度解码——只按 2 字节单元找 0x1a/0x1b 这两个操作码，会有误报，
        但我们是"要同时命中两个特定字符串"才认，误报基本不可能同时凑齐。
        """
        out = set()
        try:
            if code_off <= 0 or code_off + 16 > len(self.d):
                return out
            insns_size = struct.unpack_from("<I", self.d, code_off + 12)[0]
            base = code_off + 16
            for i in range(insns_size):
                off = base + i * 2
                if off + 6 > len(self.d):
                    break
                # DEX 的 const-string 第一个 code unit = (寄存器 << 8) | opcode，
                # 所以必须只看低字节；直接和 0x1a 比会永远不匹配（这就是之前"字符串不存在"的根因）。
                op = struct.unpack_from("<H", self.d, off)[0] & 0xFF
                if op == 0x1A:
                    idx = struct.unpack_from("<H", self.d, off + 2)[0]
                    if idx < self.n_string:
                        out.add(self.string(idx))
                elif op == 0x1B:
                    idx = struct.unpack_from("<I", self.d, off + 2)[0]
                    if idx < self.n_string:
                        out.add(self.string(idx))
        except Exception:
            pass
        return out

    def classes(self):
        """生成 (类名, 父类名, [(方法名, 返回, [参数], 引用到的字符串)], [字段名])。"""
        for k in range(self.n_class):
            base = self.o_class + k * 32
            class_idx, _acc, super_idx, _if, _src, _ann, data_off, _sv = struct.unpack_from(
                "<IIIIIIII", self.d, base)
            cls = self.type_desc(class_idx)
            sup = self.type_desc(super_idx) if super_idx != 0xFFFFFFFF else ""
            if data_off == 0:
                yield self.descriptor_to_name(cls), self.descriptor_to_name(sup), [], []
                continue
            # 微信对少数类做了加固（class_data 里是垃圾数据，专门用来搞崩反编译器）：
            # 解不开就跳过这个类，不影响其它类。
            try:
                off = data_off
                vals = []
                for _ in range(4):
                    v, off = uleb128(self.d, off)
                    vals.append(v)
                sf, inf, dm, vm = vals
                if sf + inf + dm + vm > 4000:
                    raise ValueError("absurd class_data sizes")
                fields = []
                idx = 0
                for _ in range(sf + inf):
                    diff, off = uleb128(self.d, off)
                    _, off = uleb128(self.d, off)          # access flags
                    idx = self.step(idx, diff, self.n_field)
                    fields.append(self.field_id(idx)[1])
                methods = []
                idx = 0
                for _ in range(dm + vm):
                    diff, off = uleb128(self.d, off)
                    _, off = uleb128(self.d, off)          # access flags
                    code_off, off = uleb128(self.d, off)   # code_off
                    idx = self.step(idx, diff, self.n_method)
                    _c, name, proto = self.method_id(idx)
                    ret, params = self.proto(proto)
                    methods.append((name, ret, params, self.method_strings(code_off)))
            except Exception:
                yield self.descriptor_to_name(cls), self.descriptor_to_name(sup), [], []
                continue
            yield self.descriptor_to_name(cls), self.descriptor_to_name(sup), methods, fields

    @staticmethod
    def step(prev, diff, limit):
        """
        差分推进一格。

        规范说 method_idx_diff/field_idx_diff 是「相对上一条的差值」，但微信的 dex
        实测会出现「每条列表的第一条直接写绝对索引」的写法（差值累加会越界）。
        两种写法都接受：越界就按绝对值解释，仍然越界才算坏数据。
        """
        nxt = prev + diff
        if nxt >= limit:
            if diff < limit:
                nxt = diff
            else:
                raise ValueError("index out of range")
        return nxt

    @staticmethod
    def descriptor_to_name(desc):
        if not desc:
            return ""
        if desc.startswith("L") and desc.endswith(";"):
            return desc[1:-1].replace("/", ".")
        return desc


# ── 指纹匹配 ────────────────────────────────────────────────────────────────


def type_match(expected, actual):
    if expected == "*":
        return True
    return expected == actual or expected == actual.split(".")[-1]


def sig_match(expected, name, ret, params):
    lp = expected.index("(")
    rp = expected.index(")")
    colon = expected.index(":", rp)
    en = expected[:lp]
    ep = [] if not expected[lp + 1:rp] else expected[lp + 1:rp].split(",")
    er = expected[colon + 1:]
    if en != "*" and en != name:
        return False
    if len(ep) != len(params):
        return False
    for a, b in zip(ep, params):
        if not type_match(a.strip(), b):
            return False
    return type_match(er.strip(), ret)


class ClassIndex:
    def __init__(self):
        self.classes = {}       # name -> (super, has_sig(rule) cache, fields)

    def add(self, name, sup, methods, fields):
        if name in self.classes:
            return
        self.classes[name] = (sup, methods, fields)

    def fields_deep(self, name, field):
        seen = 0
        while name and name in self.classes and seen < 12:
            sup, _m, fields = self.classes[name]
            if field in fields:
                return True
            name = sup
            seen += 1
        return False


def matches(index, name, rule):
    if name not in index.classes:
        return False
    _sup, methods, _fields = index.classes[name]
    m = rule.get("method", "")
    if m and not any(sig_match(m, n, r, p) for n, r, p, _s in methods):
        return False
    m2 = rule.get("method2", "")
    if m2 and not any(sig_match(m2, n, r, p) for n, r, p, _s in methods):
        return False

    # 按"类里引用了哪些字符串"定位（混淆名没法靠名字，字符串反而稳定）
    # 注意：dexkit 的语义是"这个方法所在的类用到这些字符串"，字符串和方法体不一定在同一个方法里，
    # 所以这里当成**类级**条件；参数形状另算一条。
    wanted = rule.get("strings", [])
    if wanted:
        # dexkit 的 usingEqStrings 是**包含**语义：写 "msg is null!" 能命中 "msg is null! pos:%s"。
        # 之前这里按精确相等找，导致"字符串不存在"的误判 —— 白干四轮。
        # ★ 类级语义：把类里所有方法引用的字符串做并集，再要求包含全部 wanted 字符串。
        #   之前是「单个方法包含全部」—— 字符串分散在不同方法（如日志 tag 与消息）时会误判"未命中"。
        all_strs = set()
        for _n, _r, _p, strs in methods:
            all_strs.update(strs)
        for w in wanted:
            if not any(w in s for s in all_strs):
                return False

    if rule.get("shape") == "activity_string_15_16":
        ok = False
        for _n, _r, p, _s in methods:
            if len(p) in (15, 16) and p[0] == "android.app.Activity" \
                    and p[1] == "java.lang.String":
                ok = True
                break
        if not ok:
            return False

    for f in [x.strip() for x in rule.get("fields", "").split(",") if x.strip()]:
        if not index.fields_deep(name, f):
            return False
    return True


def candidate_ok(name, rule):
    pkg = rule.get("pkg", "")
    if pkg and not name.startswith(pkg + "."):
        return False
    simple = rule.get("simple", "")
    if simple and not re.match(simple, name.split(".")[-1]):
        return False
    if rule.get("strings"):
        return True                     # 靠字符串特征定位的槽位不限包名/类名
    if not pkg:
        # 宽松槽位：只接受混淆风格的短名（xx/yy）
        parts = name.split(".")
        if len(parts) != 2:
            return False
        if not (2 <= len(parts[0]) <= 4) or not (1 <= len(parts[1]) <= 4):
            return False
    return True


def scan_apk(apk, verbose=False):
    """返回 (index, dex_count, class_count)"""
    index = ClassIndex()
    dex_count = 0
    class_count = 0
    with zipfile.ZipFile(apk) as z:
        names = sorted(n for n in z.namelist()
                       if n.startswith("classes") and n.endswith(".dex"))
        for n in names:
            data = z.read(n)
            dex_count += 1
            try:
                d = Dex(data)
            except Exception as e:
                if verbose:
                    print("  skip", n, e)
                continue
            for name, sup, methods, fields in d.classes():
                class_count += 1
                index.add(name, sup, methods, fields)
            del data
    return index, dex_count, class_count


def resolve(index, rule):
    """
    三级：exact -> seed（其它版本验证过的名字，通常相邻版本不变） -> 规则搜索。
    返回 (命中列表, 来源)。
    """
    # 1) 精确名
    ex = rule.get("exact", "")
    if ex:
        if ex in index.classes and matches(index, ex, rule):
            return [ex], "exact"
        return [], "exact-miss"

    # 2) 种子：任何一个版本验证过的类名，先在本版本里试一遍
    for seed in rule.get("seed", []):
        if seed in index.classes and matches(index, seed, rule):
            return [seed], "seed"

    # 3) 规则搜索
    hits = []
    for name in index.classes:
        if not candidate_ok(name, rule):
            continue
        if matches(index, name, rule):
            hits.append(name)
    hits.sort(key=lambda s: (len(s), s))
    if rule.get("strings"):
        # 靠方法内字符串常量定位的是"特征命中"，可信，不算猜测
        if rule.get("emitMethod") and hits:
            cls = hits[0]
            _sup, methods, _fields = index.classes[cls]
            wanted = rule.get("strings", [])
            # 输出「类#方法」时，方法 = 类里同时引用全部 wanted 字符串的那个方法
            # （wcx 的 dexMethod 定位就是这个语义：addNetSceneToQueue 里同时有 tag 和两条日志）
            for n, r, p, strs in methods:
                if all(any(w in s for s in strs) for w in wanted):
                    return ["%s#%s" % (cls, n)], "strings"
            return hits[:1], "strings"
        return hits[:1], "strings"
    if rule.get("allHits"):
        # 实现者天然分散在多个包/多个类里，全都要：少挂一个就少一个入口。
        limit = rule.get("maxHits", 60)
        out = sorted(hits)[:limit]
        marker = rule.get("emitMethod")          # 需要"类#方法"输出时，把方法名也带上
        if marker:
            named = []
            for cls in out:
                _sup, methods, _fields = index.classes[cls]
                for n, r, p, strs in methods:
                    if any(marker in s for s in strs):
                        named.append("%s#%s" % (cls, n))
                        break
            out = named
        return out, "scan"
    if rule.get("multi"):
        # 同一批库对象通常在同一个混淆包里：取「同包成员最多」的那一组，
        # 避免把基类（另一个包）也挂上，重复拦一次。
        groups = {}
        for h in hits:
            groups.setdefault(h.rsplit(".", 1)[0], []).append(h)
        best = max(groups.values(), key=len) if groups else []
        limit = rule.get("maxHits", 4)
        return sorted(best)[:limit], "scan"
    return hits[:1], "scan"


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    proj = sys.argv[1]
    spec = sys.argv[2:]

    out = {
        "schema": 1,
        "generated": "tools/gen-anchors.py",
        "slots": SLOTS,
        "versions": {},
    }

    for item in spec:
        ver, _, apk = item.partition("=")
        if not os.path.exists(apk):
            print("[skip] %s -> %s 不存在" % (ver, apk))
            continue
        print("[scan] %s  %s" % (ver, apk))
        index, dex_count, class_count = scan_apk(apk)
        print("       dex=%d classes=%d" % (dex_count, class_count))
        slots = {}
        for slot, rule in SLOTS.items():
            hits, how = resolve(index, rule)
            # 「猜」出来的名字（既不是种子也不是规则唯一命中）不进表：
            # 表里只放可信结果，剩下的交给设备上的运行时扫描。挂错类比少一个功能更糟。
            if how == "scan" and not rule.get("scan") and not rule.get("multi") \
                    and not rule.get("strings") and len(hits) != 1:
                # 唯一命中（method 指纹只匹配到一个类）是强证据 → 入表；
                # 多候选/零候选才丢弃（挂错类比少一个功能更糟）。
                print("       ? %-14s 仅猜测 %d 个候选，不入表: %s"
                      % (slot, len(hits), ", ".join(hits[:3])))
                hits = []
            if len(hits) > 1:
                print("       ~ %-14s 多命中(%s) %s" % (slot, how, ", ".join(hits)))
            if hits:
                slots[slot] = hits if len(hits) > 1 else hits[0]
                print("       + %-14s %-8s %s" % (slot, how, ", ".join(hits)))
            else:
                print("       - %-14s 未命中(%s)" % (slot, how))
        out["versions"][ver] = {"code": EXPECT_CODE.get(ver, 0), "slots": slots}

    dest = os.path.join(proj, "assets", "anchors.json")
    with open(dest, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=1, sort_keys=True)
    print("written ->", dest, os.path.getsize(dest), "bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
