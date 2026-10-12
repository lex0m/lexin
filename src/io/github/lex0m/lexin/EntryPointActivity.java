package io.github.lex0m.lexin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 启动跳板：LSPosed 模块页右下角的 ▶、以及桌面图标，都落到这里。
 *
 * 它没有界面，只做一件事：给微信发一条广播，让微信进程里的模块把面板打开。
 *
 * 为什么不直接 startActivity 拉起微信：本机微信开了「应用双开」（XSpace，user 999），
 * 第三方 App 只要发起对微信的 activity 启动，小米就会弹「请选择要使用的应用」
 * —— 实测 getLaunchIntentForPackage、隐式 LAUNCHER、显式组件三种写法都躲不掉。
 * 广播不涉及 activity 启动，不触发那个选择器；面板会等微信回到前台时再弹出来。
 */
public class EntryPointActivity extends Activity {

    /** 广播里带的时间戳，宿主侧用它判断是不是一次新请求。 */
    static final String EXTRA = "open_from";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        boolean sent = false;
        try {
            Intent i = new Intent(Bridge.ACTION);
            i.setPackage(Entry.HOST);
            i.putExtra(EXTRA, System.currentTimeMillis());
            sendBroadcast(i);
            sent = true;
        } catch (Throwable ignored) {
            // 走下面的手动提示
        }

        toast(sent
                ? "乐信面板已就绪，切回微信就能看到（微信没在运行时请先打开微信）"
                : "请在微信里打开：我 → 设置 → 更多 → 关怀模式 → 长按「开启」");
        finish();
        overridePendingTransition(0, 0);
    }

    private void toast(String text) {
        try {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    @Override
    public void onBackPressed() {
        // 跳板没有界面，别让它参与返回栈
        finish();
    }
}
