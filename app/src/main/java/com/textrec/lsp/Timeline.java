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
    private static final int KIND_BUDGET = 150; // 每种事件、每个任务阶段各自 150 种不同的（见 addAt），一个阶段灌满不影响别的阶段
    private static final java.util.concurrent.ConcurrentHashMap<String, Ev> AGG = new java.util.concurrent.ConcurrentHashMap<>();
    static volatile int curTask;
    /** 任务浮窗的流程进行中（收到「任务流程开始」到「任务全部完成 / 任务中止 / 浮窗关闭」）。没用浮窗时一直是 false，记录照旧全记。 */
    static volatile boolean flowActive;
    /** 配置：任务外也记录（默认关） */
    static volatile boolean recOutside;

    /**
     * 这次调用要不要跳过“建记录 / 记样本”（轻模式）：暂停记录期间，或任务流程进行中、现在不在任务里、而且没开「任务外也记录」。
     * 轻模式下 setText 仍然维护“控件 ← 设置栈”的对应（不然任务里画出来的文字查不到是谁设置的），其余一律不记。
     */
    static boolean skip() {
        return paused || (flowActive && curTask == 0 && !recOutside);
    }

    /** 记录归哪一桶：0 = 任务内（含没用任务浮窗时的全部记录），1 = 任务外（任务流程进行中、现在不在任务里，只有开了「任务外也记录」才会走到这里）。 */
    static int bucket() {
        return flowActive && curTask == 0 ? 1 : 0;
    }
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
        volatile int n = 1;        // 完全相同的事件出现了几次（合并计数，不占额度）
        volatile long lastT;

        Ev(String kind, String text, long t, long wall) {
            this.seq = SEQ.incrementAndGet();
            this.t = t;
            this.wall = wall;
            this.kind = kind;
            this.text = text;
            this.page = Recorder.pageName;
            this.tag = Timeline.tag;
            this.lastT = t;
        }
    }

    static volatile boolean fClass = true, fTouch = true, fKey = true, fPage = true, fTree = true, sText = true, sState = true, sIcon = true, sTrace = true;

    static void configure(RecConfig c) {
        on = c.tlOn;
        recOutside = c.recOutside;
        ops = c.tlOn && c.tlOps;
        samples = c.tlOn && c.tlSamples;
        fClass = c.tlClass;
        fTouch = c.tlTouch;
        fKey = c.tlKey;
        fPage = c.tlPage;
        fTree = c.tlViewTree;
        sText = c.smpText;
        sState = c.smpState;
        sIcon = c.smpIcon;
        sTrace = c.smpTrace;
        if (ops) install();
    }

    static void add(String kind, String text) {
        addAt(kind, text, SystemClock.uptimeMillis(), System.currentTimeMillis());
    }

    /** 指定发生时间（标记从 TextRec 经文件传来有延迟，按发送时刻往回推）。 */
    static void addAt(String kind, String text, long t, long wall) {
        if (!on) return;
        if (!fClass && "CLASS".equals(kind)) return;
        if (!fTouch && "TOUCH".equals(kind) || !fKey && "KEY".equals(kind) || !fPage && "PAGE".equals(kind)
                || !fTree && "VIEWTREE".equals(kind) || !sText && ("SETTEXT".equals(kind) || "CLEAR".equals(kind))
                || !sState && "STATE".equals(kind) || !sIcon && "ICON".equals(kind) || !sTrace && "TRACE".equals(kind)) return;
        if (skip() && !("MARK".equals(kind) || "BASE".equals(kind) || "TASK".equals(kind) || "CLASS".equals(kind) || "PAGE".equals(kind))) return;
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
            // 采样事件：相同的合并计数（不占额度）；不同的按「种类 + 任务阶段」各记前 KIND_BUDGET 种，
            // 超出的只计数并在导出里写明。这样第 1 个任务灌满了也不会把第 5 个任务挤掉。
            String body = tx.startsWith("(第") && tx.indexOf(") ") > 0 ? tx.substring(tx.indexOf(") ") + 2) : tx;
            int seg = curTask;
            String key = kind + "#" + seg + "#" + body;
            Ev old = AGG.get(key);
            if (old != null) {
                old.n++;
                old.lastT = t;
                return;
            }
            String bk = kind + "#" + seg;
            int[] n = KIND_N.get(bk);
            if (n == null) {
                n = new int[1];
                int[] o2 = KIND_N.putIfAbsent(bk, n);
                if (o2 != null) n = o2;
            }
            if (++n[0] > KIND_BUDGET) {
                int[] d = KIND_DROP.get(bk);
                if (d == null) {
                    d = new int[1];
                    int[] o3 = KIND_DROP.putIfAbsent(bk, d);
                    if (o3 != null) d = o3;
                }
                d[0]++;
                return;
            }
            Ev e = new Ev(kind, tx, t, wall);
            if (AGG.size() > 30000) AGG.clear();
            AGG.put(key, e);
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

    private static volatile java.io.File markerFile;
    private static long markerOff = -1;
    private static volatile long baseUntil;   // 基线自动结束的单调时刻；0 = 等“基线结束”标记

    /**
     * 标记文件 markers.log 是追加的：一行一条「序号|发送时刻毫秒|内容」。
     * 以前是覆盖同一个文件，目标进程读慢了就会漏掉中间几条（真机上主进程只收到第一条）；
     * 现在记住读到哪里，每次把新增的行全读走；再加一个每 200ms 查一次文件长度的小线程，不单靠文件监听（监听线程可能被目标 App 自己的监听堵住）。
     */
    static void startMarkers(java.io.File dir) {
        if (markerFile != null) return;
        final java.io.File f = new java.io.File(dir, "markers.log");
        markerFile = f;
        markerOff = 0;
        replayEarlier(f); // 目标进程是在标记之后才启动的（比如冷启动任务）：把这一轮已经发出的标记补进来
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(200);
                        pollMarkers();
                        checkBaselineEnd();
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "TextRec-marker");
        t.setDaemon(true);
        t.start();
    }

    /** 从最近一条「任务流程开始」开始补读（5 分钟内发出的才算，免得读到上一轮的）；之后的新增行照常读。 */
    private static void replayEarlier(java.io.File f) {
        try {
            if (!f.exists() || f.length() == 0) return;
            long len = f.length();
            byte[] b = new byte[(int) Math.min(len, 64 * 1024)];
            try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(f, "r")) {
                in.seek(len - b.length);
                in.readFully(b);
            }
            markerOff = len;
            String all = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = all.split("\n");
            int from = -1;
            long now = System.currentTimeMillis();
            for (int i = 0; i < lines.length; i++) {
                String[] p = lines[i].split("\\|", 3);
                if (p.length < 3 || !p[2].trim().startsWith("任务流程开始")) continue;
                try {
                    if (now - Long.parseLong(p[1].trim()) <= 5 * 60 * 1000L) from = i;
                } catch (Throwable ignored) {
                }
            }
            if (from < 0) return;
            replaying = true;
            for (int i = from; i < lines.length; i++) onMarkerLine(lines[i].trim());
        } catch (Throwable ignored) {
        } finally {
            replaying = false;
        }
    }

    private static volatile boolean replaying;

    /** 读走 markers.log 里新增的行（文件监听也会调）。 */
    static synchronized void pollMarkers() {
        java.io.File f = markerFile;
        if (f == null || !on) return;
        try {
            long len = f.length();
            if (len < markerOff) markerOff = 0; // 被删过重写
            if (len == markerOff) return;
            byte[] b = new byte[(int) Math.min(len - markerOff, 64 * 1024)];
            try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(f, "r")) {
                in.seek(markerOff);
                int n = in.read(b);
                if (n <= 0) return;
                // 只处理完整的行；最后不完整的那截留到下次
                int end = n;
                while (end > 0 && b[end - 1] != '\n') end--;
                if (end == 0) return;
                markerOff += end;
                String s = new String(b, 0, end, java.nio.charset.StandardCharsets.UTF_8);
                for (String line : s.split("\n")) onMarkerLine(line.trim());
            }
        } catch (Throwable ignored) {
        }
    }

    /** 浮窗点了“暂停记录”：到“记录继续”之前，目标进程什么都不记（只留标记、页面切换这类定位用的）。 */
    static volatile boolean paused;

    private static void onMarkerLine(String line) {
        if (line.isEmpty()) return;
        String[] ln = line.split("\\|", 3);
        if (ln.length < 3) return;
        long seq, sendWall;
        try {
            seq = Long.parseLong(ln[0].trim());
            sendWall = Long.parseLong(ln[1].trim());
        } catch (Throwable e) {
            return;
        }
        String label = ln[2].trim();
        if (seq <= lastMarkSeq) return;
        lastMarkSeq = seq;
        long delay = System.currentTimeMillis() - sendWall;
        if (delay < 0 || delay > (replaying ? 300000 : 15000)) delay = 0; // 两边时钟对不上就按收到的时刻；补读的标记允许更早（时间会是负数 = 进程启动前）
        long t = SystemClock.uptimeMillis() - delay;
        String suffix = replaying ? "（进程启动前发出，启动后补读，时间按发出时刻推算）" : delay > 200 ? "（TextRec 发出 " + delay + "ms 后才收到，时间已按发出时刻推算）" : "";
        if (label.startsWith("记录暂停")) {
            addAt("MARK", label + suffix, t, sendWall);
            paused = true;
            return;
        }
        if (label.startsWith("记录继续")) {
            paused = false;
            if (label.startsWith("记录继续（浮窗关闭）")) {
                flowActive = false; // 浮窗关了：回到没用浮窗时的样子，照常全记
                curTask = 0;
            }
            addAt("MARK", label + suffix, t, sendWall);
            return;
        }
        if (label.startsWith("基线开始")) {
            int secs = 0;
            try {
                secs = Integer.parseInt(label.substring(4).trim());
            } catch (Throwable ignored) {
            }
            baseline = true;
            baseStart = t;
            baseUntil = secs > 0 ? t + secs * 1000L : 0;
            addAt("BASE", "基线开始（请什么都不要操作" + (secs > 0 ? "，" + secs + " 秒后自动结束" : "") + "）" + suffix, t, sendWall);
        } else if (label.startsWith("基线结束")) {
            endBaseline(t, "基线结束" + suffix, sendWall);
        } else if (label.startsWith("任务流程开始")) {
            flowActive = true;
            curTask = 0;
            addAt("TASK", label + suffix, t, sendWall);
        } else if (label.startsWith("任务")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^任务(开始|重做) (\\d+)").matcher(label);
            if (m.find()) {
                curTask = Integer.parseInt(m.group(2));
                flowActive = true; // 冷启动补读时可能漏了「任务流程开始」，看到任务开始也算进入流程
                if (!replaying) {
                    ViewDump.request(label); // 任务开始：抓一份视图树（开关没开直接返回；冷启动补读的旧标记不抓）
                    Annot.sweep(label);
                }
            } else if (label.startsWith("任务结束") || label.startsWith("任务中止") || label.startsWith("任务全部完成")) {
                if (!replaying) {
                    ViewDump.request(label);
                    Annot.sweep(label);
                }
                curTask = 0; // flowActive 保持：做完以后到关浮窗之前，任务外仍按「任务外也记录」开关处理，不会把收尾操作记进任务内
            }
            addAt("TASK", label + suffix, t, sendWall);
        } else {
            addAt("MARK", label + suffix, t, sendWall);
            if (!replaying) {
                ViewDump.request("标记 " + label); // 记录页点「标记」：手动抓一份
                Annot.sweep("标记 " + label);
            }
        }
    }

    private static synchronized void endBaseline(long t, String text, long wall) {
        if (!baseline) return; // 没开始过（或已经自动结束了）就不再记
        baseline = false;
        baselineDone = true;
        baseEnd = t;
        baseUntil = 0;
        addAt("BASE", text + "（共 " + (t - baseStart) + "ms）", t, wall);
    }

    /** 基线定了时长、结束标记没到（或丢了）：时间一到自动结束，免得后面所有记录都被算成基线。 */
    private static void checkBaselineEnd() {
        long u = baseUntil;
        if (baseline && u > 0 && SystemClock.uptimeMillis() >= u) endBaseline(u, "基线自动结束（到时间了，没等到结束标记）", System.currentTimeMillis());
    }

    /** 现在算不算在基线里（Rec.stamp 每次调用都问）。 */
    static boolean inBaseline() {
        if (!baseline) return false;
        long u = baseUntil;
        return u == 0 || SystemClock.uptimeMillis() < u;
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
                if (e.n > 1) {
                    o.put("n", e.n);
                    o.put("lastT", e.lastT - START);
                }
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
        AGG.clear();
        DROPPED.set(0);
        KEYN.clear();
    }
}
