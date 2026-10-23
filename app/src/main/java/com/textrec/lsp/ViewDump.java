package com.textrec.lsp;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 视图树导出（1.27.0，目标进程里用）：在任务开始 / 任务结束 / 记录页点「标记」的那一刻，找到屏幕上的「文案」控件
 * （类名或 id 里包含 viewDumpKey 里任意一个词），沿父级往上走 viewDumpUp 层，把那一层的整棵子树
 * （往下最多 viewDumpDepth 层、viewDumpLines 行）列出来：类名、id、可见性、窗口坐标、布局参数、文字。
 * 目的：弄清「文案 / 作者名 / 作者声明 / 合集条 / 进度条」是不是在同一个容器里、这个容器是什么布局，
 * 才知道往哪里插一行才不受文案长短影响。
 *
 * 只读：不改任何 View，不 invalidate，不写属性。没有定时器：只在上面三种事件里抓一次，
 * 用 View.post 回到界面线程（遍历必须在界面线程），最多留最近 MAX_KEEP 份（文本每份有行数上限）。
 */
final class ViewDump {
    private ViewDump() {}

    private static final int MAX_KEEP = 16;
    private static final int TEXT_CUT = 24;
    private static final int[] LOC = new int[2];
    private static final Rect RECT = new Rect();

    private static final int MAX_TAPS = 12;

    private static final class Dump {
        final long t;
        final String reason, page, text, kind;

        Dump(long t, String reason, String page, String text, String kind) {
            this.t = t;
            this.reason = reason;
            this.page = page;
            this.text = text;
            this.kind = kind;
        }
    }

    private static final ArrayList<Dump> KEEP = new ArrayList<>();
    private static final ArrayList<Dump> TAPS = new ArrayList<>();

    // ------------------------------------------------------------ 自动抓（viewDumpAuto）

    private static final int MAX_AUTO = 8;
    private static final HashSet<String> SEEN_SHAPES = new HashSet<>();
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean> KEY_CLS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 文案控件设了文字时调（Recorder.onSetText）：它是「主角」类（viewDumpKey）、已经挂在窗口里，就把它往上到窗口根的类名链当作结构，
     * 这种结构没抓过就自动抓一份视图树。结构只看类名（不含下标 / id），同一种只抓一次；最多自动抓 MAX_AUTO 份。
     * 只在界面线程做，没挂上窗口的（还在创建、没有父级）直接返回，等下一次设文字再看。
     */
    static void onCaptionSet(TextView v) {
        try {
            RecConfig c = Recorder.cfg;
            if (c == null || !c.enabled || !c.viewDump || !c.viewDumpAuto) return;
            if (Looper.myLooper() != Looper.getMainLooper() || !v.isAttachedToWindow()) return;
            Class<?> k = v.getClass();
            Boolean isKey = KEY_CLS.get(k);
            if (isKey == null) {
                String n = k.getName().toLowerCase(Locale.ROOT);
                boolean m = false;
                for (String key : splitKeys(c.viewDumpKey)) {
                    if (n.contains(key)) {
                        m = true;
                        break;
                    }
                }
                isKey = m;
                KEY_CLS.put(k, isKey);
            }
            if (!isKey) return;
            StringBuilder sb = new StringBuilder(160);
            sb.append(k.getSimpleName());
            ViewParent p = v.getParent();
            for (int d = 0; d < 40 && p != null; d++) {
                sb.append('<').append(p.getClass().getSimpleName());
                p = p.getParent();
            }
            String shape = sb.toString();
            synchronized (SEEN_SHAPES) {
                if (SEEN_SHAPES.size() >= MAX_AUTO || !SEEN_SHAPES.add(shape)) return;
            }
            int cut = 0;
            for (int i = 0, n = 0; i < shape.length() && n < 7; i++) {
                if (shape.charAt(i) == '<' && ++n == 7) cut = i;
            }
            request("新结构 " + (cut > 0 ? shape.substring(0, cut) + "…" : shape));
        } catch (Throwable ignored) {
        }
    }

