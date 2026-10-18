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
    /** setText("") / setText(null)：文字被清空。空串没有绘制，所以单独记一类，带“谁清空的”设置栈。 */
    static final String TEXTCLEAR = "TEXTCLEAR";
    /**
     * 文字被 setText 设置过，但所在控件从未被绘制（INVISIBLE / GONE / 尺寸为 0 / 未挂到窗口 / 父级不可见）。
     * 这类文字没有“绘制”事件，只能从设置那一侧发现；是否仍“从未绘制”在写快照时动态判断。
     */
    static final String TEXTSET = "TEXTSET";
    /** 被跟踪 View 的 alpha / visibility / selected 变化（看“选中态”到底怎么实现的） */
    static final String STATE = "STATE";
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
    /** 记录时所处的页面（Activity > Fragment），见 Recorder.pageName */
    final String page;
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
    /** 链路追踪（开了“链路追踪”才有）：上游异步来源 + 下游调用 / 效果，最近一次采样 */
    volatile String chain;

    // ---- 调用时间 / 间隔 / 基线 / 标识 / 对象 / 栈上各层（1.21.0）
    volatile long firstMono, lastMono;      // uptimeMillis
    volatile long minGap = Long.MAX_VALUE, maxGap;
    volatile int baseHits, liveHits;        // 基线期间 / 其他时间的调用次数
    private final java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>();
    private volatile String lastTag = "";
    private final java.util.LinkedHashSet<Integer> objIds = new java.util.LinkedHashSet<>();
    private volatile int objOverflow;
    private volatile int frameN;
    private final java.util.LinkedHashMap<String, int[]> frameCnt = new java.util.LinkedHashMap<>();
    private volatile int frameOverflow;
    volatile String hookInfo;
    volatile String retType;

    /** 每次命中都调：单调时间、间隔、基线分账、当前标识。 */
    void stamp() {
        long now = android.os.SystemClock.uptimeMillis();
        long lm = lastMono;
        if (firstMono == 0) firstMono = now;
        if (lm != 0) {
            long g = now - lm;
            if (g < minGap) minGap = g;
            if (g > maxGap) maxGap = g;
        }
        lastMono = now;
        if (Timeline.baseline) baseHits++;
        else liveHits++;
        String t = Timeline.tag;
        if (!t.equals(lastTag)) {
            lastTag = t;
            if (!t.isEmpty()) {
                synchronized (tags) {
                    if (tags.size() < 8) tags.add(t);
                }
            }
        }
    }

    java.util.List<String> tagList() {
        synchronized (tags) {
            return new java.util.ArrayList<>(tags);
        }
    }

    String lastTag() {
        return lastTag;
    }

    void noteObj(Object o) {
        if (o == null) return;
        Integer id = System.identityHashCode(o);
        synchronized (objIds) {
            if (objIds.contains(id)) return;
            if (objIds.size() < 8) objIds.add(id);
            else objOverflow++;
        }
    }

    java.util.List<String> objList() {
        synchronized (objIds) {
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            for (Integer i : objIds) out.add(Integer.toHexString(i));
            return out;
        }
    }

    int objOverflow() {
        return objOverflow;
    }

    /** 抓到一份栈就调一次：统计每一层出现在了多少份采样栈里（每次都出现 = 稳定层）。 */
    void noteFrames(String[] lines) {
        if (lines == null) return;
        synchronized (frameCnt) {
            frameN++;
            for (String l : lines) {
                String k = l;
                int[] c = frameCnt.get(k);
                if (c == null) {
                    if (frameCnt.size() >= 80) {
                        frameOverflow++;
                        continue;
                    }
                    c = new int[1];
                    frameCnt.put(k, c);
                }
                c[0]++;
            }
        }
    }

    int frameSamples() {
        return frameN;
    }

    int frameOverflow() {
        return frameOverflow;
    }

    java.util.LinkedHashMap<String, Integer> frameStats() {
        synchronized (frameCnt) {
            java.util.LinkedHashMap<String, Integer> out = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, int[]> e : frameCnt.entrySet()) out.put(e.getKey(), e.getValue()[0]);
            return out;
        }
    }
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

    /** 同一个位置（栈相同）上出现过的不同 View id，最多 60 个。 */
    private static final int MAX_IDS = 60;
    private final java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
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

    Rec(int id, String key, String kind, String viewClass, String viewId, String activity, String page, String[] parents,
        SetStack set, Stacks.Stk draw, String drawThread, String text, long shape) {
        this.id = id;
        this.key = key;
        this.kind = kind;
        this.viewClass = viewClass;
        this.viewId = viewId;
        this.activity = activity;
        this.page = page == null ? "" : page;
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
        addId(viewId);
    }

    void addId(String id) {
        if (id == null || id.isEmpty()) return;
        synchronized (ids) {
            if (ids.size() < MAX_IDS) ids.add(id);
        }
    }

    List<String> idList() {
        synchronized (ids) {
            return new ArrayList<>(ids);
        }
    }

    boolean idsFull() {
        synchronized (ids) {
            return ids.size() >= MAX_IDS;
        }
    }

    /** 这个文字/参数变体是否已经记过（不增加计数）。 */
    boolean hasSample(long shape) {
        synchronized (samples) {
            return samples.containsKey(shape);
        }
    }

    /** 每次绘制命中这条记录时调用（obtain 之后总会调用一次，所以初始计数为 0）。 */
    void touch(String text, long shape) {
        stamp();
        drawCount.incrementAndGet();
        last = System.currentTimeMillis();
        lastText = clip(text);
        if (shape != lastShape) {
            lastShape = shape;
            addSample(shape, text);
        }
    }

    /** TEXTSET：每次 setText 命中时调用（不增加绘制次数）。 */
    void noteSet(String text, long shape) {
        stamp();
        last = System.currentTimeMillis();
        lastText = clip(text);
        if (shape != lastShape) {
            lastShape = shape;
            addSample(shape, text);
        }
    }

    /** 只计数不取样（高频方法追踪时的降级）。 */
    void tick() {
        stamp();
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

    /** 被跟踪且还活着的 View（拷贝）。 */
    List<View> liveViews() {
        ArrayList<View> out = new ArrayList<>();
        synchronized (views) {
            for (WeakReference<View> w : views) {
                View v = w.get();
                if (v != null) out.add(v);
            }
        }
        return out;
    }

    /** 已挂在窗口、可见、alpha 不为 0、可见区域与窗口有交集。 */
    static boolean onScreen(View v, Rect rc) {
        try {
            if (!v.isAttachedToWindow() || !v.isShown() || v.getAlpha() < 0.05f) return false;
            if (!v.getGlobalVisibleRect(rc)) return false;
            View root = v.getRootView();
            return !(root != null && (rc.right <= 0 || rc.bottom <= 0
                    || rc.left >= root.getWidth() || rc.top >= root.getHeight()));
        } catch (Throwable t) {
            return false;
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
                if (!onScreen(v, rc)) continue;
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

    /** 图标缩略图（base64 PNG，最长边 ≤64px）：同一个位置最近出现过的 3 种不同样子（如点赞前后）。 */
    private static final int MAX_THUMBS = 3;
    private final LinkedHashMap<Long, String> thumbs = new LinkedHashMap<>();
    private volatile long lastThumbAt;

    /** 限频：同一条记录两次截图至少隔 gapMs。 */
    boolean thumbDue(long now, long gapMs) {
        if (now - lastThumbAt < gapMs) return false;
        lastThumbAt = now;
        return true;
    }

    void addThumb(long hash, String b64) {
        synchronized (thumbs) {
            if (thumbs.containsKey(hash)) return;
            if (thumbs.size() >= MAX_THUMBS) {
                java.util.Iterator<Long> it = thumbs.keySet().iterator();
                it.next();
                it.remove(); // 顶掉最旧的
            }
            thumbs.put(hash, b64);
        }
    }

    List<String> thumbList() {
        synchronized (thumbs) {
            return new ArrayList<>(thumbs.values());
        }
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
