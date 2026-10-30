package com.textrec.lsp;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 视图快照（viewProbe，1.35.0，目标进程里用）：每次点击之后，隔 probeAfterMs 里的各个毫秒，对 viewProbe 里列出的类的实例各拍一份「此刻真实状态」，
 * 另外在点击事件处理之前自动多拍一张「点击前」。用来回答「那个控件在某一刻到底是什么状态」：位置 / 大小 / alpha / 缩放 / 平移 / 可见性 / isSelected /
 * isShown、前景和背景、ImageView 的图片和 imageAlpha、父级链前 3 层的类名 / 可见性 / alpha / clipChildren；
 * 每张快照里同一个控件相对上一张变了什么（如 imageAlpha 255→0）会标出来；某个类没找到实例也会写出来（那本身就是证据）。
 *
 * 开销：viewProbe 为空时完全没有额外开销（不装钩子；已经装了 viewTap 的钩子时，每次触摸只多一次字符串长度判断）。
 * 写了 viewProbe 才会：触摸钩子里每次点击（抬起且位置没动）做 1 + 时间点个数 次一次性遍历（最多 8000 个节点），
 * 都在界面线程，用 View.postDelayed 排到点击之后，没有定时器、不是每帧。任务外的点击不拍（遵守任务窗口，和 flowGate 一致）。
 * 只读：不改任何 View，不 invalidate，不写属性。
 */
final class ViewProbe {
    private ViewProbe() {}

    private static final int MAX_GROUPS = 8;
    private static final int MAX_NODES = 8000;
    private static final int PER_CLASS = 6;
    private static final int TOTAL = 16;
    private static final int TEXT_CAP = 40000;
    private static final int[] LOC = new int[2];

    /** 一次点击 = 一组快照。 */
    private static final class Group {
        final long t, tapAt;
        final String page;
        final int x, y;
        final List<String> names;
        final HashSet<String> nameSet;
        final int[] times;
        final HashMap<Class<?>, String> cache = new HashMap<>();
        final ArrayList<String> shots = new ArrayList<>();
        HashMap<String, LinkedHashMap<String, String>> prev = new HashMap<>();
        int len;
        boolean dead;

        Group(long t, long tapAt, String page, int x, int y, List<String> names, int[] times) {
            this.t = t;
            this.tapAt = tapAt;
            this.page = page;
            this.x = x;
            this.y = y;
            this.names = names;
            this.nameSet = new HashSet<>(names);
            this.times = times;
        }
    }

    private static final ArrayList<Group> GROUPS = new ArrayList<>();

    private static String lastNamesSrc, lastTimesSrc;
    private static List<String> lastNames;
    private static int[] lastTimes;

    /** 写了 viewProbe（才有开销）。热路径：只做空判断和长度判断。 */
    static boolean active(RecConfig c) {
        return c != null && c.enabled && c.viewProbe != null && c.viewProbe.length() > 0;
    }

    static void clear() {
        synchronized (GROUPS) {
            for (Group g : GROUPS) g.dead = true;
            GROUPS.clear();
        }
    }