    /** 任何线程都可以调：开关没开直接返回；开了就切到界面线程抓一份。 */
    static void request(final String reason) {
        try {
            final RecConfig c = Recorder.cfg;
            if (c == null || !c.enabled || !c.viewDump) return;
            Activity a = Recorder.topActivity();
            if (a == null || a.isFinishing() || a.isDestroyed()) return;
            final View decor = a.getWindow().getDecorView();
            final long t = SystemClock.uptimeMillis() - Timeline.START;
            final String page = Recorder.pageName;
            decor.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        String text = build(decor, c);
                        synchronized (KEEP) {
                            KEEP.add(new Dump(t, reason, page, text, "tree"));
                            while (KEEP.size() > MAX_KEEP) KEEP.remove(0);
                        }
                        Recorder.store.markDirty();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 快照里的 viewDumps 数组。 */
    static JSONArray toJson() {
        JSONArray arr = new JSONArray();
        ArrayList<Dump> all = new ArrayList<>();
        synchronized (KEEP) {
            all.addAll(KEEP);
            all.addAll(TAPS);
        }
        java.util.Collections.sort(all, new java.util.Comparator<Dump>() {
            @Override
            public int compare(Dump a, Dump b) {
                return a.t < b.t ? -1 : a.t > b.t ? 1 : 0;
            }
        });
        for (Dump d : all) {
            try {
                JSONObject o = new JSONObject();
                o.put("t", d.t);
                o.put("reason", d.reason);
                o.put("page", d.page);
                o.put("text", d.text);
                o.put("kind", d.kind);
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        return arr;
    }

    // ------------------------------------------------------------ 点哪查哪

    private static final AtomicBoolean TOUCH = new AtomicBoolean();
    private static float downX, downY;
    private static long downT;

    /** 开了 viewTap 才装（在 Activity.onResume 里调）；钩子里平时只记按下的位置，抬起且位置没动才抓。 */
    static void ensureTouchHook() {
        try {
            RecConfig c = Recorder.cfg;
            if (c == null || !c.enabled || !c.viewTap || TOUCH.get()) return;
            Method m = Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            if (!TOUCH.compareAndSet(false, true)) return;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        onTouch((Activity) p.thisObject, (MotionEvent) p.args[0]);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            TOUCH.set(false);
            XposedBridge.log("[TextRec] viewTap touch hook failed: " + t);
        }
    }

    private static void onTouch(Activity a, MotionEvent e) {
        RecConfig c = Recorder.cfg;
        if (c == null || !c.enabled || !c.viewTap) return;
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            downX = e.getRawX();
            downY = e.getRawY();
            downT = SystemClock.uptimeMillis();
        } else if (act == MotionEvent.ACTION_UP) {
            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
            if (dx * dx + dy * dy <= 40f * 40f && SystemClock.uptimeMillis() - downT < 800) tap(a, e.getRawX(), e.getRawY(), c);
        }
    }

    private static void tap(Activity a, float rx, float ry, RecConfig c) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        try {
            final View decor = a.getWindow().getDecorView();
            final long t = SystemClock.uptimeMillis() - Timeline.START;
            final String page = Recorder.pageName;
            final int x = Math.round(rx), y = Math.round(ry);
            String text = buildTap(decor, x, y, c); // 在界面线程的 dispatchTouchEvent 里，趁点击效果还没发生，直接抓
            synchronized (KEEP) {
                TAPS.add(new Dump(t, "点击 (" + x + "," + y + ")", page, text, "tap"));
                while (TAPS.size() > MAX_TAPS) TAPS.remove(0);
            }
            Recorder.store.markDirty();
        } catch (Throwable ignored) {
        }
    }

    private static String buildTap(View decor, int x, int y, RecConfig c) {
        ArrayList<View> hits = new ArrayList<>();
        ArrayList<Integer> dep = new ArrayList<>();
        hitCollect(decor, x, y, 0, hits, dep, new int[]{0});
        StringBuilder sb = new StringBuilder(4096);
        sb.append("这个点上命中 ").append(hits.size()).append(" 个可见控件（外层 → 内层、同级按先后；dN = 嵌套层数；◀ = 最后一个，通常是最上层）。\n")
                .append("每行：dN 类 #id 可见性 窗口坐标 宽x高 布局参数 文字。坐标是窗口内坐标（和视图树里的一致）。\n");
        int[] left = {c.viewDumpLines};
        for (int i = 0; i < hits.size() && left[0] > 0; i++) {
            left[0]--;
            sb.append('d').append(dep.get(i)).append(' ').append(describe(hits.get(i)));
            if (i == hits.size() - 1) sb.append("  ◀");
            sb.append('\n');
            String sn = setNote(hits.get(i), c);
            if (!sn.isEmpty() && left[0] > 0) {
                left[0]--;
                sb.append("     ↳ ").append(sn).append('\n');
            }
            String ln = c.annotFrames ? Annot.listenerNote(hits.get(i)) : "";
            if (!ln.isEmpty() && left[0] > 0) {
                left[0]--;
                sb.append("     ↳ ").append(ln).append('\n');
            }
        }
        if (c.annotFrames) { // 点击入口：最上层（最后命中）的、有点击监听器的控件
            for (int i = hits.size() - 1; i >= 0; i--) {
                if (Annot.hasClickListener(hits.get(i))) {
                    sb.append("▶ 点击入口候选：").append(describe(hits.get(i))).append("\n     ").append(Annot.listenerNote(hits.get(i))).append('\n');
                    break;
                }
            }
        }
        if (left[0] <= 0) sb.append("…（行数到上限，可调大 viewDumpLines）\n");
        return sb.toString();
    }

    private static void hitCollect(View v, int x, int y, int depth, ArrayList<View> out, ArrayList<Integer> dep, int[] seen) {
        if (v == null || seen[0]++ > 8000 || v.getVisibility() != View.VISIBLE) return;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        if (x < loc[0] || y < loc[1] || x >= loc[0] + v.getWidth() || y >= loc[1] + v.getHeight()) return;
        out.add(v);
        dep.add(depth);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) hitCollect(g.getChildAt(i), x, y, depth + 1, out, dep, seen);
        }
    }

