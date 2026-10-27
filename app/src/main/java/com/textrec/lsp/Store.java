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
        /** 持续型自动降频：第一次被计数的时间（uptimeMillis），以及是否已降频成只计数 */
        volatile long firstAt;
        volatile boolean quiet;
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
    static final String[] KINDS = {Rec.TEXTVIEW, Rec.CANVAS, Rec.COMPOSE, Rec.TEXTCLEAR, Rec.TEXTSET, Rec.STATE, Rec.ICON, Rec.TRACE};

    /** TEXTSET 记录数（单独限额）及因限额丢弃的数量 */
    final AtomicInteger setOnlyCount = new AtomicInteger();
    final AtomicLong droppedSetOnly = new AtomicLong();
    /** 每种记录类型各自的条数 / 因该类型额度满被丢的次数：一种灌满只挤自己，不挤别的种类 */
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> kindN = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> kindDrop = new java.util.concurrent.ConcurrentHashMap<>();

    AtomicInteger kindCount(String k) {
        AtomicInteger a = kindN.get(k);
        if (a == null) {
            a = new AtomicInteger();
            AtomicInteger o = kindN.putIfAbsent(k, a);
            if (o != null) a = o;
        }
        return a;
    }

    AtomicLong kindDropped(String k) {
        AtomicLong a = kindDrop.get(k);
        if (a == null) {
            a = new AtomicLong();
            AtomicLong o = kindDrop.putIfAbsent(k, a);
            if (o != null) a = o;
        }
        return a;
    }
    /** 采集端过滤：各个开关各挡掉了多少次调用（挡掉的不抓栈、不建记录），以及持续型降频后只计数的次数 */
    final AtomicLong blkWindow = new AtomicLong();
    final AtomicLong blkThread = new AtomicLong();
    final AtomicLong blkPage = new AtomicLong();
    final AtomicLong blkView = new AtomicLong();
    final AtomicLong blkExclude = new AtomicLong();
    final AtomicLong blkValue = new AtomicLong();
    final AtomicLong quietCnt = new AtomicLong();

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
        kindN.clear();
        kindDrop.clear();
        blkWindow.set(0);
        blkThread.set(0);
        blkPage.set(0);
        blkView.set(0);
        blkExclude.set(0);
        blkValue.set(0);
        quietCnt.set(0);
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
        key ^= Recorder.pageHash; // 换页面后重新抓栈、另建记录
        if (Timeline.bucket() != 0) key ^= 0x5DEECE66DL; // 任务外另算一份：记录在哪一桶，代理里缓存的记录就在哪一桶
        SetProxy p = setProxies.get(key);
        if (p != null) return p;
        if (setProxies.size() > PROXY_CAP) setProxies.clear();
        SetProxy np = new SetProxy();
        SetProxy old = setProxies.putIfAbsent(key, np);
        return old != null ? old : np;
    }

    DrawProxy drawProxy(long key) {
        key ^= Recorder.pageHash;
        if (Timeline.bucket() != 0) key ^= 0x5DEECE66DL; // 同上：任务内 / 任务外各缓存各的记录
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
            String st = statusText;
            if (statusDirty && st != null) {
                statusDirty = false;
                writeFile(d, "status-" + safe + ".txt", st);
            }
            RecConfig c = conf;
            Snapped sn = snapRecords();
            if (!c.splitFiles) {
                // 旧方式：一个文件装全部（总上限 maxRecords）；顺手删掉分文件留下的旧文件
                writeFile(d, "snapshot-" + safe + ".json", buildMainJson(sn, false, Timeline.toJson()).toString());
                deletePartFiles(d, safe);
                return;
            }
            // 新方式：主文件（概况 / 配置 / 统计）+ 每种记录一个文件 + 时间线一个文件，各写各的、互不影响
            writeFile(d, "snapshot-" + safe + ".json", buildMainJson(sn, true, null).toString());
            for (String k : sn.by.keySet()) {
                JSONArray arr = sn.by.get(k);
                File f = new File(d, "snapshot-" + safe + "." + k + ".json");
                if (arr.length() == 0) {
                    f.delete();
                    continue;
                }
                try {
                    JSONObject pj = partHead(k);
                    pj.put("records", arr);
                    writeFile(d, f.getName(), pj.toString());
                } catch (Throwable t) {
                    XposedBridge.log("[TextRec] part " + k + " failed: " + t);
                }
            }
            try {
                JSONObject tj = partHead("TIMELINE");
                tj.put("timeline", Timeline.toJson());
                tj.put("timelineDropped", Timeline.dropped());
                tj.put("timelineKindDropped", Timeline.kindDropped());
                tj.put("timelineSeqDropped", Timeline.seqDropped());
                tj.put("baselineDone", Timeline.baselineDone);
                writeFile(d, "snapshot-" + safe + ".TIMELINE.json", tj.toString());
            } catch (Throwable t) {
                XposedBridge.log("[TextRec] part TIMELINE failed: " + t);
            }
        } catch (Throwable t) {
            XposedBridge.log("[TextRec] flush failed: " + t);
        }
    }

    private static void writeFile(File d, String name, String data) {
        try {
            File dest = new File(d, name);
            File tmp = new File(d, name + ".tmp");
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(bytes);
                os.flush();
            }
            if (!tmp.renameTo(dest)) {
                dest.delete();
                tmp.renameTo(dest);
            }
        } catch (Throwable t) {
            XposedBridge.log("[TextRec] write " + name + " failed: " + t);
        }
    }

    private volatile String statusText;
    private volatile boolean statusDirty;

    /**
     * 本进程的状态（实际生效的配置 + 方法追踪安装结果），模块首页用 root 回读。
     * 不在调用线程（可能是目标 App 的类加载回调）上写盘，只记下内容并标脏，随下一次节流落盘一起写（最多晚 flushSec 秒）。
     */
    void setStatus(String content) {
        statusText = content;
        statusDirty = true;
        markDirty();
    }

    String procName() {
        return proc;
    }

    private static void deletePartFiles(File d, String safe) {
        for (String k : KINDS) {
            new File(d, "snapshot-" + safe + "." + k + ".json").delete();
            new File(d, "snapshot-" + safe + "." + k + ".OUT.json").delete();
        }
        new File(d, "snapshot-" + safe + ".TIMELINE.json").delete();
    }

    private JSONObject partHead(String part) throws Exception {
        JSONObject j = new JSONObject();
        j.put("schema", 1);
        j.put("module", "TextRec 1.28.1");
        j.put("part", part);
        j.put("pkg", pkg);
        j.put("process", proc);
        j.put("snapshotTime", System.currentTimeMillis());
        return j;
    }

    /** 一次快照里按种类分好的记录 JSON。 */
    private static final class Snapped {
        final java.util.LinkedHashMap<String, JSONArray> by = new java.util.LinkedHashMap<>();
        final JSONArray all = new JSONArray();
        int total;
        int setOnlyShown;
    }

    private Snapped snapRecords() throws Exception {
        Snapped sn = new Snapped();
        for (String k : KINDS) {
            sn.by.put(k, new JSONArray());
            sn.by.put(k + ".OUT", new JSONArray()); // 任务外那一桶：单独的文件，文件名带 .OUT
        }
        ArrayList<Rec> list = new ArrayList<>(recs.values());
        Collections.sort(list, new Comparator<Rec>() {
            @Override
            public int compare(Rec a, Rec b) {
                return Integer.compare(a.id, b.id);
            }
        });
        final long now = System.currentTimeMillis();
        for (Rec r : list) {
            JSONObject j = recJson(r, now);
            if (j == null) continue; // TEXTSET 且其控件都已经画过了：已有对应的 TEXTVIEW 记录，不重复
            if (Rec.TEXTSET.equals(r.kind)) sn.setOnlyShown++;
            String bk = r.bucket == 0 ? r.kind : r.kind + ".OUT";
            JSONArray a = sn.by.get(bk);
            if (a == null) {
                a = new JSONArray();
                sn.by.put(bk, a);
            }
            a.put(j);
            sn.all.put(j);
            sn.total++;
        }
        return sn;
    }

    private JSONObject buildMainJson(Snapped sn, boolean split, JSONArray legacyTimeline) throws Exception {
        RecConfig c = conf;
        JSONObject root = new JSONObject();
        root.put("schema", 1);
        root.put("module", "TextRec 1.28.1");
        root.put("splitFiles", split);
        root.put("env", envJson());
        root.put("monoNow", android.os.SystemClock.uptimeMillis() - Timeline.START);
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
        cj.put("traceState", c.traceState);
        cj.put("maxSetOnly", c.maxSetOnly);
        cj.put("splitFiles", c.splitFiles);
        cj.put("capTextView", c.capTextView);
        cj.put("capCanvas", c.capCanvas);
        cj.put("capCompose", c.capCompose);
        cj.put("capClear", c.capClear);
        cj.put("capIcon", c.capIcon);
        cj.put("capState", c.capState);
        cj.put("capTrace", c.capTrace);
        cj.put("traceDump", c.traceDump);
        cj.put("traceDumpEvery", c.traceDumpEvery);
        cj.put("traceDumpLines", c.traceDumpLines);
        cj.put("traceDumpDepth", c.traceDumpDepth);
        cj.put("viewDump", c.viewDump);
        cj.put("viewDumpKey", c.viewDumpKey);
        cj.put("viewDumpUp", c.viewDumpUp);
        cj.put("viewDumpDepth", c.viewDumpDepth);
        cj.put("viewDumpLines", c.viewDumpLines);
        cj.put("viewDumpSet", c.viewDumpSet);
        cj.put("viewTap", c.viewTap);
        cj.put("annotFrames", c.annotFrames);
        cj.put("treeOn", c.treeOn);
        cj.put("treeDepth", c.treeDepth);
        cj.put("treeNodes", c.treeNodes);
        cj.put("treeEvery", c.treeEvery);
        cj.put("treeViews", c.treeViews);
        cj.put("watchAuto", c.watchAuto);
        cj.put("annotOwners", c.annotOwners);
        cj.put("annotSweep", c.annotSweep);
        cj.put("annotBudgetMs", c.annotBudgetMs);
        cj.put("annotPerKey", c.annotPerKey);
        cj.put("traceMethods", c.traceMethods);
        cj.put("windowMs", c.windowMs);
        cj.put("windowAll", c.windowAll);
        cj.put("excludeList", c.excludeList);
        cj.put("threadFilter", c.threadFilter);
        cj.put("pageFilter", c.pageFilter);
        cj.put("viewFilter", c.viewFilter);
        cj.put("valueFilter", c.valueFilter);
        cj.put("quietCount", c.quietCount);
        cj.put("quietSpanMs", c.quietSpanMs);
        root.put("config", cj);

        JSONArray arr = split ? new JSONArray() : sn.all; // 分文件时记录在各自的文件里
        int setOnlyShown = sn.setOnlyShown;

        JSONObject st = new JSONObject();
        st.put("records", sn.total);
        st.put("setOnly", setOnlyShown);
        st.put("setEvents", setEvents.get());
        st.put("drawEvents", drawEvents.get());
        st.put("droppedByRateLimit", droppedRate.get());
        st.put("droppedByRecordCap", droppedCap.get());
        st.put("droppedBySetOnlyCap", droppedSetOnly.get());
        JSONObject kc = new JSONObject();
        for (java.util.Map.Entry<String, AtomicInteger> e : kindN.entrySet()) kc.put(e.getKey(), e.getValue().get());
        st.put("recordsByKind", kc);
        JSONObject kd = new JSONObject();
        for (java.util.Map.Entry<String, AtomicLong> e : kindDrop.entrySet()) if (e.getValue().get() > 0) kd.put(e.getKey(), e.getValue().get());
        st.put("droppedByKindCap", kd);
        st.put("blockedByWindow", blkWindow.get());
        st.put("blockedByThread", blkThread.get());
        st.put("blockedByPage", blkPage.get());
        st.put("blockedByView", blkView.get());
        st.put("blockedByExclude", blkExclude.get());
        st.put("blockedByValue", blkValue.get());
        st.put("quietCounted", quietCnt.get());
        root.put("stats", st);
        root.put("records", arr);
        if (!split) {
            root.put("timeline", legacyTimeline);
            root.put("timelineDropped", Timeline.dropped());
            root.put("timelineKindDropped", Timeline.kindDropped());
            root.put("timelineSeqDropped", Timeline.seqDropped());
        }
        root.put("viewDumps", ViewDump.toJson());
        root.put("annot", Annot.toJson());
        root.put("trees", DataTree.treesJson());
        root.put("watches", DataTree.watchesJson());
        root.put("baselineDone", Timeline.baselineDone);
        root.put("baselineMs", Timeline.baselineDone ? Timeline.baseEnd - Timeline.baseStart : 0);
        return root;
    }

    private JSONObject envJson() throws Exception {
        JSONObject e = new JSONObject();
        e.put("appVersion", appVersion);
        e.put("process", proc);
        e.put("pkg", pkg);
        e.put("sdk", Build.VERSION.SDK_INT);
        e.put("release", Build.VERSION.RELEASE);
        e.put("model", Build.MANUFACTURER + " " + Build.MODEL);
        e.put("abi", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
        e.put("build", Build.DISPLAY);
        return e;
    }

    private static JSONObject stackJson(Stacks.Stk s, String[] sigs, String thread) throws Exception {
        JSONObject o = new JSONObject();
        o.put("thread", thread);
        o.put("frames", new JSONArray(Arrays.asList(s.lines)));
        o.put("more", s.more);
        if (s.xmlOnly) o.put("xmlOnly", true);
        o.put("sigs", new JSONArray(Arrays.asList(sigs)));
        RecConfig ac = Recorder.cfg;
        if (ac == null || ac.annotFrames) o.put("frameInfo", new JSONArray(Arrays.asList(Annot.frames(s.lines))));
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
        j.put("bucket", r.bucket); // 0 = 任务内，1 = 任务外
        j.put("kind", r.kind);
        j.put("view", r.viewClass);
        j.put("viewId", r.viewId);
        // 同一个位置（栈相同）出现过的所有 View id；归并后一条记录可能对应一批控件
        j.put("ids", new JSONArray(r.idList()));
        j.put("idsFull", r.idsFull());
        j.put("activity", r.activity);
        j.put("page", r.page);
        if (Rec.ICON.equals(r.kind)) {
            java.util.List<String> th = r.thumbList();
            if (!th.isEmpty()) j.put("thumbs", new JSONArray(th));
        }
        j.put("parents", new JSONArray(Arrays.asList(r.parents)));
        j.put("drawCount", r.drawCount.get());
        j.put("setCount", r.set == null ? 0 : r.set.count.get());
        j.put("first", r.first);
        j.put("last", r.last);
        j.put("firstMono", r.firstMono == 0 ? 0 : r.firstMono - Timeline.START);
        j.put("lastMono", r.lastMono == 0 ? 0 : r.lastMono - Timeline.START);
        if (r.minGap != Long.MAX_VALUE) {
            j.put("minGap", r.minGap);
            j.put("maxGap", r.maxGap);
        }
        if (Timeline.curTask > 0 || Timeline.flowActive || r.segCounts().size() > 1 || (r.segCounts().size() == 1 && !r.segCounts().containsKey(0))) {
            JSONObject sc = new JSONObject();
            for (java.util.Map.Entry<Integer, Integer> e : r.segCounts().entrySet()) sc.put(String.valueOf(e.getKey()), e.getValue());
            j.put("segCounts", sc);
        }
        if (Timeline.maxPass > 1) {
            JSONObject pm = new JSONObject();
            for (java.util.Map.Entry<Integer, Integer> e : r.passMasks().entrySet()) pm.put(String.valueOf(e.getKey()), e.getValue());
            if (pm.length() > 0) j.put("passes", pm);
        }
        j.put("baseHits", r.baseHits);
        j.put("liveHits", r.liveHits);
        j.put("baseline", !Timeline.baselineDone ? "unknown" : r.baseHits > 0 ? "noise" : "live");
        j.put("tags", new JSONArray(r.tagList()));
        j.put("lastTag", r.lastTag());
        if (!r.objList().isEmpty()) {
            j.put("objs", new JSONArray(r.objList()));
            j.put("objsMore", r.objOverflow());
        }
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
            else if (Rec.STATE.equals(r.kind)) note = "状态变化：设置栈 = 谁改了 alpha/visibility/selected；各种取值见上方“状态变化”";
            else note = "无设置栈：设置发生在记录开始之前（可重启后冷启动记录），或被限流";
            j.put("setNote", note);
        }
        if (Rec.STATE.equals(r.kind)) {
            j.put("drawStack", JSONObject.NULL);
            j.put("drawNote", "状态变化：没有绘制栈，只记录“谁改了它的 alpha / visibility / selected”，见左侧设置栈");
        } else if (Rec.ICON.equals(r.kind)) {
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
        if (Rec.TRACE.equals(r.kind)) {
            Recorder.StateSnap ss = Recorder.stateOf(r.viewClass + "#" + r.viewId);
            if (ss != null && ss.n > 0) {
                j.put("stateDump", ss.dump == null ? "" : ss.dump);
                if (ss.hasArg) j.put("argRange", "last=" + ss.last + " min=" + ss.min + " max=" + ss.max);
            }
            String ch = r.chain;
            if (ch != null) j.put("chain", ch);
            String tk = r.viewClass + "#" + r.viewId;
            String hi = Recorder.hookInfoOf(tk);
            if (hi != null) j.put("hookInfo", hi);
            String rt = Recorder.hookRetOf(tk);
            if (rt != null) j.put("ret", rt);
        }
        Annot.putRecord(j, r);
        java.util.List<String> cdl = r.composeDumps();
        if (!cdl.isEmpty()) j.put("composeDumps", new JSONArray(cdl));
        if (r.frameSamples() > 0) {
            JSONArray fa = new JSONArray();
            for (java.util.Map.Entry<String, Integer> fe : r.frameStats().entrySet()) {
                JSONObject fo = new JSONObject();
                fo.put("f", fe.getKey());
                fo.put("n", fe.getValue());
                fa.put(fo);
            }
            j.put("frames", fa);
            j.put("frameSamples", r.frameSamples());
            j.put("frameOverflow", r.frameOverflow());
        }
        return j;
    }
}
