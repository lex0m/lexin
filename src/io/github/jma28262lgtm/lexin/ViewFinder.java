package io.github.jma28262lgtm.lexin;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayDeque;

/** 版本容忍的视图查找（移植自 Wex）：微信的资源 id 和类名会随版本变，所以要容错、找不到就跳过。 */
final class ViewFinder {

    /** Finds a view whose contentDescription contains the keyword. */
    static View byDesc(View root, String keyword, Class<?> type) {
        if (root == null || keyword == null) return null;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View v = queue.pollFirst();
            if (v == null) continue;
            CharSequence cd = v.getContentDescription();
            if (cd != null && cd.toString().contains(keyword)) {
                if (type == null || type.isInstance(v)) return v;
            }
            enqueue(queue, v);
        }
        return null;
    }

    /** Finds the first view whose class name contains the fragment. */
    static View byClassName(View root, String classNameContains) {
        if (root == null || classNameContains == null) return null;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View v = queue.pollFirst();
            if (v == null) continue;
            if (v.getClass().getName().contains(classNameContains)) return v;
            enqueue(queue, v);
        }
        return null;
    }

    /** 按文本找 TextView；exact=false 用包含匹配（微信用了带计数的包装文本）。 */
    static TextView byText(View root, String text, boolean exact) {
        if (root == null || text == null) return null;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View v = queue.pollFirst();
            if (v == null) continue;
            if (v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (t != null) {
                    String s = t.toString();
                    if (exact ? s.equals(text) : s.contains(text)) return (TextView) v;
                }
            }
            enqueue(queue, v);
        }
        return null;
    }

    private static void enqueue(ArrayDeque<View> queue, View v) {
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            if (child != null) queue.addLast(child);
        }
    }

    /** Entry point that never throws - callers treat null as "not found". */
    static View safeByDesc(View root, String keyword) {
        try {
            return byDesc(root, keyword, null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
