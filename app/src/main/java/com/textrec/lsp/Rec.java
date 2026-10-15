package com.textrec.lsp;

import android.graphics.Rect;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 一次“设置文字”的位置（同一位置共享一个对象，次数累加在这里）。 */
final class SetStack {
    final Stacks.Stk stk;
    final String thread;
    final AtomicInteger count = new AtomicInteger();
    /** 反射解析出的可 hook 方法签名（异步填充） */
    volatile String[] sigs = new String[0];

    SetStack(Stacks.Stk stk, String thread) {
        this.stk = stk;
        this.thread = thread;
    }
}

/** 一条记录 = 一个“位置”：(设置栈, 绘制栈, View) 的组合。文字内容不进归并 key，所以时长每秒变也只有一条。 */
final class Rec {
    static final String TEXTVIEW = "TEXTVIEW";
    static final String CANVAS = "CANVAS";
    static final String COMPOSE = "COMPOSE";

    final int id;
    final String key;
    final String kind;
    final String viewClass;
    final String viewId;
    final String activity;
    final String[] parents;
    final SetStack set;          // 可能为 null：自绘 / Compose / 设置发生在记录之前
    final Stacks.Stk draw;
    final String drawThread;
    final long first;
    final String firstText;
    final AtomicInteger drawCount = new AtomicInteger();
    volatile long last;
    volatile String lastText;
    volatile String[] drawSigs = new String[0];

    /** 这个位置上出现过的不同文字（按“文字形状”去重，数字折叠，所以时长只占一项），最多 40 种。 */
    private static final int MAX_SAMPLES = 40;
    private final LinkedHashMap<Long, String> samples = new LinkedHashMap<>();
    private volatile long lastShape;
    private volatile int sampleOverflow;

    /** 该位置最近画过的 TextView（弱引用，最多 16 个），用来在写快照时判断“此刻哪些在屏幕上” */
    private static final int MAX_VIEWS = 16;
    private final ArrayList<WeakReference<TextView>> views = new ArrayList<>();
    private int viewNext;

    static final class ScreenText {
        final String text;
        final int l, t, r, b;

        ScreenText(String text, int l, int t, int r, int b) {
            this.text = text;
            this.l = l;
            this.t = t;
            this.r = r;
            this.b = b;
        }
    }

    Rec(int id, String key, String kind, String viewClass, String viewId, String activity, String[] parents,
        SetStack set, Stacks.Stk draw, String drawThread, String text, long shape) {
        this.id = id;
        this.key = key;
        this.kind = kind;
        this.viewClass = viewClass;
        this.viewId = viewId;
        this.activity = activity;
        this.parents = parents;
        this.set = set;
        this.draw = draw;
        this.drawThread = drawThread;
        this.first = System.currentTimeMillis();
        this.firstText = clip(text);
        this.last = first;
        this.lastText = firstText;
        this.lastShape = shape;
        addSample(shape, text);
    }

    /** 每次绘制命中这条记录时调用（obtain 之后总会调用一次，所以初始计数为 0）。 */
    void touch(String text, long shape) {
        drawCount.incrementAndGet();
        last = System.currentTimeMillis();
        lastText = clip(text);
        if (shape != lastShape) {
            lastShape = shape;
            addSample(shape, text);
        }
    }

    private void addSample(long shape, String text) {
        synchronized (samples) {
            if (samples.containsKey(shape)) return;
            if (samples.size() >= MAX_SAMPLES) {
                sampleOverflow++;
                return;
            }
            String t = text == null ? "" : text;
            samples.put(shape, t.length() > 60 ? t.substring(0, 60) + "…" : t);
        }
    }

    void trackView(TextView v) {
        synchronized (views) {
            for (int i = 0; i < views.size(); i++) {
                if (views.get(i).get() == v) return;
            }
            if (views.size() < MAX_VIEWS) {
                views.add(new WeakReference<>(v));
                return;
            }
            for (int i = 0; i < views.size(); i++) { // 先顶掉已被回收的
                if (views.get(i).get() == null) {
                    views.set(i, new WeakReference<>(v));
                    return;
                }
            }
            views.set(viewNext++ % MAX_VIEWS, new WeakReference<>(v));
        }
    }

    boolean hasViews() {
        synchronized (views) {
            return !views.isEmpty();
        }
    }

    /**
     * 此刻在屏幕上的文字：被跟踪的 View 里，已挂在窗口上、可见、alpha 不为 0、
     * 且可见区域与窗口有交集的那些，取它们【当前】的文字和屏幕坐标。
     * 只读 View 的字段，在写快照的后台线程调用；个别竞态异常直接忽略该 View。
     */
    List<ScreenText> screenNow() {
        ArrayList<TextView> vs = new ArrayList<>();
        synchronized (views) {
            for (WeakReference<TextView> w : views) {
                TextView v = w.get();
                if (v != null) vs.add(v);
            }
        }
        ArrayList<ScreenText> out = new ArrayList<>();
        Rect rc = new Rect();
        for (TextView v : vs) {
            try {
                if (!v.isAttachedToWindow() || !v.isShown() || v.getAlpha() < 0.05f) continue;
                if (!v.getGlobalVisibleRect(rc)) continue;
                View root = v.getRootView();
                if (root != null && (rc.right <= 0 || rc.bottom <= 0
                        || rc.left >= root.getWidth() || rc.top >= root.getHeight())) continue;
                CharSequence ct = v.getText();
                if (ct == null || ct.length() == 0) continue;
                String t = ct.length() > 60 ? ct.subSequence(0, 60) + "…" : ct.toString();
                out.add(new ScreenText(t, rc.left, rc.top, rc.right, rc.bottom));
                if (out.size() >= 20) break;
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    List<String> sampleList() {
        synchronized (samples) {
            return new ArrayList<>(samples.values());
        }
    }

    int sampleOverflow() {
        return sampleOverflow;
    }

    static String clip(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) + "…(" + s.length() + ")" : s;
    }
}
