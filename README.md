# 乐信（lexin）

微信界面美化模块（Xposed / LSPosed）。

- 名称：**乐信** · lexin
- 包名：**`io.github.lex0m.lexin`**（v1.0 起）
- 作者：**lex**
- 许可证：**MIT**（见 [LICENSE](LICENSE)）

> **包名变更说明**：本模块先后使用过三个包名，均为同一模块：
> `com.sysprobe.svc`（早期）→ `io.github.jma28262lgtm.lexin`（过渡）→ **`io.github.lex0m.lexin`**（现行）。
> 升级时请先卸载旧包、再安装新包，并在 LSPosed 中为新包重新勾选作用域（微信）。


## 功能

- **全屏背景**：任意图片，可调透明度，五种缩放（填充 / 适应 / 拉伸 / 居中 / 自定义裁剪）
- **列表卡片化**：左右内缩、圆角、上下间隔、卡片高度均可调
- **状态栏**：恢复系统原色，背景图不再压住状态栏
- **顶部栏**：跟随背景，切页与下拉不再错位
- **聊天页**：保留微信自身的聊天背景与气泡，不做任何干预
- **其它**：防撤回、去广告、朋友圈去广告、媒体保存、骰子/猜拳等（见设置面板）

## 环境要求

- 已 root，并安装 LSPosed（或兼容的 Xposed 框架）
- Android 12 – 15
- 微信 8.0.x。**8.0.78 实测通过**；其它版本按特性探测工作：类名找不到时该功能**静默跳过**，不会崩溃、也不会影响微信本身与其它功能。哪些功能在你当前版本可用，见设置面板每项的说明。

### 微信更新后如何适配

类名是版本强绑定的，更新微信后可用 `tools/scan-wx.sh` 扫出候选：

```sh
sh tools/scan-wx.sh /data/local/tmp   # 约 20-30 秒
cat /data/local/tmp/wxreport.txt      # 按槽位列出的候选类名
```

脚本直接从微信 APK 的 dex 里抽明文类名（无需反编译），按"会话列表 / 页面背景层 / 标题栏 / 下拉提示条 / 聊天页容器 / 底部导航"等槽位输出候选，把结果填进源码即可。**某个槽位扫不到，说明该版本改了结构 —— 对应功能先停用，其余功能不受影响。**

## 安装

1. 安装 `lexin.apk`
2. LSPosed 中勾选「乐信」，作用域勾选微信
3. 强制停止微信后重新打开

## 使用

设置面板：微信 → 我的 → 设置 → 顶部「乐信」入口，或桌面图标。
每项改动都标注了是否需重进界面生效。

## 构建

不使用 Gradle，直接用 JDK 21 + aapt2 + apksigner + R8：

```sh
sh build.sh          # 产物：dist/lexin.apk
```

## 来源与致谢

实现思路与部分技术方案参考了以下开源项目：

- [MDWechat](https://github.com/Blankeer/MDWechat)（GPL-3.0）— 按视图树定位页面层级、ActionBar 背景接管、会话列表下拉小程序处理等
- [WechatSpellbook](https://github.com/Gh0u1L5/WechatSpellbook)
- [WechatMagician](https://github.com/Gh0u1L5/WechatMagician)
- [LSPosed](https://github.com/LSPosed/LSPosed)（运行框架）

本项目以 **MIT** 发布，为上述项目的**独立实现**（Java 语言重写，未复制其源代码），仅参考了实现思路与公开的 API 行为。若原作者认为存在不当引用，可随时联系调整。

## 免责声明

仅供学习与个人研究使用，请勿用于商业用途。使用本模块产生的一切后果由使用者自行承担。
