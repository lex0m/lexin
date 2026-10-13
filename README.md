<div align="center">

# 乐信 · lexin

**微信界面美化 + 本地功能增强**（Xposed / LSPosed）

![version](https://img.shields.io/badge/version-1.1-4A6CF7?style=flat-square)
![platform](https://img.shields.io/badge/Android-12%20~%2015-3DDC84?style=flat-square&logo=android&logoColor=white)
![wechat](https://img.shields.io/badge/微信-8.0.72%20~%208.0.79-07C160?style=flat-square&logo=wechat&logoColor=white)
![license](https://img.shields.io/badge/license-MIT-blue?style=flat-square)

包名 `io.github.lex0m.lexin` · 作者 lex · 纯 Java（无 Gradle）

[下载](../../releases) · [支持版本](#支持微信版本) · [怎么打开面板](#打开设置面板)

</div>

---

## 功能

### 消息防撤回

对方撤回的消息**原样留在聊天里**，不会被换成「xxx撤回了一条消息」。

做法是抹掉微信那条撤回指令，不发请求、不改上行报文。被撤回的气泡会压暗，
**正下方**贴一个小标签标出方向 —— 不压住原消息，切后台回来也不跑位。
自己撤回不贴标签（微信本来就有提示，再叠一个是两个提示打架）。

### 运动步数修改

三种模式，互不干扰：

| 模式 | 效果 |
| --- | --- |
| **直接增加** | 把 N 步直接加上去，立刻生效；加完就撒手，真实步数照常自然累加 |
| **自动增加** | 每小时自动加 N 步，随时间平滑涨，像真在走路 |
| **倍率** | 按真实步数放大，1.2 ~ 5 倍 |

都带**额外步数上限**（默认 20000，最高可设 80000），上限只约束"额外加的那部分"——真实步数再高也照常累加，不会把数字钉死。

走的是外部通道，不碰微信的数据接口：优先改「微信自己算出来的今天步数」（各家手机步数来源
不一样，微信内部已经适配过，所以换品牌换手机都不用改规则），挂不上再退回传感器层。
只在主进程生效，不会重复叠加。

### 去广告

| 位置 | 处理 |
| --- | --- |
| 朋友圈信息流 | 掐掉广告对象的构造，条目根本进不了时间线 |
| 小程序开屏 | 广告判定一律答「否」 |
| 开屏广告 | 同上 |

只拦本地决策点，不改任何网络请求。三处归**同一个开关**管。

### 其它

| 功能 | 说明 |
| --- | --- |
| 朋友圈保留 | 对方删掉的评论和动态，本机留副本 |
| 解除扫码限制 | 相册选图、长按图片识别二维码按「相机」场景处理 |
| 自动查看原图 | 浏览图片时自动切原图 |
| 放宽本地限制 | 表情包数量、消息多选、状态字数上限 |
| 骰子 / 猜拳自选 | 长按表情面板的骰子或猜拳，弹框选点数，也可以保持随机 |
| 界面美化 | 全屏背景（透明度/五种缩放/自定义裁剪）、列表卡片化、聊天条独立成卡、毛玻璃、状态栏与顶栏跟随背景 |
| 面板顶部 | 每日一言（每次打开换一句）+ 当前登录微信的头像昵称 |
| 首次打开 | 强制阅读两秒免责声明（含禁止国内传播条款），读过不再出现 |

---

## 安装

1. 装 [`dist/lexin.apk`](../../releases)
2. LSPosed 里勾选「乐信」，作用域勾选微信
3. 强行停止微信，重新打开

## 打开设置面板

三种都行，看你顺手：

1. **LSPosed → 模块 → 乐信 → 右下角 ▶ 按钮**
2. 桌面「乐信」图标
3. 微信里：**我 → 设置 → 更多 → 关怀模式 → 长按「开启」**（隐藏入口，常规界面零痕迹）

## 免 root 使用

没 root 也能用 —— 走 [LSPatch](https://github.com/JingMatrix/LSPatch)（给微信 APK 打补丁把框架嵌进去，不建容器、不改系统）：

1. 装 LSPatch 管理器 → ＋ → **本地模式** → 选微信 → 修补 → 安装（签名冲突，要先卸载原版微信，**记得先备份聊天记录**）
2. LSPatch 里给刚修补的微信勾选「乐信」
3. 强行停止微信再打开

完整步骤、注意事项，以及为什么**不建议**用 VirtualApp 类双开容器跑微信（检测风险），见 [免 root 使用指南](免root使用指南.md)。

## 支持微信版本

| 微信版本 | 适配情况 |
| --- | --- |
| **8.0.78** | 全部适配，本机实测 9/9 功能生效 |
| 8.0.79 / 8.0.77 | 全部适配 |
| 8.0.76 / 8.0.74 | 全部适配；骰子结果改写降级（选择框仍在） |
| 8.0.72 | 防撤回 / 表情 / 原图 / 扫码 / 入口 全通 |

表里没有的版本也能用：模块会自己在设备上扫微信 dex，按「类名 + 方法内字符串 + 方法签名」
三层特征找落点，结果缓存到本机，下次启动零成本。找不到的功能安静跳过，不崩、也不影响微信本身。

给新版本补一张表（可选，让首次启动就命中）：

```sh
python3 tools/gen-anchors.py . 8.0.80=/path/weixin8080.apk   # 约 25 秒/版本，直接解析 dex
sh build.sh
```

## 构建

不用 Gradle，JDK 21 + aapt2 + R8 + apksigner 直接出包：

```sh
PREFIX=<jdk/aapt2/apksigner 前缀> sh build.sh    # 产物 dist/lexin.apk
```

签名密钥放 `keystore/`（已在 .gitignore，不会入库）。

## 隐蔽性

发布版不写任何日志；不在微信目录新建文件（配置写进微信自己的 `shared_prefs`，键名做了混淆）；
挂在微信视图树上的只有框架类；不改任何上行报文。

---

## 致谢

这个模块的实现思路参考了不少开源项目，代码是 Java 独立写的，没有直接复制：

- [MDWechat](https://github.com/Blankeer/MDWechat) —— 按视图树定位页面层级、ActionBar 背景接管、九宫格气泡解析
- [WeKit](https://github.com/ujhhgtg/WeKit) —— 朋友圈广告拦截与扫码限制解除的做法、微信日志 tag 作特征定位
- [XposedWechatHelper](https://github.com/wuxiaosu/XposedWechatHelper) —— 传感器层改步数的思路
- [WechatSpellbook](https://github.com/Gh0u1L5/WechatSpellbook) · [WechatMagician](https://github.com/Gh0u1L5/WechatMagician) —— 早期防撤回/功能注入的参考
- [DJB-Developer/wechat-android-history-versions](https://github.com/DJB-Developer/wechat-android-history-versions) —— 微信官方历史版本直链（用来生成多版本适配表）
- [LSPosed](https://github.com/LSPosed/LSPosed) —— 运行框架

如果哪位作者觉得这里引用不当，说一声我就改。

## 免责声明

仅供个人学习与技术研究使用。
**禁止在国内网络平台传播、售卖或代装**（网盘、论坛、公众号、应用市场、短视频平台、群文件分享等一律禁止）。
使用本模块产生的一切后果由使用者自行承担。

<div align="center">

交流群 **186737276**

</div>
