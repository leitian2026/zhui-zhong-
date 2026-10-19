package com.textrec.lsp;

import android.app.Activity;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 时间线：把“发生了什么、什么时候发生”按单调时钟（uptimeMillis）+ 全局序号排成一条。
 * 来源：① 你在记录页点的标记 / 基线开始结束；② 用户操作（点击、滑动、返回键，带坐标和点中的控件）；
 *       ③ 页面切换；④ 类第一次可用；⑤ 各位置的记录样本（每个位置前 5 次，之后每 100 次一条）。
 * 另外维护“当前标识”（比如当前视频 id，由方法追踪行的 @tag=N 提供），记录和事件都会带上。
 * 有上限（3000 条，环形），丢了多少写在导出里，不悄悄丢。
 */
final class Timeline {
    private Timeline() {
    }

    static volatile boolean on = true;
    static volatile boolean ops = true;       // 记用户操作
    static volatile boolean samples = true;   // 记各位置的记录样本
    static volatile String tag = "";          // 当前标识（视频 id 之类）
    static volatile boolean baseline;         // 正在录“安静基线”
    static volatile boolean baselineDone;     // 录过基线
    static volatile long baseStart, baseEnd;
    static final long START = SystemClock.uptimeMillis();

    private static final int CAP = 3000;
    /** 关键事件（标记 / 基线 / 任务 / 操作 / 页面 / 标识 / 类可用）单独存，不会被下面这些高频采样挤掉 */
    private static final java.util.Set<String> KEY_KINDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "MARK", "BASE", "TASK", "TOUCH", "KEY", "PAGE", "TAG", "CLASS"));
    /** 采样类事件每种最多记这么多条（记满后只计数，丢了多少写进导出） */
    private static final int KIND_BUDGET = 400;
    private static final ArrayDeque<Ev> SAMP = new ArrayDeque<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, int[]> KIND_N = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, int[]> KIND_DROP = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final ArrayDeque<Ev> EVS = new ArrayDeque<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, int[]> KEYN = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicBoolean HOOKED = new AtomicBoolean();
    private static volatile long lastMarkSeq = -1;

    static final class Ev {
        final long seq, t, wall;
        final String kind, page, tag;
        volatile String text;

        Ev(String kind, String text, long t, long wall) {
            this.seq = SEQ.incrementAndGet();
            this.t = t;
            this.wall = wall;
            this.kind = kind;
            this.text = text;
            this.page = Recorder.pageName;
            this.tag = Timeline.tag;
        }
    }

    static void configure(RecConfig c) {
        on = c.tlOn;
        ops = c.tlOn && c.tlOps;
        samples = c.tlOn && c.tlSamples;
        if (ops) install();
    }

    static void add(String kind, String text) {
        addAt(kind, text, SystemClock.uptimeMillis(), System.currentTimeMillis());
    }

    /** 指定发生时间（标记从 TextRec 经文件传来有延迟，按发送时刻往回推）。 */
    static void addAt(String kind, String text, long t, long wall) {
        if (!on) return;
        String tx = text == null ? "" : (text.length() > 240 ? text.substring(0, 240) + "…" : text);
        if (KEY_KINDS.contains(kind)) {
            synchronized (EVS) {
                // 同一毫秒级的连续页面切换（碎片恢复顺序）合成一条
                if ("PAGE".equals(kind)) {
                    Ev last = EVS.peekLast();
                    if (last != null && "PAGE".equals(last.kind) && t - last.t <= 100 && last.text.length() < 400) {
                        last.text = last.text + " → " + tx.replace("页面 → ", "");
                        return;
                    }
                }
                Ev e = new Ev(kind, tx, t, wall);
                if (EVS.size() >= CAP) {
                    EVS.pollFirst();
                    DROPPED.incrementAndGet();
                }
                EVS.addLast(e);
            }
        } else {
            int[] n = KIND_N.get(kind);
            if (n == null) {
                n = new int[1];
                int[] old = KIND_N.putIfAbsent(kind, n);
                if (old != null) n = old;
            }
            if (++n[0] > KIND_BUDGET) {
                int[] d = KIND_DROP.get(kind);
                if (d == null) {
                    d = new int[1];
                    int[] old = KIND_DROP.putIfAbsent(kind, d);
                    if (old != null) d = old;
                }
                d[0]++;
                return;
            }
            Ev e = new Ev(kind, tx, t, wall);
            synchronized (EVS) {
                SAMP.addLast(e);
            }
        }
        Recorder.markChainDirty();
    }

    /** 这个位置这次要不要进时间线：前 5 次，之后每 100 次一条（先问再拼文字，省得白拼）。 */
    static boolean want(String key) {
        if (!samples) return false;
        int[] n = KEYN.get(key);
        if (n == null) {
            if (KEYN.size() > 5000) KEYN.clear();
            n = new int[1];
            int[] old = KEYN.putIfAbsent(key, n);
            if (old != null) n = old;
        }
        int c = ++n[0];
        return c <= 5 || c % 100 == 0;
    }

    /** 各位置的记录样本：每个 key 前 5 次，之后每 100 次一条。 */
    static void sample(String kind, String key, String text) {
        if (!samples) return;
        int[] n = KEYN.get(key);
        if (n == null) {
            if (KEYN.size() > 5000) KEYN.clear();
            n = new int[1];
            int[] old = KEYN.putIfAbsent(key, n);
            if (old != null) n = old;
        }
        int c = ++n[0];
        if (c <= 5 || c % 100 == 0) add(kind, (c > 5 ? "(第" + c + "次) " : "") + text);
    }

    /** 当前标识变了就记一条。 */
    static void setTag(String t) {
        if (t == null) return;
        if (t.length() > 80) t = t.substring(0, 80);
        if (t.equals(tag)) return;
        tag = t;
        add("TAG", "当前标识 → " + t);
    }

    // ------------------------------------------------------------ 标记 / 基线（来自记录页，经 marker.txt 传进来）

    /** 文件内容：第一行序号，第二行标签。序号没变说明是同一条，不重复记。 */
    static void onMarkerFile(java.io.File f) {
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 2000)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = in.read(b);
                if (n <= 0) return;
                String s = new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8);
                String[] ln = s.split("\n", 3);
                if (ln.length < 3) return;
                long seq = Long.parseLong(ln[0].trim());
                long sendWall = Long.parseLong(ln[1].trim());
                String label = ln[2].trim();
                if (seq == lastMarkSeq) return;
                lastMarkSeq = seq;
                long nowW = System.currentTimeMillis();
                long delay = nowW - sendWall;
                if (delay < 0 || delay > 15000) delay = 0; // 两边时钟对不上就按收到的时刻
                long t = SystemClock.uptimeMillis() - delay;
                String suffix = delay > 0 ? "（TextRec 发出 " + delay + "ms 后收到，时间已按发出时刻推算）" : "";
                if ("基线开始".equals(label)) {
                    baseline = true;
                    baseStart = t;
                    addAt("BASE", "基线开始（请什么都不要操作）" + suffix, t, sendWall);
                } else if ("基线结束".equals(label)) {
                    baseline = false;
                    baselineDone = true;
                    baseEnd = t;
                    addAt("BASE", "基线结束（共 " + (baseEnd - baseStart) + "ms）" + suffix, t, sendWall);
                } else if (label.startsWith("任务")) {
                    addAt("TASK", label + suffix, t, sendWall);
                } else {
                    addAt("MARK", label + suffix, t, sendWall);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 用户操作

    private static float dx, dy;
    private static long dt;
    private static String downHit = "";

    private static void install() {
        if (!HOOKED.compareAndSet(false, true)) return;
        try {
            XposedBridge.hookMethod(Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (ops) onTouch((Activity) p.thisObject, (MotionEvent) p.args[0]);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[TextRec] timeline touch hook failed: " + t);
        }
        try {
            XposedBridge.hookMethod(Activity.class.getDeclaredMethod("dispatchKeyEvent", KeyEvent.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        KeyEvent k = (KeyEvent) p.args[0];
                        if (ops && k.getAction() == KeyEvent.ACTION_UP && k.getKeyCode() == KeyEvent.KEYCODE_BACK) add("KEY", "返回键");
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[TextRec] timeline key hook failed: " + t);
        }
        try {
            XposedBridge.hookMethod(Activity.class.getDeclaredMethod("onBackPressed"), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (ops) add("KEY", "返回（onBackPressed）");
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void onTouch(Activity a, MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            dx = e.getRawX();
            dy = e.getRawY();
            dt = SystemClock.uptimeMillis();
            downHit = hit(a.getWindow().getDecorView(), (int) e.getRawX(), (int) e.getRawY());
        } else if (act == MotionEvent.ACTION_UP) {
            float mx = e.getRawX() - dx, my = e.getRawY() - dy;
            float dens = a.getResources().getDisplayMetrics().density;
            long dur = SystemClock.uptimeMillis() - dt;
            if (Math.abs(mx) > 24 * dens || Math.abs(my) > 24 * dens) {
                String dir = Math.abs(my) >= Math.abs(mx) ? (my < 0 ? "上滑" : "下滑") : (mx < 0 ? "左滑" : "右滑");
                add("TOUCH", dir + " (" + (int) dx + "," + (int) dy + ")→(" + (int) e.getRawX() + "," + (int) e.getRawY() + ") " + dur + "ms 起点控件=" + downHit);
            } else {
                add("TOUCH", (dur > 500 ? "长按 (" : "点击 (") + (int) dx + "," + (int) dy + ") 控件=" + downHit);
            }
        }
    }

    /** 点中的最深的可见控件：类 + id。 */
    private static String hit(View root, int x, int y) {
        View cur = root;
        int[] loc = new int[2];
        for (int depth = 0; depth < 40 && cur instanceof ViewGroup; depth++) {
            ViewGroup g = (ViewGroup) cur;
            View next = null;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View c = g.getChildAt(i);
                if (c.getVisibility() != View.VISIBLE) continue;
                c.getLocationOnScreen(loc);
                if (x >= loc[0] && x < loc[0] + c.getWidth() && y >= loc[1] && y < loc[1] + c.getHeight()) {
                    next = c;
                    break;
                }
            }
            if (next == null) break;
            cur = next;
        }
        String id = "";
        try {
            if (cur.getId() != View.NO_ID) id = " id=" + cur.getResources().getResourceEntryName(cur.getId());
        } catch (Throwable ignored) {
        }
        String cn = cur.getClass().getName();
        int i = cn.lastIndexOf('.');
        StringBuilder sb = new StringBuilder((i >= 0 ? cn.substring(i + 1) : cn) + id);
        try {
            CharSequence cd = cur.getContentDescription();
            if (cd != null && cd.length() > 0) sb.append(" 描述=").append(cd.length() > 20 ? cd.subSequence(0, 20) : cd);
            if (cur instanceof android.widget.TextView) {
                CharSequence tx = ((android.widget.TextView) cur).getText();
                if (tx != null && tx.length() > 0) sb.append(" 文字=").append(tx.length() > 20 ? tx.subSequence(0, 20) : tx);
            }
            View p = cur;
            sb.append(" 上级=");
            for (int k = 0; k < 3 && p.getParent() instanceof View; k++) {
                p = (View) p.getParent();
                String pn = p.getClass().getName();
                sb.append(k > 0 ? "<" : "").append(pn.substring(pn.lastIndexOf('.') + 1));
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 输出

    static JSONArray toJson() throws Exception {
        JSONArray a = new JSONArray();
        java.util.ArrayList<Ev> all = new java.util.ArrayList<>();
        synchronized (EVS) {
            all.addAll(EVS);
            all.addAll(SAMP);
        }
        java.util.Collections.sort(all, new java.util.Comparator<Ev>() {
            @Override
            public int compare(Ev x, Ev y) {
                int c = Long.compare(x.t, y.t);
                return c != 0 ? c : Long.compare(x.seq, y.seq);
            }
        });
        {
            for (Ev e : all) {
                JSONObject o = new JSONObject();
                o.put("seq", e.seq);
                o.put("t", e.t - START);
                o.put("wall", e.wall);
                o.put("kind", e.kind);
                o.put("text", e.text);
                o.put("page", e.page);
                if (!e.tag.isEmpty()) o.put("tag", e.tag);
                a.put(o);
            }
        }
        return a;
    }

    static long dropped() {
        return DROPPED.get();
    }

    /** 每种采样事件超出 400 条上限后丢了多少。 */
    static JSONObject kindDropped() throws Exception {
        JSONObject o = new JSONObject();
        for (java.util.Map.Entry<String, int[]> e : KIND_DROP.entrySet()) o.put(e.getKey(), e.getValue()[0]);
        return o;
    }

    static void clear() {
        synchronized (EVS) {
            EVS.clear();
            SAMP.clear();
        }
        KIND_N.clear();
        KIND_DROP.clear();
        DROPPED.set(0);
        KEYN.clear();
    }
}
