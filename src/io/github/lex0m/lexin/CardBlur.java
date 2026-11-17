package io.github.lex0m.lexin;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;

/**
 * 卡片图的真模糊（API 31+）。
 *
 * ★ 为什么必须单独一个类：{@link RenderEffect} 是 API 31 才有的类型。
 * 把它写在 Beautify 里，低版本 ART 在验证方法时会解析到这个不存在的类型，
 * 结果是整个外观模块 VerifyError 连坐 —— 用户侧表现成「升了个版本，美化全没了」。
 * 放在独立类里、调用点先判 SDK_INT，低版本这个类连加载都不会发生。
 *
 * ★ R8：只被 SDK 判断后的分支调用，混淆器看不到「一定被调用」，
 * 所以 proguard-rules.pro 里有一条 -keep（删掉它 = 高版本模糊失效，且没有报错）。
 *
 * 实现取舍：不用 Paint.setRenderEffect（那要 API 31 的 Paint 重载，同样的问题），
 * 而是 RenderNode 录一段 drawBitmap 再离屏回读成 Bitmap。烘焙只发生在换图/改参数时，
 * 不在滚动路径上。
 */
final class CardBlur {

    private static final String T = "svc-blur: ";

    private CardBlur() { }

    /**
     * @param src      已经裁好的原图
     * @param radiusPx 模糊半径（像素）
     * @return 模糊后的新 Bitmap；任何一步失败都返回 null（调用方保留原图）
     */
    static Bitmap blur(Bitmap src, float radiusPx) {
        if (Build.VERSION.SDK_INT < 31) return null;
        if (src == null || src.isRecycled()) return null;
        RenderNode node = null;
        Bitmap out = null;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w <= 0 || h <= 0) return null;
            float r = Math.max(0.5f, radiusPx);

            node = new RenderNode("lexin-card-blur");
            node.setPosition(0, 0, w, h);
            node.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));

            Canvas rec = node.beginRecording();
            if (rec == null) return null;
            rec.drawBitmap(src, 0f, 0f, null);
            node.endRecording();

            out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas oc = new Canvas(out);
            oc.drawRenderNode(node);
            return out;
        } catch (Throwable t) {
            Log.d(T + "blur failed: " + t.getClass().getSimpleName());
            try { if (out != null) out.recycle(); } catch (Throwable ignored) { }
            return null;
        } finally {
            try { if (node != null) node.discardDisplayList(); } catch (Throwable ignored) { }
        }
    }
}
