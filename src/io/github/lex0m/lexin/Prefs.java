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

    /** ctx is ignored now - the store is file based and process independent. */
    static void init(Context ctx) {
        Storage.init(ctx);
    }

    static boolean on(String key, boolean def) {
        return Storage.on(key, def);
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