    /** 文字控件最近一次 setText 的调用栈前几帧；没开 / 没记到返回空串。 */
    private static String setNote(View v, RecConfig c) {
        try {
            if (!c.viewDumpSet || !(v instanceof TextView)) return "";
            CharSequence tx = ((TextView) v).getText();
            if (tx == null || tx.length() == 0) return "";
            SetStack ss = Recorder.setStackOf((TextView) v);
            if (ss == null || ss.stk == null || ss.stk.lines == null || ss.stk.lines.length == 0) return "";
            StringBuilder sb = new StringBuilder("设置自：");
            int n = Math.min(4, ss.stk.lines.length);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(" ← ");
                String l = ss.stk.lines[i];
                sb.append(l.length() > 110 ? l.substring(0, 110) + "…" : l);
            }
            sb.append("  （").append(ss.count.get()).append(" 次，线程 ").append(ss.thread).append("）");
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------ 抓取

    private static String build(View decor, RecConfig c) {
        String[] keys = splitKeys(c.viewDumpKey);
        ArrayList<View> hits = new ArrayList<>();
        collect(decor, keys, hits, new int[]{0});
        StringBuilder sb = new StringBuilder(4096);
        if (hits.isEmpty()) {
            sb.append("没有找到类名或 id 含「").append(c.viewDumpKey).append("」的控件（屏幕上没有、或者词写得不对）\n");
            return sb.toString();
        }
        // 屏幕上可见面积最大的那个当主角；其余命中的只列一行
        View main = null;
        int best = -1;
        for (View v : hits) {
            int area = v.getGlobalVisibleRect(RECT) ? RECT.width() * RECT.height() : 0;
            if (area > best) {
                best = area;
                main = v;
            }
        }
        sb.append("命中 ").append(hits.size()).append(" 个控件，主角 = 屏幕上可见面积最大的（").append(Math.max(best, 0)).append(" px²）\n");
        if (hits.size() > 1) {
            for (View v : hits) if (v != main) sb.append("  其余命中：").append(brief(v)).append('\n');
        }
        // 从主角往上的父级链（一直到窗口根，只写类名和布局类型）
        sb.append("父级链（下 → 上）：\n");
        View cur = main;
        int lv = 0;
        View anchor = null;
        while (cur != null) {
            sb.append("  ").append(lv == 0 ? "◆ " : "↑").append(lv).append(' ').append(brief(cur)).append('\n');
            if (lv == c.viewDumpUp) anchor = cur; // 0 = 主角自己
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
            lv++;
            if (lv > 40) break;
        }
        if (anchor == null) {
            // 层数比链还长：取链上最高的 ViewGroup
            cur = main;
            while (cur != null) {
                if (cur instanceof ViewGroup) anchor = cur;
                ViewParent p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
        }
        if (anchor == null) return sb.toString();
        sb.append("\n从上 ").append(c.viewDumpUp).append(" 层的容器往下列（深度 ").append(c.viewDumpDepth)
                .append("，最多 ").append(c.viewDumpLines).append(" 行；◀ = 主角；每行：[子序号] 类 #id 可见性 窗口坐标 宽x高 布局参数 文字）：\n");
        int[] left = {c.viewDumpLines};
        tree(anchor, main, 0, c.viewDumpDepth, sb, left, -1);
        if (left[0] <= 0) sb.append("…（行数到上限，剩下的没列；可调大 viewDumpLines，或调小 viewDumpDepth / viewDumpUp）\n");
        return sb.toString();
    }

    private static String[] splitKeys(String s) {
        ArrayList<String> l = new ArrayList<>();
        if (s != null) {
            for (String t : s.split("[,\\n\\r]+")) {
                t = t.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) l.add(t);
            }
        }
        return l.toArray(new String[0]);
    }

    private static void collect(View v, String[] keys, ArrayList<View> out, int[] seen) {
        if (v == null || seen[0]++ > 8000 || out.size() >= 12) return;
        if (v.getVisibility() == View.VISIBLE && matches(v, keys)) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) collect(g.getChildAt(i), keys, out, seen);
        }
    }

    private static boolean matches(View v, String[] keys) {
        if (keys.length == 0) return false;
        String cn = v.getClass().getName().toLowerCase(Locale.ROOT);
        String id = idName(v).toLowerCase(Locale.ROOT);
        for (String k : keys) if (cn.contains(k) || id.contains(k)) return true;
        return false;
    }

    private static void tree(View v, View main, int depth, int maxDepth, StringBuilder sb, int[] left, int index) {
        if (left[0] <= 0) return;
        left[0]--;
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(index >= 0 ? "[" + index + "] " : "").append(describe(v));
        if (v == main) sb.append("  ◀");
        sb.append('\n');
        String sn = setNote(v, Recorder.cfg);
        if (!sn.isEmpty() && left[0] > 0) {
            left[0]--;
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append("    ↳ ").append(sn).append('\n');
        }
        if (v instanceof ViewGroup && depth < maxDepth) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) tree(g.getChildAt(i), main, depth + 1, maxDepth, sb, left, i);
        } else if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0) {
            for (int i = 0; i <= depth; i++) sb.append("  ");
            sb.append("（还有 ").append(((ViewGroup) v).getChildCount()).append(" 个子控件没展开，深度到了）\n");
        }
    }

    // ------------------------------------------------------------ 描述一个控件

    private static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return "";
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable e) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private static String idOf(View root, int id) {
        if (id <= 0) return "";
        try {
            return root.getResources().getResourceEntryName(id);
        } catch (Throwable e) {
            return "0x" + Integer.toHexString(id);
        }
    }

    /** 一行简介：类名 #id 可见性。 */
    private static String brief(View v) {
        String id = idName(v);
        return v.getClass().getName() + (id.isEmpty() ? "" : " #" + id) + " " + vis(v)
                + (v instanceof ViewGroup ? " " + layoutKind((ViewGroup) v) : "");
    }

    private static String vis(View v) {
        int s = v.getVisibility();
        return s == View.VISIBLE ? "V" : s == View.INVISIBLE ? "I(占位隐藏)" : "G(不占位)";
    }

    private static String layoutKind(ViewGroup g) {
        if (g instanceof LinearLayout) return "LinearLayout[" + (((LinearLayout) g).getOrientation() == LinearLayout.VERTICAL ? "竖" : "横") + "]";
        return "";
    }

    private static String describe(View v) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(v.getClass().getName());
        String id = idName(v);
        if (!id.isEmpty()) sb.append(" #").append(id);
        sb.append(' ').append(vis(v));
        if (v.getAlpha() < 0.99f) sb.append(" α=").append(String.format(Locale.US, "%.2f", v.getAlpha()));
        try {
            v.getLocationInWindow(LOC);
            sb.append(" @(").append(LOC[0]).append(',').append(LOC[1]).append(") ").append(v.getWidth()).append('x').append(v.getHeight());
        } catch (Throwable ignored) {
        }
        if (v instanceof ViewGroup) {
            String k = layoutKind((ViewGroup) v);
            if (!k.isEmpty()) sb.append(' ').append(k);
            sb.append(" 子").append(((ViewGroup) v).getChildCount());
        }
        sb.append(layoutParams(v));
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            CharSequence tx = t.getText();
            sb.append(" 行数").append(t.getLineCount()).append("/最多").append(t.getMaxLines() == Integer.MAX_VALUE ? "∞" : String.valueOf(t.getMaxLines()));
            if (t.getEllipsize() != null) sb.append(" 省略").append(t.getEllipsize());
            if (tx != null && tx.length() > 0) {
                String s = tx.toString().replace('\n', '⏎');
                sb.append(" 「").append(s.length() > TEXT_CUT ? s.substring(0, TEXT_CUT) + "…" : s).append("」");
            }
        }
        return sb.toString();
    }

    /** 布局参数：宽高、边距、权重 / 重力；约束布局和相对布局把「挂在哪个控件上」的规则列出来。 */
    private static String layoutParams(View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return "";
        StringBuilder sb = new StringBuilder(80);
        sb.append(" lp[").append(dim(lp.width)).append('x').append(dim(lp.height));
        try {
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                if (m.leftMargin != 0 || m.topMargin != 0 || m.rightMargin != 0 || m.bottomMargin != 0)
                    sb.append(" 边距").append(m.leftMargin).append(',').append(m.topMargin).append(',').append(m.rightMargin).append(',').append(m.bottomMargin);
            }
            if (lp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) lp;
                if (l.weight != 0f) sb.append(" 权重").append(l.weight);
                if (l.gravity != -1) sb.append(" 重力").append(l.gravity);
            }
            String cn = lp.getClass().getName();
            if (cn.contains("FrameLayout")) {
                Field g = lp.getClass().getField("gravity");
                int gv = g.getInt(lp);
                if (gv != -1) sb.append(" 重力").append(gv);
            } else if (cn.contains("RelativeLayout")) {
                int[] rules = (int[]) lp.getClass().getMethod("getRules").invoke(lp);
                String[] names = {"leftOf", "rightOf", "above", "below", "alignBaseline", "alignLeft", "alignTop", "alignRight", "alignBottom",
                        "alignParentLeft", "alignParentTop", "alignParentRight", "alignParentBottom", "centerInParent", "centerHorizontal", "centerVertical",
                        "startOf", "endOf", "alignStart", "alignEnd", "alignParentStart", "alignParentEnd"};
                for (int i = 0; i < rules.length && i < names.length; i++) {
                    if (rules[i] == 0) continue;
                    sb.append(' ').append(names[i]);
                    if (rules[i] != -1) sb.append('=').append(idOf(v, rules[i]));
                }
            } else if (cn.contains("ConstraintLayout")) {
                for (Field f : lp.getClass().getFields()) {
                    String n = f.getName();
                    if (f.getType() != int.class || !n.matches(".*To(Top|Bottom|Left|Right|Start|End|Baseline)")) continue;
                    int val = f.getInt(lp);
                    if (val == -1) continue; // UNSET
                    sb.append(' ').append(n).append('=').append(val == 0 ? "parent" : idOf(v, val));
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.append(']').toString();
    }

    private static String dim(int d) {
        return d == ViewGroup.LayoutParams.MATCH_PARENT ? "match" : d == ViewGroup.LayoutParams.WRAP_CONTENT ? "wrap" : String.valueOf(d);
    }
}
