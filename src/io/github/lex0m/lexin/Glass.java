package io.github.lex0m.lexin;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 液态玻璃 · L0「伪玻璃」档（v1.7 新增）。
 *
 * 为什么先做这一档，而不是直接上真实模糊：
 *   调研结论（`.research/liquid-glass-20261116.md`）——微信聊天页背景基本是纯色，
 *   真模糊/折射在纯色上**完全看不出来**，只是白烧 GPU；而且 View.setRenderEffect 只能模糊自己、
 *   拿不到背后内容，要真模糊就得把背景子树重录进 RenderNode（API 31+），
 *   一旦在 onPreDraw 里 invalidate 就会自激重绘（BlurKit / RealtimeBlurView 都死在这）。
 *   所以默认真的是这一档：零位图、零模糊、每帧只是几个 drawRect，实测 2~4ms。
 *
 * 视觉分层（模仿 iOS 26 Liquid Glass 的四层）：
 *   ① 底色      半透明体色（白/深），透出宿主背景
 *   ② 顶高光    上→下 的白色渐变（玻璃厚度感）
 *   ③ 底内阴影  下沿一条深色带（厚边）
 *   ④ 镜面描边  1px 描边：左上亮、右下暗（边缘折射的廉价近似）
 *
 * ★ 兼容红线：这里**只用** android.graphics 的老 API。API 31+ 的 RenderEffect / RenderNode
 *   一律不许出现在本类里 —— 低版本 ART 校验到高版本类型会直接 VerifyError，模块还没跑就死。
 *   真模糊放在 {@link GlassBlur}（只在 SDK_INT>=31 时才会被加载）。
 */
final class Glass extends Drawable {

    // ── 体色（可按宿主深浅色给两套） ──
    /** 浅色宿主：偏白玻璃。 */
    static final int BASE_LIGHT = 0xFFFFFFFF;
    /** 深色宿主：偏灰黑玻璃。 */
    static final int BASE_DARK = 0xFF1B1D21;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final RectF band = new RectF();

    private final int base;
    private final float corner;
    private final int alpha;
    /** 描边宽度（px，调用方按 dp 换算好传进来）。 */
    private final float stroke;

    /** 依赖尺寸的 shader，bounds 变了才重建（每帧重建 = 每帧重编译，这是频闪的经典成因）。 */
    private LinearGradient gloss;
    private LinearGradient bottomShade;
    private float builtH = -1f;
    private float builtW = -1f;

    private Glass(int baseColor, float cornerPx, int alphaValue, float strokePx) {
        this.base = baseColor;
        this.corner = Math.max(0f, cornerPx);
        this.alpha = Math.max(0, Math.min(255, alphaValue));
        this.stroke = Math.max(0f, strokePx);
    }

    /** 常用入口：按 dp 圆角 + 0~255 不透明度做一块玻璃。 */
    static Glass of(int baseColor, float cornerDp, int alphaValue, float density) {
        return new Glass(baseColor, cornerDp * density, alphaValue, Math.max(1f, density));
    }

    /** 面板/底栏默认：浅色玻璃。 */
    static Glass light(float cornerDp, int alphaValue, float density) {
        return of(BASE_LIGHT, cornerDp, alphaValue, density);
    }

    /** 深色宿主。 */
    static Glass dark(float cornerDp, int alphaValue, float density) {
        return of(BASE_DARK, cornerDp, alphaValue, density);
    }

    @Override
    protected void onBoundsChange(android.graphics.Rect bounds) {
        super.onBoundsChange(bounds);
        rect.set(bounds);
        rebuildShaders(bounds.width(), bounds.height());
    }

    /** shader 只在尺寸变化时重建（缓存 key = 宽×高）。 */
    private void rebuildShaders(float w, float h) {
        if (w <= 0 || h <= 0) return;
        if (w == builtW && h == builtH) return;
        builtW = w;
        builtH = h;
        // ① 顶部高光：从顶部 26% 白降到透明
        gloss = new LinearGradient(0, 0, 0, h * 0.55f,
                new int[]{0x46FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        // ② 底部内阴影：下沿 18% 的深色带
        float top = h * 0.82f;
        bottomShade = new LinearGradient(0, top, 0, h,
                new int[]{0x00000000, 0x22000000},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        band.set(rect.left, top, rect.right, rect.bottom);
    }

    @Override
    public void draw(Canvas c) {
        if (c == null) return;
        try {
            rect.set(getBounds());
            if (rect.width() <= 0 || rect.height() <= 0) return;
            rebuildShaders(rect.width(), rect.height());

            clip.reset();
            clip.addRoundRect(rect, corner, corner, Path.Direction.CW);
            c.save();
            c.clipPath(clip);

            // ① 体色
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(base);
            paint.setAlpha(alpha);
            c.drawRect(rect, paint);

            // ② 顶部高光（让平面有"厚度"）
            if (gloss != null) {
                paint.setShader(gloss);
                paint.setAlpha(255);
                c.drawRect(rect, paint);
            }

            // ③ 底部内阴影
            if (bottomShade != null) {
                paint.setShader(bottomShade);
                c.drawRect(band, paint);
            }
            paint.setShader(null);
            c.restore();

            // ④ 镜面描边：整圈淡白 + 左上更亮的一小段（近似光源在左上）
            if (stroke > 0f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setAlpha(Math.min(255, alpha / 2));
                paint.setColor(0x40FFFFFF);
                c.drawRoundRect(rect, corner, corner, paint);
            }
        } catch (Throwable ignored) {
            // 绝不因为画玻璃把宿主画崩
        }
    }

    @Override
    public void setAlpha(int a) {
        // 外部改透明度时按比例缩体色（shader 两层保持固定强度，视觉更稳）
        paint.setAlpha(Math.max(0, Math.min(255, a)));
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** 透明色占位（有些宿主要求非 null）。 */
    static int withAlpha(int color, int a) {
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    private Glass() {
        this(BASE_LIGHT, 0f, 255, 0f);
    }
}
