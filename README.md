<div align="center">

# 乐信 · lexin

**微信界面美化 + 本地功能增强** · Xposed / LSPosed 模块

![version](https://img.shields.io/badge/version-1.7.22-4A6CF7?style=flat-square)
![platform](https://img.shields.io/badge/Android-9%20~%2016-3DDC84?style=flat-square&logo=android&logoColor=white)
![wechat](https://img.shields.io/badge/微信-8.0.72%20~%208.0.80-07C160?style=flat-square&logo=wechat&logoColor=white)
![size](https://img.shields.io/badge/APK-1.9%20MB-8A8F9A?style=flat-square)
![license](https://img.shields.io/badge/license-MIT-blue?style=flat-square)

包名 `io.github.lex0m.lexin` · 作者 lex · 纯 Java · 无 Gradle

[下载](../../releases) · [功能一览](#功能一览) · [安装](#安装) · [兼容性](#兼容性) · [设计取舍](#设计取舍)

</div>

---

## 这是什么

一个跑在微信进程里的 LSPosed 模块，做两件事：

- **界面**：把微信换成你自己的样子 —— 全屏背景、卡片化列表、悬浮底栏、毛玻璃。
- **本地功能**：防撤回、朋友圈保留、去广告、自动原图、步数修改、一键转发……
  全部**在设备上完成**，不上传、不伪造、不改一条上行报文。

一个 670 KB 的 APK，没有 Gradle 工程、没有第三方依赖、没有后台服务。

---

## 功能一览

### 界面

| 功能 | 说明 |
| --- | --- |
| 全屏背景 | 任意图片，透明度可调，五种缩放（填充 / 适应 / 拉伸 / 居中 / 自定义裁剪） |
| 列表卡片化 | 左右内缩、圆角、上下间隔、卡片高度全可调 |
| 聊天条独立成卡 | 单独配图、裁剪、透明度、模糊 |
| 悬浮底栏 | 主页 TabBar 与聊天输入栏改成悬浮圆角卡片 |
| 毛玻璃 | Android 12+ 的 GPU 模糊（低版本自动降级为纯色） |
| 顶部跟随 | 状态栏、聊天标题栏跟随背景或微信深浅模式 |
| 每日一言 | 面板首屏一句诗，每次打开换一句 |

### 聊天

| 功能 | 说明 |
| --- | --- |
| 消息防撤回 | 对方撤回的消息原样留在列表，气泡压暗 + 正下方贴方向标签（不压住原文，切后台回来不跑位） |
| 朋友圈保留 | 对方删掉的评论与动态，本机留一份副本 |
| 去广告 | 开屏、小程序开屏位、朋友圈信息流 |
| 自动查看原图 | 浏览图片时自动切原图，点都不用点 |
| 放宽本地限制 | 表情包数量、消息多选、状态字数上限 |
| 骰子 / 猜拳自选 | 长按表情面板可指定点数或出拳，也可以保持随机 |

### 转发与导出

| 功能 | 说明 |
| --- | --- |
| 长按菜单加项 | 不改微信任何判定，只在长按菜单里多一格「转发（乐信）」 |
| 官方转发 | 普通消息直接触发微信自己的转发，走官方链路 |
| 受限消息导出 | 语音、图片等不能转发的消息，导出到「下载/乐信导出」 |
| 语音转 MP3 | 微信的 SILK 语音解码后转 MP3（解码在模块进程做，微信进程零 native） |
| 朋友圈转发 | 文字 / 图片 / 视频，支持一键全选 |

### 工具

| 功能 | 说明 |
| --- | --- |
| 运动步数 | 三种模式：直接增加 / 每小时自动增加 / 倍率放大，都受上限约束，不会把数字钉死 |
| 解除扫码限制 | 相册选图、长按图片识别二维码按「相机」场景处理 |
| 自动语音转文字 | 收到语音自动调微信官方的转文字（不接第三方识别） |
| 批量分类标签 | 给多个好友批量打标签，走微信官方 netscene，服务端与你手动打标签完全一致 |
| 隐蔽入口 | 面板入口藏在「关怀模式」页，常规界面零痕迹 |

---

## 安装

1. 装 [`lexin-1.7.22.apk`](../../releases)（或本仓库根目录的 APK）
2. LSPosed 里勾选「乐信」，**作用域只勾微信**
3. 强行停止微信，重新打开

> 没 root 就走免 root 框架，二选一（同一条技术路线，都是 Vector 核）：
> **[LSPatch（JingMatrix fork）](https://github.com/JingMatrix/LSPatch/releases)** ·
> **[NPatch（7723mod fork）](https://github.com/7723mod/NPatch/releases)**。
> 代价是要重装微信（签名变了），步骤与能力边界见 [免 root 使用指南](免root使用指南.md)。

## 打开面板

1. **桌面「乐信」图标**，或 **LSPosed → 模块 → 乐信 → 右下角 ▶**
   → 直接打开模块自己的设置页（全部开关一屏）；同时会给微信发一条通知，切回微信就能看到悬浮面板
2. 微信里：**我 → 设置 → 更多 → 关怀模式 → 长按「开启」**（隐藏入口）

> 步数、批量标签这类操作必须在微信进程里做，所以它们只在悬浮面板里。

---

## 兼容性

### 框架

| 框架 | 状态 | 说明 |
| --- | --- | --- |
| **LSPosed / Vector**（root） | 主力，全功能 | 模块独立安装、独立进程；语音转 MP3、独立设置页、开机自启都可用 |
| **LSPatch**（免 root · [下载](https://github.com/JingMatrix/LSPatch/releases)） | 已适配 | 模块 dex 嵌进微信补丁包；素材改从宿主 assets 读，主题图与锚点表都能拿到 |
| **NPatch**（免 root · [下载](https://github.com/7723mod/NPatch/releases)） | 已适配 | 与 LSPatch 同路线（Vector 核），走同一套兜底 |
| 双开容器 / 虚拟机 | 不建议 | 容器特征明显，主号别用 |

免 root 下没有独立模块进程：**语音转 MP3、独立设置页、开机自启不可用**，其余功能照常；
面板诊断行会写明当前框架与素材来源。详细步骤见 [免 root 使用指南](免root使用指南.md)。

### Android

| 版本 | 状态 |
| --- | --- |
| Android 15 / 16 | 实测主力环境（edge-to-edge、强制深色、16 KB 页对齐都已处理） |
| Android 12 ~ 14 | 支持 |
| Android 9 ~ 11 | 理论可用（minSdk 28）；音频转码库只带 arm64，32 位机型只能用到无 native 的功能 |

### 微信

| 版本 | 状态 |
| --- | --- |
| **8.0.80** | 全部适配，35/35 槽位命中（开屏广告判定在 8.0.80 改名为 `N2`，改按方法内特征定位） |
| 8.0.78 | 全部适配，本机实测 9/9 |
| 8.0.79 / 8.0.77 | 全部适配（8.0.79 的撤回标签与开屏广告锚点在 1.3.9 修正） |
| 8.0.76 / 8.0.74 | 全部适配；骰子结果改写降级（选择框仍在） |
| 8.0.72 | 防撤回 / 表情 / 原图 / 扫码 / 入口 全通 |

表里没有的版本照样能用：模块会自己在设备上扫微信 dex，按「类名 + 方法内字符串 +
方法签名」三层特征找落点，结果缓存到本机，下次启动零成本。找不到的功能安静跳过，
不崩、也不影响微信本身。

**面板显示的是三态，不是布尔**：`未适配` / `已挂载未命中` / `已命中 N 次`。
中间那一档是重点 —— 微信 8.0.80 用 Tinker 热修，真正执行的类是补丁 dex 里那份，
用框架给的 ClassLoader 解析出来的"死类"照样能挂 hook、照样算成功，但回调永远不会触发。
只有「命中数在涨」才算真的在工作。

给新版本补一张表（可选，让首次启动直接命中）：

```sh
python3 tools/gen-anchors.py . 8.0.80=/path/weixin8080.apk   # 约 25 秒/版本，直接解析 dex
sh build.sh
```

---

## 设计取舍

这几条决定了代码为什么长这样：

- **不改微信的判定，只借它的链路。** 转发用微信自己的转发、标签用微信自己的 netscene、
  语音发送用微信自己的发送入口。不伪造消息 xml，不改上行报文。
- **每个功能独立失败。** 一个功能挂不上就静默跳过、面板里标「未适配」，绝不连累其它功能，
  也不把异常抛给宿主。
- **降级要干净。** 锚点四级回退：版本表 → 邻近版本 → 本机学习缓存 → 运行时 dex 扫描，
  每级都做指纹校验 —— 宁可少一个功能，也不挂错类。
- **微信进程不加载任何 native 库。** 解码、转码这类活放在模块自己的进程里，微信侧只递字节。
- **发布版零日志。** `Log.d` 编进空实现；要排查时在面板里显式打开诊断日志，重启微信才生效。
- **不在微信目录里留新文件。** 配置写进微信自己的 `shared_prefs`，键名统一 `ap_+hashCode`。

---

## 构建

不用 Gradle，JDK 21 + aapt2 + R8 + apksigner：

```sh
sh build.sh                      # 正式包 → dist/lexin-<版本>.apk
sh build.sh --debug              # 诊断包（日志开关打开）
sh tools/build-audio.sh --test   # 重编音频库并自测
```

版本号唯一来源是 `AndroidManifest.xml`；`build.sh` 会自己探测 Termux 工具链，
打完自动跑 `tools/verify_apk.py`（组件是否被 R8 裁掉、`.so` 是否 STORED、
`resources.arsc` 是否未压缩、`xposed_init`、JNI 符号、buildId 指纹），任何一项不过就失败。

---

## 更新日志

### v1.7.22（2026-11-17）— 免 root 框架自审：修掉三处「真机不生效」

这一版不加功能，只让 LSPatch / NPatch 的适配真的能跑：

- `DexPathList.Element.path` 是 `File` 不是 `String`，旧代码的 `instanceof String` 在真机上必然跳过 —— 整段兜底是死代码（已用本机 framework dex 实测字段类型）
- 集成模式没有独立模块 APK（模块在宿主 `assets/lspatch|npatch/modules/`），新增读宿主 assets 的探路，主题图与加密锚点表在免 root 下也能读
- 「同 loader ⇒ 内嵌」判据在 Vector 下恒假（模块用 `VectorModuleClassLoader` + `ByteBufferDexClassLoader`），免 root 会被误报成 LSPosed 且缓存错值 —— 改成注入产物优先、判不出来不缓存
- 免 root 下语音导出先判模块组件是否可达，不再假装成功；`Application.attach` 不触发时用 `onCreate` 幂等兜底；面板诊断行写清资源来源与免 root 能力边界

> 这次提交同时带入 v1.7.0 ~ v1.7.21 的全部改动（此前只在本地，未推送）。
> 真机打补丁（要卸载正版微信）仍未验证，面板 `os=` 与主题图的实际显示以机主环境为准。
> 逐条清单见 [`更新说明-v1.7.22.md`](更新说明-v1.7.22.md)。

### v1.6.2（2026-11-16）— 全盘复审：自检复活 + 背景图通道 + 构建可复现

这一版没有新功能，只修**"装上了、面板也说好了，但就是不工作"**。

**两个阻断**
- **自检机制自己就是坏的**：`SelfCheck` 用 `getDeclaredMethod("pulse")` 找方法，
  但 R8 把 `pulse` 改名成了 `le`（mapping 里 `void pulse() -> le`）⇒ 发布包里必然抛
  `NoSuchMethodException`。真机证据：`status.txt` 里 `hook 活性 armed=1 / hits=0`，
  37 分钟一次没涨（自检 15 秒一次，该有 148 次）。改成**按签名找**（`static void`、零参数）
  —— R8 改不了签名。
- **「重新自动适配」从来不扫**：`Anchors.forget()` 的 `AutoScan.reset()` 把待扫槽位清空，
  紧接着 `schedule()` 看到空表直接 return，一次扫描都不发生，toast 却说"已开始"。

**高危**
- `ForwardPlus.hookMenuSelect` 不校验返回类型就 `setResult(null)`：挑中 `(MenuItem)->boolean`
  的重载会拆箱 `null` → **崩微信**（`getDeclaredMethods` 顺序不保证）
- 两处 `getOutline` 缺 try/catch：框架在渲染线程调，首次布局前 0×0，`setRoundRect` 抛异常
  直接漏进微信绘制流程
- `applyCardHeight` 的守卫是**空 if 块**：`WRAP_CONTENT` 被改写成绝对像素，多行文本被截断
- **背景图三处全走 `content://`**，而微信 targetSdk 34 且没有 `QUERY_ALL_PACKAGES`
  （已用 `dumpsys package` 核实），解析不到 authority ⇒ 面板选图必失败、背景画不出来。
  改成写/读都走微信自己 `files/` 的点号文件
- 老背景图迁移失败也照 `delete()` ⇒ 用户的图直接消失
- `ThemePack` 的 `FileReader` 泄漏（最终 "Too many open files"）、`image()` 复用同一个
  `Drawable` 实例（bounds 互抢、错图）

**构建可复现**
`build.sh` 以前每次用随机锚点密钥 ⇒ 同一份源码连编三次三个哈希，`VERSIONS.md` 记的
哈希永远对不上。改成 `sha256("lexin-anchor:" + 项目私盐 + 源码指纹)` 派生，盐在
`keystore/anchor.salt`（不入库、不进包）。**源码不变 ⇒ 包完全一致**（已实测连跑两次相同）。
详见 [`更新说明-v1.6.2.md`](更新说明-v1.6.2.md)。

### v1.5.0（2026-11-10）— 适配微信 8.0.80 + 环境隐蔽

**兼容**
- 锚点表补 8.0.80（3220），35 个槽位全中；8.0.72 ~ 8.0.79 原表保留
- 小程序开屏广告判定在 8.0.80 从 `K2` 改名为 `N2`、类从 `f7` 换到 `g7`。槽位改成按方法内
  日志特征（`checkCanShowAd`）定位，表里直接记「类#方法」，`AdBlock` 优先用表里的方法名 ——
  下次微信再改名只要重跑 `tools/gen-anchors.py`，不用改 Java

**隐蔽性**
- 拦微信自带的三道探针：① 栈帧自检（TinkerCrashProtect 遍历栈找 `XposedBridge`）
  ② Matrix `checkHook` 对 `libwechatcrash` 等 so 的 native 自查 ③ 「已装应用列表」里的包名自证
- `Stealth` 从启动后 1.2 秒的延后队列提到最早期 —— 前两道在启动阶段就可能跑过一次，晚装等于没装
- 隐蔽开关默认开（`K_STEALTH`），面板「环境隐蔽」一行报各探针挂载数；单道装不上不影响另外两道

### v1.4.0（2026-10-28）— 两轮兼容性优化

**功能兼容**
- 桌面图标 / LSPosed ▶ 直接进设置页；「界面美化」页补返回出口
  （之前进去出不来，看起来像"模块只有界面美化"）
- 设置页补齐到与微信面板同一套：转发增强、语音·底栏·标签、诊断日志、面板入口
- **设置页的开关真的生效了**：原来模块进程写自己的 `svc.xml`、微信进程写微信的 prefs，
  两边各读自己那份 ⇒ 设置页改的开关微信读不到。现在改动会经 provider 同步进微信进程（按时间戳水位合并）
- 外观页选的背景图现在生效（之前存在模块目录、微信读自己的目录）
- 转发增强的触摸热路径：先判抬手再查父级文字

**设备 / 系统兼容**
- targetSdk 35 在 Android 15 强制 edge-to-edge ⇒ 底部补系统栏高度，三键导航不再挡内容
- 深色模式 force dark 会反转浅色界面 ⇒ 设置页 / 外观页 / 微信面板三处全关
- 微信 targetSdk 34 且无 `MANAGE_EXTERNAL_STORAGE` ⇒ Android 11+ 写不了 `/sdcard/Download`
  （语音/媒体导出会静默失败）。导出改成三层兜底 + MediaStore
- 微信的 `:xweb_*` / `:sandboxed_process` 沙箱进程不再装全套 hook；面板广播、状态推送、dex 扫描只在主进程
- 版本号改由构建脚本从 `AndroidManifest.xml` 生成
- `BgProvider` 的写操作校验调用方

**未修**（需要真机回归）：朋友圈广告在构造阶段拦截的方式、锚点空结果缓存、
转发增强的栈学习范围、32 位机型缺音频库。

### v1.3.x

- **1.3.9** 修微信 8.0.79 两个锚点错种子（`chat_binder` / `ad_open`）
- **1.3.3** 安卓 17 兼容（运行时字符串特征扫描）、频闪 / 拖影修复
- **1.3.1** 主页仿 TG 悬浮底栏、修复聊天标题栏黑边、朋友圈一键全部转发
- **1.3.0** 自动语音转文字、悬浮聊天底栏、批量分类标签

### v1.2.x

- **1.2.3** 朋友圈视频转发、自绘勾选 UI
- **1.2.2** 长按菜单收敛为一条路径 + 朋友圈转发
- **1.2.1** 语音转 MP3（SILK 解码 + LAME 编码，真机逐字节验证）

---

## 致谢

实现思路参考了不少开源项目，代码是 Java 独立写的，没有直接复制：

- [MDWechat](https://github.com/Blankeer/MDWechat) —— 按视图树定位页面层级、ActionBar 背景接管、九宫格气泡解析
- [WeKit](https://github.com/ujhhgtg/WeKit) —— 朋友圈广告拦截与扫码限制解除的做法、微信日志 tag 作特征定位
- [XposedWechatHelper](https://github.com/wuxiaosu/XposedWechatHelper) —— 传感器层改步数的思路
- [WechatSpellbook](https://github.com/Gh0u1L5/WechatSpellbook) · [WechatMagician](https://github.com/Gh0u1L5/WechatMagician) —— 早期防撤回 / 功能注入的参考
- [DJB-Developer/wechat-android-history-versions](https://github.com/DJB-Developer/wechat-android-history-versions) —— 微信官方历史版本直链（生成多版本适配表用）
- [LSPosed](https://github.com/LSPosed/LSPosed) —— 运行框架

哪位作者觉得引用不当，说一声我就改。

## 免责声明

仅供个人学习与技术研究使用。
**禁止在国内网络平台传播、售卖或代装**（网盘、论坛、公众号、应用市场、短视频平台、群文件分享等一律禁止）。
使用本模块产生的一切后果由使用者自行承担。

<div align="center">

交流群 **186737276**

</div>
