package com.textrec.lsp;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
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

/** TextView 文字颜色最近一次被设置的位置和颜色值。 */
final class ColorSet {
    final SetStack ss; // 可能为 null（被限流没抓到栈）
    final int color;

    ColorSet(SetStack ss, int color) {
        this.ss = ss;
        this.color = color;
    }
}

/** 一条记录 = 一个“位置”：(设置栈, 绘制栈, View) 的组合。文字内容不进归并 key，所以时长每秒变也只有一条。 */
final class Rec {
    static final String TEXTVIEW = "TEXTVIEW";
    static final String CANVAS = "CANVAS";
    static final String COMPOSE = "COMPOSE";
    /** ImageView 图标 / 设了 contentDescription 的非文字控件 */
    static final String ICON = "ICON";
    /** 用户指定的“方法追踪”：draw 栈 = 调用栈，texts = 参数→返回值变体 */
    static final String TRACE = "TRACE";

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

    /** TextView 文字颜色的设置位置（只记最近一次） */
    volatile ColorSet colorSet;
    /** 自绘 / Compose 最近一次 drawText 的画布内坐标（不是屏幕坐标） */
    volatile float posX = Float.NaN;
    volatile float posY = Float.NaN;

    /** 这个位置上出现过的不同文字（按“文字形状”去重，数字折叠，所以时长只占一项），最多 40 种。 */
    private static final int MAX_SAMPLES = 40;
    private final LinkedHashMap<Long, String> samples = new LinkedHashMap<>();
    private volatile long lastShape;
    private volatile int sampleOverflow;

    static final class PaintVar {
        final String desc;
        volatile int n;

        PaintVar(String desc) {
            this.desc = desc;
        }
    }

    /** 画笔 / 绘制参数变体（颜色、字号、描边或填充、Compose paint 的参数），最多 16 种。 */
    private static final int MAX_PAINTS = 16;
    private final LinkedHashMap<Long, PaintVar> paints = new LinkedHashMap<>();
    private volatile int paintOverflow;

    /** 该位置最近画过的 View（弱引用，最多 40 个），用来在写快照时判断“此刻哪些在屏幕上” */
    private static final int MAX_VIEWS = 40;
    private final ArrayList<WeakReference<View>> views = new ArrayList<>();
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

    /** 只计数不取样（高频方法追踪时的降级）。 */
    void tick() {
        drawCount.incrementAndGet();
        last = System.currentTimeMillis();
    }

    private void addSample(long shape, String text) {
        synchronized (samples) {
            if (samples.containsKey(shape)) return;
            if (samples.size() >= MAX_SAMPLES) {
                sampleOverflow++;
                return;
            }
            String t = text == null ? "" : text;
            samples.put(shape, t.length() > 200 ? t.substring(0, 200) + "…" : t);
        }
    }

    // ------------------------------------------------------------ 画笔 / 参数变体

    /** 已有这个变体就计数并返回 true；没有返回 false（调用方再构造描述串调用 addPaint，避免每帧拼字符串）。 */
    boolean knowsPaint(long key) {
        synchronized (paints) {
            PaintVar p = paints.get(key);
            if (p == null) return false;
            p.n++;
            return true;
        }
    }

    void addPaint(long key, String desc) {
        synchronized (paints) {
            PaintVar p = paints.get(key);
            if (p != null) {
                p.n++;
                return;
            }
            if (paints.size() >= MAX_PAINTS) {
                paintOverflow++;
                return;
            }
            PaintVar np = new PaintVar(desc);
            np.n = 1;
            paints.put(key, np);
        }
    }

    List<PaintVar> paintList() {
        synchronized (paints) {
            return new ArrayList<>(paints.values());
        }
    }

    int paintOverflow() {
        return paintOverflow;
    }

    boolean hasPos() {
        return !Float.isNaN(posX) && !Float.isNaN(posY);
    }

    // ------------------------------------------------------------ 屏幕可见性

    void trackView(View v) {
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
     * 此刻在屏幕上的文字/图标：被跟踪的 View 里，已挂在窗口上、可见、alpha 不为 0、
     * 且可见区域与窗口有交集的那些，取它们【当前】的文字和屏幕坐标。
     * TextView 取文字；图标取“[类名:Drawable类名] 内容描述”。
     * 只读 View 的字段，在写快照的后台线程调用；个别竞态异常直接忽略该 View。
     */
    List<ScreenText> screenNow() {
        ArrayList<View> vs = new ArrayList<>();
        synchronized (views) {
            for (WeakReference<View> w : views) {
                View v = w.get();
                if (v != null) vs.add(v);
            }
        }
        ArrayList<ScreenText> out = new ArrayList<>();
        Rect rc = new Rect();
        for (View v : vs) {
            try {
                if (!v.isAttachedToWindow() || !v.isShown() || v.getAlpha() < 0.05f) continue;
                if (!v.getGlobalVisibleRect(rc)) continue;
                View root = v.getRootView();
                if (root != null && (rc.right <= 0 || rc.bottom <= 0
                        || rc.left >= root.getWidth() || rc.top >= root.getHeight())) continue;
                String t;
                if (v instanceof TextView) {
                    CharSequence ct = ((TextView) v).getText();
                    if (ct == null || ct.length() == 0) continue;
                    t = ct.length() > 200 ? ct.subSequence(0, 200) + "…" : ct.toString();
                } else {
                    String dn = "";
                    if (v instanceof ImageView) {
                        Drawable d = ((ImageView) v).getDrawable();
                        if (d != null) dn = ":" + d.getClass().getSimpleName();
                    }
                    CharSequence cd = v.getContentDescription();
                    t = "[" + v.getClass().getSimpleName() + dn + "]"
                            + (cd == null || cd.length() == 0 ? "" : " " + cd);
                }
                out.add(new ScreenText(t, rc.left, rc.top, rc.right, rc.bottom));
                if (out.size() >= 50) break;
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
        return s.length() > 300 ? s.substring(0, 300) + "…(" + s.length() + ")" : s;
    }
}
