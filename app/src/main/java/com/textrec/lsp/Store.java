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

    volatile String appVersion = "";
    private volatile String warn = "";
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
        root.put("module", "TextRec 1.0");
        root.put("pkg", pkg);
        root.put("process", proc);
        root.put("appVersion", appVersion);
        root.put("sdk", Build.VERSION.SDK_INT);
        root.put("snapshotTime", System.currentTimeMillis());
        root.put("warn", warn);

        JSONObject cj = new JSONObject();
        cj.put("mode", c.keywordMode ? "keyword" : "all");
        cj.put("keywords", c.keywords);
        cj.put("regex", c.regex);
        cj.put("stackDepth", c.stackDepth);
        cj.put("perKeyStacks", c.perKeyStacks);
        cj.put("sampleEvery", c.sampleEvery);
        cj.put("stacksPerSec", c.stacksPerSec);
        cj.put("maxRecords", c.maxRecords);
        root.put("config", cj);

        ArrayList<Rec> list = new ArrayList<>(recs.values());
        Collections.sort(list, new Comparator<Rec>() {
            @Override
            public int compare(Rec a, Rec b) {
                return Integer.compare(a.id, b.id);
            }
        });

        JSONObject st = new JSONObject();
        st.put("records", list.size());
        st.put("setEvents", setEvents.get());
        st.put("drawEvents", drawEvents.get());
        st.put("droppedByRateLimit", droppedRate.get());
        st.put("droppedByRecordCap", droppedCap.get());
        root.put("stats", st);

        JSONArray arr = new JSONArray();
        for (Rec r : list) arr.put(recJson(r));
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

    private static JSONObject recJson(Rec r) throws Exception {
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
        if (r.set != null) {
            j.put("setStack", stackJson(r.set.stk, r.set.sigs, r.set.thread));
        } else {
            j.put("setStack", JSONObject.NULL);
            String note;
            if (Rec.COMPOSE.equals(r.kind)) note = "Compose：无 setText（状态变化触发重组后再绘制），“谁算出这段文字”要靠盯参数找";
            else if (Rec.CANVAS.equals(r.kind)) note = "自绘文字：没有 setText，只有画的位置";
            else note = "无设置栈：设置发生在记录开始之前（可重启后冷启动记录），或被限流";
            j.put("setNote", note);
        }
        j.put("drawStack", stackJson(r.draw, r.drawSigs, r.drawThread));
        return j;
    }
}
