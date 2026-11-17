package io.github.lex0m.lexin;

import android.app.Application;

/**
 * 模块 App 的 Application（v1.7.2）。
 *
 * 只做一件事：把「工作目录监听」立起来 —— 微信把待转换的语音写进
 * Download/乐信/待转换/ 之后，由这里接管转码（跨进程通道在这台设备上全断，
 * MediaStore 是唯一还通的一条，见 WorkDirWatcher 的注释）。
 */
public class LexinApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            WorkDirWatcher.install(this);
            WorkService.kick(this);      // 顺手拉起常驻服务（后台可能被拒，失败就靠 Activity 启动）
        } catch (Throwable ignored) {
            // 监听起不来不影响 App 其它功能
        }
    }
}
