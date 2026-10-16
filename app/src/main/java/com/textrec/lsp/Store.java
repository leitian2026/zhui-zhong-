package com.textrec.lsp;

import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XposedBridge;

/** 内存索引：记录归并、代理 key 计数、限流、节流落盘成 JSON 快照。 */
final class Store {
    static final class SetProxy {
        final AtomicInteger n = new AtomicInteger();
        volatile SetStack last;
    }

    static final class DrawProxy {
        final AtomicInteger n = new AtomicInteger();
        volatile Rec rec;
    }

    private static final int PROXY_CAP = 50000;
    private static final int SETS_CAP = 20000;

    final ConcurrentHashMap<String, Rec> recs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, SetProxy> setProxies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, DrawProxy> drawProxies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, SetStack> sets = new ConcurrentHashMap<>();
    final AtomicInteger nextId = new AtomicInteger(1);
    final AtomicLong setEvents = new AtomicLong();
    final AtomicLong drawEvents = new AtomicLong();
    final AtomicLong droppedRate = new AtomicLong();
    final AtomicLong droppedCap = new AtomicLong();
    /** TEXTSET 记录数（单独限额）及因限额丢弃的数量 */
    final AtomicInteger setOnlyCount = new AtomicInteger();
    final AtomicLong droppedSetOnly = new AtomicLong();

    volatile String appVersion = "";
    private volatile String warn = "";
    private volatile String traceWarn = "";
    private volatile RecConfig conf = new RecConfig();
    private volatile File dir;
    private volatile String proc = "main";
    private volatile String pkg = "";