    /** 在界面线程的 dispatchTouchEvent 里调（点击已经判定：抬起且位置没动），此刻点击事件还没处理。 */
    static void onTap(Activity a, float rx, float ry, RecConfig c) {
        try {
            if (!active(c) || a == null || a.isFinishing() || a.isDestroyed()) return;
            if (Timeline.skip()) return; // 任务外 / 暂停 / 浮窗关闭后：不拍
            if (!c.viewProbe.equals(lastNamesSrc)) {
                lastNames = ProbeText.splitClasses(c.viewProbe);
                lastNamesSrc = c.viewProbe;
            }
            if (lastNames.isEmpty()) return;
            if (c.probeAfterMs == null || !c.probeAfterMs.equals(lastTimesSrc)) {
                lastTimes = ProbeText.parseTimes(c.probeAfterMs);
                lastTimesSrc = c.probeAfterMs;
            }
            final View decor = a.getWindow().getDecorView();
            final long now = SystemClock.uptimeMillis();
            final int x = Math.round(rx), y = Math.round(ry);
            final Group g = new Group(now - Timeline.START, now, Recorder.pageName, x, y, new ArrayList<>(lastNames), lastTimes);
            synchronized (GROUPS) {
                GROUPS.add(g);
                while (GROUPS.size() > MAX_GROUPS) GROUPS.remove(0).dead = true;
            }
            StringBuilder tm = new StringBuilder();
            for (int ms : g.times) tm.append(tm.length() > 0 ? "," : "").append(ms);
            Timeline.add("MARK", "视图快照 点击 (" + x + "," + y + ") 点击前 + " + tm + "ms");
            capture(decor, g, "点击前（点击事件处理之前）");
            for (final int ms : g.times) {
                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        capture(decor, g, ms == 0 ? "+0ms（点击处理完的那一刻）" : "+" + ms + "ms");
                    }
                };
                if (ms <= 0) decor.post(r);
                else decor.postDelayed(r, ms);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 拍一张

    private static void capture(View decor, Group g, String plan) {
        try {
            if (g.dead) return;
            long dt = SystemClock.uptimeMillis() - g.tapAt;
            StringBuilder sb = new StringBuilder(2048);
            sb.append("── ").append(plan);
            if (!plan.startsWith("点击前")) sb.append("（实际 +").append(dt).append("ms）");
            sb.append(" ──\n");
            boolean first;
            synchronized (g) {
                first = g.shots.isEmpty();
            }
            if (!decor.isAttachedToWindow()) {
                sb.append("（窗口已经不在了：页面切走或被销毁）\n");
            } else {
                HashMap<String, ArrayList<View>> found = new HashMap<>();
                HashMap<String, Integer> omitted = new HashMap<>();
                walk(decor, g, found, omitted, new int[]{0}, new int[]{0});
                HashMap<String, LinkedHashMap<String, String>> cur = new HashMap<>();
                for (String name : g.names) {
                    ArrayList<View> vs = found.get(name);
                    if (vs == null || vs.isEmpty()) {
                        sb.append("未找到实例：").append(name).append('\n');
                        continue;
                    }
                    for (View v : vs) {
                        LinkedHashMap<String, String> st = stateOf(v, name);
                        String key = Integer.toHexString(System.identityHashCode(v)) + "@" + name;
                        cur.put(key, st);
                        sb.append("  ").append(ProbeText.line(st)).append('\n');
                        String par = ProbeText.parents(st);
                        if (!par.isEmpty()) sb.append("    ").append(par).append('\n');
                        if (!first) {
                            LinkedHashMap<String, String> pv = g.prev.get(key);
                            if (pv == null) sb.append("    Δ 新出现（上一张里没有这个实例）\n");
                            else {
                                String d = ProbeText.diff(pv, st);
                                sb.append("    Δ ").append(d.isEmpty() ? "无变化" : d).append('\n');
                            }
                        }
                    }
                    Integer om = omitted.get(name);
                    if (om != null && om > 0) sb.append("  …另有 ").append(om).append(" 个同类实例没列（每个类最多 ").append(PER_CLASS).append(" 个、合计 ").append(TOTAL).append(" 个）\n");
                }
                if (!first) {
                    for (java.util.Map.Entry<String, LinkedHashMap<String, String>> e : g.prev.entrySet()) {
                        if (!cur.containsKey(e.getKey())) sb.append("  ✗ 上一张有、这一张没有：").append(e.getValue().get("cls")).append('\n');
                    }
                }
                g.prev = cur;
            }
            synchronized (g) {
                if (g.len + sb.length() <= TEXT_CAP) {
                    g.shots.add(sb.toString());
                    g.len += sb.length();
                } else {
                    g.shots.add("── " + plan + " ──\n（这组快照文字超过 " + TEXT_CAP + " 字，这一张没记）\n");
                }
            }
            Recorder.store.markDirty();
        } catch (Throwable ignored) {
        }
    }

    private static String matchedName(View v, Group g) {
        Class<?> k = v.getClass();
        String c = g.cache.get(k);
        if (c == null) {
            ArrayList<String> chain = new ArrayList<>();
            int depth = 0;
            for (Class<?> x = k; x != null && depth < 12; x = x.getSuperclass(), depth++) chain.add(x.getName());
            c = ProbeText.matchChain(chain, g.nameSet);
            if (c == null) c = "";
            g.cache.put(k, c);
        }
        return c.isEmpty() ? null : c;
    }

    private static void walk(View v, Group g, HashMap<String, ArrayList<View>> found, HashMap<String, Integer> omitted, int[] seen, int[] total) {
        if (v == null || seen[0]++ > MAX_NODES) return;
        String hit = matchedName(v, g);
        if (hit != null) {
            ArrayList<View> l = found.get(hit);
            if (l == null) {
                l = new ArrayList<>();
                found.put(hit, l);
            }
            if (l.size() < PER_CLASS && total[0] < TOTAL) {
                l.add(v);
                total[0]++;
            } else {
                Integer o = omitted.get(hit);
                omitted.put(hit, o == null ? 1 : o + 1);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) walk(vg.getChildAt(i), g, found, omitted, seen, total);
        }
    }

    // ------------------------------------------------------------ 控件的真实状态

    private static String f2(float f) {
        return String.format(Locale.US, "%.2f", f);
    }

    private static String drawableDesc(Drawable d) {
        StringBuilder sb = new StringBuilder(d.getClass().getName());
        try {
            Rect b = d.getBounds();
            sb.append('[').append(b.left).append(',').append(b.top).append(',').append(b.right).append(',').append(b.bottom).append(']');
            if (!d.isVisible()) sb.append(" 不可见");
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static LinkedHashMap<String, String> stateOf(View v, String matched) {
        LinkedHashMap<String, String> m = ProbeText.map();
        String cn = v.getClass().getName();
        m.put("cls", cn + "@" + Integer.toHexString(System.identityHashCode(v)) + (cn.equals(matched) ? "" : "（匹配父类 " + matched + "）"));
        try {
            String id = ViewDump.idName(v);
            if (!id.isEmpty()) m.put("id", "#" + id);
        } catch (Throwable ignored) {
        }
        boolean attached = v.isAttachedToWindow();
        m.put("attached", String.valueOf(attached));
        m.put("shown", String.valueOf(v.isShown()));
        m.put("vis", ViewDump.vis(v));
        try {
            if (attached) {
                v.getLocationInWindow(LOC);
                m.put("pos", "@(" + LOC[0] + "," + LOC[1] + ") " + v.getWidth() + "x" + v.getHeight());
            } else {
                m.put("pos", v.getWidth() + "x" + v.getHeight());
            }
        } catch (Throwable ignored) {
        }
        m.put("alpha", f2(v.getAlpha()));
        m.put("scale", f2(v.getScaleX()) + "," + f2(v.getScaleY()));
        m.put("trans", f2(v.getTranslationX()) + "," + f2(v.getTranslationY()));
        m.put("selected", String.valueOf(v.isSelected()));
        try {
            Drawable fg = v.getForeground();
            m.put("fg", fg == null ? "无" : drawableDesc(fg));
        } catch (Throwable ignored) {
        }
        try {
            Drawable bg = v.getBackground();
            m.put("bg", bg == null ? "无" : bg.getClass().getName());
        } catch (Throwable ignored) {
        }
        if (v instanceof ImageView) {
            try {
                ImageView iv = (ImageView) v;
                Drawable d = iv.getDrawable();
                m.put("img", d == null ? "无" : drawableDesc(d));
                m.put("imageAlpha", String.valueOf(iv.getImageAlpha()));
                if (d != null) m.put("drawAlpha", String.valueOf(d.getAlpha()));
            } catch (Throwable ignored) {
            }
        }
        ViewParent p = v.getParent();
        for (int i = 1; i <= 3 && p instanceof View; i++) {
            View pv = (View) p;
            StringBuilder sb = new StringBuilder(pv.getClass().getName());
            sb.append(" vis=").append(ViewDump.vis(pv)).append(" α=").append(f2(pv.getAlpha()));
            if (pv instanceof ViewGroup) sb.append(" clip=").append(((ViewGroup) pv).getClipChildren());
            m.put("p" + i, sb.toString());
            p = pv.getParent();
        }
        return m;
    }

    // ------------------------------------------------------------ 导出

    /** 把每组快照写进 viewDumps 数组（kind = probe），和视图树 / 点击放在同一节、按时间排。 */
    static void addTo(ArrayList<JSONObject> out) {
        synchronized (GROUPS) {
            for (Group g : GROUPS) {
                try {
                    StringBuilder sb = new StringBuilder();
                    StringBuilder tm = new StringBuilder();
                    for (int ms : g.times) tm.append(tm.length() > 0 ? "、" : "").append(ms);
                    sb.append("监视类：");
                    for (int i = 0; i < g.names.size(); i++) sb.append(i > 0 ? "、" : "").append(g.names.get(i));
                    sb.append("\n时间点：点击前、点击后 ").append(tm).append(" ms。每行一个控件；Δ = 和上一张快照里同一个控件相比变了什么（—→x 表示这个字段是新出现的）；“未找到实例”表示那一刻视图树里没有这个类的控件。\n");
                    int got;
                    synchronized (g) {
                        got = g.shots.size();
                        for (String s : g.shots) sb.append(s);
                    }
                    int plan = 1 + g.times.length;
                    if (got < plan) sb.append("…还有 ").append(plan - got).append(" 张没拍到（点击后不到 ").append(g.times[g.times.length - 1]).append("ms 就导出了，或页面已经切走）\n");
                    JSONObject o = new JSONObject();
                    o.put("t", g.t);
                    o.put("reason", "视图快照 点击 (" + g.x + "," + g.y + ")");
                    o.put("page", g.page);
                    o.put("text", sb.toString());
                    o.put("kind", "probe");
                    out.add(o);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
