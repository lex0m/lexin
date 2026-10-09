#!/system/bin/sh
# 微信更新后跑一次：扫出模块依赖的关键类名，输出候选清单供人工确认。
# 用法：sh tools/scan-wx.sh [输出目录]   默认 /data/local/tmp
#
# 原理：dex 里类名是明文，直接从微信 APK 里抽出来按槽位匹配，
# 一次约 20-30 秒（微信 280MB / 7.8 万个类），不需要反编译。

OUT=${1:-/data/local/tmp}
APK=$(pm path com.tencent.mm 2>/dev/null | sed 's/package://' | head -1)
if [ -z "$APK" ]; then
    echo "找不到微信 APK（需要 root 或 Shizuku）"
    exit 1
fi

echo "微信 APK: $APK"
echo "扫描中..."

unzip -p "$APK" 'classes*.dex' 2>/dev/null | strings \
    | grep -oE 'Lcom/tencent/mm/[A-Za-z0-9/_$]+;' \
    | sed 's/^L//; s/;$//; s|/|.|g' | sort -u > "$OUT/wxclasses.txt"

# 框架层（androidx/Android）单独存一份：标题栏、RecyclerView 这些在这里，不在微信包里
unzip -p "$APK" 'classes*.dex' 2>/dev/null | strings \
    | grep -oE 'L(androidx|android)/[A-Za-z0-9/_$]+;' \
    | sed 's/^L//; s/;$//; s|/|.|g' | sort -u > "$OUT/fwclasses.txt"

echo "类名总数: $(wc -l < "$OUT/wxclasses.txt") 微信 / $(wc -l < "$OUT/fwclasses.txt") 框架"

R="$OUT/wxreport.txt"
: > "$R"

slot() {
    echo "" >> "$R"
    echo "## $1" >> "$R"
    grep -E "$2" "$OUT/wxclasses.txt" | head -8 >> "$R"
}

# 框架层槽位：在 fwclasses.txt 里找
slot_fw() {
    echo "" >> "$R"
    echo "## $1" >> "$R"
    grep -E "$2" "$OUT/fwclasses.txt" | head -8 >> "$R"
}

slot "会话列表"        "ui\.conversation\.[A-Za-z]*ListView"
slot "页面背景层"      "(MMWeUIBounceView|LayoutListenerView)"
slot_fw "标题栏"       "(ActionBarContainer|ActionBarOverlayLayout|Toolbar)$"
slot "标题栏(微信侧)"  "(HomeActionBar|MMActivity|ActionBar)"
slot "下拉提示条"      "plugin\.taskbar\.ui\.TaskBarBottomView"
slot "下拉小程序面板"  "(plugin\.taskbar\.ui\.TaskBarContainer|AppBrandDesktopContainerView)"
slot "聊天页容器"      "pluginsdk\.ui\.chat\.(ChattingUILayout|ChattingScrollLayout)"
slot "聊天消息列表"    "chatting\.view\.MMChattingListView"
slot "底部导航"        "LauncherUIBottomTabView"
slot "主界面"          "ui\.(LauncherUI|HomeUI)"
slot "联系人列表"      "ui\.contact\.[A-Za-z]*(ListView|RecyclerView)"
slot "发现/设置页"     "ui\.(DiscoverUI|SettingsUI)|ui\.setting\.[A-Za-z]*UI"
slot_fw "列表容器"     "(RecyclerView|AbsListView)$"
slot_fw "滚动容器"     "(NestedScrollView|ScrollView)$"

echo ""
echo "完成，候选清单：$R"
echo "（把里面的名字填到源码的候选表；找不到的槽位说明该版本改了结构，对应功能先停用）"
cat "$R"
