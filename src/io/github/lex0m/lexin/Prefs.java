package io.github.lex0m.lexin;

import android.content.Context;

/** Storage 的兼容外观层：真正的存储已搬到 Storage。 */
final class Prefs {

    static final String K_RECALL = Storage.K_RECALL;
    static final String K_MOMENTS = Storage.K_MOMENTS;
    static final String K_ADS = Storage.K_ADS;
    static final String K_MEDIA = Storage.K_MEDIA;
    static final String K_LIMITS = Storage.K_LIMITS;
    static final String K_EMOJI = Storage.K_EMOJI;
    static final String K_DICE = Storage.K_DICE;
    static final String K_FIST = Storage.K_FIST;
    static final String K_THEME = Storage.K_THEME;
    static final String K_BG = Storage.K_BG;
    static final String K_BG_HOME = Storage.K_BG_HOME;
    static final String K_SCAN = Storage.K_SCAN;
    /** 转发增强：菜单注入 / 解除多选上限（新功能默认关，用户主动开才动）。 */
    static final String K_FWD_MENU = "fwd_menu";
    static final String K_FWD_SELECT = "fwd_select";
    /** v1.3 新功能开关（默认关，见 Storage 注释）。 */
    static final String K_VOICE_TEXT = Storage.K_VOICE_TEXT;
    static final String K_FLOAT_FOOTER = Storage.K_FLOAT_FOOTER;
    static final String K_BATCH_LABEL = Storage.K_BATCH_LABEL;
    static final String K_HOME_FLOAT_TAB = Storage.K_HOME_FLOAT_TAB;

    /** ctx is ignored now - the store is file based and process independent. */
    static void init(Context ctx) {
        Storage.init(ctx);
    }

    static boolean on(String key, boolean def) {
        return Storage.on(key, def);
    }

    /**
     * 开关的默认值（唯一来源）。
     *
     * 保护类功能默认开：防撤回 / 朋友圈保留 / 去广告 / 自动原图 / 放宽限制；
     * 会弹窗或改外观的默认关：骰子、猜拳、界面美化 —— 用户主动开才动界面。
     */
    static boolean def(String key) {
        return K_RECALL.equals(key) || K_MOMENTS.equals(key) || K_ADS.equals(key)
                || K_MEDIA.equals(key) || K_LIMITS.equals(key) || K_SCAN.equals(key)
                || K_FWD_MENU.equals(key) || K_FWD_SELECT.equals(key);   // 转发增强默认开（本地 UI 功能，零联网）
    }

    /** 带默认值读取：hook 路径一律用这个，省得两处默认值写岔。 */
    static boolean onDefault(String key) {
        return on(key, def(key));
    }

    static void setOn(String key, boolean value) {
        Storage.setOn(key, value);
    }

    static String str(String key, String def) {
        return Storage.str(key, def);
    }

    static void setStr(String key, String value) {
        Storage.setStr(key, value);
    }

    static int num(String key, int def) {
        return Storage.num(key, def);
    }

    static void setNum(String key, int value) {
        Storage.setNum(key, value);
    }

    /** Diagnostics: where the settings file lives. */
    static String side() {
        return Storage.side();
    }
}
