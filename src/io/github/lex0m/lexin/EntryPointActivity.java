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

        // ① 先把自己的设置页拉起来 —— 用户点图标最想看到的就是「全部开关一屏」。
        //   老版本这里只发广播、然后无声无息 finish()，微信不在前台时等于什么都没发生，
        //   于是大家都跑去点「外观」那个二级页，以为模块只有美化功能。
        boolean opened = false;
        try {
            Intent s = new Intent();
            s.setClassName(getPackageName(), getPackageName() + ".SettingsActivity");
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(s);
            opened = true;
        } catch (Throwable ignored) {
            // 落到下面的提示
        }

        // ② 顺手通知微信进程：切回微信时把悬浮面板弹出来（微信没在跑就先挂着）
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

        toast(opened
                ? (sent ? "已打开设置页；切回微信可看到悬浮面板" : "已打开设置页（微信里长按关怀模式「开启」也能开面板）")
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