    private volatile long winStart;
    private final AtomicInteger winCount = new AtomicInteger();

    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "textrec-writer");
        t.setDaemon(true);
        return t;
    });

    void init(File d, String processName, String packageName) {
        dir = d;
        proc = processName == null || processName.isEmpty() ? "main" : processName;
        pkg = packageName == null ? "" : packageName;
    }

    void configure(RecConfig c) {
        conf = c;
    }

    void setWarn(String w) {
        warn = w == null ? "" : w;
    }

    /** 方法追踪的安装结果（类没找到、方法不存在等），随快照一起显示在“⚠”里。 */
    void setTraceWarn(String w) {
        traceWarn = w == null ? "" : w;
    }

    void reset() {
        recs.clear();
        setProxies.clear();
        drawProxies.clear();
        sets.clear();
        nextId.set(1);
        setEvents.set(0);
        drawEvents.set(0);
        droppedRate.set(0);
        droppedCap.set(0);
        setOnlyCount.set(0);
        droppedSetOnly.set(0);
        markDirty();
    }

    // ------------------------------------------------------------ 限流 / 代理

    /** 全局每秒抓栈上限（兜底）。 */
    boolean allowStack(int perSec) {
        long now = SystemClock.elapsedRealtime();
        if (now - winStart >= 1000) {
            winStart = now;
            winCount.set(0);
        }
        if (winCount.incrementAndGet() <= perSec) return true;
        droppedRate.incrementAndGet();
        return false;
    }

    SetProxy setProxy(long key) {
        SetProxy p = setProxies.get(key);
        if (p != null) return p;
        if (setProxies.size() > PROXY_CAP) setProxies.clear();
        SetProxy np = new SetProxy();
        SetProxy old = setProxies.putIfAbsent(key, np);
        return old != null ? old : np;
    }

    DrawProxy drawProxy(long key) {
        DrawProxy p = drawProxies.get(key);
        if (p != null) return p;
        if (drawProxies.size() > PROXY_CAP) drawProxies.clear();
        DrawProxy np = new DrawProxy();
        DrawProxy old = drawProxies.putIfAbsent(key, np);
        return old != null ? old : np;
    }

    /** 同一个设置位置（签名相同）只保留一个 SetStack，次数累加在它上面。 */
    SetStack internSet(SetStack ns, ClassLoader hint) {
        if (sets.size() > SETS_CAP) sets.clear();
        SetStack old = sets.putIfAbsent(ns.stk.sig, ns);
        if (old != null) return old;
        SigResolver.resolveSet(ns, hint);
        return ns;
    }

    // ------------------------------------------------------------ 落盘（事件触发 + 单次延迟，没有定时轮询）

    void markDirty() {
        dirty.set(true);
        if (dir != null && scheduled.compareAndSet(false, true)) {
            try {
                ex.schedule(this::flush, conf.flushSec, TimeUnit.SECONDS);
            } catch (Throwable t) {
                scheduled.set(false);
            }
        }
    }

    private void flush() {
        scheduled.set(false);
        if (!dirty.getAndSet(false)) return;
        try {
            File d = dir;
            if (d == null) return;
            d.mkdirs();
            String safe = proc.replaceAll("[^A-Za-z0-9_.-]", "_");
            File dest = new File(d, "snapshot-" + safe + ".json");
            File tmp = new File(d, "snapshot-" + safe + ".json.tmp");
            byte[] data = buildJson().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(data);
                os.flush();
            }
            if (!tmp.renameTo(dest)) {
                dest.delete();
                tmp.renameTo(dest);
            }
        } catch (Throwable t) {
            XposedBridge.log("[TextRec] flush failed: " + t);
        }
    }

    private String buildJson() throws Exception {
        RecConfig c = conf;
        JSONObject root = new JSONObject();
        root.put("schema", 1);
        root.put("module", "TextRec 1.5");
        root.put("pkg", pkg);
        root.put("process", proc);
        root.put("appVersion", appVersion);
        root.put("sdk", Build.VERSION.SDK_INT);
        root.put("snapshotTime", System.currentTimeMillis());
        root.put("warn", warn + traceWarn);

        JSONObject cj = new JSONObject();
        cj.put("mode", c.keywordMode ? "keyword" : "all");
        cj.put("keywords", c.keywords);
        cj.put("regex", c.regex);
        cj.put("stackDepth", c.stackDepth);
        cj.put("perKeyStacks", c.perKeyStacks);
        cj.put("sampleEvery", c.sampleEvery);
        cj.put("stacksPerSec", c.stacksPerSec);
        cj.put("maxRecords", c.maxRecords);
        cj.put("traceColor", c.traceColor);
        cj.put("traceIcons", c.traceIcons);
        cj.put("autoParams", c.autoParams);
        cj.put("traceSetOnly", c.traceSetOnly);
        cj.put("maxSetOnly", c.maxSetOnly);
        cj.put("traceMethods", c.traceMethods);
        root.put("config", cj);

        ArrayList<Rec> list = new ArrayList<>(recs.values());
        Collections.sort(list, new Comparator<Rec>() {
            @Override
            public int compare(Rec a, Rec b) {
                return Integer.compare(a.id, b.id);
            }
        });

        final long now = System.currentTimeMillis();
        JSONArray arr = new JSONArray();
        int setOnlyShown = 0;
        for (Rec r : list) {
            JSONObject j = recJson(r, now);
            if (j == null) continue; // TEXTSET 且其控件都已经画过了：已有对应的 TEXTVIEW 记录，不重复
            if (Rec.TEXTSET.equals(r.kind)) setOnlyShown++;
            arr.put(j);
        }

        JSONObject st = new JSONObject();
        st.put("records", arr.length());
        st.put("setOnly", setOnlyShown);
        st.put("setEvents", setEvents.get());
        st.put("drawEvents", drawEvents.get());
        st.put("droppedByRateLimit", droppedRate.get());
        st.put("droppedByRecordCap", droppedCap.get());
        st.put("droppedBySetOnlyCap", droppedSetOnly.get());
        root.put("stats", st);
        root.put("records", arr);
        return root.toString();
    }

    private static JSONObject stackJson(Stacks.Stk s, String[] sigs, String thread) throws Exception {
        JSONObject o = new JSONObject();
        o.put("thread", thread);
        o.put("frames", new JSONArray(Arrays.asList(s.lines)));
        o.put("more", s.more);
        o.put("sigs", new JSONArray(Arrays.asList(sigs)));
        return o;
    }

    private static JSONObject recJson(Rec r, long now) throws Exception {
        final boolean setOnly = Rec.TEXTSET.equals(r.kind);
        java.util.List<android.view.View> und = null;
        if (setOnly) {
            und = Recorder.undrawn(r);
            if (und.isEmpty()) return null;
        }
        JSONObject j = new JSONObject();
        j.put("id", r.id);
        j.put("kind", r.kind);
        j.put("view", r.viewClass);
        j.put("viewId", r.viewId);
        j.put("activity", r.activity);
        j.put("parents", new JSONArray(Arrays.asList(r.parents)));
        j.put("drawCount", r.drawCount.get());
        j.put("setCount", r.set == null ? 0 : r.set.count.get());
        j.put("first", r.first);
        j.put("last", r.last);
        j.put("firstText", r.firstText);
        j.put("lastText", r.lastText);
        if (setOnly) {
            // 仅设置：给出这些“从未绘制”的控件【当前】的文字，以及它们为什么没被画
            java.util.LinkedHashSet<String> cur = new java.util.LinkedHashSet<>();
            JSONArray ud = new JSONArray();
            JSONArray scr = new JSONArray();
            android.graphics.Rect rc = new android.graphics.Rect();
            for (android.view.View v : und) {
                if (ud.length() >= 30) break;
                String t = "";
                if (v instanceof android.widget.TextView) {
                    CharSequence ct = ((android.widget.TextView) v).getText();
                    t = ct == null ? "" : (ct.length() > 200 ? ct.subSequence(0, 200) + "…" : ct.toString());
                }
                if (!t.isEmpty() && cur.size() < 40) cur.add(t);
                JSONObject uo = new JSONObject();
                uo.put("text", t);
                uo.put("reason", Recorder.notDrawnReason(v));
                uo.put("w", v.getWidth());
                uo.put("h", v.getHeight());
                ud.put(uo);
                if (Rec.onScreen(v, rc) && !t.isEmpty()) {
                    JSONObject so = new JSONObject();
                    so.put("text", t);
                    so.put("rect", new JSONArray(Arrays.asList(rc.left, rc.top, rc.right, rc.bottom)));
                    scr.put(so);
                }
            }
            j.put("texts", new JSONArray(new ArrayList<>(cur)));
            j.put("textsMore", 0);
            j.put("unDrawn", ud);
            j.put("screenSetOnly", scr);
        } else {
            j.put("texts", new JSONArray(r.sampleList()));
            j.put("textsMore", r.sampleOverflow());
        }

        // 画笔 / 绘制参数变体（颜色、字号、描边或填充；Compose 文字还有 paint 的参数）
        JSONArray pj = new JSONArray();
        for (Rec.PaintVar pv : r.paintList()) {
            JSONObject po = new JSONObject();
            po.put("desc", pv.desc);
            po.put("n", pv.n);
            pj.put(po);
        }
        j.put("paints", pj);
        j.put("paintsMore", r.paintOverflow());
        if (r.hasPos()) j.put("pos", new JSONArray(Arrays.asList(r.posX, r.posY)));
        ColorSet cs = r.colorSet;
        if (cs != null) {
            j.put("textColor", String.format("0x%08X", cs.color));
            if (cs.ss != null) j.put("colorStack", stackJson(cs.ss.stk, cs.ss.sigs, cs.ss.thread));
        }

        // 此刻是否在屏幕上：TextView 记录直接问被跟踪的 View；自绘/Compose 没有 View 可问，
        // 3 秒内画过就算在屏幕上，否则标“未知”（不会因此被“只看屏幕上”过滤掉）
        JSONArray screen = new JSONArray();
        String vis;
        if (setOnly) {
            // 从未绘制过：一般就是不在屏幕上；若控件“可见、已挂窗口”却没画，标 ?（可能刚设置还没来得及画）
            vis = j.optJSONArray("screenSetOnly") != null && j.optJSONArray("screenSetOnly").length() > 0 ? "unknown" : "no";
        } else if (r.hasViews()) {
            for (Rec.ScreenText s : r.screenNow()) {
                JSONObject so = new JSONObject();
                so.put("text", s.text);
                so.put("rect", new JSONArray(Arrays.asList(s.l, s.t, s.r, s.b)));
                screen.put(so);
            }
            vis = screen.length() > 0 ? "yes" : "no";
        } else {
            vis = (now - r.last <= 3000) ? "yes" : "unknown";
        }
        j.put("vis", vis);
        j.put("screen", screen);
        if (r.set != null) {
            j.put("setStack", stackJson(r.set.stk, r.set.sigs, r.set.thread));
        } else {
            j.put("setStack", JSONObject.NULL);
            String note;
            if (Rec.COMPOSE.equals(r.kind)) note = "Compose：无 setText（状态变化触发重组后再绘制），看下方“画笔/参数”里的颜色和描边/填充，以及“可 hook 的方法签名”里标 [稳定] 的那个";
            else if (Rec.CANVAS.equals(r.kind)) note = "自绘文字：没有 setText，只有画的位置";
            else if (Rec.TRACE.equals(r.kind)) note = "方法追踪：右侧“调用栈”是谁调用了这个方法；参数→返回值的各种取值见上方“调用参数变体”";
            else note = "无设置栈：设置发生在记录开始之前（可重启后冷启动记录），或被限流";
            j.put("setNote", note);
        }
        if (Rec.ICON.equals(r.kind)) {
            // 图标没有“绘制栈”，只有“谁设置了它”（左侧设置栈）
            j.put("drawStack", JSONObject.NULL);
            j.put("drawNote", "图标：没有绘制栈，只记录“谁设置了图标”（setImage* / setContentDescription），见左侧设置栈");
        } else if (setOnly) {
            j.put("drawStack", JSONObject.NULL);
            j.put("drawNote", "仅设置·从未绘制：这段文字被 setText 设置过，但控件一直没被绘制，所以没有绘制栈。"
                    + "原因见“未绘制的控件”一栏（INVISIBLE/GONE、尺寸为 0、未挂窗口、祖先不可见）。想改这段文字，hook 左侧设置侧即可");
        } else {
            j.put("drawStack", stackJson(r.draw, r.drawSigs, r.drawThread));
        }
        return j;
    }
}
