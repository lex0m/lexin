package io.github.lex0m.lexin;

import java.util.List;

/**
 * DexKit 探针（v1.7.6，只在诊断模式跑）。
 *
 * 目标：验证 DexKit 能不能在这台设备上真的扫到「孤儿串」类。
 * 用的是 WeKit / wcx 完全相同的特征：
 *   · 拍一拍  → "MicroMsg.AvatarDoubleClickListener" + "onDoubleClick: %s"
 *   · 撤回    → "doRevokeMsg revokeFlag=%d msgId=%s talker=%s type=%d revokeMsgSvrId=%d"
 *   · 语音    → "getAmrFullPath cost: "
 *
 * 输入状态（typing）那条已在 v1.7.7 随功能下线一并移除。
 */
final class DexProbe {

    private static final String T = "svc-dexprobe: ";
    static final String NAME = "DexKit 探针";

    static void install() {
        if (!Log.runtimeOn()) return;
        if (Status.ok(NAME)) return;
        Status.armed(NAME, 1);
        // 扫 dex 很重，绝不能放主线程
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(4000);      // 等宿主启动完，别抢启动时间
                    long t0 = System.currentTimeMillis();
                    // 先全量搜（不限定包）——补丁后类可能不在原包里
                    List<String> pat = DexFind.findClasses(Features.loader,
                            null,
                            "MicroMsg.AvatarDoubleClickListener", "onDoubleClick: %s");
                    long dt1 = System.currentTimeMillis() - t0;

                    // 顺带验证 DexKit 的通用性：撤回处理器 / 语音路径工具
                    List<String> revoke = DexFind.findClasses(Features.loader, null,
                            // ★ usingEqStrings 是**精确匹配**：必须给完整串，
                            //   少一个 "%d msgId=%s talker=%s type=%d ..." 都匹配不上（第一次就栽在这）
                            "doRevokeMsg revokeFlag=%d msgId=%s talker=%s type=%d revokeMsgSvrId=%d",
                            "doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s");
                    List<String> voice = DexFind.findClasses(Features.loader, null,
                            "getAmrFullPath cost: ");
                    // 拍一拍的**发送**入口：真机日志字符串（精确匹配）
                    List<String> patSend = DexFind.findClasses(Features.loader, null,
                            "scene %d, sendPatMsg to %s, pattedUser %s");
                    // 拍一拍的接收处理（sysmsg 侧）
                    List<String> patRecv = DexFind.findClasses(Features.loader, null,
                            "dealPatMsg self:");

                    Status.note(NAME, "pat=" + pat + "(" + dt1 + "ms)"
                            + " · revoke=" + revoke + " · voice=" + voice
                            + " · patSend=" + patSend + " · patRecv=" + patRecv
                            + " · create=" + DexFind.describeCreate() + " · err=" + DexFind.lastError);
                    Log.d(T + "pat=" + pat + " " + dt1 + "ms");
                } catch (Throwable e) {
                    Status.note(NAME, "失败: " + e.getClass().getSimpleName());
                    Log.d(T + "failed: " + e);
                }
            }
        }, "dexprobe").start();
    }

    private DexProbe() {}
}
